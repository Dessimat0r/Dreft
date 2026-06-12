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
 * Concurrent adversarial fuzzing: many threads fire randomly-mutated, malformed requests at the
 * server <i>simultaneously</i>, interleaved with valid traffic that must keep getting correct
 * responses. Where {@code HttpFuzzTest} fuzzes serially (does any single input crash the parser?),
 * this hunts the rarer failure mode — does a storm of concurrent garbage corrupt shared state,
 * starve the loop, or disrupt the legitimate requests woven through it? The invariant: the server
 * never crashes, the valid requests interleaved with the garbage all succeed, and the server is
 * still live afterwards. Includes multi-reactor, jitter-proxy, and higher-intensity variants.
 */
public class ConcurrentFuzzTest {

	private static HttpServer server;
	private static int PORT;

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
			@Override public void get(HttpRequest q, HttpResponse r) { r.write("ok"); }
		});
		h.put("/post", new RequestHandler() {
			@Override public void post(HttpRequest q, HttpResponse r) { r.write("posted"); }
		});
		return h;
	}

	/** Writes garbage and closes without waiting — the server must process it (or see EOF) safely. */
	private static void fireGarbage(int port, byte[] payload) {
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.setSoLinger(true, 0);
			s.setSoTimeout(3000);
			OutputStream os = s.getOutputStream();
			os.write(payload);
			os.flush();
		} catch (IOException ignore) {
		}
	}

	/** Sends a valid GET /ok and returns true iff it got a correct 200 "ok". */
	private static boolean validOk(int port) {
		for (int attempt = 0; attempt < 5; attempt++) {
			try (Socket s = new Socket("127.0.0.1", port)) {
				s.setSoTimeout(15000);
				s.getOutputStream().write(
					"GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
						.getBytes(StandardCharsets.ISO_8859_1));
				s.getOutputStream().flush();
				String resp = ConcurrentLoadTest.readOneResponse(s.getInputStream(), false);
				if (resp.startsWith("HTTP/1.1 200") && ConcurrentLoadTest.body(resp).equals("ok")) {
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

	/** Runs the concurrent storm against {@code port}: each task either fires garbage (~75%) or sends
	 *  a valid request (~25%) that must succeed. Asserts zero valid-request failures + final liveness. */
	private void storm(int port, int threads, int tasks, int timeoutSec) throws Exception {
		ExecutorService exec = Executors.newFixedThreadPool(threads);
		CountDownLatch done = new CountDownLatch(tasks);
		AtomicInteger validSent = new AtomicInteger();
		AtomicInteger validFailed = new AtomicInteger();
		try {
			for (int i = 0; i < tasks; i++) {
				exec.submit(() -> {
					try {
						ThreadLocalRandom rnd = ThreadLocalRandom.current();
						if (rnd.nextInt(4) == 0) {
							validSent.incrementAndGet();
							if (!validOk(port)) validFailed.incrementAndGet();
						} else {
							fireGarbage(port, FuzzPayloads.random(rnd));
						}
					} finally {
						done.countDown();
					}
				});
			}
			assertTrue("storm did not finish in time", done.await(timeoutSec, TimeUnit.SECONDS));
		} finally {
			exec.shutdownNow();
			exec.awaitTermination(10, TimeUnit.SECONDS);
		}
		assertEquals("valid requests interleaved with concurrent garbage must all succeed ("
			+ validSent.get() + " sent)", 0, validFailed.get());
		assertTrue("server must survive the concurrent fuzz storm", validOk(port));
	}

	@Test
	public void concurrentFuzzStormDoesNotCrashOrDisruptValidTraffic() throws Exception {
		storm(PORT, 48, 3000, 120);
	}

	@Test
	public void concurrentFuzzStormAcrossMultipleReactors() throws Exception {
		int mrPort;
		try (ServerSocket probe = new ServerSocket(0)) { mrPort = probe.getLocalPort(); }
		HttpServer mr = new HttpServer(new Application(handlers()));
		mr.bind(mrPort);
		mr.start(4);
		try {
			TestServerSupport.awaitListening(mrPort);
			storm(mrPort, 48, 3000, 120);
		} finally {
			mr.stop();
		}
	}

	@Test
	public void concurrentFuzzStormThroughJitterProxy() throws Exception {
		try (ImpairmentProxy proxy = new ImpairmentProxy(PORT)) {
			proxy.toServer.chunkSize = 40;
			proxy.toServer.jitterMaxMs = 1;
			proxy.toClient.chunkSize = 1024;
			proxy.toClient.jitterMaxMs = 1;
			storm(proxy.getPort(), 32, 1500, 180); // lighter: fragmented + jittered incurs proxy overhead
		}
	}

	@Test
	public void concurrentHighIntensityFuzzSingleLoop() throws Exception {
		storm(PORT, 64, 6000, 180); // 2× the default intensity
	}
}
