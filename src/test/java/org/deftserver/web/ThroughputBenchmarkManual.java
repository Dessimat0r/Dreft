package org.deftserver.web;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpProtocol;
import org.junit.Test;

/**
 * Manual throughput / scaling benchmark — NOT a correctness test. The class name doesn't match the
 * Surefire pattern, so it never runs in a normal {@code mvn test}. Run it explicitly:
 *
 *   mvn test -Dtest=ThroughputBenchmarkManual#benchmarkGetThroughput
 *   mvn test -Dtest=ThroughputBenchmarkManual#benchmarkParameterTreeScaling
 *
 * The throughput run is a CLOSED-LOOP load generator: N persistent keep-alive sockets, each tightly
 * looping raw write-request / read-response, with request count measured over a fixed window. Raw sockets
 * (rather than the JDK HttpClient) keep the generator allocation-lean and avoid the client becoming the
 * bottleneck.
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
		int connections = 100; // closed-loop keep-alive connections, each looping requests
		int warmupSec = 2;
		int measureSec = 5;

		// Measure pure keep-alive request throughput: lift the per-connection request cap so the server
		// doesn't force-close (and the generator reconnect) mid-measurement. Restored in finally.
		int savedKa = HttpProtocol.MAX_KEEP_ALIVE_REQUESTS;
		HttpProtocol.MAX_KEEP_ALIVE_REQUESTS = Integer.MAX_VALUE;
		try {
			System.out.println("\n=== Dreft GET throughput benchmark (raw keep-alive sockets) ===");
			System.out.printf("connections=%d warmup=%ds measure=%ds%n", connections, warmupSec, measureSec);
			for (int reactors : reactorCounts) {
				runThroughput(reactors, connections, warmupSec, measureSec);
			}
		} finally {
			HttpProtocol.MAX_KEEP_ALIVE_REQUESTS = savedKa;
		}
	}

	private void runThroughput(int reactors, int connections, int warmupSec, int measureSec) throws Exception {
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
		TestServerSupport.awaitListening(port);

		final byte[] reqBytes = ("GET /bench HTTP/1.1\r\nHost: localhost\r\n\r\n")
			.getBytes(StandardCharsets.ISO_8859_1); // keep-alive (no Connection: close)
		final LongAdder completed = new LongAdder();
		final AtomicInteger errors = new AtomicInteger(0);
		final AtomicInteger reconnects = new AtomicInteger(0);
		final AtomicBoolean stop = new AtomicBoolean(false);

		// One worker per connection: each opens its own keep-alive socket and tightly loops
		// write-request / read-response. If the server ever closes the connection (e.g. the keep-alive
		// request cap, or shutdown), the worker reconnects and continues — a realistic client, and robust
		// to a single connection dropping mid-run.
		Thread[] workers = new Thread[connections];
		for (int i = 0; i < connections; i++) {
			workers[i] = new Thread(() -> {
				StringBuilder line = new StringBuilder(128);
				byte[] bodyBuf = new byte[8192];
				while (!stop.get()) {
					try (Socket s = new Socket()) {
						s.setTcpNoDelay(true);
						s.connect(new InetSocketAddress("localhost", port), 2000);
						s.setSoTimeout(10_000);
						OutputStream os = s.getOutputStream();
						InputStream is = new BufferedInputStream(s.getInputStream(), 4096);
						while (!stop.get()) {
							os.write(reqBytes);
							os.flush();
							if (readOneResponse(is, line, bodyBuf)) {
								completed.increment();
							} else {
								reconnects.incrementAndGet(); // connection closed (or framing) → reconnect
								break;
							}
						}
					} catch (Exception e) {
						if (!stop.get()) errors.incrementAndGet();
					}
				}
			}, "bench-conn-" + i);
			workers[i].setDaemon(true);
			workers[i].start();
		}

		Thread.sleep(warmupSec * 1000L); // let JIT/GC settle, connections warm
		long c0 = completed.sum();
		long t0 = System.nanoTime();
		Thread.sleep(measureSec * 1000L);
		long elapsedNs = System.nanoTime() - t0;
		long measured = completed.sum() - c0;
		stop.set(true);
		for (Thread w : workers) w.join(2000); // each worker closes its own socket (try-with-resources)

		double rps = measured / (elapsedNs / 1_000_000_000.0);
		System.out.printf("reactors=%d  connections=%d  rps=%,.0f  completed=%,d  reconnects=%d  errors=%d%n",
			reactors, connections, rps, measured, reconnects.get(), errors.get());
		server.stop();
		Thread.sleep(150);
	}

	/** Reads exactly one Content-Length-framed HTTP/1.1 response (status line + headers + body), leaving
	 *  the stream positioned at the next response. Returns false on EOF / malformed framing / non-200.
	 *  Allocation-free in the hot path: caller passes a reusable line buffer and body scratch. */
	private static boolean readOneResponse(InputStream is, StringBuilder line, byte[] bodyBuf) throws java.io.IOException {
		int contentLength = -1;
		int status = -1;
		line.setLength(0);
		int lineNo = 0;
		int prev = -1, b;
		while (true) {
			b = is.read();
			if (b == -1) return false;
			if (b == '\n' && prev == '\r') {
				if (line.length() == 0) {
					break; // blank line → end of headers
				}
				if (lineNo == 0) {
					int sp = line.indexOf(" "); // "HTTP/1.1 200 OK"
					if (sp >= 0 && line.length() >= sp + 4) {
						try { status = Integer.parseInt(line.substring(sp + 1, sp + 4).trim()); } catch (NumberFormatException ignore) {}
					}
				} else {
					int colon = line.indexOf(":");
					if (colon > 0 && line.length() > colon + 1) {
						String name = line.substring(0, colon).trim();
						if (name.equalsIgnoreCase("content-length")) {
							try { contentLength = Integer.parseInt(line.substring(colon + 1).trim()); } catch (NumberFormatException ignore) {}
						}
					}
				}
				line.setLength(0);
				lineNo++;
				prev = -1;
				continue;
			}
			if (b != '\r') {
				if (prev == '\r') line.append('\r'); // a lone CR (not expected in valid headers)
				line.append((char) b);
			}
			prev = b;
		}
		if (status != 200 || contentLength < 0) return false;
		long remaining = contentLength;
		while (remaining > 0) {
			int n = is.read(bodyBuf, 0, (int) Math.min(bodyBuf.length, remaining));
			if (n == -1) return false;
			remaining -= n;
		}
		return true;
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
				org.deftserver.web.http.HttpRequest.of(java.nio.ByteBuffer.wrap(raw.getBytes(StandardCharsets.ISO_8859_1)));
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
