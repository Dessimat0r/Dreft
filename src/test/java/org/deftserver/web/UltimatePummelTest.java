package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.handler.WebSocketHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.deftserver.web.http.WebSocketConnection;
import org.junit.Test;

public class UltimatePummelTest {

	private static SSLSocketFactory trustAllFactory;

	static {
		try {
			SSLContext clientCtx = SSLContext.getInstance("TLS");
			clientCtx.init(null, new TrustManager[]{new X509TrustManager() {
				public void checkClientTrusted(X509Certificate[] c, String a) {}
				public void checkServerTrusted(X509Certificate[] c, String a) {}
				public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
			}}, new SecureRandom());
			trustAllFactory = clientCtx.getSocketFactory();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static SSLContext createServerSSLContext() throws Exception {
		KeyStore ks = KeyStore.getInstance("PKCS12");
		try (FileInputStream fis = new FileInputStream("src/test/resources/keystore.p12")) {
			ks.load(fis, "password".toCharArray());
		}
		KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
		kmf.init(ks, "password".toCharArray());
		SSLContext serverCtx = SSLContext.getInstance("TLS");
		serverCtx.init(kmf.getKeyManagers(), null, null);
		return serverCtx;
	}

	private static class EchoWs extends WebSocketHandler {
		@Override public void onOpen(WebSocketConnection c) {}
		@Override public void onMessage(WebSocketConnection c, String m) { c.write("echo:" + m); }
		@Override public void onClose(WebSocketConnection c) {}
	}

	private static Map<String, RequestHandler> handlers() {
		Map<String, RequestHandler> h = new HashMap<>();
		h.put("/ok", new RequestHandler() {
			@Override public void get(HttpRequest q, HttpResponse r) { r.write("ok"); }
		});
		h.put("/post", new RequestHandler() {
			@Override public void post(HttpRequest q, HttpResponse r) { r.write(q.getBody()); }
		});
		h.put("/heavy", new RequestHandler() {
			@Override public void get(HttpRequest q, HttpResponse r) {
				try { Thread.sleep(10); } catch (InterruptedException ignore) {}
				r.write("heavy");
			}
		});
		h.put("/ws", new EchoWs());
		return h;
	}

	private static Socket connectWithTimeout(int port, int connectTimeoutMs, int readTimeoutMs) throws IOException {
		Socket s = new Socket();
		s.setSoLinger(true, 0);
		s.setSoTimeout(readTimeoutMs);
		s.connect(new java.net.InetSocketAddress("127.0.0.1", port), connectTimeoutMs);
		return s;
	}

	private static SSLSocket connectSslWithTimeout(int port, int connectTimeoutMs, int readTimeoutMs) throws IOException {
		SSLSocket s = (SSLSocket) trustAllFactory.createSocket();
		s.setSoLinger(true, 0);
		s.setSoTimeout(readTimeoutMs);
		s.connect(new java.net.InetSocketAddress("127.0.0.1", port), connectTimeoutMs);
		return s;
	}

	private static boolean cleanPlaintextHttp(int port, ConcurrentLinkedQueue<String> errors) {
		try (Socket s = connectWithTimeout(port, 4000, 20000)) {
			s.getOutputStream().write("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
				.getBytes(StandardCharsets.ISO_8859_1));
			s.getOutputStream().flush();
			String resp = ConcurrentLoadTest.readOneResponse(s.getInputStream(), false);
			boolean ok = resp.startsWith("HTTP/1.1 200") && ConcurrentLoadTest.body(resp).equals("ok");
			if (!ok) {
				errors.add("cleanPlaintextHttp(port=" + port + ") bad response: '" + resp.substring(0, Math.min(80, resp.length())) + "'");
			}
			return ok;
		} catch (Exception e) {
			errors.add("cleanPlaintextHttp(port=" + port + ") exception: " + e);
			return false;
		}
	}

	private static boolean cleanPlaintextHttpPost(int port, ThreadLocalRandom rnd, ConcurrentLinkedQueue<String> errors) {
		try (Socket s = connectWithTimeout(port, 4000, 20000)) {
			String body = "posted";
			byte[] bodyBytes = body.getBytes(StandardCharsets.ISO_8859_1);
			s.getOutputStream().write(("POST /post HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: " 
				+ bodyBytes.length + "\r\n\r\n" + body).getBytes(StandardCharsets.ISO_8859_1));
			s.getOutputStream().flush();
			String resp = ConcurrentLoadTest.readOneResponse(s.getInputStream(), false);
			boolean ok = resp.startsWith("HTTP/1.1 200") && ConcurrentLoadTest.body(resp).equals("posted");
			if (!ok) {
				errors.add("cleanPlaintextHttpPost(port=" + port + ") bad response: '" + resp.substring(0, Math.min(80, resp.length())) + "'");
			}
			return ok;
		} catch (Exception e) {
			errors.add("cleanPlaintextHttpPost(port=" + port + ") exception: " + e);
			return false;
		}
	}

	private static boolean cleanHeavy(int port, ConcurrentLinkedQueue<String> errors) {
		try (Socket s = connectWithTimeout(port, 4000, 20000)) {
			s.getOutputStream().write("GET /heavy HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
				.getBytes(StandardCharsets.ISO_8859_1));
			s.getOutputStream().flush();
			String resp = ConcurrentLoadTest.readOneResponse(s.getInputStream(), false);
			boolean ok = resp.startsWith("HTTP/1.1 200") && ConcurrentLoadTest.body(resp).equals("heavy");
			if (!ok) {
				errors.add("cleanHeavy(port=" + port + ") bad response: '" + resp.substring(0, Math.min(80, resp.length())) + "'");
			}
			return ok;
		} catch (Exception e) {
			errors.add("cleanHeavy(port=" + port + ") exception: " + e);
			return false;
		}
	}

	private static boolean cleanHttps(int port, ConcurrentLinkedQueue<String> errors) {
		SSLSocket s = null;
		try {
			s = connectSslWithTimeout(port, 4000, 20000);
			s.startHandshake();
			s.getOutputStream().write("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
				.getBytes(StandardCharsets.ISO_8859_1));
			s.getOutputStream().flush();
			String resp = ConcurrentLoadTest.readOneResponse(s.getInputStream(), false);
			boolean ok = resp.startsWith("HTTP/1.1 200") && ConcurrentLoadTest.body(resp).equals("ok");
			if (!ok) {
				errors.add("cleanHttps(port=" + port + ") bad response: '" + resp.substring(0, Math.min(80, resp.length())) + "'");
			}
			return ok;
		} catch (Exception e) {
			errors.add("cleanHttps(port=" + port + ") exception: " + e);
			return false;
		} finally {
			if (s != null) {
				try { s.close(); } catch (Exception ignore) {}
			}
		}
	}

	private static boolean cleanWebSocket(int port, ThreadLocalRandom rnd, ConcurrentLinkedQueue<String> errors) {
		try (Socket s = connectWithTimeout(port, 4000, 20000)) {
			if (!wsHandshake(s)) {
				errors.add("cleanWebSocket(port=" + port + ") handshake failed");
				return false;
			}
			String msg = "testMsg";
			s.getOutputStream().write(maskedTextFrame(msg, rnd));
			s.getOutputStream().flush();
			String echoed = readServerTextFrame(s.getInputStream());
			boolean ok = ("echo:" + msg).equals(echoed);
			if (!ok) {
				errors.add("cleanWebSocket(port=" + port + ") bad echo: '" + echoed + "'");
			}
			return ok;
		} catch (Exception e) {
			errors.add("cleanWebSocket(port=" + port + ") exception: " + e);
			return false;
		}
	}

	private static void fuzzedPlaintextHttp(int port, ThreadLocalRandom rnd) {
		try (Socket s = connectWithTimeout(port, 1000, 1000)) {
			s.getOutputStream().write(FuzzPayloads.random(rnd));
			s.getOutputStream().flush();
			s.getInputStream().read(new byte[256]);
		} catch (Exception ignore) {}
	}

	private static void fuzzedHttps(int port, ThreadLocalRandom rnd) {
		try (Socket s = connectWithTimeout(port, 1000, 1000)) {
			s.getOutputStream().write(FuzzPayloads.random(rnd));
			s.getOutputStream().flush();
			s.getInputStream().read(new byte[256]);
		} catch (Exception ignore) {}
	}

	private static void badTlsRecords(int port, ThreadLocalRandom rnd) {
		try (Socket s = connectWithTimeout(port, 1000, 1000)) {
			s.getOutputStream().write(tlsGarbage(rnd));
			s.getOutputStream().flush();
			s.getInputStream().read(new byte[256]);
		} catch (Exception ignore) {}
	}

	private static void badWsFrames(int port, ThreadLocalRandom rnd) {
		try (Socket s = connectWithTimeout(port, 1000, 1000)) {
			if (wsHandshake(s)) {
				s.getOutputStream().write(malformedWsFrame(rnd));
				s.getOutputStream().flush();
				s.getInputStream().read(new byte[256]);
			}
		} catch (Exception ignore) {}
	}

	private static void slowloris(int port, ThreadLocalRandom rnd) {
		try (Socket s = connectWithTimeout(port, 1000, 3000)) {
			OutputStream os = s.getOutputStream();
			os.write("GET /ok HTTP/1.1\r\n".getBytes(StandardCharsets.ISO_8859_1));
			os.flush();
			Thread.sleep(15);
			os.write("Host: localhost\r\n".getBytes(StandardCharsets.ISO_8859_1));
			os.flush();
			Thread.sleep(15);
			os.write("X-Stall: ".getBytes(StandardCharsets.ISO_8859_1));
			os.flush();
			for (int i = 0; i < 10; i++) {
				os.write('A');
				os.flush();
				Thread.sleep(15);
			}
		} catch (Exception ignore) {}
	}

	private static void abruptDisconnect(int port, ThreadLocalRandom rnd) {
		try (Socket s = connectWithTimeout(port, 1000, 1000)) {
			s.getOutputStream().write("GET /".getBytes(StandardCharsets.ISO_8859_1));
			s.getOutputStream().flush();
		} catch (Exception ignore) {}
	}

	private static boolean wsHandshake(Socket s) throws IOException {
		s.getOutputStream().write((
			"GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
			+ "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
			.getBytes(StandardCharsets.ISO_8859_1));
		s.getOutputStream().flush();
		InputStream in = s.getInputStream();
		java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
		byte[] one = new byte[1];
		while (!acc.toString(StandardCharsets.ISO_8859_1).contains("\r\n\r\n")) {
			int r = in.read(one);
			if (r == -1) return false;
			acc.write(one, 0, r);
			if (acc.size() > 4096) return false;
		}
		return acc.toString(StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 101");
	}

	private static byte[] maskedTextFrame(String text, ThreadLocalRandom rnd) {
		byte[] payload = text.getBytes(StandardCharsets.UTF_8);
		byte[] key = new byte[4];
		rnd.nextBytes(key);
		java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
		f.write(0x81);
		if (payload.length < 126) {
			f.write(0x80 | payload.length);
		} else {
			f.write(0x80 | 126);
			f.write((payload.length >> 8) & 0xFF);
			f.write(payload.length & 0xFF);
		}
		f.write(key, 0, 4);
		for (int i = 0; i < payload.length; i++) f.write(payload[i] ^ key[i % 4]);
		return f.toByteArray();
	}

	private static String readServerTextFrame(InputStream in) throws IOException {
		int b0 = in.read();
		int b1 = in.read();
		if (b0 == -1 || b1 == -1) throw new IOException("EOF reading frame");
		long len = b1 & 0x7F;
		if (len == 126) {
			len = ((long) readByte(in) << 8) | readByte(in);
		} else if (len == 127) {
			len = 0;
			for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
		}
		byte[] payload = new byte[(int) len];
		int off = 0;
		while (off < payload.length) {
			int r = in.read(payload, off, payload.length - off);
			if (r == -1) throw new IOException("EOF reading payload");
			off += r;
		}
		return new String(payload, StandardCharsets.UTF_8);
	}

	private static int readByte(InputStream in) throws IOException {
		int b = in.read();
		if (b == -1) throw new IOException("EOF");
		return b;
	}

	private static byte[] tlsGarbage(ThreadLocalRandom rnd) {
		switch (rnd.nextInt(4)) {
			case 0: { byte[] b = new byte[1 + rnd.nextInt(512)]; rnd.nextBytes(b); return b; }
			case 1:
				{
					int len = rnd.nextInt(1024);
					byte[] b = new byte[5 + Math.min(len, 256)];
					b[0] = 22; b[1] = 3; b[2] = 3; b[3] = (byte) (len >> 8); b[4] = (byte) len;
					for (int i = 5; i < b.length; i++) b[i] = (byte) rnd.nextInt(256);
					return b;
				}
			case 2: return new byte[]{22, 3, 1};
			default: {
				byte[] b = new byte[10];
				b[0] = 23; b[1] = 3; b[2] = 3; b[3] = (byte) 0xFF; b[4] = (byte) 0xFF;
				for (int i = 5; i < 10; i++) b[i] = (byte) rnd.nextInt(256);
				return b;
			}
		}
	}

	private static byte[] malformedWsFrame(ThreadLocalRandom rnd) {
		switch (rnd.nextInt(5)) {
			case 0: {
				byte[] p = "unmasked".getBytes(StandardCharsets.UTF_8);
				java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
				f.write(0x81); f.write(p.length); f.writeBytes(p);
				return f.toByteArray();
			}
			case 1: {
				return new byte[]{(byte) (0x80 | (3 + rnd.nextInt(5))), (byte) 0x80, 1, 2, 3, 4};
			}
			case 2: {
				byte[] f = new byte[10];
				f[0] = (byte) 0x82;
				f[1] = (byte) (0x80 | 127);
				f[2] = (byte) 0x80;
				for (int i = 3; i < 10; i++) f[i] = (byte) rnd.nextInt(256);
				return f;
			}
			case 3: {
				return new byte[]{(byte) 0x82, (byte) (0x80 | 127)};
			}
			default: {
				byte[] p = new byte[1 + rnd.nextInt(32)];
				rnd.nextBytes(p);
				return p;
			}
		}
	}

	private void pummel(int plaintextPort, int sslPort, int proxyPlainPort, int proxySslPort, int threads, int tasks) throws Exception {
		ExecutorService exec = Executors.newFixedThreadPool(threads);
		CountDownLatch done = new CountDownLatch(tasks);
		AtomicInteger cleanSent = new AtomicInteger();
		AtomicInteger cleanFailed = new AtomicInteger();
		ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
		int printInterval = tasks / 10;
		if (printInterval == 0) printInterval = 1;
		final int interval = printInterval;

		for (int i = 0; i < tasks; i++) {
			exec.submit(() -> {
				try {
					ThreadLocalRandom rnd = ThreadLocalRandom.current();
					int choice = rnd.nextInt(12);
					int targetPort;
					switch (choice) {
						case 0:
							cleanSent.incrementAndGet();
							targetPort = rnd.nextBoolean() ? plaintextPort : proxyPlainPort;
							if (!cleanPlaintextHttp(targetPort, errors)) {
								cleanFailed.incrementAndGet();
							}
							break;
						case 1:
							cleanSent.incrementAndGet();
							targetPort = rnd.nextBoolean() ? plaintextPort : proxyPlainPort;
							if (!cleanPlaintextHttpPost(targetPort, rnd, errors)) {
								cleanFailed.incrementAndGet();
							}
							break;
						case 2:
							cleanSent.incrementAndGet();
							targetPort = rnd.nextBoolean() ? sslPort : proxySslPort;
							if (!cleanHttps(targetPort, errors)) {
								cleanFailed.incrementAndGet();
							}
							break;
						case 3:
							cleanSent.incrementAndGet();
							targetPort = rnd.nextBoolean() ? plaintextPort : proxyPlainPort;
							if (!cleanWebSocket(targetPort, rnd, errors)) {
								cleanFailed.incrementAndGet();
							}
							break;
						case 4:
							cleanSent.incrementAndGet();
							if (!cleanHeavy(plaintextPort, errors)) {
								cleanFailed.incrementAndGet();
							}
							break;
						case 5:
							fuzzedPlaintextHttp(rnd.nextBoolean() ? plaintextPort : proxyPlainPort, rnd);
							break;
						case 6:
							fuzzedHttps(rnd.nextBoolean() ? sslPort : proxySslPort, rnd);
							break;
						case 7:
							badTlsRecords(rnd.nextBoolean() ? sslPort : proxySslPort, rnd);
							break;
						case 8:
							badWsFrames(rnd.nextBoolean() ? plaintextPort : proxyPlainPort, rnd);
							break;
						case 9:
							slowloris(plaintextPort, rnd);
							break;
						case 10:
							abruptDisconnect(plaintextPort, rnd);
							break;
						default:
							cleanSent.incrementAndGet();
							boolean kaOk = true;
							String kaErr = null;
							targetPort = plaintextPort;
							try (Socket s = connectWithTimeout(targetPort, 4000, 20000)) {
								OutputStream os = s.getOutputStream();
								InputStream is = s.getInputStream();
								for (int k = 0; k < 5; k++) {
									String req = (k == 4)
										? "GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
										: "GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\n";
									os.write(req.getBytes(StandardCharsets.ISO_8859_1));
									os.flush();
									String resp;
									try {
										resp = ConcurrentLoadTest.readOneResponse(is, false);
									} catch (Exception e) {
										kaOk = false; kaErr = "read fail at loop " + k + ": " + e; break;
									}
									if (!resp.startsWith("HTTP/1.1 200") || !ConcurrentLoadTest.body(resp).equals("ok")) {
										kaOk = false;
										kaErr = "bad response at loop " + k + ": " + resp.substring(0, Math.min(80, resp.length()));
										break;
									}
								}
							} catch (Exception e) {
								kaOk = false;
								kaErr = "Exception: " + e;
							}
							if (!kaOk) {
								cleanFailed.incrementAndGet();
								errors.add("keep-alive serial requests failed (port=" + targetPort + "): " + kaErr);
							}
							break;
					}
				} finally {
					done.countDown();
					long completed = tasks - done.getCount();
					if (completed % interval == 0) {
						System.out.println("Completed " + completed + "/" + tasks + " tasks");
					}
				}
			});
		}

		try {
			assertTrue("Pummel storm did not finish in time", done.await(180, TimeUnit.SECONDS));
		} finally {
			exec.shutdownNow();
			exec.awaitTermination(15, TimeUnit.SECONDS);
		}

		assertEquals("Clean requests failed: " + errors, 0, cleanFailed.get());
		assertTrue("Server must survive and respond after storm", cleanPlaintextHttp(plaintextPort, errors));
		assertTrue("Server must survive and respond via HTTPS after storm", cleanHttps(sslPort, errors));
	}

	@Test
	public void ultimatePummelSingleReactor() throws Exception {
		int port;
		int sslPort;
		try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
		try (ServerSocket probe = new ServerSocket(0)) { sslPort = probe.getLocalPort(); }

		HttpServer s = new HttpServer(new Application(handlers()));
		s.enableSSL(createServerSSLContext());
		s.bind(port, false);
		s.bind(sslPort);
		s.start(1);

		try {
			TestServerSupport.awaitListening(port);
			TestServerSupport.awaitListening(sslPort);
			try (
				ImpairmentProxy plainProxy = new ImpairmentProxy(port);
				ImpairmentProxy sslProxy = new ImpairmentProxy(sslPort)
			) {
				plainProxy.toServer.chunkSize = 32;
				plainProxy.toServer.jitterMaxMs = 1;
				plainProxy.toClient.chunkSize = 256;
				plainProxy.toClient.jitterMaxMs = 1;

				sslProxy.toServer.chunkSize = 64;
				sslProxy.toServer.jitterMaxMs = 1;
				sslProxy.toClient.chunkSize = 512;
				sslProxy.toClient.jitterMaxMs = 1;

				pummel(port, sslPort, plainProxy.getPort(), sslProxy.getPort(), 16, 80);
			}
		} finally {
			s.stop();
		}
	}

	@Test
	public void ultimatePummelMultiReactor() throws Exception {
		int port;
		int sslPort;
		try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
		try (ServerSocket probe = new ServerSocket(0)) { sslPort = probe.getLocalPort(); }

		HttpServer s = new HttpServer(new Application(handlers()));
		s.enableSSL(createServerSSLContext());
		s.bind(port, false);
		s.bind(sslPort);
		s.start(4);

		try {
			TestServerSupport.awaitListening(port);
			TestServerSupport.awaitListening(sslPort);
			try (
				ImpairmentProxy plainProxy = new ImpairmentProxy(port);
				ImpairmentProxy sslProxy = new ImpairmentProxy(sslPort)
			) {
				plainProxy.toServer.chunkSize = 48;
				plainProxy.toServer.jitterMaxMs = 1;
				plainProxy.toClient.chunkSize = 1024;
				plainProxy.toClient.jitterMaxMs = 1;

				sslProxy.toServer.chunkSize = 64;
				sslProxy.toServer.jitterMaxMs = 1;
				sslProxy.toClient.chunkSize = 2048;
				sslProxy.toClient.jitterMaxMs = 1;

				pummel(port, sslPort, plainProxy.getPort(), sslProxy.getPort(), 32, 300);
			}
		} finally {
			s.stop();
		}
	}
}
