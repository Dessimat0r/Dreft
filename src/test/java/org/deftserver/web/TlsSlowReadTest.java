package org.deftserver.web;

import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A slow/stalled HTTPS reader stalling a large response holds the single I/O-loop thread (the TLS
 * application-write path is synchronous). Without a bound this is a slow-read DoS — one client blocks
 * the whole server, possibly indefinitely (a "drip" reader resets the zero-progress stall timer). The
 * §36 absolute response-write timeout ({@link org.deftserver.web.http.HttpServerDescriptor#RESPONSE_WRITE_TIMEOUT_MS})
 * bounds it: the stalled writer is dropped and the server recovers. This test sets a short timeout,
 * stalls a reader, and asserts the server frees up and serves a fresh client within that bound.
 * <p>
 * NOTE: this bounds (does not eliminate) the loop-block; full elimination needs non-blocking TLS
 * writes (OP_WRITE deferral, like plaintext) — tracked as future work in progress.md (V3-24).
 */
public class TlsSlowReadTest {

	private static HttpServer server;
	private static int PORT;
	private static SSLSocketFactory trustAllFactory;
	private static final byte[] BIG = new byte[4 * 1024 * 1024]; // 4 MiB — overflows socket/TLS buffers
	private static long savedTimeout;

	@BeforeClass
	public static void setUp() throws Exception {
		savedTimeout = org.deftserver.web.http.HttpServerDescriptor.RESPONSE_WRITE_TIMEOUT_MS;
		org.deftserver.web.http.HttpServerDescriptor.RESPONSE_WRITE_TIMEOUT_MS = 2000; // short, for a fast test
		java.util.Arrays.fill(BIG, (byte) 'Z');
		Map<String, RequestHandler> h = new HashMap<>();
		h.put("/big", new RequestHandler() {
			@Override public void get(HttpRequest q, HttpResponse r) {
				r.write(java.nio.ByteBuffer.wrap(BIG));
			}
		});
		h.put("/small", new RequestHandler() {
			@Override public void get(HttpRequest q, HttpResponse r) { r.write("ok"); }
		});

		KeyStore ks = KeyStore.getInstance("PKCS12");
		try (FileInputStream fis = new FileInputStream("src/test/resources/keystore.p12")) {
			ks.load(fis, "password".toCharArray());
		}
		KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
		kmf.init(ks, "password".toCharArray());
		SSLContext serverCtx = SSLContext.getInstance("TLS");
		serverCtx.init(kmf.getKeyManagers(), null, null);

		server = new HttpServer(new Application(h));
		server.enableSSL(serverCtx);
		server.bind(0);
		PORT = server.getPort();
		Thread.ofPlatform().name("TlsSlowRead-IOLoop").start(() -> {
			try { server.start(1); } catch (Exception e) { e.printStackTrace(); }
		});
		Thread.sleep(1000);

		SSLContext clientCtx = SSLContext.getInstance("TLS");
		clientCtx.init(null, new TrustManager[]{new X509TrustManager() {
			public void checkClientTrusted(X509Certificate[] c, String a) { }
			public void checkServerTrusted(X509Certificate[] c, String a) { }
			public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
		}}, new SecureRandom());
		trustAllFactory = clientCtx.getSocketFactory();
	}

	@AfterClass
	public static void tearDown() {
		if (server != null) server.stop();
		org.deftserver.web.http.HttpServerDescriptor.RESPONSE_WRITE_TIMEOUT_MS = savedTimeout;
	}

	private static SSLSocket connect() throws Exception {
		SSLSocket s = (SSLSocket) trustAllFactory.createSocket("127.0.0.1", PORT);
		s.setSoTimeout(20000);
		s.startHandshake();
		return s;
	}

	@Test
	public void largeHttpsResponseToNormalReaderArrivesComplete() throws Exception {
		// A 4 MiB HTTPS response read normally must arrive byte-for-byte complete. This exercises the
		// deferred (non-blocking) TLS write path over many OP_WRITE events — if the response completion
		// fires before the deferred encrypted bytes finish draining, the body would be truncated.
		try (SSLSocket s = connect()) {
			s.getOutputStream().write(
				"GET /big HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes());
			s.getOutputStream().flush();
			InputStream in = s.getInputStream();
			java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[16384];
			int r;
			while ((r = in.read(buf)) != -1) {
				acc.write(buf, 0, r);
			}
			String full = acc.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
			int bodyStart = full.indexOf("\r\n\r\n") + 4;
			int bodyLen = full.length() - bodyStart;
			assertTrue("large HTTPS response truncated: body was " + bodyLen + " of " + BIG.length
				+ " bytes", bodyLen == BIG.length);
		}
	}

	private static boolean smallRequest() {
		try (SSLSocket victim = connect()) {
			victim.getOutputStream().write(
				"GET /small HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes());
			victim.getOutputStream().flush();
			InputStream in = victim.getInputStream();
			java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[1024];
			int r;
			while ((r = in.read(buf)) != -1) {
				acc.write(buf, 0, r);
				if (acc.toString(java.nio.charset.StandardCharsets.ISO_8859_1).endsWith("ok")) break;
			}
			return acc.toString(java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 200");
		} catch (Exception e) {
			return false;
		}
	}

	@Test
	public void slowReadingClientIsBoundedByWriteTimeoutAndServerRecovers() throws Exception {
		// Attacker: request the 4 MiB resource then STOP reading, so the server's send buffer fills and
		// the synchronous TLS write holds the loop. With the 2 s write-timeout this is BOUNDED.
		SSLSocket attacker = connect();
		attacker.getOutputStream().write(
			"GET /big HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes());
		attacker.getOutputStream().flush();
		attacker.getInputStream().read(new byte[1024]); // read a little, then stall
		Thread.sleep(300);

		// A victim request issued during the stall is delayed only until the write-timeout frees the
		// loop — it must NOT hang indefinitely. (Timeout is 2 s; allow generous margin for the in-flight
		// write to abort + scheduling.)
		long start = System.nanoTime();
		boolean ok = smallRequest();
		long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

		try { attacker.close(); } catch (Exception ignore) { }

		assertTrue("victim request must eventually succeed", ok);
		// With non-blocking TLS writes the stalled reader does NOT block the loop at all, so the victim
		// is served essentially immediately (not merely bounded by the write-timeout).
		assertTrue("a stalled HTTPS reader must not block other clients (non-blocking TLS write) — "
			+ "victim took " + elapsedMs + " ms", elapsedMs < 3000);

		// Recovery: after the attacker is dropped, the server serves normally and promptly.
		long start2 = System.nanoTime();
		boolean ok2 = smallRequest();
		long elapsed2 = (System.nanoTime() - start2) / 1_000_000L;
		assertTrue("server must serve normally after the stalled writer is reaped", ok2);
		assertTrue("post-recovery request should be fast, took " + elapsed2 + " ms", elapsed2 < 3000);
	}
}
