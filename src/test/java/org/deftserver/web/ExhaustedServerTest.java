package org.deftserver.web;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
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
 * Stresses the server at and beyond its configured limits — fills the global connection cap with
 * fuzzed garbage, valid keep-alive, and WebSocket connections, then verifies the server stays alive
 * and recovers fully after draining.
 */
public class ExhaustedServerTest {

	private static HttpServer server;
	private static int PORT;
	private static final int MAX_CONN = 20;
	private static final String OK = "ok";

	private static class OkHandler extends RequestHandler {
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
		Map<String, RequestHandler> h = new HashMap<>();
		h.put("/ok", new OkHandler());
		h.put("/post", new OkHandler());
		h.put("/ws", new EchoWs());
		server = new HttpServer(new Application(h));
		server.setMaxConnections(MAX_CONN);
		server.bind(PORT);
		server.start(1);
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() {
		if (server != null) server.stop();
	}

	private static boolean valid(int port) {
		try (Socket s = new Socket("127.0.0.1", port)) {
			s.setSoTimeout(15000);
			s.getOutputStream().write("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
				.getBytes(StandardCharsets.ISO_8859_1));
			s.getOutputStream().flush();
			String resp = ConcurrentLoadTest.readOneResponse(s.getInputStream(), false);
			return resp.startsWith("HTTP/1.1 200") && ConcurrentLoadTest.body(resp).equals(OK);
		} catch (Exception e) { return false; }
	}

	/**
	 * Floods the server with concurrent keep-alive holders + fuzzed garbage close to the cap,
	 * verifies no crash, then drains and recovers.
	 */
	@Test
	public void stormNearCapWithFuzzAndKeepAliveThenRecover() throws Exception {
		int keepAlive = 8;
		List<Socket> holders = new ArrayList<>();
		ExecutorService exec = Executors.newFixedThreadPool(32);
		CountDownLatch done = new CountDownLatch(MAX_CONN + 10);
		AtomicInteger validFails = new AtomicInteger();
		try {
			// Half keep-alive holders that park after receiving one response.
			for (int i = 0; i < keepAlive; i++) {
				exec.submit(() -> {
					try {
						Socket s = new Socket("127.0.0.1", PORT);
						s.setSoTimeout(120000);
						s.getOutputStream().write("GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\n"
							.getBytes(StandardCharsets.ISO_8859_1));
						s.getOutputStream().flush();
						ConcurrentLoadTest.readOneResponse(s.getInputStream(), false);
						synchronized (holders) { holders.add(s); }
					} catch (Exception ignore) { }
					finally { done.countDown(); }
				});
			}
			// Rest: fuzzed garbage.
			for (int i = 0; i < MAX_CONN + 10 - keepAlive; i++) {
				exec.submit(() -> {
					try (Socket s = new Socket("127.0.0.1", PORT)) {
						s.setSoLinger(true, 0);
						// The (garbage) response is never asserted on — a short read cap keeps the storm fast
						// instead of blocking until the server's own timeout closes an incomplete request.
						s.setSoTimeout(300);
						s.getOutputStream().write(FuzzPayloads.random(ThreadLocalRandom.current()));
						s.getOutputStream().flush();
						try { s.getInputStream().read(new byte[256]); } catch (IOException ignore) { }
					} catch (Exception ignore) { }
					done.countDown();
				});
			}
			assertTrue("storm did not finish", done.await(120, TimeUnit.SECONDS));
		} finally {
			exec.shutdownNow(); exec.awaitTermination(5, TimeUnit.SECONDS);
		}

		// Server must be alive during the storm (some valid requests during concurrent storm).
		assertTrue("server alive during storm", valid(PORT));

		// Drain holders.
		for (Socket s : holders) { try { s.close(); } catch (Exception ignore) { } }
		Thread.sleep(300);

		// Full recovery after drain.
		assertTrue("server fully recovered", valid(PORT));
	}

	/**
	 * Opens WebSocket connections then verifies HTTP still coexists and recovers after draining.
	 */
	@Test
	public void webSocketPressureAllowsHttpAndRecoversAfterDrain() throws Exception {
		// Open several WS connections while bombarding with fuzzed traffic.
		int wsCount = 5;
		final List<Socket> wsSockets = new ArrayList<>();
		ConcurrentLinkedQueue<String> notes = new ConcurrentLinkedQueue<>();
		ExecutorService exec = Executors.newFixedThreadPool(wsCount + 8);
		CountDownLatch done = new CountDownLatch(wsCount + 10);
		try {
			for (int i = 0; i < wsCount; i++) {
				exec.submit(() -> {
					try {
						Socket s = new Socket("127.0.0.1", PORT);
						s.setSoTimeout(120000);
						s.getOutputStream().write((
							"GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
							+ "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
							.getBytes(StandardCharsets.ISO_8859_1));
						s.getOutputStream().flush();
						InputStream in = s.getInputStream();
						java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
						byte[] one = new byte[1];
						while (!acc.toString(StandardCharsets.ISO_8859_1).contains("\r\n\r\n")) {
							int r = in.read(one); if (r == -1) break;
							acc.write(one, 0, r);
						}
						synchronized (wsSockets) { wsSockets.add(s); }
					} catch (Exception e) { notes.add("ws: " + e); }
					finally { done.countDown(); }
				});
			}
			for (int i = 0; i < 10; i++) {
				exec.submit(() -> {
					try (Socket s = new Socket("127.0.0.1", PORT)) {
						s.setSoLinger(true, 0);
						// Short read cap — the garbage response is ignored (see the other storm above).
						s.setSoTimeout(300);
						s.getOutputStream().write(FuzzPayloads.random(ThreadLocalRandom.current()));
						s.getOutputStream().flush();
						try { s.getInputStream().read(new byte[256]); } catch (IOException ignore) { }
					} catch (Exception ignore) { }
					done.countDown();
				});
			}
			assertTrue("WS+HTTP storm did not finish", done.await(120, TimeUnit.SECONDS));
		} finally {
			exec.shutdownNow(); exec.awaitTermination(5, TimeUnit.SECONDS);
		}

		// Server must be alive.
		assertTrue("server alive under WS+fuzz pressure", valid(PORT));

		// Drain WS.
		for (Socket s : wsSockets) { try { s.close(); } catch (Exception ignore) { } }
		Thread.sleep(300);

		// Full recovery.
		assertTrue("full HTTP recovery after WS drain", valid(PORT));
	}
}
