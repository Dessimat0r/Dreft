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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Runs a broad, representative set of HTTP request/response scenarios through the
 * {@link ImpairmentProxy} in two modes — <b>PURE</b> (pass-through, no impairment) and <b>JITTER</b>
 * (heavy request fragmentation + random per-chunk delay in both directions) — to widen the testing
 * surface: the same correctness assertions must hold whether the bytes arrive cleanly or in a
 * jittery, fragmented stream. PURE also proves the proxy itself is transparent.
 * <p>
 * Self-contained: its own dedicated {@link org.deftserver.io.IOLoop} (via {@link HttpServer#start(int)}),
 * isolated from the shared {@code IOLoop.INSTANCE}.
 */
@RunWith(Parameterized.class)
public class ProxiedHttpScenariosTest {

	enum Mode { PURE, JITTER }

	@Parameter
	public Mode mode;

	@Parameters(name = "{0}")
	public static Collection<Object[]> modes() {
		return Arrays.asList(new Object[][]{{Mode.PURE}, {Mode.JITTER}});
	}

	private static HttpServer server;
	private static int PORT;
	private static final String BIG = "x".repeat(32 * 1024);

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocket probe = new ServerSocket(0)) {
			PORT = probe.getLocalPort();
		}
		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/ok", new RequestHandler() {
			@Override public void get(HttpRequest q, HttpResponse r) { r.write("ok"); }
		});
		handlers.put("/echo", new RequestHandler() {
			@Override public void post(HttpRequest q, HttpResponse r) { r.write(q.getBody()); }
		});
		handlers.put("/big", new RequestHandler() {
			@Override public void get(HttpRequest q, HttpResponse r) { r.write(BIG); }
		});
		handlers.put("/param", new RequestHandler() {
			@Override public void get(HttpRequest q, HttpResponse r) {
				String v = q.getParameter("x");
				r.write(v != null ? v : "none");
			}
		});
		server = new HttpServer(new Application(handlers));
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

	/** A proxy configured for the current mode. PURE is pass-through; JITTER fragments heavily and
	 *  adds random delay in both directions. */
	private ImpairmentProxy newProxy() throws IOException {
		ImpairmentProxy p = new ImpairmentProxy(PORT);
		if (mode == Mode.JITTER) {
			// Heavily fragment the request (stress the incremental parser) and add jitter both ways.
			p.toServer.chunkSize = 40;
			p.toServer.jitterMaxMs = 2;
			p.toClient.chunkSize = 1024;
			p.toClient.jitterMaxMs = 2;
		}
		return p;
	}

	// --- response framing helper (handles Content-Length, chunked, and bodiless responses) ---

	/** Reads exactly one HTTP response from the stream (status line + headers + correctly-framed
	 *  body), tolerant of jittery/fragmented delivery. Does not close the socket (so keep-alive can
	 *  read a second response). {@code headRequest} suppresses body reading for HEAD. */
	private static String readOneResponse(InputStream in, boolean headRequest) throws IOException {
		ByteArrayOutputStream acc = new ByteArrayOutputStream();
		// 1. Read until end of headers.
		int headerEnd = -1;
		byte[] one = new byte[1];
		while (headerEnd == -1) {
			int r = in.read(one);
			if (r == -1) throw new IOException("EOF before end of response headers");
			acc.write(one, 0, r);
			String cur = acc.toString(StandardCharsets.ISO_8859_1);
			int idx = cur.indexOf("\r\n\r\n");
			if (idx != -1) headerEnd = idx + 4;
		}
		String header = acc.toString(StandardCharsets.ISO_8859_1).substring(0, headerEnd);
		String lower = header.toLowerCase(Locale.ROOT);
		int status = Integer.parseInt(header.substring(9, 12).trim());

		boolean bodiless = headRequest || status == 204 || status == 304 || status / 100 == 1;
		if (bodiless) {
			return acc.toString(StandardCharsets.ISO_8859_1);
		}
		if (lower.contains("transfer-encoding: chunked")) {
			readUntil(in, acc, "0\r\n\r\n");
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

	/** Reads bytes into {@code acc} until its tail matches {@code terminator}. */
	private static void readUntil(InputStream in, ByteArrayOutputStream acc, String terminator) throws IOException {
		byte[] one = new byte[1];
		while (true) {
			String cur = acc.toString(StandardCharsets.ISO_8859_1);
			if (cur.endsWith(terminator)) return;
			int r = in.read(one);
			if (r == -1) return;
			acc.write(one, 0, r);
		}
	}

	private static String body(String full) {
		int idx = full.indexOf("\r\n\r\n");
		return idx == -1 ? "" : full.substring(idx + 4);
	}

	/** Opens a connection to the proxy, sends {@code request}, reads one response, closes. */
	private String send(ImpairmentProxy proxy, String request, boolean head) throws IOException {
		try (Socket s = new Socket("127.0.0.1", proxy.getPort())) {
			s.setSoTimeout(20000);
			s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
			s.getOutputStream().flush();
			return readOneResponse(s.getInputStream(), head);
		}
	}

	// --- scenarios (each runs in PURE and JITTER) ---

	@Test
	public void simpleGet() throws IOException {
		try (ImpairmentProxy p = newProxy()) {
			String resp = send(p, "GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", false);
			assertTrue(resp.startsWith("HTTP/1.1 200"));
			assertEquals("ok", body(resp));
		}
	}

	@Test
	public void postWithContentLengthBody() throws IOException {
		try (ImpairmentProxy p = newProxy()) {
			String payload = "hello-impairment-body";
			String resp = send(p, "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
				+ payload.length() + "\r\nConnection: close\r\n\r\n" + payload, false);
			assertTrue(resp.startsWith("HTTP/1.1 200"));
			assertEquals(payload, body(resp));
		}
	}

	@Test
	public void postWithChunkedBody() throws IOException {
		try (ImpairmentProxy p = newProxy()) {
			// "Wiki" + "pedia" sent as two chunks → server dechunks → echo "Wikipedia".
			String resp = send(p, "POST /echo HTTP/1.1\r\nHost: localhost\r\n"
				+ "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
				+ "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n", false);
			assertTrue(resp.startsWith("HTTP/1.1 200"));
			assertEquals("Wikipedia", body(resp));
		}
	}

	@Test
	public void notFound() throws IOException {
		try (ImpairmentProxy p = newProxy()) {
			String resp = send(p, "GET /nope HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", false);
			assertTrue("got: " + resp.substring(0, Math.min(40, resp.length())),
				resp.startsWith("HTTP/1.1 404"));
		}
	}

	@Test
	public void largeResponseArrivesIntact() throws IOException {
		try (ImpairmentProxy p = newProxy()) {
			String resp = send(p, "GET /big HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", false);
			assertTrue(resp.startsWith("HTTP/1.1 200"));
			assertEquals("32 KiB body must arrive complete and intact under impairment", BIG, body(resp));
		}
	}

	@Test
	public void queryParameter() throws IOException {
		try (ImpairmentProxy p = newProxy()) {
			String resp = send(p, "GET /param?x=hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", false);
			assertTrue(resp.startsWith("HTTP/1.1 200"));
			assertEquals("hello", body(resp));
		}
	}

	@Test
	public void headRequestHasNoBody() throws IOException {
		try (ImpairmentProxy p = newProxy()) {
			String resp = send(p, "HEAD /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", true);
			assertTrue(resp.startsWith("HTTP/1.1 200"));
			assertTrue("HEAD must report a Content-Length", resp.toLowerCase(Locale.ROOT).contains("content-length:"));
			assertEquals("HEAD must have no body", "", body(resp));
		}
	}

	@Test
	public void keepAliveTwoRequestsOnOneConnection() throws IOException {
		try (ImpairmentProxy p = newProxy(); Socket s = new Socket("127.0.0.1", p.getPort())) {
			s.setSoTimeout(20000);
			OutputStream os = s.getOutputStream();
			InputStream is = s.getInputStream();
			// First request: keep-alive.
			os.write("GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
			os.flush();
			String r1 = readOneResponse(is, false);
			assertTrue("first keep-alive response 200, got: " + r1.substring(0, Math.min(40, r1.length())),
				r1.startsWith("HTTP/1.1 200"));
			assertEquals("ok", body(r1));
			// Second request on the SAME connection, then close.
			os.write("GET /param?x=second HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
				.getBytes(StandardCharsets.ISO_8859_1));
			os.flush();
			String r2 = readOneResponse(is, false);
			assertTrue("second keep-alive response 200, got: " + r2.substring(0, Math.min(40, r2.length())),
				r2.startsWith("HTTP/1.1 200"));
			assertEquals("second", body(r2));
		}
	}
}
