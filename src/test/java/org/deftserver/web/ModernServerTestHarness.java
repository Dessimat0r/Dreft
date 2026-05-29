package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.deftserver.io.IOLoop;
import org.deftserver.web.handler.RequestHandler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModernServerTestHarness {

	private static final Logger logger = LoggerFactory.getLogger(ModernServerTestHarness.class);
	private static final int PORT = 8089;
	private static final String BASE_URL = "http://localhost:" + PORT;

	private static class SimpleGetHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("OK GET");
		}
	}

	private static class BodyPostHandler extends RequestHandler {
		@Override
		public void post(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			echo(request, response);
		}

		@Override
		public void put(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			echo(request, response);
		}

		@Override
		public void patch(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			echo(request, response);
		}

		@Override
		public void head(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("HEAD BODY SHOULD NOT BE SENT");
		}

		private void echo(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("ECHO: " + request.getBody());
		}
	}

	@BeforeClass
	public static void setupServer() throws Exception {
		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/get", new SimpleGetHandler());
		handlers.put("/post", new BodyPostHandler());

		final Application application = new Application(handlers);

		new Thread(() -> {
			try {
				new HttpServer(application).listen(PORT);
				IOLoop.INSTANCE.start();
			} catch (IOException e) {
				logger.error("Failed to start server", e);
			}
		}).start();

		waitForServer();
	}

	@AfterClass
	public static void tearDownServer() throws Exception {
		IOLoop.INSTANCE.addCallback(() -> IOLoop.INSTANCE.stop());
		Thread.sleep(20);
	}

	@Test
	public void testConcurrentGetRequestsUsingVirtualThreads() throws InterruptedException {
		int numRequests = 200;
		CountDownLatch latch = new CountDownLatch(numRequests);
		AtomicInteger successCount = new AtomicInteger(0);

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			HttpClient client = HttpClient.newHttpClient();

			for (int i = 0; i < numRequests; i++) {
				executor.submit(() -> {
					try {
						HttpRequest req = HttpRequest.newBuilder()
								.uri(URI.create(BASE_URL + "/get"))
								.GET()
								.build();
						HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
						
						if (resp.statusCode() == 200 && "OK GET".equals(resp.body())) {
							successCount.incrementAndGet();
						}
					} catch (Exception e) {
						logger.error("Error sending concurrent GET request", e);
					} finally {
						latch.countDown();
					}
				});
			}

			assertTrue(latch.await(10, TimeUnit.SECONDS));
			assertEquals(numRequests, successCount.get());
		}
	}

	@Test
	public void testPostBodyParsingVerification() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		String requestBodyStr = "Hello Deft Server!";

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(BASE_URL + "/post"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(requestBodyStr))
				.build();

		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		
		assertEquals(200, resp.statusCode());
		assertEquals("ECHO: Hello Deft Server!", resp.body());
	}

	@Test
	public void testUnsupportedHttpVerbsReturn501() throws Exception {
		HttpClient client = HttpClient.newHttpClient();

		// Test OPTIONS verb
		HttpRequest optionsReq = HttpRequest.newBuilder()
				.uri(URI.create(BASE_URL + "/get"))
				.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> optionsResp = client.send(optionsReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(501, optionsResp.statusCode());

		// Test TRACE verb
		HttpRequest traceReq = HttpRequest.newBuilder()
				.uri(URI.create(BASE_URL + "/get"))
				.method("TRACE", HttpRequest.BodyPublishers.noBody())
				.build();
		HttpResponse<String> traceResp = client.send(traceReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(501, traceResp.statusCode());
	}

	@Test
	public void testSlowChunkedPostOverRawSocket() throws Exception {
		try (Socket socket = new Socket("127.0.0.1", PORT)) {
			OutputStream out = socket.getOutputStream();
			out.write("""
					POST /post HTTP/1.1\r
					Host: localhost\r
					Transfer-Encoding: chunked\r
					Content-Type: text/plain\r
					Connection: close\r
					\r
					""".getBytes(StandardCharsets.ISO_8859_1));
			out.flush();
			Thread.sleep(10);
			out.write("4\r\nWiki\r\n".getBytes(StandardCharsets.ISO_8859_1));
			out.flush();
			Thread.sleep(10);
			out.write("5\r\npedia\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
			out.flush();

			String response = readAll(socket.getInputStream());
			assertTrue(response.startsWith("HTTP/1.1 200 OK"));
			assertTrue(response.endsWith("ECHO: Wikipedia"));
		}
	}

	@Test
	public void testHeadSuppressesBody() throws Exception {
		String response = rawRequest("""
				HEAD /post HTTP/1.1\r
				Host: localhost\r
				Connection: close\r
				\r
				""");

		assertTrue(response.startsWith("HTTP/1.1 200 OK"));
		assertTrue(response.contains("Content-Length: 28"));
		assertTrue(response.endsWith("\r\n\r\n"));
	}

	@Test
	public void testUnknownMethodReturns501() throws Exception {
		String response = rawRequest("""
				PROPFIND /get HTTP/1.1\r
				Host: localhost\r
				Connection: close\r
				\r
				""");

		assertTrue(response.startsWith("HTTP/1.1 501 Not Implemented"));
	}

	@Test
	public void testConflictingContentLengthReturnsBadRequest() throws Exception {
		String response = rawRequest("""
				POST /post HTTP/1.1\r
				Host: localhost\r
				Content-Type: text/plain\r
				Content-Length: 4\r
				Content-Length: 5\r
				Connection: close\r
				\r
				test""");

		assertTrue(response.startsWith("HTTP/1.1 400 Bad Request"));
	}

	private static String rawRequest(String request) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", PORT)) {
			socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
			return readAll(socket.getInputStream());
		}
	}

	private static String readAll(InputStream in) throws IOException {
		return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
	}

	private static void waitForServer() throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			try (Socket ignored = new Socket("127.0.0.1", PORT)) {
				return;
			} catch (IOException e) {
				Thread.sleep(25);
			}
		}
		throw new AssertionError("Server did not start");
	}

}
