package org.deftserver.web;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Adversarial fuzz test for the single most important invariant: <b>no input may crash the I/O
 * loop</b> (robustness is the top priority). It throws a large, diverse, seeded (reproducible) set
 * of malformed byte sequences at a live server and periodically proves the loop is still serving.
 * <p>
 * This runs on its <b>own dedicated {@link org.deftserver.io.IOLoop}</b> (via
 * {@link HttpServer#start(int)}, which allocates a fresh loop and disposes it on {@link
 * HttpServer#stop()}) rather than the shared {@code IOLoop.INSTANCE}. The fuzzer opens hundreds of
 * connections in quick succession; isolating it keeps that churn from destabilising the sibling
 * integration-test classes that share the singleton loop.
 */
public class HttpFuzzTest {

	private static HttpServer server;
	private static int PORT;

	/** Trivial handler so a well-formed liveness GET returns 200. */
	private static class OkHandler extends RequestHandler {
		@Override
		public void get(HttpRequest request, HttpResponse response) {
			response.write("ok");
		}
		@Override
		public void post(HttpRequest request, HttpResponse response) {
			response.write("ok");
		}
	}

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocket probe = new ServerSocket(0)) {
			PORT = probe.getLocalPort();
		}
		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/", new OkHandler());
		handlers.put("/post", new OkHandler());
		server = new HttpServer(new Application(handlers));
		server.bind(PORT);
		server.start(1); // dedicated IOLoop on its own thread — isolated from IOLoop.INSTANCE
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() {
		if (server != null) {
			server.stop(); // disposes the dedicated loop + closes the server socket
		}
	}

	/** Sends raw bytes on a fresh socket, does one bounded read, and closes. All client-side I/O
	 *  errors are expected and ignored — only the server's survival matters. */
	private static void fireRawBytesAndForget(byte[] payload) {
		try (Socket socket = new Socket("localhost", PORT)) {
			// Graceful close (FIN, not RST): after we write+flush and close, the kernel still delivers the
			// buffered payload to the server, which parses it asynchronously — so there is no need to block
			// on a per-iteration read to hold the connection open (the old code waited up to 120 ms PER
			// incomplete input, which dominated the runtime). Many fuzz inputs are deliberately incomplete,
			// so the server never replies; we don't care about the response, only that the server survives,
			// which the periodic/final liveness() round-trips below verify. Not setting SO_LINGER(0) keeps
			// the close graceful so every payload is delivered regardless of timing.
			socket.getOutputStream().write(payload);
			socket.getOutputStream().flush();
		} catch (IOException expected) {
			// connect / write failure — fine; the server must merely not crash.
		}
	}

	/** A well-formed request; returns the response text (empty on failure). Used to prove liveness. */
	private static String liveness() throws IOException {
		try (Socket socket = new Socket("localhost", PORT)) {
			socket.setSoTimeout(3000);
			socket.getOutputStream().write(
				"GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
					.getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
			byte[] buf = new byte[4096];
			int n = socket.getInputStream().read(buf);
			return n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.ISO_8859_1);
		}
	}

	@Test
	public void fuzzMalformedRequestsDoNotCrashServer() throws IOException {
		final Random rnd = new Random(0xDEF7C0DEL); // fixed seed → reproducible
		final String[] verbs = {"GET", "POST", "PUT", "HEAD", "DELETE", "OPTIONS", "FOO", "", "GET GET"};
		final String[] versions = {"HTTP/1.1", "HTTP/1.0", "HTTP/9.9", "HTTP/1", "ICY/1", ""};
		final String[] paths = {"/", "/post", "/../../etc/passwd", "/%", "/%zz", "/a%00b",
			"/" + "x".repeat(9000), "/ ", "/a?b=c&d", "*", "//evil"};

		for (int i = 0; i < 500; i++) {
			byte[] payload;
			switch (rnd.nextInt(9)) {
				case 0 -> { // pure random bytes
					payload = new byte[rnd.nextInt(2048)];
					rnd.nextBytes(payload);
				}
				case 1 -> { // valid-ish line + random header octets (often unterminated)
					StringBuilder sb = new StringBuilder(verbs[rnd.nextInt(verbs.length)])
						.append(' ').append(paths[rnd.nextInt(paths.length)])
						.append(' ').append(versions[rnd.nextInt(versions.length)]).append("\r\n");
					int hdrs = rnd.nextInt(20);
					for (int h = 0; h < hdrs; h++) {
						sb.append("X-").append(rnd.nextInt(99)).append(": ");
						int vlen = rnd.nextInt(40);
						for (int v = 0; v < vlen; v++) sb.append((char) (rnd.nextInt(94) + 32));
						sb.append("\r\n");
					}
					if (rnd.nextBoolean()) sb.append("\r\n");
					payload = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
				}
				case 2 -> // oversized single header line (exercises the 431 path)
					payload = ("GET / HTTP/1.1\r\nHost: localhost\r\nX-Big: " + "A".repeat(70000)
						+ "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
				case 3 -> { // garbage chunked body (lying sizes, random chunk-extensions)
					StringBuilder sb = new StringBuilder("POST /post HTTP/1.1\r\nHost: localhost\r\n"
						+ "Transfer-Encoding: chunked\r\n\r\n");
					int chunks = rnd.nextInt(10);
					for (int c = 0; c < chunks; c++) {
						sb.append(Integer.toHexString(rnd.nextInt(50000)));
						if (rnd.nextBoolean()) sb.append(";ext=").append(rnd.nextInt(9));
						sb.append("\r\n");
						int datalen = rnd.nextInt(20);
						for (int d = 0; d < datalen; d++) sb.append((char) (rnd.nextInt(94) + 32));
						sb.append("\r\n");
					}
					if (rnd.nextBoolean()) sb.append("0\r\n\r\n");
					payload = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
				}
				case 4 -> // conflicting framing headers (CL+CL+TE)
					payload = ("POST /post HTTP/1.1\r\nHost: localhost\r\n"
						+ "Content-Length: " + rnd.nextInt(100) + "\r\n"
						+ "Content-Length: " + rnd.nextInt(100) + "\r\n"
						+ "Transfer-Encoding: chunked\r\n\r\njunk").getBytes(StandardCharsets.ISO_8859_1);
				case 5 -> // truncated mid-headers
					payload = "GET / HTTP/1.1\r\nHost: localho".getBytes(StandardCharsets.ISO_8859_1);
				case 6 -> // bare-LF line endings
					payload = "GET / HTTP/1.1\nHost: localhost\n\nbody".getBytes(StandardCharsets.ISO_8859_1);
				case 7 -> // negative / overflowing content-length
					payload = ("POST /post HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
						+ (rnd.nextBoolean() ? "-5" : "99999999999999999999") + "\r\n\r\nx")
						.getBytes(StandardCharsets.ISO_8859_1);
				default -> // malformed multipart
					payload = ("POST /post HTTP/1.1\r\nHost: localhost\r\n"
						+ "Content-Type: multipart/form-data; boundary=xyz\r\n"
						+ "Content-Length: 20\r\n\r\n--xyz\r\nbroken").getBytes(StandardCharsets.ISO_8859_1);
			}
			fireRawBytesAndForget(payload);

			if (i % 100 == 0) {
				String alive = liveness();
				assertTrue("server must still serve after fuzzing (iteration " + i + "), got: "
					+ (alive.length() > 40 ? alive.substring(0, 40) : alive),
					alive.startsWith("HTTP/1.1 200"));
			}
		}

		String finalAlive = liveness();
		assertTrue("server must survive the full fuzz run, got: "
			+ (finalAlive.length() > 40 ? finalAlive.substring(0, 40) : finalAlive),
			finalAlive.startsWith("HTTP/1.1 200"));
	}
}
