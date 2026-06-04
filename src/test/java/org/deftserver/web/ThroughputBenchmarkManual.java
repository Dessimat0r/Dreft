package org.deftserver.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.deftserver.web.handler.RequestHandler;
import org.junit.Test;

/**
 * Manual throughput / scaling benchmark — NOT a correctness test. The class name doesn't match the
 * Surefire pattern, so it never runs in a normal {@code mvn test}. Run it explicitly:
 *
 *   mvn test -Dtest=ThroughputBenchmarkManual#benchmarkGetThroughput
 *   mvn test -Dtest=ThroughputBenchmarkManual#benchmarkParameterTreeScaling
 *
 * It reports request/sec and latency percentiles for plaintext GETs at a few reactor counts, and
 * verifies the V3-38 nested-parameter parser scales linearly (not O(n^2)).
 */
public class ThroughputBenchmarkManual {

	private static class PlainGetHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.setHeader("Content-Type", "text/plain");
			response.write("Hello, Dreft benchmark world! This is a representative small response body.");
		}
	}

	@Test
	public void benchmarkGetThroughput() throws Exception {
		int[] reactorCounts = {1, 2, 4};
		int totalRequests = 60_000;
		int concurrency = 200;

		System.out.println("\n=== Dreft GET throughput benchmark ===");
		System.out.printf("requests=%d concurrency=%d%n", totalRequests, concurrency);

		for (int reactors : reactorCounts) {
			runThroughput(reactors, totalRequests, concurrency);
		}
	}

	private void runThroughput(int reactors, int totalRequests, int concurrency) throws Exception {
		int port;
		try (ServerSocketChannel ssc = ServerSocketChannel.open()) {
			ssc.bind(new InetSocketAddress(0));
			port = ssc.socket().getLocalPort();
		}
		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/bench", new PlainGetHandler());
		HttpServer server = new HttpServer(new Application(handlers));
		server.bind(port);
		server.start(reactors);
		Thread.sleep(400);

		HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
		URI uri = URI.create("http://localhost:" + port + "/bench");
		HttpRequest req = HttpRequest.newBuilder().uri(uri).GET().build();

		// Warmup
		warmup(client, req, 5_000, 100);

		final long[] latencies = new long[totalRequests];
		final AtomicInteger idx = new AtomicInteger(0);
		final AtomicInteger errors = new AtomicInteger(0);
		final AtomicLong dispatched = new AtomicLong(0);

		ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
		CountDownLatch done = new CountDownLatch(totalRequests);
		long t0 = System.nanoTime();
		// Bound in-flight requests to `concurrency`.
		java.util.concurrent.Semaphore inflight = new java.util.concurrent.Semaphore(concurrency);
		for (int i = 0; i < totalRequests; i++) {
			inflight.acquire();
			pool.submit(() -> {
				long s = System.nanoTime();
				try {
					HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
					if (r.statusCode() != 200) errors.incrementAndGet();
				} catch (Exception e) {
					errors.incrementAndGet();
				} finally {
					long e = System.nanoTime() - s;
					int k = idx.getAndIncrement();
					if (k < latencies.length) latencies[k] = e;
					inflight.release();
					done.countDown();
				}
			});
		}
		done.await();
		long elapsedNs = System.nanoTime() - t0;
		pool.shutdownNow();
		server.stop();
		Thread.sleep(150);

		java.util.Arrays.sort(latencies);
		double seconds = elapsedNs / 1_000_000_000.0;
		double rps = totalRequests / seconds;
		System.out.printf(
			"reactors=%d  rps=%,.0f  errors=%d  p50=%.2fms p90=%.2fms p99=%.2fms max=%.2fms%n",
			reactors, rps, errors.get(),
			latencies[(int) (latencies.length * 0.50)] / 1_000_000.0,
			latencies[(int) (latencies.length * 0.90)] / 1_000_000.0,
			latencies[(int) (latencies.length * 0.99)] / 1_000_000.0,
			latencies[latencies.length - 1] / 1_000_000.0);
	}

	private void warmup(HttpClient client, HttpRequest req, int n, int conc) throws Exception {
		ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
		java.util.concurrent.Semaphore sem = new java.util.concurrent.Semaphore(conc);
		CountDownLatch latch = new CountDownLatch(n);
		for (int i = 0; i < n; i++) {
			sem.acquire();
			pool.submit(() -> {
				try { client.send(req, HttpResponse.BodyHandlers.discarding()); }
				catch (Exception ignore) {}
				finally { sem.release(); latch.countDown(); }
			});
		}
		latch.await();
		pool.shutdownNow();
	}

	/**
	 * Micro-benchmark proving the V3-38 nested-parameter tree builder is O(n), not O(n^2): the time
	 * for a 10x larger array-param body must grow ~10x, not ~100x.
	 */
	@Test
	public void benchmarkParameterTreeScaling() {
		System.out.println("\n=== getParametersTree() scaling (V3-38, must be ~linear) ===");
		int[] sizes = {1_000, 2_000, 4_000, 8_000};
		double prevMs = -1;
		for (int n : sizes) {
			StringBuilder body = new StringBuilder();
			for (int i = 0; i < n; i++) {
				if (i > 0) body.append('&');
				body.append("a[]=").append(i);
			}
			String raw = "POST /p HTTP/1.1\r\nHost: localhost\r\n"
				+ "Content-Type: application/x-www-form-urlencoded\r\n"
				+ "Content-Length: " + body.length() + "\r\n\r\n" + body;
			org.deftserver.web.http.HttpRequest request =
				org.deftserver.web.http.HttpRequest.of(java.nio.ByteBuffer.wrap(raw.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
			// Time repeated tree builds (uncached, so each call does the full work).
			long t0 = System.nanoTime();
			int reps = 50;
			int lastSize = 0;
			for (int r = 0; r < reps; r++) {
				@SuppressWarnings("unchecked")
				Map<String, Object> a = (Map<String, Object>) request.getParametersTree().get("a");
				lastSize = a.size();
			}
			double ms = (System.nanoTime() - t0) / 1_000_000.0 / reps;
			String ratio = prevMs < 0 ? "" : String.format("  (%.1fx prev for 2x n)", ms / prevMs);
			System.out.printf("n=%-6d builtSize=%-6d perBuild=%.3fms%s%n", n, lastSize, ms, ratio);
			prevMs = ms;
		}
		System.out.println("(ratios near ~2x per size-doubling => linear; near ~4x => quadratic regression)");
	}
}
