package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.deftserver.io.IOLoop;
import org.deftserver.web.handler.RequestHandler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class DynamicCompressionTest {

	private static HttpServer server;
	private static int PORT;
	private static final String PAYLOAD;
	
	static {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 500; i++) {
			sb.append("DreftDynamicCompressionTesting-");
		}
		PAYLOAD = sb.toString();
	}

	private static class CompressibleRequestHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) throws IOException {
			response.setHeader("Content-Type", "text/plain; charset=utf-8");
			response.write(PAYLOAD);
		}
	}

	private static class DoubleFinishRequestHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) throws IOException {
			response.setHeader("Content-Type", "text/plain; charset=utf-8");
			response.write(PAYLOAD);
			response.finish(); // Explicit finish #1
			// HttpProtocol will trigger finish #2 automatically
		}
	}

	@BeforeClass
	public static void setUp() throws Exception {
		// Find a free ephemeral port
		try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
			serverChannel.bind(new InetSocketAddress(0));
			PORT = serverChannel.socket().getLocalPort();
		}

		Map<String, RequestHandler> reqHandlers = new HashMap<>();
		reqHandlers.put("/compress", new CompressibleRequestHandler());
		reqHandlers.put("/double_finish", new DoubleFinishRequestHandler());
		
		server = new HttpServer(new Application(reqHandlers));
		
		Thread.ofPlatform().start(() -> {
			try {
				server.listen(PORT);
				IOLoop.INSTANCE.start();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		
		// Wait a brief moment for the loop to boot
		Thread.sleep(200);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		server.stop();
		IOLoop.INSTANCE.stop();
		// Wait brief moment for cleanup
		Thread.sleep(100);
	}

	@Test
	public void testGzipCompressionAndChunking() throws Exception {
		HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.build();

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + PORT + "/compress"))
				.header("Accept-Encoding", "gzip")
				.GET()
				.build();

		HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
		
		assertNotNull(response);
		assertEquals(200, response.statusCode());
		
		// Verify standard headers are set correctly
		String contentEncoding = response.headers().firstValue("Content-Encoding").orElse(null);
		String transferEncoding = response.headers().firstValue("Transfer-Encoding").orElse(null);
		
		assertEquals("gzip", contentEncoding);
		assertEquals("chunked", transferEncoding);
		assertTrue(response.headers().firstValue("Content-Length").isEmpty());

		// Manually decompress the GZIP body to verify payload integrity
		byte[] bodyBytes = response.body();
		String decompressed = decompressGzip(bodyBytes);
		
		assertEquals(PAYLOAD, decompressed);
	}

	@Test
	public void testNoCompressionWhenUnsupported() throws Exception {
		HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.build();

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + PORT + "/compress"))
				.GET() // No Accept-Encoding header
				.build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		
		assertNotNull(response);
		assertEquals(200, response.statusCode());
		
		// Verify compression is NOT applied
		assertTrue(response.headers().firstValue("Content-Encoding").isEmpty());
		
		// Content-Length should be present and equal to payload size
		String contentLength = response.headers().firstValue("Content-Length").orElse(null);
		assertNotNull(contentLength);
		assertEquals(String.valueOf(PAYLOAD.getBytes().length), contentLength);
		assertEquals(PAYLOAD, response.body());
	}

	@Test
	public void testDoubleFinishResponseIsIdempotent() throws Exception {
		HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.build();

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + PORT + "/double_finish"))
				.header("Accept-Encoding", "gzip")
				.GET()
				.build();

		HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
		
		assertNotNull(response);
		assertEquals(200, response.statusCode());
		
		String contentEncoding = response.headers().firstValue("Content-Encoding").orElse(null);
		String transferEncoding = response.headers().firstValue("Transfer-Encoding").orElse(null);
		
		assertEquals("gzip", contentEncoding);
		assertEquals("chunked", transferEncoding);
		assertTrue(response.headers().firstValue("Content-Length").isEmpty());

		byte[] bodyBytes = response.body();
		String decompressed = decompressGzip(bodyBytes);
		
		assertEquals(PAYLOAD, decompressed);
	}

	private String decompressGzip(byte[] compressed) throws IOException {
		try (
			GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
			java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()
		) {
			byte[] buffer = new byte[1024];
			int len;
			while ((len = gis.read(buffer)) > 0) {
				baos.write(buffer, 0, len);
			}
			return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
		}
	}

	@Test
	public void testConditionalGetOnGzippedResourceYieldsCleanNotModified() throws Exception {
		// 1. Fetch the gzipped representation and capture its (quoted) ETag.
		String first = rawRequest(
			"GET /compress HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Accept-Encoding: gzip\r\n" +
			"Connection: close\r\n\r\n");
		assertTrue(first.startsWith("HTTP/1.1 200"));
		int etagIdx = first.indexOf("Etag: ");
		assertTrue("expected an Etag header:\n" + first.substring(0, Math.min(300, first.length())), etagIdx != -1);
		String etag = first.substring(etagIdx + 6, first.indexOf("\r\n", etagIdx)).trim();

		// 2. Conditional GET with that ETag must yield a CLEAN 304: no Transfer-Encoding,
		//    no Content-Encoding, no chunked body — just headers (regression for the bug
		//    where gzip framing leaked into the 304, producing a stray "0\r\n\r\n" body).
		String second = rawRequest(
			"GET /compress HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Accept-Encoding: gzip\r\n" +
			"If-None-Match: " + etag + "\r\n" +
			"Connection: close\r\n\r\n");
		assertTrue("expected 304, got:\n" + second.substring(0, Math.min(80, second.length())),
			second.startsWith("HTTP/1.1 304"));
		assertFalse("304 must not carry Transfer-Encoding:\n" + second, second.contains("Transfer-Encoding:"));
		assertFalse("304 must not carry Content-Encoding:\n" + second, second.contains("Content-Encoding:"));
		int bodyStart = second.indexOf("\r\n\r\n") + 4;
		assertEquals("304 must have an empty body", bodyStart, second.length());
	}

	private String rawRequest(String request) throws IOException {
		try (java.net.Socket socket = new java.net.Socket("localhost", PORT)) {
			socket.setSoTimeout(3000);
			socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			java.io.InputStream is = socket.getInputStream();
			while ((n = is.read(buf)) != -1) {
				out.write(buf, 0, n);
			}
			return out.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
		}
	}
}
