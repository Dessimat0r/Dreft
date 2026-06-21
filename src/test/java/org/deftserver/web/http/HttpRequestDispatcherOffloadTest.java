package org.deftserver.web.http;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.util.HashMap;
import java.util.Map;

import org.deftserver.web.Application;
import org.deftserver.web.HttpServer;
import org.deftserver.web.HttpVerb;
import org.deftserver.web.handler.RequestHandler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Verifies the adaptive-offload decision (P44): a slow handler that merely *buffers* its response is
 * flagged for virtual-thread offload, but a slow handler that performs network I/O (flush/stream/file)
 * during dispatch is NOT — because its NIO must stay on the I/O-loop thread (offloading it would race
 * the selector and the loop's own writes).
 */
public class HttpRequestDispatcherOffloadTest {

	private static HttpServer server;
	private static int PORT;
	private static long savedThreshold;

	/** A slow handler that only buffers (write) — safe to offload. */
	private static class BufferingHandler extends RequestHandler {
		@Override
		public void get(HttpRequest request, HttpResponse response) {
			sleep();
			response.write("buffered");
		}
	}

	/** A slow handler that flushes (performs NIO) during dispatch — must NOT be offloaded. */
	private static class FlushingHandler extends RequestHandler {
		@Override
		public void get(HttpRequest request, HttpResponse response) {
			sleep();
			response.setHeader("Content-Length", "8");
			response.write("flushed!");
			response.flush(); // network I/O during dispatch
		}
	}

	private static void sleep() {
		try { Thread.sleep(2); } catch (InterruptedException ignore) { /* nop */ }
	}

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocketChannel ssc = ServerSocketChannel.open()) {
			ssc.bind(new InetSocketAddress(0));
			PORT = ssc.socket().getLocalPort();
		}
		// Force every handler slower than ~0 to be considered "heavy" so the offload decision is
		// driven purely by whether the handler did network I/O.
		savedThreshold = HttpRequestDispatcher.HEAVY_THRESHOLD_NS;
		HttpRequestDispatcher.HEAVY_THRESHOLD_NS = 0L;
		HttpRequestDispatcher.clearHeavyHandlers();

		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/buffer", new BufferingHandler());
		handlers.put("/flush", new FlushingHandler());
		server = new HttpServer(new Application(handlers));
		server.bind(PORT);
		server.start(1); // dedicated IOLoop, isolated from the shared IOLoop.INSTANCE
		org.deftserver.web.TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		HttpRequestDispatcher.HEAVY_THRESHOLD_NS = savedThreshold;
		HttpRequestDispatcher.clearHeavyHandlers();
		server.stop();
		Thread.sleep(100);
	}

	private static void hit(String path) throws IOException {
		try (Socket socket = new Socket("localhost", PORT)) {
			socket.setSoTimeout(4000);
			socket.getOutputStream().write(
				("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
					.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
			InputStream is = socket.getInputStream();
			byte[] buf = new byte[512];
			while (is.read(buf) != -1) { /* drain to completion */ }
		}
	}

	@Test
	public void slowBufferingHandlerIsOffloadedButFlushingHandlerIsNot() throws IOException {
		hit("/buffer");
		hit("/flush");

		assertTrue("a slow buffering handler should be flagged for offload",
			HttpRequestDispatcher.isFlaggedHeavy(BufferingHandler.class, HttpVerb.GET));
		assertFalse("a slow handler that did network I/O during dispatch must NOT be offloaded "
			+ "(its NIO must stay on the I/O-loop thread)",
			HttpRequestDispatcher.isFlaggedHeavy(FlushingHandler.class, HttpVerb.GET));
	}

	@Test
	public void terminalHandlersAreNeverFlaggedForOffload() throws IOException {
		// A trivial terminal handler (here the 404 NotFound for an unrouted path) opts out of offload
		// (isOffloadable()=false). Even with HEAVY_THRESHOLD_NS forced to 0 — so any normal handler would
		// be flagged after one call — it must never be flagged, so a flood of unmatched/malformed requests
		// can't make every one spawn a virtual thread + cross-thread callback (a DoS-amplification vector,
		// and the precondition for the offloaded-terminal-handler response race).
		hit("/no-such-path-404");
		hit("/no-such-path-404");
		assertFalse("a terminal (404) handler must never be flagged for virtual-thread offload",
			HttpRequestDispatcher.isFlaggedHeavy(
				org.deftserver.web.handler.NotFoundRequestHandler.class, HttpVerb.GET));
	}
}
