package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.deftserver.web.handler.RequestHandler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class AdvancedHttpComplianceTest {

	private static HttpServer server;
	private static int PORT;

	private static class NegotiationHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) throws IOException {
			String preferredLang = request.getPreferredLanguage(Arrays.asList("da", "en-US", "fr"));
			String preferredCharset = request.getPreferredCharset(Arrays.asList("utf-8", "iso-8859-1"));
			String preferredEncoding = request.getPreferredEncoding(Arrays.asList("gzip", "identity"));
			
			response.setHeader("X-Language", preferredLang != null ? preferredLang : "none");
			response.setHeader("X-Charset", preferredCharset != null ? preferredCharset : "none");
			response.setHeader("X-Encoding", preferredEncoding != null ? preferredEncoding : "none");
			response.write("negotiated");
		}

		@Override
		public void post(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) throws IOException {
			// Read body and assert trailers
			String body = request.getBody();
			String fooTrailer = request.getTrailer("X-Foo-Trailer");
			String barTrailer = request.getTrailer("X-Bar-Trailer");
			
			response.setHeader("X-Foo-Recv", fooTrailer != null ? fooTrailer : "none");
			response.setHeader("X-Bar-Recv", barTrailer != null ? barTrailer : "none");
			response.write("body:" + body);
		}

		@Override
		public void put(org.deftserver.web.http.HttpRequest q, org.deftserver.web.http.HttpResponse r) {
			r.write("PUT:" + q.getBody());
		}

		@Override
		public void patch(org.deftserver.web.http.HttpRequest q, org.deftserver.web.http.HttpResponse r) {
			r.write("PATCH:" + q.getBody());
		}

		@Override
		public void delete(org.deftserver.web.http.HttpRequest q, org.deftserver.web.http.HttpResponse r) {
			r.write("DELETE");
		}
	}

	/** Sends a raw request and returns the full response (status line + headers + body). */
	private static String raw(String request) throws Exception {
		try (Socket socket = new Socket("127.0.0.1", PORT)) {
			socket.setSoTimeout(5000);
			socket.getOutputStream().write(request.getBytes());
			socket.getOutputStream().flush();
			java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int r;
			while ((r = socket.getInputStream().read(buf)) != -1) {
				acc.write(buf, 0, r);
			}
			return acc.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
		}
	}

	@Test
	public void putPatchDeleteMethodsDispatchToTheHandler() throws Exception {
		// §3/§9: PUT, PATCH and DELETE must dispatch to the handler's overridden method (not 501/405).
		String put = raw("PUT /compliance HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc");
		assertTrue("PUT must dispatch, got: " + put.substring(0, Math.min(40, put.length())), put.startsWith("HTTP/1.1 200"));
		assertTrue("PUT body echoed", put.endsWith("PUT:abc"));

		String patch = raw("PATCH /compliance HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2\r\nConnection: close\r\n\r\nhi");
		assertTrue("PATCH must dispatch, got: " + patch.substring(0, Math.min(40, patch.length())), patch.startsWith("HTTP/1.1 200"));
		assertTrue("PATCH body echoed", patch.endsWith("PATCH:hi"));

		String del = raw("DELETE /compliance HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
		assertTrue("DELETE must dispatch, got: " + del.substring(0, Math.min(40, del.length())), del.startsWith("HTTP/1.1 200"));
		assertTrue("DELETE handled", del.endsWith("DELETE"));
	}

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
			serverChannel.bind(new InetSocketAddress(0));
			PORT = serverChannel.socket().getLocalPort();
		}

		Map<String, RequestHandler> reqHandlers = new HashMap<>();
		reqHandlers.put("/compliance", new NegotiationHandler());
		
		server = new HttpServer(new Application(reqHandlers));

		server.bind(PORT);
		server.start(1); // dedicated IOLoop, isolated from the shared IOLoop.INSTANCE
		
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		server.stop();
		Thread.sleep(100);
	}

	@Test
	public void testDuplicateHostHeadersRejection() throws Exception {
		try (Socket socket = new Socket("127.0.0.1", PORT)) {
			OutputStream os = socket.getOutputStream();
			os.write(("GET /compliance HTTP/1.1\r\n" +
			          "Host: localhost\r\n" +
			          "Host: localhost\r\n\r\n").getBytes());
			os.flush();

			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			String statusLine = reader.readLine();
			assertNotNull(statusLine);
			assertTrue(statusLine.contains("400 Bad Request"));
		}
	}

	@Test
	public void testTransferEncodingAndContentLengthRejected() throws Exception {
		// RFC 9112 §6.1: a message with BOTH Transfer-Encoding and Content-Length is a classic
		// request-smuggling vector (front-end and back-end may frame the body differently) and MUST
		// be rejected. Locks in the HttpRequest constructor's TE+CL guard with a running test.
		try (Socket socket = new Socket("127.0.0.1", PORT)) {
			OutputStream os = socket.getOutputStream();
			os.write(("POST /compliance HTTP/1.1\r\n" +
			          "Host: localhost\r\n" +
			          "Transfer-Encoding: chunked\r\n" +
			          "Content-Length: 5\r\n\r\n" +
			          "0\r\n\r\n").getBytes());
			os.flush();

			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			String statusLine = reader.readLine();
			assertNotNull(statusLine);
			assertTrue("TE+CL together must be rejected, got: " + statusLine,
				statusLine.contains("400 Bad Request"));
		}
	}

	@Test
	public void testConflictingContentLengthRejected() throws Exception {
		// RFC 9110 §8.6: two Content-Length fields with DIFFERENT values is unresolvable framing (a
		// smuggling vector) and MUST be rejected. Locks in addHeader's conflicting-CL guard.
		try (Socket socket = new Socket("127.0.0.1", PORT)) {
			OutputStream os = socket.getOutputStream();
			os.write(("POST /compliance HTTP/1.1\r\n" +
			          "Host: localhost\r\n" +
			          "Content-Length: 5\r\n" +
			          "Content-Length: 6\r\n\r\n" +
			          "hello").getBytes());
			os.flush();

			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			String statusLine = reader.readLine();
			assertNotNull(statusLine);
			assertTrue("conflicting Content-Length must be rejected, got: " + statusLine,
				statusLine.contains("400 Bad Request"));
		}
	}

	@Test
	public void testAbsoluteUriHostHeaderMismatch() throws Exception {
		try (Socket socket = new Socket("127.0.0.1", PORT)) {
			OutputStream os = socket.getOutputStream();
			os.write(("GET http://example.com/compliance HTTP/1.1\r\n" +
			          "Host: another.com\r\n\r\n").getBytes());
			os.flush();

			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			String statusLine = reader.readLine();
			assertNotNull(statusLine);
			assertTrue(statusLine.contains("400 Bad Request"));
		}
	}

	@Test
	public void testAbsoluteUriHostHeaderMatch() throws Exception {
		try (Socket socket = new Socket("127.0.0.1", PORT)) {
			OutputStream os = socket.getOutputStream();
			os.write(("GET http://example.com/compliance HTTP/1.1\r\n" +
			          "Host: example.com\r\n\r\n").getBytes());
			os.flush();

			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			String statusLine = reader.readLine();
			assertNotNull(statusLine);
			assertTrue(statusLine.contains("200 OK"));
		}
	}

	@Test
	public void testAbsoluteUriHostHeaderMatchWithPort() throws Exception {
		try (Socket socket = new Socket("127.0.0.1", PORT)) {
			OutputStream os = socket.getOutputStream();
			os.write(("GET http://example.com:80/compliance HTTP/1.1\r\n" +
			          "Host: example.com\r\n\r\n").getBytes());
			os.flush();

			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			String statusLine = reader.readLine();
			assertNotNull(statusLine);
			assertTrue(statusLine.contains("200 OK"));
		}
	}

	@Test
	public void testIpv6LiteralHostAccepted() throws Exception {
		// §6: an IPv6 literal Host (with brackets, colons, and an optional port) must be accepted —
		// the header-value parser allows the colons, and the request routes normally.
		try (Socket socket = new Socket("127.0.0.1", PORT)) {
			OutputStream os = socket.getOutputStream();
			os.write(("GET /compliance HTTP/1.1\r\n" +
			          "Host: [2001:db8::1]:8443\r\n\r\n").getBytes());
			os.flush();
			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			String statusLine = reader.readLine();
			assertNotNull(statusLine);
			assertTrue("IPv6 literal Host must be accepted, got: " + statusLine, statusLine.contains("200 OK"));
		}
	}

	@Test
	public void testAbsoluteUriIpv6HostMatch() throws Exception {
		// §6: absolute-form target with an IPv6 authority must match the IPv6 Host (normalizeAuthority
		// handles bracketed literals + default-port stripping).
		try (Socket socket = new Socket("127.0.0.1", PORT)) {
			OutputStream os = socket.getOutputStream();
			os.write(("GET http://[2001:db8::1]/compliance HTTP/1.1\r\n" +
			          "Host: [2001:db8::1]\r\n\r\n").getBytes());
			os.flush();
			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			String statusLine = reader.readLine();
			assertNotNull(statusLine);
			assertTrue("absolute IPv6 URI must match IPv6 Host, got: " + statusLine, statusLine.contains("200 OK"));
		}
	}

	@Test
	public void testChunkedRequestTrailersAndProhibitions() throws Exception {
		// Case 1: Valid chunked body with valid trailers
		try (Socket socket = new Socket("127.0.0.1", PORT)) {
			OutputStream os = socket.getOutputStream();
			os.write(("POST /compliance HTTP/1.1\r\n" +
			          "Host: localhost\r\n" +
			          "Transfer-Encoding: chunked\r\n\r\n" +
			          "4\r\n" +
			          "test\r\n" +
			          "0\r\n" +
			          "X-Foo-Trailer: hello\r\n" +
			          "X-Bar-Trailer: world\r\n\r\n").getBytes());
			os.flush();

			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			String statusLine = reader.readLine();
			assertNotNull(statusLine);
			assertTrue(statusLine.contains("200 OK"));

			String line;
			boolean foundFoo = false;
			boolean foundBar = false;
			while ((line = reader.readLine()) != null && !line.isEmpty()) {
				if (line.startsWith("X-Foo-Recv: hello")) foundFoo = true;
				if (line.startsWith("X-Bar-Recv: world")) foundBar = true;
			}
			assertTrue(foundFoo);
			assertTrue(foundBar);
		}

		// Case 2: Chunked body with a prohibited header in trailer (Host)
		try (Socket socket = new Socket("127.0.0.1", PORT)) {
			OutputStream os = socket.getOutputStream();
			os.write(("POST /compliance HTTP/1.1\r\n" +
			          "Host: localhost\r\n" +
			          "Transfer-Encoding: chunked\r\n\r\n" +
			          "4\r\n" +
			          "test\r\n" +
			          "0\r\n" +
			          "Host: badhack.com\r\n\r\n").getBytes());
			os.flush();

			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			String statusLine = reader.readLine();
			assertNotNull(statusLine);
			assertTrue(statusLine.contains("400 Bad Request"));
		}

		// Case 3: Chunked body with whitespace before colon in trailer
		try (Socket socket = new Socket("127.0.0.1", PORT)) {
			OutputStream os = socket.getOutputStream();
			os.write(("POST /compliance HTTP/1.1\r\n" +
			          "Host: localhost\r\n" +
			          "Transfer-Encoding: chunked\r\n\r\n" +
			          "4\r\n" +
			          "test\r\n" +
			          "0\r\n" +
			          "X-Foo : space\r\n\r\n").getBytes());
			os.flush();

			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			String statusLine = reader.readLine();
			assertNotNull(statusLine);
			assertTrue(statusLine.contains("400 Bad Request"));
		}
	}

	@Test
	public void testAdvancedContentNegotiation() throws Exception {
		HttpClient client = HttpClient.newHttpClient();

		// Case 1: Preferences: Accept-Language da (q=0.9), en-US (q=0.8); supports "da", "en-US", "fr"
		HttpRequest request1 = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + PORT + "/compliance"))
				.header("Accept-Language", "en-US;q=0.8, da;q=0.9")
				.header("Accept-Charset", "iso-8859-1;q=0.5, utf-8;q=1.0")
				.header("Accept-Encoding", "deflate;q=0.2, gzip;q=0.8")
				.GET()
				.build();

		HttpResponse<String> response1 = client.send(request1, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response1.statusCode());
		assertEquals("da", response1.headers().firstValue("X-Language").orElse(null));
		assertEquals("utf-8", response1.headers().firstValue("X-Charset").orElse(null));
		assertEquals("gzip", response1.headers().firstValue("X-Encoding").orElse(null));

		// Case 2: Language Prefix Matching (e.g. client accepts "en", should match supported "en-US")
		HttpRequest request2 = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + PORT + "/compliance"))
				.header("Accept-Language", "en;q=0.9")
				.GET()
				.build();

		HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response2.statusCode());
		assertEquals("en-US", response2.headers().firstValue("X-Language").orElse(null));

		// Case 3: Filtering out weight 0.0 (unacceptable values)
		HttpRequest request3 = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + PORT + "/compliance"))
				.header("Accept-Language", "da;q=0.0, fr;q=0.5")
				.header("Accept-Encoding", "gzip;q=0.0") // gzip is unacceptable, should fall back to identity
				.GET()
				.build();

		HttpResponse<String> response3 = client.send(request3, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response3.statusCode());
		assertEquals("fr", response3.headers().firstValue("X-Language").orElse(null)); // da was ignored due to q=0.0
		assertEquals("identity", response3.headers().firstValue("X-Encoding").orElse(null)); // gzip ignored due to q=0.0
	}
}
