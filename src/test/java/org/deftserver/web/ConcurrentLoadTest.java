package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Concurrent load tests: many client threads simultaneously pummel the server with a varied mix of
 * requests — valid and malformed, small and large, Content-Length and chunked, keep-alive and close,
 * direct and through a jittery proxy — to flush out concurrency/load bugs (accept races, per-channel
 * state corruption, timeout-queue churn, the multi-reactor accept path). The invariant under test is
 * the robustness mandate: <b>every</b> well-formed request gets the right response, every malformed
 * one gets a clean 4xx, and the server never crashes, hangs, or corrupts a response — no matter how
 * much strange traffic hits it at once.
 */
public class ConcurrentLoadTest {

	private static HttpServer server;
	private static int PORT;
	private static final String BIG = "Z".repeat(48 * 1024);

	/** One request template plus a predicate over the full raw response. */
	private record Scenario(String name, String request, boolean head, Predicate<String> ok) { }

	private static final List<Scenario> VALID = new ArrayList<>();
	private static final List<Scenario> MALFORMED = new ArrayList<>();
	static {
		VALID.add(new Scenario("get-ok",
			"GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", false,
			r -> r.startsWith("HTTP/1.1 200") && body(r).equals("ok")));
		VALID.add(new Scenario("post-echo",
			"POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 11\r\nConnection: close\r\n\r\nhello world", false,
			r -> r.startsWith("HTTP/1.1 200") && body(r).equals("hello world")));
		VALID.add(new Scenario("post-chunked",
			"POST /echo HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
				+ "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n", false,
			r -> r.startsWith("HTTP/1.1 200") && body(r).equals("Wikipedia")));
		VALID.add(new Scenario("get-big",
			"GET /big HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", false,
			r -> r.startsWith("HTTP/1.1 200") && body(r).equals(BIG)));
		VALID.add(new Scenario("get-404",
			"GET /does-not-exist HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", false,
			r -> r.startsWith("HTTP/1.1 404")));
		VALID.add(new Scenario("head-ok",
			"HEAD /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", true,
			r -> r.startsWith("HTTP/1.1 200") && body(r).isEmpty()));

		MALFORMED.add(new Scenario("missing-host",
			"GET /ok HTTP/1.1\r\nConnection: close\r\n\r\n", false,
			r -> r.startsWith("HTTP/1.1 400")));
		MALFORMED.add(new Scenario("conflicting-cl",
			"POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\nContent-Length: 4\r\nConnection: close\r\n\r\nabc", false,
			r -> r.startsWith("HTTP/1.1 400")));
		MALFORMED.add(new Scenario("te-and-cl",
			"POST /echo HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nContent-Length: 3\r\nConnection: close\r\n\r\n0\r\n\r\n", false,
			r -> r.startsWith("HTTP/1.1 400")));
		MALFORMED.add(new Scenario("bad-version",
			"GET /ok HTTP/9.9\r\nHost: localhost\r\nConnection: close\r\n\r\n", false,
			r -> r.startsWith("HTTP/1.1 505")));
	}

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocket probe = new ServerSocket(0)) {
			PORT = probe.getLocalPort();
		}
		server = new HttpServer(new Application(handlers()));
		server.bind(PORT);
		server.start(1);
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() {
		if (server != null) {
			server.stop();
		}
	}

	private static Map<String, RequestHandler> handlers() {
		Map<String, RequestHandler> h = new HashMap<>();
		h.put("/ok", new RequestHandler() {
			@Override public void get(HttpRequest q, HttpResponse r) { r.write("ok"); }
		});
		h.put("/echo", new RequestHandler() {
			@Override public void post(HttpRequest q, HttpResponse r) { r.write(q.getBody()); }
		});
		h.put("/big", new RequestHandler() {
			@Override public void get(HttpRequest q, HttpResponse r) { r.write(BIG); }
		});
		return h;
	}

	// --- response framing (Content-Length / chunked / bodiless aware, tolerant of fragmentation) ---

	static String readOneResponse(InputStream in, boolean head) throws IOException {
		ByteArrayOutputStream acc = new ByteArrayOutputStream();
		int headerEnd = -1;
		byte[] one = new byte[1];
		while (headerEnd == -1) {
			int r = in.read(one);
			if (r == -1) throw new IOException("EOF before response headers");
			acc.write(one, 0, r);
			int idx = acc.toString(StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n");
			if (idx != -1) headerEnd = idx + 4;
		}
		String header = acc.toString(StandardCharsets.ISO_8859_1).substring(0, headerEnd);
		String lower = header.toLowerCase(Locale.ROOT);
		int status = Integer.parseInt(header.substring(9, 12).trim());
		if (head || status == 204 || status == 304 || status / 100 == 1) {
			return acc.toString(StandardCharsets.ISO_8859_1);
		}
		if (lower.contains("transfer-encoding: chunked")) {
			byte[] b = new byte[1];
			while (!acc.toString(StandardCharsets.ISO_8859_1).endsWith("0\r\n\r\n")) {
				int r = in.read(b);
				if (r == -1) break;
				acc.write(b, 0, r);
			}
			return acc.toString(StandardCharsets.ISO_8859_1);
		}
		int cl = lower.indexOf("content-length:");
		if (cl != -1) {
			int eol = lower.indexOf("\r\n", cl);
			int contentLength = Integer.parseInt(header.substring(cl + 15, eol).trim());
			int have = acc.size() - headerEnd;
			byte[] buf = new byte[8192];
			while (have < contentLength) {
				int r = in.read(buf, 0, Math.min(buf.length, contentLength - have));
				if (r == -1) break;
				acc.write(buf, 0, r);
				have += r;
			}
		}
		return acc.toString(StandardCharsets.ISO_8859_1);
	}

	static String body(String full) {
		int idx = full.indexOf("\r\n\r\n");
		return idx == -1 ? "" : full.substring(idx + 4);
	}

	/** Sends one scenario over a fresh connection; returns null on success or an error description. */
	private static String runScenario(int targetPort, Scenario sc) {
		try (Socket s = new Socket("127.0.0.1", targetPort)) {
			s.setSoTimeout(15000);
			OutputStream os = s.getOutputStream();
			os.write(sc.request().getBytes(StandardCharsets.ISO_8859_1));
			os.flush();
			String resp = readOneResponse(s.getInputStream(), sc.head());
			if (!sc.ok().test(resp)) {
				return sc.name() + " → unexpected: " + resp.substring(0, Math.min(48, resp.length()));
			}
			return null;
		} catch (Exception e) {
			return sc.name() + " → exception: " + e;
		}
	}

	/** Drives {@code totalRequests} across {@code threads} workers, each picking from {@code pool} at
	 *  random, against {@code targetPort}. Asserts zero failures and that the server is still live. */
	private void hammer(int targetPort, List<Scenario> pool, int threads, int totalRequests) throws Exception {
		ExecutorService exec = Executors.newFixedThreadPool(threads);
		CountDownLatch done = new CountDownLatch(totalRequests);
		AtomicInteger failures = new AtomicInteger();
		ConcurrentLinkedQueue<String> firstFailures = new ConcurrentLinkedQueue<>();
		try {
			for (int i = 0; i < totalRequests; i++) {
				exec.submit(() -> {
					try {
						Scenario sc = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
						String err = runScenario(targetPort, sc);
						if (err != null) {
							failures.incrementAndGet();
							if (firstFailures.size() < 10) firstFailures.add(err);
						}
					} finally {
						done.countDown();
					}
				});
			}
			assertTrue("load run did not finish in time", done.await(120, TimeUnit.SECONDS));
		} finally {
			exec.shutdownNow();
			exec.awaitTermination(10, TimeUnit.SECONDS);
		}
		assertEquals("concurrent load produced failures: " + firstFailures, 0, failures.get());
		// Liveness: a clean request still works after the storm.
		assertTrue("server must remain live after the load", runScenario(targetPort, VALID.get(0)) == null);
	}

	@Test
	public void concurrentValidTrafficSingleLoop() throws Exception {
		hammer(PORT, VALID, 40, 2000);
	}

	@Test
	public void concurrentValidAndMalformedTraffic() throws Exception {
		List<Scenario> mixed = new ArrayList<>(VALID);
		mixed.addAll(MALFORMED);
		// Malformed requests must each get a clean 4xx/5xx and must not destabilise the loop for the
		// concurrent valid traffic interleaved with them.
		hammer(PORT, mixed, 48, 2400);
	}

	@Test
	public void concurrentTrafficThroughJitterProxy() throws Exception {
		try (ImpairmentProxy proxy = new ImpairmentProxy(PORT)) {
			proxy.toServer.chunkSize = 64;
			proxy.toServer.jitterMaxMs = 1;
			proxy.toClient.chunkSize = 2048;
			proxy.toClient.jitterMaxMs = 1;
			List<Scenario> mixed = new ArrayList<>(VALID);
			mixed.addAll(MALFORMED);
			// Lighter count: each request is fragmented + jitter-delayed, so this is much slower.
			hammer(proxy.getPort(), mixed, 24, 400);
		}
	}

	@Test
	public void concurrentKeepAliveSessions() throws Exception {
		// Many persistent connections, each issuing a long sequence of keep-alive requests, all at
		// once — stresses per-connection keep-alive state (request counter, idle-timeout prolong,
		// read-buffer reuse across requests) under concurrency rather than connection-per-request.
		int threads = 30;
		int reqsPerConn = 25;
		ExecutorService exec = Executors.newFixedThreadPool(threads);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicInteger failures = new AtomicInteger();
		ConcurrentLinkedQueue<String> firstFailures = new ConcurrentLinkedQueue<>();
		try {
			for (int t = 0; t < threads; t++) {
				exec.submit(() -> {
					try (Socket s = new Socket("127.0.0.1", PORT)) {
						s.setSoTimeout(15000);
						OutputStream os = s.getOutputStream();
						InputStream is = s.getInputStream();
						for (int i = 0; i < reqsPerConn; i++) {
							boolean last = (i == reqsPerConn - 1);
							// Alternate GET /ok and POST /echo on the same persistent connection.
							String req = (i % 2 == 0)
								? "GET /ok HTTP/1.1\r\nHost: localhost\r\n" + (last ? "Connection: close\r\n" : "") + "\r\n"
								: "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n"
									+ (last ? "Connection: close\r\n" : "") + "\r\nxyz";
							os.write(req.getBytes(StandardCharsets.ISO_8859_1));
							os.flush();
							String resp = readOneResponse(is, false);
							String expect = (i % 2 == 0) ? "ok" : "xyz";
							if (!resp.startsWith("HTTP/1.1 200") || !body(resp).equals(expect)) {
								failures.incrementAndGet();
								if (firstFailures.size() < 10) {
									firstFailures.add("req " + i + " → " + resp.substring(0, Math.min(48, resp.length())));
								}
								return;
							}
						}
					} catch (Exception e) {
						failures.incrementAndGet();
						if (firstFailures.size() < 10) firstFailures.add("exception: " + e);
					} finally {
						done.countDown();
					}
				});
			}
			assertTrue("keep-alive load did not finish in time", done.await(120, TimeUnit.SECONDS));
		} finally {
			exec.shutdownNow();
			exec.awaitTermination(10, TimeUnit.SECONDS);
		}
		assertEquals("concurrent keep-alive sessions produced failures: " + firstFailures, 0, failures.get());
		assertTrue("server live after keep-alive storm", runScenario(PORT, VALID.get(0)) == null);
	}

	@Test
	public void concurrentTrafficAcrossMultipleReactors() throws Exception {
		// Exercise the multi-reactor accept path: N I/O loops sharing one ServerSocketChannel for
		// OP_ACCEPT, with concurrent connections racing across them.
		int mrPort;
		try (ServerSocket probe = new ServerSocket(0)) { mrPort = probe.getLocalPort(); }
		HttpServer mr = new HttpServer(new Application(handlers()));
		mr.bind(mrPort);
		mr.start(4); // four reactor threads
		try {
			TestServerSupport.awaitListening(mrPort);
			hammer(mrPort, VALID, 48, 2400);
		} finally {
			mr.stop();
		}
	}
}
