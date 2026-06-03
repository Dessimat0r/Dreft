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

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Network-condition robustness tests: drive the server through an {@link ImpairmentProxy} that
 * dribbles, throttles, delays and drops bytes, and assert the server stays correct and crash-free
 * (the robustness-first mandate, under adverse network behaviour rather than malformed bytes).
 * <p>
 * Runs on its own dedicated {@link org.deftserver.io.IOLoop} (via {@link HttpServer#start(int)}) so
 * the slow/dropped connections never disturb the sibling classes sharing {@code IOLoop.INSTANCE}.
 */
public class NetworkImpairmentTest {

	private static HttpServer server;
	private static int PORT;
	/** A deterministic ~512 KiB body — large enough to overflow the OS socket send buffer so a slow
	 *  reader forces the server onto its OP_WRITE deferral path. */
	private static final String BIG_BODY;
	static {
		StringBuilder sb = new StringBuilder(512 * 1024);
		for (int i = 0; sb.length() < 512 * 1024; i++) {
			sb.append("LINE").append(i).append("-abcdefghijklmnopqrstuvwxyz\n");
		}
		BIG_BODY = sb.toString();
	}

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocket probe = new ServerSocket(0)) {
			PORT = probe.getLocalPort();
		}
		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/", new RequestHandler() {
			@Override public void get(HttpRequest req, HttpResponse resp) { resp.write("ok"); }
		});
		handlers.put("/big", new RequestHandler() {
			@Override public void get(HttpRequest req, HttpResponse resp) { resp.write(BIG_BODY); }
		});
		server = new HttpServer(new Application(handlers));
		server.bind(PORT);
		server.start(1); // dedicated, isolated IOLoop
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() {
		if (server != null) {
			server.stop();
		}
	}

	/** Sends a full request through the proxy and reads the complete response (headers + a body of the
	 *  advertised Content-Length). Generous timeout so deliberately-slow paths don't time out. */
	private static String roundTrip(int proxyPort, String request, int timeoutMs) throws IOException {
		try (Socket s = new Socket("127.0.0.1", proxyPort)) {
			s.setSoTimeout(timeoutMs);
			s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
			s.getOutputStream().flush();
			InputStream in = s.getInputStream();
			java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[16384];
			int contentLength = -1;
			int headerEnd = -1;
			int n;
			while ((n = in.read(buf)) != -1) {
				acc.write(buf, 0, n);
				String cur = acc.toString(StandardCharsets.ISO_8859_1);
				if (headerEnd == -1) {
					int idx = cur.indexOf("\r\n\r\n");
					if (idx != -1) {
						headerEnd = idx + 4;
						int cl = cur.toLowerCase(java.util.Locale.ROOT).indexOf("content-length:");
						if (cl != -1) {
							int eol = cur.indexOf("\r\n", cl);
							contentLength = Integer.parseInt(cur.substring(cl + 15, eol).trim());
						}
					}
				}
				if (headerEnd != -1 && contentLength != -1 && acc.size() >= headerEnd + contentLength) {
					break;
				}
			}
			return acc.toString(StandardCharsets.ISO_8859_1);
		}
	}

	private static String body(String fullResponse) {
		int idx = fullResponse.indexOf("\r\n\r\n");
		return idx == -1 ? "" : fullResponse.substring(idx + 4);
	}

	@Test
	public void dribbledRequestIsParsedIncrementally() throws IOException {
		// Feed the request to the server ONE byte every 8 ms (Slowloris-ish but within the header
		// read timeout). The server's incremental header parser (partials/grow-buffer) must still
		// assemble it and respond 200.
		try (ImpairmentProxy proxy = new ImpairmentProxy(PORT)) {
			proxy.toServer.chunkSize = 1;
			proxy.toServer.perChunkDelayMs = 8;
			String resp = roundTrip(proxy.getPort(),
				"GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", 8000);
			assertTrue("dribbled request must still parse → 200, got: "
				+ resp.substring(0, Math.min(40, resp.length())), resp.startsWith("HTTP/1.1 200"));
			assertEquals("ok", body(resp));
		}
	}

	@Test
	public void slowReaderForcesDeferredWriteButBodyArrivesIntact() throws IOException {
		// The proxy relays the 512 KiB response to the client in small, delayed chunks, so it reads
		// from the server slowly → the server's send buffer fills → it defers to OP_WRITE. The whole
		// body must still arrive byte-for-byte and the server must not crash or truncate.
		try (ImpairmentProxy proxy = new ImpairmentProxy(PORT)) {
			proxy.toClient.chunkSize = 4096;
			proxy.toClient.perChunkDelayMs = 2;
			String resp = roundTrip(proxy.getPort(),
				"GET /big HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", 30000);
			assertTrue("slow-read response must be 200, got: "
				+ resp.substring(0, Math.min(40, resp.length())), resp.startsWith("HTTP/1.1 200"));
			assertEquals("slow-read body must arrive intact and complete", BIG_BODY, body(resp));
		}
	}

	@Test
	public void abruptMidRequestDropIsHandledAndServerStaysAlive() throws IOException {
		// Forward only the first 12 bytes of the request, then drop the connection — the server sees a
		// partial request followed by EOF and must clean up without crashing.
		try (ImpairmentProxy proxy = new ImpairmentProxy(PORT)) {
			proxy.toServer.bytesBeforeDrop = 12;
			try (Socket s = new Socket("127.0.0.1", proxy.getPort())) {
				s.setSoTimeout(2000);
				OutputStream os = s.getOutputStream();
				os.write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
					.getBytes(StandardCharsets.ISO_8859_1));
				os.flush();
				try { s.getInputStream().read(new byte[256]); } catch (IOException expected) { /* drop */ }
			}
		}

		// The I/O loop must still be alive and serving — a clean request through a fresh proxy works.
		try (ImpairmentProxy proxy = new ImpairmentProxy(PORT)) {
			String resp = roundTrip(proxy.getPort(),
				"GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", 5000);
			assertTrue("server must stay alive after a mid-request drop, got: "
				+ resp.substring(0, Math.min(40, resp.length())), resp.startsWith("HTTP/1.1 200"));
		}
	}
}
