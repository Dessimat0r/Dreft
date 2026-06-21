package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Regression for V3-92: static-file serving runs its stat/realpath/read on a virtual thread, so a slow
 * disk read must NOT stall the single I/O loop. (Mirrors the V3-90 WebSocket and V3-96 finalize probes.)
 */
public class StaticContentOffLoopTest {

	private static HttpServer server;
	private static int PORT;
	private static File webroot;

	private static class PingHandler extends RequestHandler {
		@Override public void get(HttpRequest request, HttpResponse response) { response.write("pong"); }
	}

	@BeforeClass
	public static void setUp() throws Exception {
		webroot = Files.createTempDirectory("dreft-static-offloop").toRealPath().toFile();
		try (FileOutputStream fos = new FileOutputStream(new File(webroot, "file.txt"))) {
			fos.write("static body content".getBytes(StandardCharsets.UTF_8));
		}
		try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
			serverChannel.bind(new InetSocketAddress(0));
			PORT = serverChannel.socket().getLocalPort();
		}
		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/ping", new PingHandler());
		Application app = new Application(handlers);
		app.setStaticContentDir(webroot.getAbsolutePath());

		server = new HttpServer(app);
		server.bind(PORT);
		server.start(1); // ONE loop — the whole point is that other connections aren't stalled
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		server.stop();
		File f = new File(webroot, "file.txt");
		if (f.exists()) f.delete();
		if (webroot.exists()) webroot.delete();
		Thread.sleep(100);
	}

	/** With a ~2 s slow static read in flight on its virtual thread, a concurrent GET /ping on the SAME
	 *  single loop must still answer promptly — proving the disk read doesn't park the reactor. */
	@Test
	public void slowStaticReadDoesNotStallTheLoopForOthers() throws Exception {
		StaticContentHandlerDelay.set(2000);
		try {
			final String staticPath = webroot.getAbsolutePath() + "/file.txt";
			// The slow request signals this latch once it has been DELIVERED (written to the socket), so the
			// main thread waits on that trigger rather than sleeping a guessed interval: the server has the
			// request and is entering its delayed off-loop read by the time we fire /ping.
			final CountDownLatch slowDelivered = new CountDownLatch(1);
			Thread slow = new Thread(() -> {
				try (Socket s = new Socket()) {
					s.connect(new InetSocketAddress("localhost", PORT), 3000);
					s.setSoTimeout(8000);
					s.getOutputStream().write(("GET " + staticPath
						+ " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
						.getBytes(StandardCharsets.ISO_8859_1));
					s.getOutputStream().flush();
					slowDelivered.countDown();      // request on the wire — server will now do its slow read
					s.getInputStream().readAllBytes(); // block while the server serves it (slowly)
				} catch (Exception ignore) {
					slowDelivered.countDown();
				}
			});
			slow.start();
			assertTrue("slow static request was not delivered in time",
				slowDelivered.await(3, TimeUnit.SECONDS));

			long t0 = System.nanoTime();
			String ping = request("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
			long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

			assertTrue("expected 200 for /ping, got: " + ping.substring(0, Math.min(40, ping.length())),
				ping.startsWith("HTTP/1.1 200"));
			assertTrue("/ping body missing", ping.contains("pong"));
			assertTrue("/ping took " + elapsedMs + " ms — the loop was stalled by the static disk read",
				elapsedMs < 1500);
			slow.join(5000);
		} finally {
			StaticContentHandlerDelay.set(0);
		}
	}

	/** Sanity: the static file is still served correctly when no delay is injected. */
	@Test
	public void staticFileServedNormally() throws Exception {
		String resp = request("GET " + webroot.getAbsolutePath() + "/file.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
		assertTrue("unexpected response: [" + resp + "]", resp.startsWith("HTTP/1.1 200"));
		assertTrue("body missing: [" + resp + "]", resp.contains("static body content"));
	}

	private String request(String req) throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			socket.setSoTimeout(4000);
			OutputStream out = socket.getOutputStream();
			out.write(req.getBytes(StandardCharsets.ISO_8859_1));
			out.flush();
			InputStream in = socket.getInputStream();
			java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
			byte[] chunk = new byte[4096];
			int n;
			while ((n = in.read(chunk)) != -1) {
				buf.write(chunk, 0, n);
			}
			return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
		}
	}

	/** Indirection so the test in this package can set the package-cross test hook on StaticContentHandler. */
	private static final class StaticContentHandlerDelay {
		static void set(long ms) { org.deftserver.web.handler.StaticContentHandler.TEST_STATIC_READ_DELAY_MS = ms; }
	}
}
