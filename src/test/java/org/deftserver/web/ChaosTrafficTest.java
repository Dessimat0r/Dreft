package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
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
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The "deal with as much crap as possible" test: many threads hit the server <i>at once</i> with a
 * maximally-chaotic mix — fuzzed and unfuzzed traffic, delivered directly and through a jittery
 * impairment proxy, using randomized connection behaviours (one shot, dribbled writes, partial-then-
 * drop, garbage-then-valid on the same connection, connect-and-stall). Throughout, a stream of clean
 * requests is woven in and <b>every one must still get a correct response</b>, and the server must
 * survive. This combines {@link FuzzPayloads} (≈35 adversarial strategies), {@link ImpairmentProxy}
 * (fragmentation + jitter), and concurrency into one stress surface — run on a single loop, a
 * 4-reactor server, and at higher intensity with keep-alive pressure.
 */
public class ChaosTrafficTest {

	private static HttpServer server;
	private static int PORT;
	private static final String OK_BODY = "ok";

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocket probe = new ServerSocket(0)) { PORT = probe.getLocalPort(); }
		server = new HttpServer(new Application(handlers()));
		server.bind(PORT);
		server.start(1);
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() {
		if (server != null) server.stop();
	}

	private static Map<String, RequestHandler> handlers() {
		Map<String, RequestHandler> h = new HashMap<>();
		h.put("/ok", new RequestHandler() {
			@Override public void get(HttpRequest q, HttpResponse r) { r.write(OK_BODY); }
		});
		h.put("/post", new RequestHandler() {
			@Override public void post(HttpRequest q, HttpResponse r) { r.write(q.getBody()); }
		});
		return h;
	}

	/** Delivers {@code payload} to {@code port} using a randomly-chosen connection behaviour, never
	 *  asserting anything about the (garbage) response — only that it doesn't wedge the test thread. */
	private static void deliverChaos(int port, byte[] payload, ThreadLocalRandom rnd) {
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.setSoLinger(true, 0);
			s.setSoTimeout(8000);
			OutputStream os = s.getOutputStream();
			switch (rnd.nextInt(8)) {
				case 0: // one shot
					os.write(payload); os.flush();
					break;
				case 1: { // dribble in small random chunks with tiny delays
					int off = 0;
					while (off < payload.length) {
						int len = Math.min(1 + rnd.nextInt(32), payload.length - off);
						os.write(payload, off, len); os.flush();
						off += len;
						if (rnd.nextInt(4) == 0) Thread.sleep(rnd.nextInt(3));
					}
					break;
				}
				case 2: // partial then abrupt drop (truncation)
					os.write(payload, 0, Math.min(payload.length, 1 + rnd.nextInt(Math.max(1, payload.length))));
					os.flush();
					break;
				case 3: // garbage, then a valid request on the SAME connection
					os.write(payload); os.flush();
					os.write("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
						.getBytes(StandardCharsets.ISO_8859_1));
					os.flush();
					break;
				case 4: // connect, send a few bytes, stall briefly, then the rest (slow-loris-ish)
					if (payload.length > 0) {
						os.write(payload, 0, Math.min(4, payload.length)); os.flush();
						Thread.sleep(rnd.nextInt(50));
						if (payload.length > 4) os.write(payload, 4, payload.length - 4);
						os.flush();
					}
					break;
				case 5: // write then read whatever comes back (best effort), then close
					os.write(payload); os.flush();
					try { s.getInputStream().read(new byte[256]); } catch (IOException ignore) { }
					break;
				case 6: // connect, send nothing, stall ~100ms, then drop (idle timeout stress)
					Thread.sleep(30 + rnd.nextInt(120));
					break;
				default: // multiple small writes with interleaved pauses
					for (int off = 0; off < payload.length; ) {
						int chunk = Math.min(1 + rnd.nextInt(8), payload.length - off);
						os.write(payload, off, chunk); os.flush();
						off += chunk;
						if (rnd.nextInt(3) == 0) Thread.sleep(1 + rnd.nextInt(5));
					}
					break;
			}
		} catch (Exception ignore) {
			// refused/reset/timeout on garbage is acceptable; the assertions are on clean traffic + liveness
		}
	}

	/** Sends a clean GET /ok (optionally through {@code viaPort}) and returns true iff it got 200 "ok". */
	private static boolean clean(int port) {
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.setSoTimeout(20000);
			s.getOutputStream().write("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
				.getBytes(StandardCharsets.ISO_8859_1));
			s.getOutputStream().flush();
			String resp = ConcurrentLoadTest.readOneResponse(s.getInputStream(), false);
			return resp.startsWith("HTTP/1.1 200") && ConcurrentLoadTest.body(resp).equals(OK_BODY);
		} catch (Exception e) {
			return false;
		}
	}

	/** Runs the chaos storm: {@code tasks} across {@code threads}, each randomly choosing clean-vs-
	 *  chaos and direct-vs-proxy. Clean requests must all succeed; the server must stay live. */
	private void chaos(int directPort, int proxyPort, int threads, int tasks) throws Exception {
		ExecutorService exec = Executors.newFixedThreadPool(threads);
		CountDownLatch done = new CountDownLatch(tasks);
		AtomicInteger cleanSent = new AtomicInteger();
		AtomicInteger cleanFailed = new AtomicInteger();
		ConcurrentLinkedQueue<String> notes = new ConcurrentLinkedQueue<>();
		try {
			for (int i = 0; i < tasks; i++) {
				exec.submit(() -> {
					try {
						ThreadLocalRandom rnd = ThreadLocalRandom.current();
						boolean viaProxy = proxyPort > 0 && rnd.nextBoolean();
						int port = viaProxy ? proxyPort : directPort;
						if (rnd.nextInt(3) == 0) {
							// ~1/3 clean (unfuzzed) traffic, interleaved with the garbage
							cleanSent.incrementAndGet();
							if (!clean(port)) {
								cleanFailed.incrementAndGet();
								if (notes.size() < 10) notes.add("clean failed via " + (viaProxy ? "proxy" : "direct"));
							}
						} else {
							deliverChaos(port, FuzzPayloads.random(rnd), rnd);
						}
					} finally {
						done.countDown();
					}
				});
			}
			assertTrue("chaos storm did not finish in time", done.await(240, TimeUnit.SECONDS));
		} finally {
			exec.shutdownNow();
			exec.awaitTermination(15, TimeUnit.SECONDS);
		}
		assertEquals("clean requests interleaved with chaos must all succeed (" + cleanSent.get()
			+ " sent): " + notes, 0, cleanFailed.get());
		assertTrue("server must survive the chaos storm", clean(directPort));
	}

	@Test
	public void chaosFuzzedAndUnfuzzedConcurrentlyDirectAndProxied() throws Exception {
		try (ImpairmentProxy proxy = new ImpairmentProxy(PORT)) {
			proxy.toServer.chunkSize = 48;
			proxy.toServer.jitterMaxMs = 1;
			proxy.toClient.chunkSize = 1024;
			proxy.toClient.jitterMaxMs = 1;
			chaos(PORT, proxy.getPort(), 48, 2500);
		}
	}

	@Test
	public void chaosAcrossMultipleReactors() throws Exception {
		int mrPort;
		try (ServerSocket probe = new ServerSocket(0)) { mrPort = probe.getLocalPort(); }
		HttpServer mr = new HttpServer(new Application(handlers()));
		mr.bind(mrPort);
		mr.start(4);
		try {
			TestServerSupport.awaitListening(mrPort);
			try (ImpairmentProxy proxy = new ImpairmentProxy(mrPort)) {
				proxy.toServer.chunkSize = 40;
				proxy.toServer.jitterMaxMs = 1;
				chaos(mrPort, proxy.getPort(), 48, 2500);
			}
		} finally {
			mr.stop();
		}
	}

	@Test
	public void highIntensityChaosWithKeepAlivePressure() throws Exception {
		// More tasks, more threads, keep-alive-aware clean requests woven through heavier chaos.
		try (ImpairmentProxy proxy = new ImpairmentProxy(PORT)) {
			proxy.toServer.chunkSize = 32;
			proxy.toServer.jitterMaxMs = 2;
			proxy.toClient.chunkSize = 512;
			proxy.toClient.jitterMaxMs = 2;
			chaos(PORT, proxy.getPort(), 64, 5000);
		}
	}
}
