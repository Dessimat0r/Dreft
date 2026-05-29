package org.deftserver.web;

import static org.junit.Assert.assertEquals;
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
}
