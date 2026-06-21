package org.deftserver.web;

import static org.junit.Assert.assertEquals;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Regression: a malformed/smuggling request (Transfer-Encoding+Content-Length, or conflicting duplicate
 * Content-Length) must ALWAYS be rejected with 4xx, even under heavy concurrent load — never accepted (2xx)
 * and never delivered as a corrupted response.
 *
 * <p>The bug this guards against: under sustained load the adaptive dispatcher could flag even a trivial
 * <em>terminal</em> handler ({@code BadRequestRequestHandler}, which serves the 400) as "heavy" and offload
 * it to a virtual thread. {@code proceedWithCompleteRequest} then called {@code response.finish()} on the
 * I/O loop for the {@code MalFormedHttpRequest} <em>regardless</em> of the offload, so the loop's
 * {@code finish()}/{@code flush()} raced the virtual thread's handler+framing+{@code finish()} on the SAME
 * {@code HttpResponse} — flushing a still-empty {@code 200}/{@code Content-Length: 0} while the vthread wrote
 * the real 400 body. The client then saw a corrupted "200 OK" for a request the server had correctly
 * rejected. Once the terminal handler was flagged heavy the race fired for nearly every malformed request
 * (bimodal: zero, or thousands). Driving heavy {@code /big} responses (which DO offload) alongside the
 * malformed vectors reproduces it; the fix gates the loop-side finish on {@code !isAsyncOrOffloaded}.
 */
public class OffloadedHandlerResponseRaceTest {

	private static HttpServer server;
	private static int PORT;
	private static final String BIG;
	static {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 40000; i++) sb.append("0123456789");
		BIG = sb.toString(); // ~400 KB → heavy response → adaptive offload to a virtual thread
	}

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocket probe = new ServerSocket(0)) { PORT = probe.getLocalPort(); }
		Map<String, RequestHandler> h = new HashMap<>();
		h.put("/echo", new RequestHandler() {
			@Override public void post(HttpRequest q, HttpResponse r) { r.write(q.getBody()); }
		});
		h.put("/big", new RequestHandler() {
			@Override public void get(HttpRequest q, HttpResponse r) { r.write(BIG); }
		});
		server = new HttpServer(new Application(h));
		server.bind(PORT);
		server.start(1);
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() {
		if (server != null) server.stop();
	}

	private static final String TE_AND_CL =
		"POST /echo HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nContent-Length: 3\r\nConnection: close\r\n\r\n0\r\n\r\n";
	private static final String CONFLICTING_CL =
		"POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\nContent-Length: 4\r\nConnection: close\r\n\r\nabc";

	/** Sends a request, reads a little of the response, and returns the 3-digit status (the trigger needs
	 *  the client to read only a little and close early, as a real client reading the first line would). */
	private static String statusOf(String request) {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
			s.setSoTimeout(8000);
			OutputStream os = s.getOutputStream();
			os.write(request.getBytes(StandardCharsets.ISO_8859_1));
			os.flush();
			InputStream in = s.getInputStream();
			byte[] buf = new byte[64];
			int n = in.read(buf);
			if (n <= 0) return "EOF";
			String line = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
			int sp = line.indexOf(' ');
			return sp >= 0 && line.length() >= sp + 4 ? line.substring(sp + 1, sp + 4) : line.trim();
		} catch (Exception e) {
			return "ERR";
		}
	}

	private static void hitBig() {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
			s.setSoTimeout(8000);
			s.getOutputStream().write("GET /big HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
				.getBytes(StandardCharsets.ISO_8859_1));
			s.getOutputStream().flush();
			InputStream in = s.getInputStream();
			byte[] buf = new byte[8192];
			while (in.read(buf) != -1) { /* drain */ }
		} catch (Exception ignore) { }
	}

	@Test
	public void smugglingVectorsAlwaysRejectedUnderHeavyOffloadLoad() throws Exception {
		final int threads = 48;
		final int rounds = 5000;
		ExecutorService exec = Executors.newFixedThreadPool(threads);
		final AtomicInteger accepted = new AtomicInteger();
		final ConcurrentLinkedQueue<String> bad = new ConcurrentLinkedQueue<>();
		final AtomicBoolean stop = new AtomicBoolean(false);
		CountDownLatch done = new CountDownLatch(rounds);
		try {
			// Background load: continuously hammer /big (heavy → offloaded) to keep the reactor busy and
			// get handlers flagged heavy.
			for (int i = 0; i < threads / 2; i++) {
				exec.submit(() -> { while (!stop.get()) hitBig(); });
			}
			for (int i = 0; i < rounds; i++) {
				final String vec = (i % 2 == 0) ? TE_AND_CL : CONFLICTING_CL;
				exec.submit(() -> {
					try {
						String st = statusOf(vec);
						if (st.startsWith("2")) {
							accepted.incrementAndGet();
							if (bad.size() < 10) bad.add((vec == TE_AND_CL ? "te-and-cl" : "conflicting-cl") + " → " + st);
						}
					} finally {
						done.countDown();
					}
				});
			}
			done.await(180, TimeUnit.SECONDS);
			stop.set(true);
		} finally {
			exec.shutdownNow();
			exec.awaitTermination(10, TimeUnit.SECONDS);
		}
		assertEquals("a smuggling vector was accepted (2xx) / corrupted under load: " + bad, 0, accepted.get());
	}
}
