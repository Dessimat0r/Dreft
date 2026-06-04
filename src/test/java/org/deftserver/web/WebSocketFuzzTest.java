package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
import org.deftserver.web.http.WebSocketConnection;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Concurrent adversarial fuzzing of the WebSocket binary frame layer. Many connections upgrade for
 * real, then flood the server with malformed frames — unmasked client frames (RFC 6455 §5.1
 * violation), reserved opcodes, oversized/lying payload lengths, illegal control frames (FIN=0 or
 * &gt;125 bytes), invalid-UTF-8 text, fragmentation-state violations, truncated headers and pure
 * random bytes — all at once, interleaved with valid masked frames that must still echo correctly.
 * Invariant: the server cleanly closes each abusive connection, never crashes, and the legitimate
 * WebSocket traffic woven through the storm keeps working.
 */
public class WebSocketFuzzTest {

	private static HttpServer server;
	private static int PORT;

	private static class EchoWs extends WebSocketHandler {
		@Override public void onOpen(WebSocketConnection c) { }
		@Override public void onMessage(WebSocketConnection c, String m) { c.write("echo:" + m); }
		@Override public void onClose(WebSocketConnection c) { }
	}

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocket probe = new ServerSocket(0)) { PORT = probe.getLocalPort(); }
		Map<String, RequestHandler> h = new HashMap<>();
		h.put("/ws", new EchoWs());
		server = new HttpServer(new Application(h));
		server.bind(PORT);
		server.start(1);
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() {
		if (server != null) server.stop();
	}

	// --- WebSocket wire helpers ---

	/** Performs the opening handshake on {@code s}; returns true iff the server replied 101. */
	private static boolean handshake(Socket s) throws IOException {
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

	/** Builds a well-formed masked client text frame (FIN=1, opcode=0x1). */
	private static byte[] maskedTextFrame(String text, ThreadLocalRandom rnd) {
		byte[] payload = text.getBytes(StandardCharsets.UTF_8);
		byte[] key = new byte[4];
		rnd.nextBytes(key);
		java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
		f.write(0x81); // FIN + text
		if (payload.length < 126) {
			f.write(0x80 | payload.length); // MASK + len
		} else {
			f.write(0x80 | 126);
			f.write((payload.length >> 8) & 0xFF);
			f.write(payload.length & 0xFF);
		}
		f.write(key, 0, 4);
		for (int i = 0; i < payload.length; i++) f.write(payload[i] ^ key[i % 4]);
		return f.toByteArray();
	}

	/** Reads one unmasked server→client frame's text payload (used to validate echoes). */
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

	/** Builds one random malformed/abusive WebSocket frame (or raw garbage). */
	private static byte[] malformedFrame(ThreadLocalRandom rnd) {
		switch (rnd.nextInt(10)) {
			case 9: { // RSV bit set with no extension negotiated (server MUST fail — RFC 6455 §5.2)
				byte[] p = "rsv".getBytes(StandardCharsets.UTF_8);
				byte[] key = {9, 8, 7, 6};
				java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
				int rsv = (1 + rnd.nextInt(7)) << 4;     // one or more of RSV1/2/3 set
				f.write(0x80 | rsv | 0x1);               // FIN + RSV + text
				f.write(0x80 | p.length);                // MASK + len
				f.write(key, 0, 4);
				for (int i = 0; i < p.length; i++) f.write(p[i] ^ key[i % 4]);
				return f.toByteArray();
			}
			case 0: { // unmasked client text frame (server MUST close — RFC 6455 §5.1)
				byte[] p = "unmasked".getBytes(StandardCharsets.UTF_8);
				java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
				f.write(0x81); f.write(p.length); f.writeBytes(p); // no MASK bit
				return f.toByteArray();
			}
			case 1: { // reserved opcode (0x3..0x7) — masked but illegal opcode
				return new byte[]{(byte) (0x80 | (3 + rnd.nextInt(5))), (byte) 0x80, 1, 2, 3, 4};
			}
			case 2: { // 64-bit length with the high bit set (invalid) / oversized claim
				byte[] f = new byte[10];
				f[0] = (byte) 0x82; // binary
				f[1] = (byte) (0x80 | 127);
				f[2] = (byte) 0x80; // high bit set → negative/invalid length
				for (int i = 3; i < 10; i++) f[i] = (byte) rnd.nextInt(256);
				return f;
			}
			case 3: { // control frame (close, 0x8) with FIN=0 and >125 length (both illegal)
				byte[] f = new byte[6];
				f[0] = 0x08; // FIN=0, opcode close
				f[1] = (byte) (0x80 | 126); // len field 126 (>125 for a control frame)
				f[2] = 0; f[3] = (byte) 200;
				f[4] = 1; f[5] = 2;
				return f;
			}
			case 4: { // text frame with invalid UTF-8 (server MUST fail per §8.1)
				byte[] key = {1, 2, 3, 4};
				byte[] bad = {(byte) 0xFF, (byte) 0xFE, (byte) 0xC0};
				java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
				f.write(0x81); f.write(0x80 | bad.length); f.write(key, 0, 4);
				for (int i = 0; i < bad.length; i++) f.write(bad[i] ^ key[i % 4]);
				return f.toByteArray();
			}
			case 5: // continuation (0x0) with no message started
				return new byte[]{(byte) 0x80, (byte) 0x80, 5, 6, 7, 8};
			case 6: { // truncated header (claims extended length but no bytes follow)
				return new byte[]{(byte) 0x82, (byte) (0x80 | 127)};
			}
			case 7: { // huge masked payload length claim (16 MiB+1) but no data — exceeds the cap
				byte[] f = new byte[14];
				f[0] = (byte) 0x82;
				f[1] = (byte) (0x80 | 127);
				long len = 16L * 1024 * 1024 + 1;
				for (int i = 0; i < 8; i++) f[2 + i] = (byte) ((len >> (8 * (7 - i))) & 0xFF);
				f[10] = 1; f[11] = 2; f[12] = 3; f[13] = 4;
				return f;
			}
			default: { // pure random bytes
				byte[] p = new byte[1 + rnd.nextInt(64)];
				rnd.nextBytes(p);
				return p;
			}
		}
	}

	private static String session(boolean valid, ThreadLocalRandom rnd) {
		return sessionAtPort(PORT, valid, rnd);
	}

	/** Same as session but against a specific port. */
	private static String sessionAtPort(int port, boolean valid, ThreadLocalRandom rnd) {
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.setSoLinger(true, 0);
			s.setSoTimeout(15000);
			if (!handshake(s)) return "handshake failed";
			OutputStream os = s.getOutputStream();
			if (valid) {
				int msgs = 1 + rnd.nextInt(4);
				for (int i = 0; i < msgs; i++) {
					String msg = "m" + rnd.nextInt(100000);
					os.write(maskedTextFrame(msg, rnd));
					os.flush();
					String echoed = readServerTextFrame(s.getInputStream());
					if (!("echo:" + msg).equals(echoed)) {
						return "echo mismatch: expected echo:" + msg + " got " + echoed;
					}
				}
				return null;
			} else {
				// Flood several malformed frames, then close. We don't block reading a reply: some
				// "malformed" frames are merely incomplete (e.g. a truncated header), which the server
				// *correctly* waits on rather than closing — blocking here would just stall on the WS
				// idle timeout. The assertions are server liveness + valid-echo success, not what the
				// abusive connection receives.
				int n = 1 + rnd.nextInt(5);
				for (int i = 0; i < n; i++) {
					try { os.write(malformedFrame(rnd)); os.flush(); } catch (IOException closed) { break; }
				}
				return null;
			}
		} catch (Exception e) {
			// On the VALID path an exception is a real failure; on the malformed path it's expected.
			return valid ? "valid session exception: " + e : null;
		}
	}

	@Test
	public void concurrentMalformedFramesDoNotCrashAndValidEchoesSucceed() throws Exception {
		int threads = 40;
		int tasks = 1500;
		ExecutorService exec = Executors.newFixedThreadPool(threads);
		CountDownLatch done = new CountDownLatch(tasks);
		AtomicInteger validSent = new AtomicInteger();
		AtomicInteger validFailed = new AtomicInteger();
		ConcurrentLinkedQueue<String> notes = new ConcurrentLinkedQueue<>();
		try {
			for (int i = 0; i < tasks; i++) {
				exec.submit(() -> {
					try {
						ThreadLocalRandom rnd = ThreadLocalRandom.current();
						boolean valid = rnd.nextInt(3) == 0; // ~1/3 legitimate WS traffic
						if (valid) validSent.incrementAndGet();
						String err = session(valid, rnd);
						if (valid && err != null) {
							validFailed.incrementAndGet();
							if (notes.size() < 10) notes.add(err);
						}
					} finally {
						done.countDown();
					}
				});
			}
			assertTrue("WS fuzz storm did not finish in time", done.await(150, TimeUnit.SECONDS));
		} finally {
			exec.shutdownNow();
			exec.awaitTermination(15, TimeUnit.SECONDS);
		}
		assertEquals("valid WebSocket echoes interleaved with malformed-frame floods must all succeed ("
			+ validSent.get() + " sent): " + notes, 0, validFailed.get());
		// Final liveness: a clean WS handshake + echo still works.
		assertTrue("server must survive the WS fuzz storm", session(true, ThreadLocalRandom.current()) == null);
	}

	@Test
	public void webSocketFuzzThroughJitterProxy() throws Exception {
		int threads = 32;
		int tasks = 600;
		int proxyPort;
		try (ImpairmentProxy proxy = new ImpairmentProxy(PORT)) {
			proxy.toServer.chunkSize = 64;
			proxy.toServer.jitterMaxMs = 1;
			proxy.toClient.chunkSize = 1024;
			proxy.toClient.jitterMaxMs = 1;
			proxyPort = proxy.getPort();
			ExecutorService exec = Executors.newFixedThreadPool(threads);
			CountDownLatch done = new CountDownLatch(tasks);
			AtomicInteger validSent = new AtomicInteger();
			AtomicInteger validFailed = new AtomicInteger();
			ConcurrentLinkedQueue<String> notes = new ConcurrentLinkedQueue<>();
			try {
				for (int i = 0; i < tasks; i++) {
					exec.submit(() -> {
						try {
							ThreadLocalRandom rnd = ThreadLocalRandom.current();
							boolean valid = rnd.nextInt(3) == 0;
							if (valid) validSent.incrementAndGet();
							String err = sessionViaProxy(proxyPort, valid, rnd);
							if (valid && err != null) {
								validFailed.incrementAndGet();
								if (notes.size() < 10) notes.add(err);
							}
						} finally {
							done.countDown();
						}
					});
				}
				assertTrue("WS fuzz+proxy storm did not finish", done.await(150, TimeUnit.SECONDS));
			} finally {
				exec.shutdownNow();
				exec.awaitTermination(15, TimeUnit.SECONDS);
			}
			assertEquals("valid WS echoes through proxy must succeed (" + validSent.get()
				+ " sent): " + notes, 0, validFailed.get());
			assertTrue("server must survive WS fuzz through proxy",
				sessionViaProxy(proxyPort, true, ThreadLocalRandom.current()) == null);
		}
	}

	/** Same as {@link #session(boolean, ThreadLocalRandom)} but via a specific port. */
	private static String sessionViaProxy(int proxyPort, boolean valid, ThreadLocalRandom rnd) {
		return sessionAtPort(proxyPort, valid, rnd);
	}
}
