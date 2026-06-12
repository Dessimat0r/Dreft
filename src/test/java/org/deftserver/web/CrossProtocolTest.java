package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.handler.WebSocketHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.deftserver.web.http.WebSocketConnection;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Simultaneous HTTP + WebSocket adversarial stress: many threads hit the same server with a mix of
 * — valid HTTP requests (GET/POST), fuzzed HTTP garbage, legitimate WebSocket handshakes + echo
 * exchanges, and malformed WebSocket frames — all at once. The invariant: the HTTP responses remain
 * correct, the WebSocket echoes remain correct, the server never crashes, and the I/O loop keeps
 * serving both protocols without cross-contamination. Tests single-loop and 4-reactor variants, and
 * a jitter-proxy variant where traffic is further fragmented.
 */
public class CrossProtocolTest {

	private static HttpServer server;
	private static int PORT;
	private static final String OK = "ok";

	// --- Handlers ---

	private static class HelloHandler extends RequestHandler {
		@Override public void get(HttpRequest r, HttpResponse w) { w.write(OK); }
		@Override public void post(HttpRequest r, HttpResponse w) { w.write(r.getBody()); }
	}

	private static class EchoWs extends WebSocketHandler {
		@Override public void onOpen(WebSocketConnection c) { }
		@Override public void onMessage(WebSocketConnection c, String m) { c.write("echo:" + m); }
		@Override public void onClose(WebSocketConnection c) { }
	}

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocket probe = new ServerSocket(0)) { PORT = probe.getLocalPort(); }
		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/ok", new HelloHandler());
		handlers.put("/echo", new HelloHandler());
		handlers.put("/ws", new EchoWs());
		server = new HttpServer(new Application(handlers));
		server.bind(PORT);
		server.start(1);
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() {
		if (server != null) server.stop();
	}

	// --- WebSocket wire helpers ---

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
		if (payload.length < 126) f.write(0x80 | payload.length);
		else { f.write(0x80 | 126); f.write((payload.length >> 8) & 0xFF); f.write(payload.length & 0xFF); }
		f.write(key, 0, 4);
		for (int i = 0; i < payload.length; i++) f.write(payload[i] ^ key[i % 4]);
		return f.toByteArray();
	}

	private static String readServerTextFrame(InputStream in) throws IOException {
		int b0 = in.read(); int b1 = in.read();
		if (b0 == -1 || b1 == -1) throw new IOException("EOF reading frame");
		long len = b1 & 0x7F;
		if (len == 126) { len = ((long) readByte(in) << 8) | readByte(in); }
		else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in); }
		byte[] payload = new byte[(int) len];
		int off = 0;
		while (off < payload.length) { int r = in.read(payload, off, payload.length - off); if (r == -1) throw new IOException("EOF"); off += r; }
		return new String(payload, StandardCharsets.UTF_8);
	}

	private static int readByte(InputStream in) throws IOException { int b = in.read(); if (b == -1) throw new IOException("EOF"); return b; }

	// --- Valid HTTP / WS tasks ---

	private static boolean validHttp(int port) {
		for (int attempt = 0; attempt < 5; attempt++) {
			try (Socket s = new Socket("127.0.0.1", port)) {
				s.setSoTimeout(15000);
				s.getOutputStream().write("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
					.getBytes(StandardCharsets.ISO_8859_1));
				s.getOutputStream().flush();
				String resp = ConcurrentLoadTest.readOneResponse(s.getInputStream(), false);
				if (resp.startsWith("HTTP/1.1 200") && ConcurrentLoadTest.body(resp).equals(OK)) {
					return true;
				}
			} catch (Exception e) {
				if (attempt == 4) {
					return false;
				}
				try { Thread.sleep(50 + attempt * 50); } catch (InterruptedException ignored) {}
			}
		}
		return false;
	}

	/** Completes a full WS handshake, exchanges several valid echoes, and closes. */
	private static String validWsSession(int port, ThreadLocalRandom rnd) {
		String lastError = null;
		for (int attempt = 0; attempt < 5; attempt++) {
			try (Socket s = new Socket("127.0.0.1", port)) {
				s.setSoTimeout(15000);
				if (!wsHandshake(s)) {
					lastError = "ws handshake failed";
					if (attempt < 4) {
						try { Thread.sleep(50 + attempt * 50); } catch (InterruptedException ignored) {}
						continue;
					}
					return lastError;
				}
				int msgs = 1 + rnd.nextInt(4);
				for (int i = 0; i < msgs; i++) {
					String msg = "m" + rnd.nextInt(100000);
					s.getOutputStream().write(maskedTextFrame(msg, rnd));
					s.getOutputStream().flush();
					String echoed = readServerTextFrame(s.getInputStream());
					if (!("echo:" + msg).equals(echoed)) return "echo mismatch: expected echo:" + msg + " got " + echoed;
				}
				return null;
			} catch (Exception e) {
				lastError = "ws exception: " + e;
				if (attempt < 4) {
					try { Thread.sleep(50 + attempt * 50); } catch (InterruptedException ignored) {}
				}
			}
		}
		return lastError;
	}

	/** Opens a WS, then floods malformed frames and drops the connection. */
	private static void malformedWsSession(int port, ThreadLocalRandom rnd) {
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.setSoLinger(true, 0);
			s.setSoTimeout(8000);
			if (!wsHandshake(s)) return;
			int n = 1 + rnd.nextInt(5);
			for (int i = 0; i < n; i++) {
				try {
					byte[] frame = switch (rnd.nextInt(6)) {
						case 0 -> new byte[]{(byte) 0x81, 0x05, 'u', 'n', 'm', 'a', 's', 'k', 'd'}; // unmasked
						case 1 -> new byte[]{(byte) (0x80 | (3 + rnd.nextInt(5))), (byte) 0x80, 1, 2, 3, 4}; // reserved opcode
						case 2 -> new byte[]{(byte) 0x82, (byte) (0x80 | 127)}; // truncated 64-bit length
						case 3 -> new byte[]{0x08, (byte) (0x80 | 126), 0, (byte) 200, 1, 2}; // bad close frame
						case 4 -> new byte[]{(byte) 0x80, (byte) 0x80, 5, 6, 7, 8}; // continuation w/o start
						default -> { byte[] r = new byte[1 + rnd.nextInt(64)]; rnd.nextBytes(r); yield r; }
					};
					s.getOutputStream().write(frame); s.getOutputStream().flush();
				} catch (IOException closed) { break; }
			}
		} catch (Exception ignore) { }
	}

	// --- Storm runner ---

	private void crossStorm(int port, int threads, int tasks) throws Exception {
		ExecutorService exec = Executors.newFixedThreadPool(threads);
		CountDownLatch done = new CountDownLatch(tasks);
		AtomicInteger httpSent = new AtomicInteger();
		AtomicInteger httpFailed = new AtomicInteger();
		AtomicInteger wsSent = new AtomicInteger();
		AtomicInteger wsFailed = new AtomicInteger();
		ConcurrentLinkedQueue<String> notes = new ConcurrentLinkedQueue<>();
		try {
			for (int i = 0; i < tasks; i++) {
				exec.submit(() -> {
					try {
						ThreadLocalRandom rnd = ThreadLocalRandom.current();
						int kind = rnd.nextInt(6);
						switch (kind) {
							case 0: // valid HTTP
								httpSent.incrementAndGet();
								if (!validHttp(port)) { httpFailed.incrementAndGet(); if (notes.size() < 10) notes.add("http"); }
								break;
							case 1: // valid WS
								wsSent.incrementAndGet();
								String wserr = validWsSession(port, rnd);
								if (wserr != null) { wsFailed.incrementAndGet(); if (notes.size() < 10) notes.add(wserr); }
								break;
							case 2: // fuzzed HTTP garbage
								fireGarbage(port, FuzzPayloads.random(rnd));
								break;
							case 3: // malformed WS frames
								malformedWsSession(port, rnd);
								break;
						case 4: // HTTP then WS on the same connection
								try (Socket s = new Socket("127.0.0.1", port)) {
									s.setSoLinger(true, 0);
									s.setSoTimeout(8000);
									s.getOutputStream().write(("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
										.getBytes(StandardCharsets.ISO_8859_1));
									s.getOutputStream().flush();
									try { s.getInputStream().read(new byte[256]); } catch (IOException ignore) { }
								} catch (Exception ignore) { }
								break;
						default: // raw bytes + disconnect
								try (Socket s = new Socket("127.0.0.1", port)) {
									s.setSoLinger(true, 0);
									s.setSoTimeout(3000);
									byte[] b = new byte[1 + rnd.nextInt(512)];
									rnd.nextBytes(b);
									s.getOutputStream().write(b);
									s.getOutputStream().flush();
								} catch (Exception ignore) { }
								break;
						}
					} finally { done.countDown(); }
				});
			}
			assertTrue("cross-protocol storm did not finish", done.await(240, TimeUnit.SECONDS));
		} finally {
			exec.shutdownNow(); exec.awaitTermination(15, TimeUnit.SECONDS);
		}
		assertEquals("valid HTTP must all succeed (" + httpSent.get() + " sent): " + notes, 0, httpFailed.get());
		assertEquals("valid WS echoes must all succeed (" + wsSent.get() + " sent): " + notes, 0, wsFailed.get());
		assertTrue("server live after cross-protocol storm", validHttp(port));
	}

	private static void fireGarbage(int port, byte[] payload) {
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.setSoLinger(true, 0);
			s.setSoTimeout(3000);
			s.getOutputStream().write(payload); s.getOutputStream().flush();
		} catch (IOException ignore) { }
	}

	@Test
	public void httpAndWebSocketMixedConcurrentStorm() throws Exception {
		crossStorm(PORT, 40, 2000);
	}

	@Test
	public void httpAndWebSocketMixedAcrossMultipleReactors() throws Exception {
		int mrPort;
		try (ServerSocket probe = new ServerSocket(0)) { mrPort = probe.getLocalPort(); }
		Map<String, RequestHandler> h = new HashMap<>();
		h.put("/ok", new HelloHandler()); h.put("/echo", new HelloHandler()); h.put("/ws", new EchoWs());
		HttpServer mr = new HttpServer(new Application(h));
		mr.bind(mrPort); mr.start(4);
		try {
			TestServerSupport.awaitListening(mrPort);
			crossStorm(mrPort, 48, 2500);
		} finally { mr.stop(); }
	}

	@Test
	public void httpAndWebSocketThroughJitterProxy() throws Exception {
		try (ImpairmentProxy proxy = new ImpairmentProxy(PORT)) {
			proxy.toServer.chunkSize = 64;
			proxy.toServer.jitterMaxMs = 1;
			proxy.toClient.chunkSize = 2048;
			proxy.toClient.jitterMaxMs = 1;
			crossStorm(proxy.getPort(), 40, 1500);
		}
	}
}
