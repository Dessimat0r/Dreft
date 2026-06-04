package org.deftserver.web;

import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Regression for the TLS unwrap path: a client that coalesces a complete HTTP request with its
 * close_notify alert into a single TCP segment (a write-side half-close immediately after sending)
 * must still have its request processed and answered — the request's decrypted plaintext must not be
 * discarded just because close_notify rode along in the same record batch.
 */
public class TlsCoalescedCloseNotifyTest {

	private static int PORT;
	private static HttpServer server;

	public static class HelloHandler extends RequestHandler {
		@Override
		public void get(HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("Hello Secure World!");
		}
	}

	@BeforeClass
	public static void setup() throws Exception {
		Map<String, RequestHandler> reqHandlers = new HashMap<>();
		reqHandlers.put("/", new HelloHandler());
		Application application = new Application(reqHandlers);

		KeyStore ks = KeyStore.getInstance("PKCS12");
		try (FileInputStream fis = new FileInputStream("src/test/resources/keystore.p12")) {
			ks.load(fis, "password".toCharArray());
		}
		KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
		kmf.init(ks, "password".toCharArray());
		SSLContext sslContext = SSLContext.getInstance("TLS");
		sslContext.init(kmf.getKeyManagers(), null, null);

		server = new HttpServer(application);
		server.enableSSL(sslContext);
		server.bind(0);
		PORT = server.getPort();

		Thread.ofPlatform().name("Coalesced-Close-I/O-Loop").start(() -> {
			try { server.start(1); } catch (IOException e) { e.printStackTrace(); }
		});
		Thread.sleep(1000);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		if (server != null) server.stop();
		Thread.sleep(300);
	}

	private static SSLContext trustAllContext() throws Exception {
		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(null, new TrustManager[] { new X509TrustManager() {
			public X509Certificate[] getAcceptedIssuers() { return null; }
			public void checkClientTrusted(X509Certificate[] c, String a) {}
			public void checkServerTrusted(X509Certificate[] c, String a) {}
		} }, null);
		return ctx;
	}

	@Test
	public void requestCoalescedWithCloseNotifyIsStillAnswered() throws Exception {
		SSLEngine engine = trustAllContext().createSSLEngine("localhost", PORT);
		engine.setUseClientMode(true);

		int pkt = engine.getSession().getPacketBufferSize();
		int app = engine.getSession().getApplicationBufferSize();
		ByteBuffer myNet = ByteBuffer.allocate(pkt);
		ByteBuffer peerNet = ByteBuffer.allocate(pkt);
		ByteBuffer myApp = ByteBuffer.allocate(app);
		ByteBuffer peerApp = ByteBuffer.allocate(app);

		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("localhost", PORT), 5000);
			socket.setSoTimeout(5000);
			java.io.InputStream in = socket.getInputStream();
			java.io.OutputStream out = socket.getOutputStream();

			// --- Drive the TLS handshake to completion ---
			engine.beginHandshake();
			SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
			while (hs != SSLEngineResult.HandshakeStatus.FINISHED
					&& hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
				switch (hs) {
					case NEED_WRAP -> {
						myNet.clear();
						SSLEngineResult r = engine.wrap(ByteBuffer.allocate(0), myNet);
						hs = r.getHandshakeStatus();
						myNet.flip();
						byte[] b = new byte[myNet.remaining()];
						myNet.get(b);
						out.write(b);
						out.flush();
					}
					case NEED_UNWRAP -> {
						int n = readSome(in, peerNet);
						if (n < 0) throw new IOException("EOF during handshake");
						peerNet.flip();
						SSLEngineResult r;
						do {
							r = engine.unwrap(peerNet, peerApp);
							hs = r.getHandshakeStatus();
							if (hs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
								Runnable t;
								while ((t = engine.getDelegatedTask()) != null) t.run();
								hs = engine.getHandshakeStatus();
							}
						} while (r.getStatus() == SSLEngineResult.Status.OK && peerNet.hasRemaining()
								&& hs == SSLEngineResult.HandshakeStatus.NEED_UNWRAP);
						peerNet.compact();
					}
					case NEED_TASK -> {
						Runnable t;
						while ((t = engine.getDelegatedTask()) != null) t.run();
						hs = engine.getHandshakeStatus();
					}
					default -> { }
				}
			}

			// --- Build [application request record] + [close_notify record] into ONE buffer ---
			String req = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
			ByteBuffer combined = ByteBuffer.allocate(pkt * 2);

			myNet.clear();
			engine.wrap(ByteBuffer.wrap(req.getBytes(StandardCharsets.US_ASCII)), myNet);
			myNet.flip();
			combined.put(myNet);

			engine.closeOutbound(); // produces the close_notify on the next wrap
			myNet.clear();
			engine.wrap(ByteBuffer.allocate(0), myNet);
			myNet.flip();
			combined.put(myNet);

			combined.flip();
			byte[] oneSegment = new byte[combined.remaining()];
			combined.get(oneSegment);
			// Single write: the request and close_notify arrive coalesced.
			out.write(oneSegment);
			out.flush();

			// --- Read and decrypt the server's response ---
			StringBuilder plaintext = new StringBuilder();
			peerNet.clear();
			long deadline = System.currentTimeMillis() + 4000;
			boolean closed = false;
			while (System.currentTimeMillis() < deadline && !closed) {
				int n;
				try {
					n = readSome(in, peerNet);
				} catch (java.net.SocketTimeoutException ste) {
					break;
				}
				if (n < 0) break;
				peerNet.flip();
				while (peerNet.hasRemaining()) {
					peerApp.clear();
					SSLEngineResult r = engine.unwrap(peerNet, peerApp);
					if (r.getStatus() == SSLEngineResult.Status.CLOSED) { closed = true; break; }
					if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) break;
					peerApp.flip();
					byte[] b = new byte[peerApp.remaining()];
					peerApp.get(b);
					plaintext.append(new String(b, StandardCharsets.ISO_8859_1));
					if (r.getStatus() == SSLEngineResult.Status.OK
							&& r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
						Runnable t;
						while ((t = engine.getDelegatedTask()) != null) t.run();
					}
				}
				peerNet.compact();
			}

			String resp = plaintext.toString();
			assertTrue("server must answer the coalesced request with 200, got:\n"
				+ resp.substring(0, Math.min(80, resp.length())), resp.startsWith("HTTP/1.1 200"));
			assertTrue("response body must be present:\n" + resp, resp.contains("Hello Secure World!"));
		}
	}

	/** Reads a chunk of bytes from {@code in} into {@code dst} (at its current position). */
	private static int readSome(java.io.InputStream in, ByteBuffer dst) throws IOException {
		byte[] tmp = new byte[dst.remaining()];
		if (tmp.length == 0) return 0;
		int n = in.read(tmp);
		if (n > 0) dst.put(tmp, 0, n);
		return n;
	}
}
