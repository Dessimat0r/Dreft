package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.deftserver.io.IOLoop;
import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.CorsConfig;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class CorsAndNegotiationTest {

	private static HttpServer server;
	private static int PORT;

	private static class CorsTestHandler extends RequestHandler {
		public CorsTestHandler() {
			CorsConfig cors = new CorsConfig();
			cors.setAllowedOrigins("http://example.com", "http://test.org");
			cors.setAllowedMethods("GET", "POST", "DELETE");
			cors.setAllowedHeaders("X-Custom-Header", "Content-Type");
			cors.setAllowCredentials(true);
			cors.setMaxAge(7200L);
			setCorsConfig(cors);
		}

		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) throws IOException {
			// Resolve preferred MIME type using q-values
			String preferred = request.getPreferredContentType(Arrays.asList("application/json", "text/html"));
			response.setHeader("Content-Type", preferred != null ? preferred : "text/plain");
			response.write("preferred:" + preferred);
		}
	}

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
			serverChannel.bind(new InetSocketAddress(0));
			PORT = serverChannel.socket().getLocalPort();
		}

		Map<String, RequestHandler> reqHandlers = new HashMap<>();
		reqHandlers.put("/cors", new CorsTestHandler());
		
		server = new HttpServer(new Application(reqHandlers));

		Thread.ofPlatform().start(() -> {
			try {
				server.listen(PORT);
				IOLoop.INSTANCE.start();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		
		Thread.sleep(200);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		server.stop();
		IOLoop.INSTANCE.stop();
		Thread.sleep(100);
	}

	@Test
	public void testAutomatedCorsPreflight() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + PORT + "/cors"))
				.header("Origin", "http://example.com")
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "X-Custom-Header")
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		
		assertEquals(204, response.statusCode());
		assertEquals("http://example.com", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
		assertEquals("true", response.headers().firstValue("Access-Control-Allow-Credentials").orElse(null));
		assertEquals("GET, POST, DELETE", response.headers().firstValue("Access-Control-Allow-Methods").orElse(null));
		assertEquals("X-Custom-Header, Content-Type", response.headers().firstValue("Access-Control-Allow-Headers").orElse(null));
		assertEquals("7200", response.headers().firstValue("Access-Control-Max-Age").orElse(null));
	}

	@Test
	public void testQValuesContentNegotiation() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		
		// Case 1: Prefers HTML (q=0.9) over JSON (q=0.5)
		HttpRequest r1 = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + PORT + "/cors"))
				.header("Origin", "http://test.org")
				.header("Accept", "application/json;q=0.5, text/html;q=0.9")
				.GET()
				.build();

		HttpResponse<String> response1 = client.send(r1, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response1.statusCode());
		assertEquals("text/html", response1.headers().firstValue("Content-Type").orElse(null));
		assertEquals("preferred:text/html", response1.body());
		assertEquals("http://test.org", response1.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

		// Case 2: Prefers JSON (no q value = 1.0) over HTML (q=0.8)
		HttpRequest r2 = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + PORT + "/cors"))
				.header("Origin", "http://test.org")
				.header("Accept", "text/html;q=0.8, application/json")
				.GET()
				.build();

		HttpResponse<String> response2 = client.send(r2, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response2.statusCode());
		assertEquals("application/json", response2.headers().firstValue("Content-Type").orElse(null));
		assertEquals("preferred:application/json", response2.body());
	}
}
