package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import org.deftserver.example.kv.KeyValueStore;
import org.deftserver.io.IOLoop;
import org.deftserver.io.timeout.Timeout;
import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpException;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.client.AsynchronousHttpClient;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;


public class DeftSystemTest {

	private static int PORT;
	// Held so @AfterClass can close the listening socket. Without this, the ServerSocketChannel
	// stays bound and registered on the shared IOLoop.INSTANCE selector after this class finishes,
	// leaking into subsequent test classes that reuse the singleton (a source of full-suite
	// cross-class flakiness).
	private static HttpServer httpServer;

	public static final String expectedPayload = "hello test";

	private static class ExampleRequestHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write(expectedPayload);
		}
	}

	private static class WRequestHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("1");
		}
	}

	private static class WWRequestHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("1");
			response.write("2");
		}
	}

	private static class WWFWRequestHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("1");
			response.write("2");
			response.flush();
			response.write("3");
		}
	}

	private static class WFWFRequestHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("1");
			response.flush();
			response.write("2");
			response.flush();
		}
	}
	
	private static class WFFFWFFFRequestHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("1");
			response.flush();
			response.flush();
			response.flush();
			response.write("2");
			response.flush();
			response.flush();
			response.flush();
		}
	}

	private static class DeleteRequestHandler extends RequestHandler {
		@Override
		public void delete(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("del");
			response.flush();
			response.write("ete");
			response.flush();
		}
	}

	private static class PostRequestHandler extends RequestHandler {
		@Override
		public void post(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("po");
			response.flush();
			response.write("st");
			response.flush();
		}
	}

	private static class PutRequestHandler extends RequestHandler {
		@Override
		public void put(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("p");
			response.flush();
			response.write("ut");
			response.flush();
		}
	}

	private static class CapturingRequestRequestHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write(request.getRequestedPath());
		}
	}

	private static class ThrowingHttpExceptionRequestHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			throw new HttpException(500, "exception message");
		}
	}

	private static class AsyncThrowingHttpExceptionRequestHandler extends RequestHandler {
		@Asynchronous
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			throw new HttpException(500, "exception message");
		}
	}

	/** An async handler that never finishes its response — must be bounded by the processing timeout. */
	private static class HangingAsyncRequestHandler extends RequestHandler {
		@Asynchronous
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			// Intentionally never calls response.finish().
		}
	}

	public static class NoBodyRequestHandler extends RequestHandler {
		@Override
		public void get(HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.setStatusCode(200);
		}
	}

	public static class MovedPermanentlyRequestHandler extends RequestHandler {
		@Override
		public void get(HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.setStatusCode(301);
			response.setHeader("Location", "/");
		}
	}

	public static class UserDefinedStaticContentHandler extends RequestHandler {
		@Override
		public void get(HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write(new File("src/test/resources/test.txt"));
		}
	}

	public static class KeyValueStoreExampleRequestHandler extends RequestHandler {

		private final int port;

		public KeyValueStoreExampleRequestHandler() {
			KeyValueStore store = new KeyValueStore();
			store.start();
			port = store.getPort();
		}

		@Override
		@Asynchronous
		public void get(HttpRequest request, final org.deftserver.web.http.HttpResponse response) {
			Thread.startVirtualThread(() -> {
				try (
					Socket socket = new Socket(KeyValueStore.HOST, port);
					BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream()));
					BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))
				) {
					writer.write("GET deft\r\n");
					writer.flush();
					String result = reader.readLine();
					IOLoop.INSTANCE.addCallback(() -> response.write(result).finish());
				} catch (IOException e) {
					IOLoop.INSTANCE.addCallback(() -> {
						response.setStatusCode(500);
						response.write(e.getMessage()).finish();
					});
				}
			});
		}

	}
	
	public static class _450KBResponseEntityRequestHandler extends RequestHandler {
		public static String entity;

		static {
			int iterations = 450*1024;
			StringBuilder sb = new StringBuilder();
			for (int i = 1; i <= iterations; i++) { sb.append("a"); }
			entity = sb.toString();
		}
		
		@Override
		public void get(HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write(entity);
		}
	}
	
	public static class EchoingPostBodyRequestHandler extends RequestHandler {
		@Override
		public void post(HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write(request.getBody());
		}
	}
	
	public static class AuthenticatedRequestHandler extends RequestHandler {
		@Override
		@Authenticated
		public void get(HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write(request.getHeader("user"));
		}
		
		@Override
		public String getCurrentUser(HttpRequest request) {
			return request.getHeader("user");
		}
	}
	
	private static class QueryParamsRequestHandler extends RequestHandler {
		@Override
		public void get(HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write(request.getParameter("key1") + " " + request.getParameter("key2"));
		}
	}
	
	private static class ChunkedRequestHandler extends RequestHandler {
		@Override
		public void get(HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.setHeader("Transfer-Encoding", "chunked");
			sleep(10);
			
			response.write("1\r\n");
			response.write("a\r\n").flush();
			sleep(10);
			
			response.write("5\r\n");
			response.write("roger\r\n").flush();
			sleep(10);
			
			response.write("2\r\n");
			response.write("ab\r\n").flush();
			sleep(10);
			
			response.write("0\r\n");
			response.write("\r\n");
		}
		
		private static void sleep(long ms) {
			try { Thread.sleep(ms); } catch (InterruptedException ignore) { /* nop */	}
		}
	}

	@BeforeClass
	public static void setup() throws IOException {
		PORT = freePort();
		org.deftserver.io.AsynchronousSocket.setDnsResolver((host, port) -> {
			if ("somehost.invalid".equalsIgnoreCase(host)) {
				throw new java.nio.channels.UnresolvedAddressException();
			}
			return new java.net.InetSocketAddress(host, port);
		});
		Map<String, RequestHandler> reqHandlers = new HashMap<String, RequestHandler>();
		reqHandlers.put("/", new ExampleRequestHandler());
		reqHandlers.put("/w", new WRequestHandler());
		reqHandlers.put("/ww", new WWRequestHandler());
		reqHandlers.put("/wwfw", new WWFWRequestHandler());
		reqHandlers.put("/wfwf", new WFWFRequestHandler());
		reqHandlers.put("/wfffwfff", new WFFFWFFFRequestHandler());
		reqHandlers.put("/delete", new DeleteRequestHandler());
		reqHandlers.put("/post", new PostRequestHandler());
		reqHandlers.put("/put", new PutRequestHandler());
		reqHandlers.put("/capturing/([0-9]+)", new CapturingRequestRequestHandler());
		reqHandlers.put("/throw", new ThrowingHttpExceptionRequestHandler());
		reqHandlers.put("/async_throw", new AsyncThrowingHttpExceptionRequestHandler());
		reqHandlers.put("/async_hang", new HangingAsyncRequestHandler());
		reqHandlers.put("/no_body", new NoBodyRequestHandler());
		reqHandlers.put("/moved_perm", new MovedPermanentlyRequestHandler());
		reqHandlers.put("/static_file_handler", new UserDefinedStaticContentHandler());
		reqHandlers.put("/keyvalue", new KeyValueStoreExampleRequestHandler());
		reqHandlers.put("/450kb_body", new _450KBResponseEntityRequestHandler());
		reqHandlers.put("/echo", new EchoingPostBodyRequestHandler());
		reqHandlers.put("/authenticated", new AuthenticatedRequestHandler());
		reqHandlers.put("/query_params", new QueryParamsRequestHandler());
		reqHandlers.put("/chunked", new ChunkedRequestHandler());
		reqHandlers.put("/writebb", new RequestHandler() {
			@Override
			public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
				response.write(java.nio.ByteBuffer.wrap("bytebufferbody".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			}
		});

		reqHandlers.put("/lastmod", new RequestHandler() {
			@Override
			public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
				// Fixed Last-Modified so conditional-precedence tests are deterministic (correct
				// day-of-week: 2021-01-01 was a Friday — java.time RFC_1123 validates the name).
				response.setHeader("Last-Modified", "Fri, 01 Jan 2021 00:00:00 GMT");
				response.setHeader("Content-Type", "text/plain; charset=utf-8");
				response.write("ok");
			}
		});

		reqHandlers.put("/largeclose", new RequestHandler() {
			@Override
			public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
				// Large body + Connection: close. The body is big enough to (usually) defer the
				// write to OP_WRITE; the connection must still close once the deferred write
				// completes rather than being re-registered for read (P41).
				response.setHeader("Connection", "close");
				response.setHeader("Content-Type", "text/plain; charset=utf-8");
				StringBuilder sb = new StringBuilder(1_000_000);
				for (int i = 0; i < 1_000_000; i++) sb.append('x');
				response.write(sb.toString());
			}
		});

		reqHandlers.put("/preencoded", new RequestHandler() {
			@Override
			public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
				// Handler pre-compressed the body and declared it. The framework must NOT gzip again
				// (double-encoding would corrupt it), even though the type is gzippable text.
				response.setHeader("Content-Type", "text/plain; charset=utf-8");
				response.setHeader("Content-Encoding", "gzip");
				response.write("already-encoded-bytes");
			}
		});

		reqHandlers.put("/partial206", new RequestHandler() {
			@Override
			public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
				// A handler that emits 206 Partial Content with a text body; must NOT be gzipped
				// (re-encoding would invalidate the byte range / Content-Range).
				response.setStatusCode(206);
				response.setHeader("Content-Type", "text/plain; charset=utf-8");
				response.setHeader("Content-Range", "bytes 0-4/10");
				response.write("Hello");
			}
		});

		final Application application = new Application(reqHandlers);
		application.setStaticContentDir("src/test/resources");

		// start deft instance from a new thread because the start invocation is blocking 
		// (invoking thread will be I/O loop thread)
		httpServer = new HttpServer(application);
		Thread.ofPlatform().name("I/O-LOOP").start(() -> {
			try {
				httpServer.listen(PORT);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			IOLoop.INSTANCE.start();
		});
		waitForServer();
	}

	private static int freePort() throws IOException {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static void waitForServer() throws IOException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			try (java.net.Socket ignored = new java.net.Socket("127.0.0.1", PORT)) {
				return;
			} catch (IOException e) {
				try {
					Thread.sleep(25);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for server", interrupted);
				}
			}
		}
		throw new IOException("Server did not start on port " + PORT);
	}
	
	@AfterClass
	public static void tearDown() throws InterruptedException {
		// Close the listening socket so the ServerSocketChannel is unbound and its key cancelled —
		// otherwise it leaks into the shared IOLoop.INSTANCE and the next test class inherits a
		// stale, still-bound server channel. This matches the canonical teardown used by every
		// other server-starting test class (server.stop() only). We deliberately do NOT call
		// IOLoop.INSTANCE.stop(): the singleton loop is shared across all test classes in the same
		// JVM (forkCount=1, reuseForks=true), and stopping it mid-suite disrupts classes that run
		// afterwards (their already-issued start() is a no-op on an already-running loop).
		if (httpServer != null) {
			httpServer.stop();
		}
		Thread.sleep(20);
		org.deftserver.io.AsynchronousSocket.setDnsResolver(java.net.InetSocketAddress::new);
	}

	@Test
	public void simpleGetRequestTest() throws IOException, InterruptedException {
		doSimpleGetRequest();
	}

	private void doSimpleGetRequest() throws IOException, InterruptedException {
		String[] expectedHeaders = {"Server", "Date", "Content-Length", "Etag", "Connection"};
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());

		for (String header : expectedHeaders) {
			assertTrue(response.headers().firstValue(header).isPresent());
		}

		assertEquals(expectedPayload, response.body().trim());
		assertEquals(expectedPayload.length()+"", response.headers().firstValue("Content-Length").orElse(""));
	}

	/**
	 * Test a RH that does a single write
	 * @
	 * @throws IOException
	 */
	@Test
	public void wTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/w"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals("1", response.body().trim());
		assertEquals("1", response.headers().firstValue("Content-Length").orElse(""));
	}


	@Test
	public void wwTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/ww"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals("12", response.body().trim());
		assertEquals("2", response.headers().firstValue("Content-Length").orElse(""));
	}

	@Test
	public void wwfwTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/wwfw"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals("123", response.body().trim());
		assertEquals(3, response.headers().map().size());
	}

	@Test
	public void wfwfTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/wfwf"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals("12", response.body().trim());
		assertEquals(3, response.headers().map().size());
	}
	
	@Test
	public void wfffwfffTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/wfffwfff"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals("12", response.body().trim());
		assertEquals(3, response.headers().map().size());
	}

	@Test
	public void deleteTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/delete"))
			.DELETE()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals("delete", response.body().trim());
	}

	@Test
	public void PostTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/post"))
			.POST(java.net.http.HttpRequest.BodyPublishers.noBody())
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals("post", response.body().trim());
	}

	@Test
	public void putTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/put"))
			.PUT(java.net.http.HttpRequest.BodyPublishers.noBody())
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals("put", response.body().trim());
	}

	@Test
	public void capturingTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/capturing/1911"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals("/capturing/1911", response.body().trim());
	}

	@Test
	public void erroneousCapturingTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/capturing/r1911"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(404, response.statusCode());
		assertEquals("<html><head><title>404: Not found</title></head><body>Requested resource: <tt>/capturing/r1911</tt> was not found.</body>", response.body().trim());
	}

	@Test
	public void simpleConcurrentGetRequestTest() {
		int nThreads = 8;
		int nRequests = 50;
		final CountDownLatch latch = new CountDownLatch(nRequests);
		ExecutorService executor = Executors.newFixedThreadPool(nThreads);

		for (int i = 1; i <= nRequests; i++) {
			executor.submit(new Runnable() {

				@Override
				public void run() {
					try {
						doSimpleGetRequest();
						latch.countDown();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}

			});
		}
		try {
			latch.await(15 * 1000, TimeUnit.MILLISECONDS);	// max wait time
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		if (latch.getCount() != 0) {
			assertTrue("Did not finish " + nRequests + " # of requests", false);
		}
	}

	@Test
	public void keepAliveRequestTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();

		for (int i = 1; i <= 5; i++) {
			doKeepAliveRequestTest(httpclient);
		}
	}

	private void doKeepAliveRequestTest(java.net.http.HttpClient httpclient)
	throws IOException, InterruptedException {
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals(expectedPayload, response.body().trim());
	}

	@Test
	public void HTTP_1_0_noConnectionHeaderTest() throws IOException {
		// Uses sendRawRequest because JDK HttpClient doesn't allow setting the HTTP version.
		String response = sendRawRequest(
			"GET / HTTP/1.0\r\n" +
			"Host: localhost\r\n\r\n"
		);
		assertTrue(response.startsWith("HTTP/1.0 200") || response.startsWith("HTTP/1.1 200"));
		String responseLower = response.toLowerCase(java.util.Locale.ROOT);
		String[] expectedHeaders = {"server", "date", "content-length", "etag", "connection"};
		for (String header : expectedHeaders) {
			assertTrue("Missing header: " + header, responseLower.contains(header + ": "));
		}
		int bodyStart = response.indexOf("\r\n\r\n") + 4;
		String body = response.substring(bodyStart).trim();
		assertEquals(expectedPayload, body);
	}


	@Test
	public void httpExceptionTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/throw"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(500, response.statusCode());
		// The tiny error body ("exception message", 17 B) is below COMPRESSION_MIN_SIZE (V3-57), so it
		// is sent identity (NOT gzipped) — gzip would only make it larger. The error path still emits
		// X-Content-Type-Options: nosniff.
		assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
		assertNull("a sub-threshold error body must not be gzipped", response.headers().firstValue("Content-Encoding").orElse(null));
		assertEquals("exception message", response.body().trim());
	}

	@Test
	public void asyncHttpExceptionTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/async_throw"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(500, response.statusCode());
		// Tiny error body below COMPRESSION_MIN_SIZE (V3-57) → identity, not gzipped.
		assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null));
		assertNull("a sub-threshold error body must not be gzipped", response.headers().firstValue("Content-Encoding").orElse(null));
		assertEquals("exception message", response.body().trim());
	}

	@Test
	public void staticFileRequestTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/src/test/resources/test.txt"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		// Content-Type is resolved extension-first (.txt → text/plain) with a UTF-8 charset appended.
		assertEquals("text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElse(null));
		assertEquals("test.txt", response.body().trim());
	}

	@Test
	public void pictureStaticFileRequestTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/src/test/resources/n792205362_2067.jpg"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals("54963", response.headers().firstValue("Content-Length").orElse(null));
		assertEquals("image/jpeg", response.headers().firstValue("Content-Type").orElse(null));
		assertNotNull(response.headers().firstValue("Last-Modified").orElse(null));
	}
	
	@Test
	public void pictureStaticLargeFileRequestTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/src/test/resources/f4_impact_1_original.jpg"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		//assertEquals("2145094", response.getFirstHeader("Content-Length").getValue()); // my mb says 2145066, imac says 2145094
		assertEquals("image/jpeg", response.headers().firstValue("Content-Type").orElse(null));
		assertNotNull(response.headers().firstValue("Last-Modified").orElse(null));
		// TODO RS 101026 Verify that the actual body/entity is 2145094 bytes big (when we have support for "large" file)
	}

	@Test
	public void noBodyRequest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/no_body"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		String[] expectedHeaders = {"Server", "Date", "Content-Length", "Connection"};

		assertEquals(200, response.statusCode());

		for (String header : expectedHeaders) {
			assertTrue(response.headers().firstValue(header).isPresent());
		}

		assertEquals("", response.body().trim());
		assertEquals("0", response.headers().firstValue("Content-Length").orElse(null));
	}

	@Test
	public void movedPermanentlyRequest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newBuilder()
			.followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
			.build();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/moved_perm"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		String[] expectedHeaders = {"Server", "Date", "Content-Length", "Connection", "Etag"};

		assertEquals(200, response.statusCode());

		for (String header : expectedHeaders) {
			assertTrue(response.headers().firstValue(header).isPresent());
		}

		assertEquals(expectedPayload, response.body().trim());
		assertEquals(expectedPayload.length()+"", response.headers().firstValue("Content-Length").orElse(null));
	}

	@Test
	public void sendGarbageTest() throws IOException {
		InetSocketAddress socketAddress = new InetSocketAddress(PORT);
		SocketChannel channel = SocketChannel.open(socketAddress);
		channel.write(
				ByteBuffer.wrap(
						new byte[] {1, 1, 1, 1}	// garbage
				)
		);
		channel.close();
	}

	@Test
	public void userDefinedStaticContentHandlerTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/static_file_handler"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals("8", response.headers().firstValue("Content-Length").orElse(null));
	}

	@Test
	public void timeoutTest() throws InterruptedException {
		long now = System.currentTimeMillis();
		final CountDownLatch latch = new CountDownLatch(5);
		final AsyncCallback cb = new AsyncCallback() {

			@Override public void onCallback() { latch.countDown(); }

		};

		Timeout t1 = new Timeout(now+20, cb);
		Timeout t2 = new Timeout(now+40, cb);
		Timeout t3 = new Timeout(now+60, cb);
		Timeout t4 = new Timeout(now+80, cb);
		Timeout t5 = new Timeout(now+100, cb);
		IOLoop.INSTANCE.addTimeout(t1);
		IOLoop.INSTANCE.addTimeout(t2);
		IOLoop.INSTANCE.addTimeout(t3);
		IOLoop.INSTANCE.addTimeout(t4);
		IOLoop.INSTANCE.addTimeout(t5);

		latch.await(1000, TimeUnit.MILLISECONDS);
		assertTrue(latch.getCount() == 0);
	}
	
	@Test
	public void callbackTest() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(5);
		final AsyncCallback cb = new AsyncCallback() {

			@Override public void onCallback() { latch.countDown(); }
		
		};
		IOLoop.INSTANCE.addCallback(cb);
		IOLoop.INSTANCE.addCallback(cb);
		IOLoop.INSTANCE.addCallback(cb);
		IOLoop.INSTANCE.addCallback(cb);
		IOLoop.INSTANCE.addCallback(cb);
		
		latch.await(5 * 1000, TimeUnit.MILLISECONDS);
		assertTrue(latch.getCount() == 0);
	}

	@Test
	public void keyValueStoreClientTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/keyvalue"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals("7", response.headers().firstValue("Content-Length").orElse(null));
		assertEquals("kickass", response.body().trim());
	}

	//formerly used Ning async-http-client; now uses JDK java.net.http.HttpClient
	@Test
	public void doSimpleAsyncRequestTestWithNing() throws IOException, InterruptedException {
		int iterations = 5;
		java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
		List<String> expectedHeaders = Arrays.asList("Server", "Date", "Content-Length", "Etag", "Connection");
		for (int i = 1; i <= iterations; i++) {
			java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
					.uri(URI.create("http://localhost:" + PORT + "/"))
					.GET()
					.build();
			try {
				java.net.http.HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
				assertEquals(200, response.statusCode());
				assertEquals(expectedPayload, response.body());
				for (String header : expectedHeaders) {
					assertTrue("Missing header: " + header, response.headers().firstValue(header).isPresent());
				}
				assertEquals(String.valueOf(expectedPayload.length()), response.headers().firstValue("Content-Length").orElse(""));
			} catch (java.net.http.HttpTimeoutException e) {
				throw new AssertionError("Request timed out", e);
			}
		}
	}
	
	// TODO 101108 RS enable when /mySql (AsyncDbHandler is properly implemented)
	//ning === http://github.com/ning/async-http-client
//	@Test
//	public void doAsynchronousRequestTestWithNing() throws IOException, InterruptedException {
//		int iterations = 200;
//		final CountDownLatch latch = new CountDownLatch(iterations);
//		AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
//		for (int i = 1; i <= iterations; i++) {
//
//			asyncHttpClient.prepareGet("http://localhost:" + PORT + "/mySql").
//			execute(new AsyncCompletionHandler<com.ning.http.client.Response>(){
//
//				@Override
//				public com.ning.http.client.Response onCompleted(com.ning.http.client.Response response) throws Exception{
//					String body = response.getResponseBody();
//					assertEquals("Name: Jim123", body);
//					List<String> expectedHeaders = Arrays.asList(new String[] {"Server", "Date", "Content-Length", "Etag", "Connection"});
//					assertEquals(200, response.getStatusCode());
//					assertEquals(expectedHeaders.size(), response.getHeaders().getHeaderNames().size());
//					for (String header : expectedHeaders) {
//						assertTrue(response.getHeader(header) != null);
//					}
//					assertEquals(""+ "Name: Jim123".length(), response.getHeader("Content-Length"));
//					latch.countDown();
//					return response;
//				}
//
//				@Override
//				public void onThrowable(Throwable t){
//					assertTrue(false);
//				}
//
//			});
//		}
//		latch.await(15 * 1000, TimeUnit.MILLISECONDS);
//		assertEquals(0, latch.getCount());
//	}
	
	@Test
	public void _450KBEntityTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/450kb_body"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals(_450KBResponseEntityRequestHandler.entity, response.body().trim());
	}
	
	@Test
	public void smallHttpPostBodyWithUnusualCharactersTest() throws IOException, InterruptedException {
		final String body = "Räger Schildmäijår";
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/echo"))
			.header("Content-Type", "text/plain; charset=ISO-8859-1")
			.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body, java.nio.charset.Charset.forName("ISO-8859-1")))
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());	

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals(body, response.body().trim());
	}
	
	@Test
	public void smallHttpPostBodyTest() throws IOException, InterruptedException {
		final String body = "Roger Schildmeijer";
		java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + PORT + "/echo"))
				.POST(BodyPublishers.ofString(body))
				.build();
		try {
			java.net.http.HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
			assertNotNull(response);
			assertEquals(200, response.statusCode());
			assertEquals(body, response.body());
		} catch (java.net.http.HttpTimeoutException e) {
			throw new AssertionError("Request timed out", e);
		}
	}
	
	@Test
	public void largeHttpPostBodyTest() throws IOException, InterruptedException {
		StringBuilder sb = new StringBuilder("Roger Schildmeijer: 0\n");
		for (int i = 1; i <= 1000; i++) {
			sb.append("Roger Schildmeijer: ").append(i).append("\n");
		}
		String body = sb.toString();
		java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + PORT + "/echo"))
				.POST(BodyPublishers.ofString(body))
				.build();
		try {
			java.net.http.HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
			assertNotNull(response);
			assertEquals(200, response.statusCode());
			assertEquals(body, response.body());
		} catch (java.net.http.HttpTimeoutException e) {
			throw new AssertionError("Request timed out", e);
		}
	}
	
	@Test
	public void authenticatedRequestHandlerTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/authenticated"))
			.header("user", "Roger Schildmeijer")
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(200, response.statusCode());
		assertEquals("Roger Schildmeijer", response.body().trim());
	}
	
	@Test
	public void notAuthenticatedRequestHandlerTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/authenticated"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		assertNotNull(response);
		assertEquals(403, response.statusCode());
		assertEquals("Authentication failed", response.body().trim());
	}
	
	@Test
	public void queryParamsTest() throws IOException, InterruptedException {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/query_params?key1=value1&key2=value2"))
			.GET()
			.build();
		java.net.http.HttpResponse<String> response = httpclient.send(request, HttpResponse.BodyHandlers.ofString());

		String[] expectedHeaders = {"Server", "Date", "Content-Length", "Etag", "Connection"};

		assertEquals(200, response.statusCode());

		for (String header : expectedHeaders) {
			assertTrue(response.headers().firstValue(header).isPresent());
		}

		final String expected = "value1 value2";
		assertEquals(expected, response.body().trim());
		assertEquals(expected.length()+"", response.headers().firstValue("Content-Length").orElse(null));
	}
	
	@Test
	public void multipleStartStopCombinations() throws InterruptedException, IOException {
		final HttpServer server = new HttpServer(new Application(new HashMap<String, RequestHandler>()));
		final int port = freePort();
		
		final int n = 2;
		for (int i = 0; i < n; i++) {
			final CountDownLatch latch = new CountDownLatch(1);
			IOLoop.INSTANCE.addCallback(new AsyncCallback() { public void onCallback() { 
				try {
					server.listen(port);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}});
			IOLoop.INSTANCE.addCallback(new AsyncCallback() { public void onCallback() { 
				server.stop(); 
				IOLoop.INSTANCE.addCallback(new AsyncCallback() { public void onCallback() {
					latch.countDown();
				}});
			}});
			assertTrue("Server stop timed out between iterations", latch.await(2, TimeUnit.SECONDS));
		}
	}
	
	@Test
	public void connectToUnresolvableAddressUsingAsynchronousHttpClient() throws InterruptedException {
		final String unresolvableAddress = "http://somehost.invalid/start";
		final CountDownLatch latch = new CountDownLatch(1);
		final AsynchronousHttpClient client = new AsynchronousHttpClient();
		final AsyncCallback runByIOLoop = new AsyncCallback() {

			public void onCallback() {
				client.fetch(unresolvableAddress, new AsyncResult<org.deftserver.web.http.client.Response>() {

					public void onSuccess(org.deftserver.web.http.client.Response result) { client.close(); }

					public void onFailure(Throwable caught) { 
						if (caught instanceof UnresolvedAddressException) latch.countDown();
						client.close();
					}
				});
			}
		};
		IOLoop.INSTANCE.addCallback(runByIOLoop);
		
		latch.await(5, TimeUnit.SECONDS);
		assertEquals(0, latch.getCount());
	}

	@Test
	public void connectToUnconnectableAddressUsingAsynchronousHttpClient() throws InterruptedException {
		final String unconnectableAddress = "http://localhost:8039/start";
		final CountDownLatch latch = new CountDownLatch(1);
		final AsynchronousHttpClient client = new AsynchronousHttpClient();
		final AsyncCallback runByIOLoop = new AsyncCallback() {

			public void onCallback() {
				client.fetch(unconnectableAddress, new AsyncResult<org.deftserver.web.http.client.Response>() {

					public void onSuccess(org.deftserver.web.http.client.Response result) { client.close(); }

					public void onFailure(Throwable caught) { 
						if (caught instanceof ConnectException) latch.countDown();
						client.close();
					}
				});
			}
		};
		IOLoop.INSTANCE.addCallback(runByIOLoop);
		
		latch.await(5, TimeUnit.SECONDS);
		assertEquals(0, latch.getCount());
	}
	
	@Test
	public void multipleAsynchronousHttpClientTest() throws InterruptedException {
		for (int i = 0; i < 5; i++) {
			final CountDownLatch latch = new CountDownLatch(1);
			final String url = "http://localhost:" + PORT + "/";
			final AsynchronousHttpClient http = new AsynchronousHttpClient();
			final String[] result = {"BODY_PLACEHOLDER", "STATUSCODE_PLACEHOLDER"};
			final AsyncResult<org.deftserver.web.http.client.Response> cb =
				new AsyncResult<org.deftserver.web.http.client.Response>() {

				public void onSuccess(org.deftserver.web.http.client.Response response) { 
					result[0] = response.getBody();
					result[1] = response.getStatusLine();
					latch.countDown(); 
				}

				public void onFailure(Throwable ignore) { }
			};
			// make sure that the http.fetch(..) is invoked from the ioloop thread
			IOLoop.INSTANCE.addCallback(new AsyncCallback() { public void onCallback() { http.fetch(url, cb); }});
			latch.await(15, TimeUnit.SECONDS);
			assertEquals(0, latch.getCount());
			assertEquals("hello test", result[0]);
			assertEquals("HTTP/1.1 200 OK", result[1]);
		}
	}
	
	@Test
	public void asyncClientMalformedContentLengthInvokesOnFailureTest() throws Exception {
		// A server that returns a non-numeric Content-Length must surface as onFailure on the async
		// client, not a silent hang (the parse used to throw an uncaught NumberFormatException that
		// dropped the channel without notifying the caller).
		final java.net.ServerSocket raw = new java.net.ServerSocket(0);
		final int rawPort = raw.getLocalPort();
		Thread server = new Thread(() -> {
			try (Socket s = raw.accept()) {
				InputStream in = s.getInputStream();
				byte[] buf = new byte[4096];
				in.read(buf); // consume the request (best-effort)
				s.getOutputStream().write(
					("HTTP/1.1 200 OK\r\nContent-Length: notanumber\r\n\r\n")
						.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
				s.getOutputStream().flush();
				Thread.sleep(200); // give the client time to read before we close
			} catch (Exception ignore) {
				// test thread teardown
			}
		});
		server.setDaemon(true);
		server.start();

		final CountDownLatch latch = new CountDownLatch(1);
		final AsynchronousHttpClient http = new AsynchronousHttpClient();
		final AsyncResult<org.deftserver.web.http.client.Response> cb =
			new AsyncResult<org.deftserver.web.http.client.Response>() {
				public void onSuccess(org.deftserver.web.http.client.Response r) { http.close(); }
				public void onFailure(Throwable e) { latch.countDown(); http.close(); }
			};
		final String url = "http://localhost:" + rawPort + "/";
		IOLoop.INSTANCE.addCallback(new AsyncCallback() { public void onCallback() { http.fetch(url, cb); }});
		try {
			assertTrue("async client must invoke onFailure on malformed Content-Length",
				latch.await(5, TimeUnit.SECONDS));
		} finally {
			raw.close();
		}
	}

	@Test
	public void AsynchronousHttpClientConnectionFailedTest() throws InterruptedException, IOException {
		final CountDownLatch latch = new CountDownLatch(1);
		final String url = "http://localhost:" + freePort() + "/";
		final AsynchronousHttpClient http = new AsynchronousHttpClient();
		final AsyncResult<org.deftserver.web.http.client.Response> cb =
			new AsyncResult<org.deftserver.web.http.client.Response>() {

			public void onSuccess(org.deftserver.web.http.client.Response response) { }

			public void onFailure(Throwable e) { if (e instanceof ConnectException) latch.countDown(); }
		};
		// make sure that the http.fetch(..) is invoked from the ioloop thread
		IOLoop.INSTANCE.addCallback(new AsyncCallback() { public void onCallback() { http.fetch(url, cb); }});
		latch.await(5, TimeUnit.SECONDS);
		assertEquals(0, latch.getCount());
	}
	
	@Test
	public void AsynchronousHttpClientRedirectTest() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);
		//final String url = "http://localhost:" + (PORT) + "/moved_perm";
		final String url = "http://localhost:" + PORT + "/moved_perm";
		final AsynchronousHttpClient http = new AsynchronousHttpClient();
		final AsyncResult<org.deftserver.web.http.client.Response> cb =
			new AsyncResult<org.deftserver.web.http.client.Response>() {

			public void onSuccess(org.deftserver.web.http.client.Response response) { 
				if (response.getBody().equals(expectedPayload)) {
					latch.countDown();
				}
			}
			
			public void onFailure(Throwable e) { }
		
		};
		// make sure that the http.fetch(..) is invoked from the ioloop thread
		IOLoop.INSTANCE.addCallback(new AsyncCallback() { public void onCallback() { http.fetch(url, cb); }});
		latch.await(5, TimeUnit.SECONDS);
		assertEquals(0, latch.getCount());
	}
	
	@Test
	public void asynchronousHttpClientTransferEncodingChunkedTest() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);
		final String url = "http://localhost:" + PORT + "/chunked";
		final AsynchronousHttpClient http = new AsynchronousHttpClient();
		final AsyncResult<org.deftserver.web.http.client.Response> cb =
			new AsyncResult<org.deftserver.web.http.client.Response>() {

			public void onSuccess(org.deftserver.web.http.client.Response response) { 
				if (response.getBody().equals("arogerab") && 
					response.getHeader("Transfer-Encoding").equals("chunked")) 
				{
					latch.countDown();
				}
			}
			
			public void onFailure(Throwable e) { }
		
		};
		// make sure that the http.fetch(..) is invoked from the ioloop thread
		IOLoop.INSTANCE.addCallback(new AsyncCallback() { public void onCallback() { http.fetch(url, cb); }});
		latch.await(5, TimeUnit.SECONDS);
		assertEquals(0, latch.getCount());		
	}
	
	private String convertStreamToString(InputStream is) throws IOException {
		if (is != null) {
			StringBuilder sb = new StringBuilder();
			String line;

			try {
				BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
				while ((line = reader.readLine()) != null) {
					sb.append(line).append("\n");
				}
			} finally {
				is.close();
			}
			return sb.toString();
		} else {       
			return "";
		}
	}

	private String sendRawRequest(String request) throws IOException {
		try (
			Socket socket = new Socket("localhost", PORT);
			java.io.OutputStream os = socket.getOutputStream();
			InputStream is = socket.getInputStream()
		) {
			socket.setSoTimeout(3000);
			os.write(request.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			os.flush();
			
			// read response in a loop until we get the whole response including body
			byte[] buffer = new byte[16384];
			int totalRead = 0;
			int read;
			while (totalRead < buffer.length) {
				read = is.read(buffer, totalRead, buffer.length - totalRead);
				if (read == -1) {
					break;
				}
				totalRead += read;
				
				// check if we have received the full body based on Content-Length
				String current = new String(buffer, 0, totalRead, java.nio.charset.StandardCharsets.ISO_8859_1);
				if (current.contains("\r\n\r\n")) {
					if (request.startsWith("HEAD ") || current.contains("HTTP/1.1 204") || current.contains("HTTP/1.1 304")) {
						break;
					}
					int bodyStart = current.indexOf("\r\n\r\n") + 4;
					int bodyLen = totalRead - bodyStart;
					if (current.contains("Content-Length: ")) {
						int clIdx = current.indexOf("Content-Length: ");
						int clEnd = current.indexOf("\r\n", clIdx);
						try {
							int contentLength = Integer.parseInt(current.substring(clIdx + 16, clEnd).trim());
							if (bodyLen >= contentLength) {
								break;
							}
						} catch (Exception e) {
							// ignore parsing failures
						}
					}
				}
			}
			
			if (totalRead > 0) {
				return new String(buffer, 0, totalRead, java.nio.charset.StandardCharsets.ISO_8859_1);
			}
			return "";
		}
	}

	@Test
	public void pathTraversalSecurityTest() throws IOException {
		// Null byte rejection
		String responseNull = sendRawRequest(
			"GET /src/test/resources/%00test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n\r\n"
		);
		assertTrue(responseNull.startsWith("HTTP/1.1 400"));
		assertTrue(responseNull.contains("Null bytes in path are forbidden"));

		// Relative path escaping root rejection
		String responseEscape = sendRawRequest(
			"GET /src/test/resources/../test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n\r\n"
		);
		assertTrue(responseEscape.startsWith("HTTP/1.1 403"));
		assertTrue(responseEscape.contains("Directory traversal attempt blocked"));

		// Parent directory reference escaping root
		String responseEscape2 = sendRawRequest(
			"GET /.. HTTP/1.1\r\n" +
			"Host: localhost\r\n\r\n"
		);
		assertTrue(responseEscape2.startsWith("HTTP/1.1 403"));
		assertTrue(responseEscape2.contains("Directory traversal attempt blocked"));
	}

	@Test
	public void expect100ContinueTest() throws IOException {
		try (
			Socket socket = new Socket("localhost", PORT);
			java.io.OutputStream os = socket.getOutputStream();
			InputStream is = socket.getInputStream()
		) {
			socket.setSoTimeout(3000);
			// 1. Send only headers expecting 100-continue
			String requestHeaders = 
				"POST /post HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Expect: 100-continue\r\n" +
				"Content-Length: 4\r\n\r\n";
			os.write(requestHeaders.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			os.flush();

			// 2. Read early response: it should be 100 Continue
			byte[] buffer = new byte[1024];
			int read = is.read(buffer);
			assertTrue(read > 0);
			String earlyResponse = new String(buffer, 0, read, java.nio.charset.StandardCharsets.ISO_8859_1);
			assertTrue(earlyResponse.contains("HTTP/1.1 100 Continue"));

			// 3. Send the body now
			os.write("body".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			os.flush();

			// 4. Read final response
			read = is.read(buffer);
			assertTrue(read > 0);
			String finalResponse = new String(buffer, 0, read, java.nio.charset.StandardCharsets.ISO_8859_1);
			assertTrue(finalResponse.contains("HTTP/1.1 200"));
		}
	}

	@Test
	public void unrecognizedExpectationReturns417Test() throws IOException {
		// RFC 9110 §10.1.1: an Expect value the server can't meet (anything other than
		// 100-continue) must not be silently ignored — that would leave a client withholding its
		// body waiting forever. The server replies 417 Expectation Failed.
		String response = sendRawRequest(
			"POST /post HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Expect: 102-processing\r\n" +
			"Content-Length: 4\r\n\r\n"
		);
		assertTrue("unrecognized Expect must yield 417, got: " + response,
			response.startsWith("HTTP/1.1 417"));
	}

	@Test
	public void http10ExpectHeaderIsIgnoredNot417Test() throws IOException {
		// An HTTP/1.0 client doesn't understand interim responses; its Expect must be ignored
		// entirely (no 100, and crucially no 417 either — that would break the request).
		String response = sendRawRequest(
			"POST /post HTTP/1.0\r\n" +
			"Host: localhost\r\n" +
			"Expect: 100-continue\r\n" +
			"Content-Length: 4\r\n\r\n" +
			"body"
		);
		assertTrue("HTTP/1.0 Expect must be ignored (no 417), got: " + response,
			response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.0 200"));
	}

	@Test
	public void staticFileIfMatchAndIfUnmodifiedSinceTest() throws IOException {
		// Fetch the static file's current strong ETag.
		String first = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
		assertTrue(first.startsWith("HTTP/1.1 200"));
		int etIdx = first.toLowerCase(java.util.Locale.ROOT).indexOf("etag: ");
		assertTrue("static response must carry an ETag", etIdx != -1);
		String etag = first.substring(etIdx + 6, first.indexOf("\r\n", etIdx)).trim();

		// If-Match with a non-matching tag → 412 Precondition Failed (RFC 9110 §13.1.1).
		String ifMatchFail = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\nHost: localhost\r\n" +
			"If-Match: \"nopenope\"\r\nConnection: close\r\n\r\n");
		assertTrue("failing If-Match must be 412, got: " + ifMatchFail.substring(0, Math.min(40, ifMatchFail.length())),
			ifMatchFail.startsWith("HTTP/1.1 412"));

		// If-Match with the current tag → 200 OK (precondition passes).
		String ifMatchOk = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\nHost: localhost\r\n" +
			"If-Match: " + etag + "\r\nConnection: close\r\n\r\n");
		assertTrue("matching If-Match must be 200, got: " + ifMatchOk.substring(0, Math.min(40, ifMatchOk.length())),
			ifMatchOk.startsWith("HTTP/1.1 200"));

		// If-Unmodified-Since in the distant past → the file was modified after it → 412.
		String ius = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\nHost: localhost\r\n" +
			"If-Unmodified-Since: Mon, 01 Jan 1990 00:00:00 GMT\r\nConnection: close\r\n\r\n");
		assertTrue("If-Unmodified-Since in the past must be 412, got: " + ius.substring(0, Math.min(40, ius.length())),
			ius.startsWith("HTTP/1.1 412"));
	}

	@Test
	public void conditionalPrecedenceIfMatchBeatsIfNoneMatchTest() throws IOException {
		// RFC 9110 §13.2.2 mandates If-Match be evaluated BEFORE If-None-Match. First fetch the
		// resource's current ETag, then send If-Match with a WRONG tag + If-None-Match with the
		// CURRENT tag. If-Match fails (current != wrong) so the correct result is 412 — NOT the 304
		// that an If-None-Match-first evaluation would (incorrectly) produce.
		String first = sendRawRequest("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
		assertTrue(first.startsWith("HTTP/1.1 200"));
		int etIdx = first.indexOf("ETag: ");
		assertTrue("response must carry an ETag", etIdx != -1);
		String etag = first.substring(etIdx + 6, first.indexOf("\r\n", etIdx)).trim();

		String resp = sendRawRequest(
			"GET / HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"If-Match: \"deadbeefwrongtag\"\r\n" +
			"If-None-Match: " + etag + "\r\n" +
			"Connection: close\r\n\r\n");
		assertTrue("If-Match (failing) must take precedence over If-None-Match -> 412, got: "
			+ (resp.length() > 40 ? resp.substring(0, 40) : resp),
			resp.startsWith("HTTP/1.1 412"));
	}

	@Test
	public void multiRangeRequestServedAsFull200Test() throws IOException {
		// §22: a multi-range request (comma-separated ranges → multipart/byteranges) is OPTIONAL.
		// This server does not implement multipart/byteranges, so per RFC 9110 §14.2 it ignores the
		// Range and serves the full representation (200) rather than crashing or sending a malformed
		// partial response. (test.txt is the 8-byte "test.txt".)
		String response = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Range: bytes=0-1,3-4\r\n\r\n"
		);
		assertTrue("multi-range must be served as full 200, got: "
			+ response.substring(0, Math.min(40, response.length())), response.startsWith("HTTP/1.1 200"));
		assertFalse("multi-range must not produce a 206 partial", response.startsWith("HTTP/1.1 206"));
		assertTrue("full body must be present", response.trim().endsWith("test.txt"));
	}

	@Test
	public void rangeRequestTest() throws IOException {
		// Valid range 0-3
		String resp1 = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Range: bytes=0-3\r\n\r\n"
		);
		assertTrue(resp1.startsWith("HTTP/1.1 206"));
		assertTrue(resp1.contains("Content-Range: bytes 0-3/8"));
		assertTrue(resp1.contains("Content-Length: 4"));
		assertTrue(resp1.trim().endsWith("test"));

		// Range suffix (last 3 bytes)
		String resp2 = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Range: bytes=-3\r\n\r\n"
		);
		assertTrue(resp2.startsWith("HTTP/1.1 206"));
		assertTrue(resp2.contains("Content-Range: bytes 5-7/8"));
		assertTrue(resp2.contains("Content-Length: 3"));
		assertTrue(resp2.trim().endsWith("txt"));

		// Range offset from 4 to end
		String resp3 = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Range: bytes=4-\r\n\r\n"
		);
		assertTrue(resp3.startsWith("HTTP/1.1 206"));
		assertTrue(resp3.contains("Content-Range: bytes 4-7/8"));
		assertTrue(resp3.contains("Content-Length: 4"));
		assertTrue(resp3.trim().endsWith(".txt"));

		// Non-satisfiable range
		String resp4 = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Range: bytes=10-20\r\n\r\n"
		);
		assertTrue(resp4.startsWith("HTTP/1.1 416"));
		assertTrue(resp4.contains("Content-Range: bytes */8"));

		// RFC 9110 §14.1.2: a suffix range of zero length (bytes=-0) is unsatisfiable -> 416.
		String resp5 = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Range: bytes=-0\r\n\r\n"
		);
		assertTrue("zero-length suffix range must be 416, got: "
			+ resp5.substring(0, Math.min(40, resp5.length())), resp5.startsWith("HTTP/1.1 416"));
		assertTrue(resp5.contains("Content-Range: bytes */8"));
	}

	@Test
	public void conditionalPrecedenceTest() throws IOException {
		// RFC 9110 §13.1.3: when If-None-Match is present (even non-matching), If-Modified-Since
		// must be ignored. A far-future If-Modified-Since alone would yield 304, but a present,
		// non-matching If-None-Match must force a 200.
		String response = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"If-None-Match: \"does-not-match\"\r\n" +
			"If-Modified-Since: Wed, 01 Jan 2098 00:00:00 GMT\r\n\r\n");
		assertTrue("INM present (non-matching) must override IMS → 200, got: " +
			response.substring(0, Math.min(30, response.length())),
			response.startsWith("HTTP/1.1 200"));
	}

	@Test
	public void ifRangeTest() throws IOException {
		// Fetch the ETag first.
		String full = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\nHost: localhost\r\n\r\n");
		int idx = full.indexOf("ETag: ");
		assertTrue(idx != -1);
		String etag = full.substring(idx + 6, full.indexOf("\r\n", idx)).trim();

		// If-Range matches → the Range is honoured (206).
		String matched = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\nHost: localhost\r\n" +
			"If-Range: " + etag + "\r\nRange: bytes=0-3\r\n\r\n");
		assertTrue("matching If-Range should give 206, got: " + matched.substring(0, Math.min(30, matched.length())),
			matched.startsWith("HTTP/1.1 206"));

		// If-Range does NOT match → the Range is ignored, full file served (200).
		String unmatched = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\nHost: localhost\r\n" +
			"If-Range: \"stale-etag\"\r\nRange: bytes=0-3\r\n\r\n");
		assertTrue("non-matching If-Range should give 200, got: " + unmatched.substring(0, Math.min(30, unmatched.length())),
			unmatched.startsWith("HTTP/1.1 200"));
	}

	@Test
	public void automaticHeadRequestTest() throws IOException {
		String response = sendRawRequest(
			"HEAD / HTTP/1.1\r\n" +
			"Host: localhost\r\n\r\n"
		);
		assertTrue(response.startsWith("HTTP/1.1 200"));
		assertTrue(response.contains("Content-Length: 10")); // "hello test" length is 10
		
		// The response should end with headers separator \r\n\r\n and contain NO body bytes
		assertTrue(response.endsWith("\r\n\r\n"));
	}

	@Test
	public void corsPreflightOptionsTest() throws IOException {
		String response = sendRawRequest(
			"OPTIONS /any/route HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Origin: http://example.com\r\n" +
			"Access-Control-Request-Method: PUT\r\n\r\n"
		);
		assertTrue(response.startsWith("HTTP/1.1 204"));
		assertTrue(response.contains("Access-Control-Allow-Origin: http://example.com"));
		assertTrue(response.contains("Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS"));
		assertTrue(response.contains("Access-Control-Allow-Headers: *"));
		assertTrue(response.contains("Access-Control-Max-Age: 86400"));
	}

	@Test
	public void threadSafeDateValidationTest() throws IOException {
		String response = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n\r\n"
		);
		assertTrue(response.startsWith("HTTP/1.1 200"));
		
		// Parse Last-Modified
		int lmIdx = response.indexOf("Last-Modified: ");
		assertTrue(lmIdx != -1);
		int lmEnd = response.indexOf("\r\n", lmIdx);
		String lastModifiedStr = response.substring(lmIdx + 15, lmEnd);
		
		// Try parsing Last-Modified header
		long parsed = org.deftserver.util.DateUtil.parseRFC1123ToMillis(lastModifiedStr);
		assertTrue(parsed > 0);
		
		// Send If-Modified-Since
		String resp304 = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"If-Modified-Since: " + lastModifiedStr + "\r\n\r\n"
		);
		assertTrue(resp304.startsWith("HTTP/1.1 304"));
	}

	@Test
	public void staticFileEtagCachingTest() throws IOException {
		String response = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n\r\n"
		);
		assertTrue(response.startsWith("HTTP/1.1 200"));
		
		// Parse ETag
		int etagIdx = response.indexOf("ETag: ");
		assertTrue(etagIdx != -1);
		int etagEnd = response.indexOf("\r\n", etagIdx);
		String etagStr = response.substring(etagIdx + 6, etagEnd).trim();
		assertTrue(etagStr.startsWith("\"") && etagStr.endsWith("\""));
		
		// Send If-None-Match with matching ETag
		String resp304 = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"If-None-Match: " + etagStr + "\r\n\r\n"
		);
		assertTrue(resp304.startsWith("HTTP/1.1 304"));
		
		// Send If-None-Match with weak matching ETag
		String resp304Weak = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"If-None-Match: W/" + etagStr + "\r\n\r\n"
		);
		assertTrue(resp304Weak.startsWith("HTTP/1.1 304"));

		// Send If-None-Match with non-matching ETag (should get 200 OK)
		String resp200 = sendRawRequest(
			"GET /src/test/resources/test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"If-None-Match: \"non-matching-etag\"\r\n\r\n"
		);
		assertTrue(resp200.startsWith("HTTP/1.1 200"));
	}

	@Test
	public void httpVersionValidationTest() throws IOException {
		String response = sendRawRequest(
			"GET / HTTP/2.0\r\n" +
			"Host: localhost\r\n\r\n"
		);
		System.out.println("DEBUG VERSION RESP: [" + response + "]");
		assertTrue(response.startsWith("HTTP/1.1 505"));
		assertTrue(response.contains("HTTP Version Not Supported"));
	}

	@Test
	public void largeHeaderBlockExceedingReadBufferTest() throws IOException {
		// Header block far larger than READ_BUFFER_SIZE (2048): must grow the read buffer and
		// succeed, not crash with IllegalStateException.
		StringBuilder big = new StringBuilder("a".repeat(8000));
		String response = sendRawRequest(
			"GET / HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"X-Big: " + big + "\r\n" +
			"Connection: close\r\n\r\n"
		);
		assertTrue("expected 200 for large-but-legal headers, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 200"));
	}

	@Test
	public void oversizedContentLengthReturns413Test() throws IOException {
		// A Content-Length above the 16 MiB body cap must be 413 Payload Too Large, not 400.
		long tooBig = (16L * 1024 * 1024) + 1;
		String response = sendRawRequest(
			"POST /post HTTP/1.1\r\nHost: localhost\r\n" +
			"Content-Length: " + tooBig + "\r\nConnection: close\r\n\r\n");
		assertTrue("expected 413, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 413"));
	}

	@Test
	public void uriTooLongReturns414Test() throws IOException {
		// A request-target longer than the request-line limit must be 414 (URI Too Long), not 400.
		String longPath = "/" + "a".repeat(9000); // > MAX_REQUEST_LINE_SIZE (8192)
		String response = sendRawRequest(
			"GET " + longPath + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
		assertTrue("expected 414, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 414"));
	}

	@Test
	public void tooManyHeadersReturns431Test() throws IOException {
		// Exceeding the header-count limit is 431 (Request Header Fields Too Large), not 400.
		StringBuilder sb = new StringBuilder("GET / HTTP/1.1\r\nHost: localhost\r\n");
		for (int i = 0; i < 150; i++) {
			sb.append("X-H").append(i).append(": v\r\n");
		}
		sb.append("Connection: close\r\n\r\n");
		String response = sendRawRequest(sb.toString());
		assertTrue("expected 431, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 431"));
	}

	@Test
	public void oversizedHeaderBlockReturns431Test() throws IOException {
		// Header block exceeding the 64 KiB cap must be rejected with 431, not a crash/hang.
		String big = "a".repeat(70000);
		String response = sendRawRequest(
			"GET / HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"X-Big: " + big + "\r\n" +
			"Connection: close\r\n\r\n"
		);
		assertTrue("expected 431, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 431"));
	}

	@Test
	public void headStaticFileReportsContentLengthTest() throws IOException {
		// HEAD on a static file must report the file size in Content-Length (not 0) and no body.
		java.io.File f = new java.io.File("src/test/resources/test.txt");
		long size = f.length();
		String response = sendRawRequest(
			"HEAD /src/test/resources/test.txt HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Connection: close\r\n\r\n"
		);
		assertTrue(response.startsWith("HTTP/1.1 200"));
		assertTrue("expected Content-Length: " + size + " in:\n" + response,
			response.contains("Content-Length: " + size + "\r\n"));
		// No body after the header terminator.
		int bodyStart = response.indexOf("\r\n\r\n") + 4;
		assertEquals("HEAD response must have no body", bodyStart, response.length());
	}

	@Test
	public void writeByteBufferBodyTest() throws IOException {
		// Exercises HttpResponse.write(ByteBuffer): the body must round-trip with a correct
		// Content-Length (the rewrite buffers through flush() so partial writes aren't lost).
		String response = sendRawRequest(
			"GET /writebb HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
		assertTrue(response.startsWith("HTTP/1.1 200"));
		assertTrue(response.contains("Content-Length: 14\r\n"));
		assertTrue(response.endsWith("bytebufferbody"));
	}

	@Test
	public void pipelinedRequestsCloseConnectionTest() throws IOException {
		try (Socket socket = new Socket("localhost", PORT)) {
			socket.setSoTimeout(3000);
			String two = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n" +
			             "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
			socket.getOutputStream().write(two.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			InputStream is = socket.getInputStream();
			while ((n = is.read(buf)) != -1) {
				out.write(buf, 0, n);
			}
			String resp = out.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
			assertTrue(resp.startsWith("HTTP/1.1 200"));
			assertTrue(resp.contains("Connection: close") || resp.contains("Connection: Close"));
			int firstHttp = resp.indexOf("HTTP/1.1");
			int secondHttp = resp.indexOf("HTTP/1.1", firstHttp + 8);
			assertTrue(secondHttp > 0);
			int thirdHttp = resp.indexOf("HTTP/1.1", secondHttp + 8);
			assertEquals(-1, thirdHttp);
		}
	}

	/**
	 * Reads exactly one HTTP response (status line + headers + Content-Length body) from a
	 * persistent connection's input stream, leaving the stream positioned at the start of the
	 * next response. Used by {@link #serialKeepAliveReuseTest} to verify per-request framing on
	 * a reused keep-alive connection.
	 */
	private static String readOneResponse(InputStream is) throws IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		// Read until end of header block (CRLFCRLF).
		int headerEnd = -1;
		while (headerEnd == -1) {
			int b = is.read();
			if (b == -1) throw new java.io.EOFException("stream closed before header end");
			out.write(b);
			byte[] cur = out.toByteArray();
			if (cur.length >= 4 && cur[cur.length - 4] == '\r' && cur[cur.length - 3] == '\n'
					&& cur[cur.length - 2] == '\r' && cur[cur.length - 1] == '\n') {
				headerEnd = cur.length;
			}
		}
		String head = out.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
		int cl = 0;
		int idx = head.toLowerCase(java.util.Locale.ROOT).indexOf("content-length:");
		if (idx != -1) {
			int eol = head.indexOf("\r\n", idx);
			cl = Integer.parseInt(head.substring(idx + 15, eol).trim());
		}
		// Read exactly Content-Length body bytes.
		for (int i = 0; i < cl; i++) {
			int b = is.read();
			if (b == -1) throw new java.io.EOFException("stream closed mid-body");
			out.write(b);
		}
		return out.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
	}

	@Test
	public void serialKeepAliveReuseTest() throws IOException {
		// Hammer a single keep-alive connection with many serial requests of varying shape and
		// body size, reading each response fully before sending the next. This exercises the
		// per-request buffer reset (getHttpRequest compact + fresh of()) and proves the body
		// offset is recomputed correctly every time — a reused connection must never bleed one
		// request's bytes into another's body, nor mis-frame a response.
		try (Socket socket = new Socket("localhost", PORT)) {
			socket.setSoTimeout(5000);
			java.io.OutputStream os = socket.getOutputStream();
			InputStream is = socket.getInputStream();
			for (int i = 0; i < 50; i++) {
				if (i % 2 == 0) {
					// Variable-size POST /echo — body grows so a stale offset/length would misframe.
					StringBuilder body = new StringBuilder();
					// ASCII body so the request-decode (ISO-8859-1) / response-encode (UTF-8) round
					// trip is byte-identical; this test targets framing/buffer-reset, not charset.
					for (int j = 0; j <= i; j++) body.append("ab").append(j);
					byte[] bodyBytes = body.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
					String req = "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
						+ bodyBytes.length + "\r\n\r\n";
					os.write(req.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
					os.write(bodyBytes);
					os.flush();
					String resp = readOneResponse(is);
					assertTrue("iter " + i + " expected 200, got:\n" + resp, resp.startsWith("HTTP/1.1 200"));
					int bs = resp.indexOf("\r\n\r\n") + 4;
					String echoed = resp.substring(bs);
					assertEquals("iter " + i + " echoed body must equal request body",
						body.toString(), echoed);
				} else {
					// Simple GET / between POSTs to vary request shape on the reused connection.
					String req = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
					os.write(req.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
					os.flush();
					String resp = readOneResponse(is);
					assertTrue("iter " + i + " GET expected 200, got:\n"
						+ resp.substring(0, Math.min(40, resp.length())), resp.startsWith("HTTP/1.1 200"));
				}
			}
		}
	}

	@Test
	public void largeFileChunkedStreamingTest() throws Exception {
		// Force the multi-window chunked path with a tiny window and verify the served bytes match
		// the file exactly (this exercises the >2 GiB-class code path with a small fixture).
		int original = org.deftserver.web.http.HttpProtocol.FILE_WINDOW_SIZE;
		org.deftserver.web.http.HttpProtocol.FILE_WINDOW_SIZE = 4096;
		try {
			java.io.File f = new java.io.File("src/test/resources/n792205362_2067.jpg");
			byte[] expected = java.nio.file.Files.readAllBytes(f.toPath());
			org.junit.Assert.assertTrue("fixture must exceed the window", expected.length > 4096);
			try (Socket socket = new Socket("localhost", PORT)) {
				socket.setSoTimeout(5000);
				socket.getOutputStream().write(
					"GET /src/test/resources/n792205362_2067.jpg HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
						.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
				socket.getOutputStream().flush();
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
				byte[] buf = new byte[8192];
				int n;
				while ((n = socket.getInputStream().read(buf)) != -1) {
					out.write(buf, 0, n);
				}
				byte[] resp = out.toByteArray();
				byte[] sep = "\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
				int bodyStart = -1;
				for (int i = 0; i + 4 <= resp.length; i++) {
					if (resp[i] == sep[0] && resp[i + 1] == sep[1] && resp[i + 2] == sep[2] && resp[i + 3] == sep[3]) {
						bodyStart = i + 4;
						break;
					}
				}
				org.junit.Assert.assertTrue("response header terminator found", bodyStart != -1);
				byte[] body = java.util.Arrays.copyOfRange(resp, bodyStart, resp.length);
				org.junit.Assert.assertArrayEquals("chunked-streamed body must equal the file", expected, body);
			}
		} finally {
			org.deftserver.web.http.HttpProtocol.FILE_WINDOW_SIZE = original;
		}
	}

	@Test
	public void serverWideOptionsAsteriskTest() throws IOException {
		// "OPTIONS * HTTP/1.1" (asterisk-form) must be handled (204 + Allow), not 400/crash.
		String response = sendRawRequest(
			"OPTIONS * HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Connection: close\r\n\r\n"
		);
		assertTrue("expected 204 for OPTIONS *, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 204"));
		assertTrue("expected Allow header:\n" + response, response.contains("Allow: "));
	}

	@Test
	public void notFoundEscapesReflectedPathTest() throws IOException {
		// Reflected-XSS defence: an attacker-controlled path reflected into the 404 body must be
		// HTML-escaped, and the response must declare a content type + nosniff so it can't be
		// MIME-sniffed and executed as HTML.
		String response = sendRawRequest(
			"GET /%3Cscript%3Ealert(1)%3C/script%3E HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
		assertTrue("expected 404, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 404"));
		assertFalse("404 body must not reflect a raw <script> tag:\n" + response, response.contains("<script>"));
		assertTrue("path should be HTML-escaped:\n" + response, response.contains("&lt;script&gt;"));
		assertTrue("nosniff header expected:\n" + response, response.contains("X-Content-Type-Options: nosniff"));
	}

	@Test
	public void postToUnknownPathReturns404Test() throws IOException {
		// A POST (or any method) to a non-existent resource must be 404, not 501.
		String response = sendRawRequest(
			"POST /no/such/path HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Content-Length: 3\r\n" +
			"Connection: close\r\n\r\n" +
			"abc"
		);
		assertTrue("expected 404 for POST to unknown path, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 404"));
	}

	@Test
	public void obsoleteHeaderFoldingRejectionTest() throws IOException {
		// A continuation line starting with SP (obs-fold) must be rejected (RFC 7230 §3.2.4).
		String response = sendRawRequest(
			"GET / HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"X-Foo: bar\r\n" +
			" baz\r\n\r\n"
		);
		assertTrue("expected 400 for obs-fold, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 400"));
	}

	@Test
	public void controlCharInHeaderValueRejectionTest() throws IOException {
		// A bare CR embedded in a header value must be rejected (header-injection defence).
		String response = sendRawRequest(
			"GET / HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"X-Foo: a\rb\r\n\r\n"
		);
		assertTrue("expected 400 for control char in value, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 400"));
	}

	@Test
	public void transferEncodingChunkedNotFinalRejectedTest() throws IOException {
		// RFC 9112 §6.3: chunked must be the FINAL coding; otherwise the length is ambiguous
		// (request-smuggling hazard) and the request must be rejected.
		String notFinal = sendRawRequest(
			"POST /post HTTP/1.1\r\nHost: localhost\r\n" +
			"Transfer-Encoding: chunked, gzip\r\nConnection: close\r\n\r\n");
		assertTrue("chunked-not-final must be 400, got: " + notFinal.substring(0, Math.min(40, notFinal.length())),
			notFinal.startsWith("HTTP/1.1 400"));

		// Transfer-Encoding present but no chunked at all → length undeterminable → 400.
		String noChunked = sendRawRequest(
			"POST /post HTTP/1.1\r\nHost: localhost\r\n" +
			"Transfer-Encoding: gzip\r\nConnection: close\r\n\r\n");
		assertTrue("non-chunked TE must be 400, got: " + noChunked.substring(0, Math.min(40, noChunked.length())),
			noChunked.startsWith("HTTP/1.1 400"));
	}

	@Test
	public void knownMethodUnimplementedReturns405WithAllowTest() throws IOException {
		// "/" (ExampleRequestHandler) implements only GET. A POST is a *known* method the resource
		// doesn't support → 405 Method Not Allowed with an Allow header (RFC 9110 §15.5.6), not 501.
		String response = sendRawRequest(
			"POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
		assertTrue("expected 405, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 405"));
		assertTrue("405 must carry Allow listing GET:\n" + response,
			response.contains("Allow: ") && response.contains("GET"));
	}

	@Test
	public void optionsOnResourceSynthesizes204WithAllowTest() throws IOException {
		// An OPTIONS to a resource that doesn't override options() is answered with 204 + Allow
		// describing the supported methods (RFC 9110 §9.3.7), rather than 501.
		String response = sendRawRequest(
			"OPTIONS / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
		assertTrue("expected 204, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 204"));
		assertTrue("OPTIONS must carry Allow listing GET, HEAD, OPTIONS:\n" + response,
			response.contains("Allow: ") && response.contains("GET") && response.contains("HEAD")
			&& response.contains("OPTIONS"));
	}

	@Test
	public void unknownMethodStillReturns501Test() throws IOException {
		// A genuinely unrecognised method (not in HttpVerb) remains 501 Not Implemented — 405 is
		// only for *known* methods unsupported by the resource.
		String response = sendRawRequest(
			"FROBNICATE / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
		assertTrue("expected 501, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 501"));
	}

	@Test
	public void ifUnmodifiedSinceIgnoredWhenIfMatchPresentTest() throws IOException {
		// RFC 9110 §13.2.2: If-Unmodified-Since MUST be ignored when If-Match is present. The
		// resource's Last-Modified (2025) is after the stale If-Unmodified-Since (2020), so a naive
		// evaluation would 412 — but If-Match: * satisfies the precondition, so we must get 200.
		String withIfMatch = sendRawRequest(
			"GET /lastmod HTTP/1.1\r\nHost: localhost\r\n" +
			"If-Match: *\r\n" +
			"If-Unmodified-Since: Wed, 21 Oct 2020 07:28:00 GMT\r\nConnection: close\r\n\r\n");
		assertTrue("If-Match present must ignore stale If-Unmodified-Since (expect 200), got: "
			+ withIfMatch.substring(0, Math.min(40, withIfMatch.length())),
			withIfMatch.startsWith("HTTP/1.1 200"));

		// Sanity: without If-Match, the stale If-Unmodified-Since IS evaluated → 412.
		String withoutIfMatch = sendRawRequest(
			"GET /lastmod HTTP/1.1\r\nHost: localhost\r\n" +
			"If-Unmodified-Since: Wed, 21 Oct 2020 07:28:00 GMT\r\nConnection: close\r\n\r\n");
		assertTrue("stale If-Unmodified-Since alone must be 412, got: "
			+ withoutIfMatch.substring(0, Math.min(40, withoutIfMatch.length())),
			withoutIfMatch.startsWith("HTTP/1.1 412"));
	}

	private static String writeAndReadOneKeepAliveResponse(java.io.OutputStream os, InputStream is, String request) throws IOException {
		os.write(request.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
		os.flush();
		StringBuilder sb = new StringBuilder();
		int headerEnd = -1;
		// read until end of headers
		while (headerEnd == -1) {
			int b = is.read();
			if (b == -1) break;
			sb.append((char) b);
			if (sb.length() >= 4 && sb.substring(sb.length() - 4).equals("\r\n\r\n")) {
				headerEnd = sb.length();
			}
		}
		// parse Content-Length and consume the body so the stream is positioned at the next response
		String headers = sb.toString();
		int clIdx = headers.toLowerCase(java.util.Locale.ROOT).indexOf("content-length:");
		if (clIdx != -1) {
			int eol = headers.indexOf("\r\n", clIdx);
			int cl = Integer.parseInt(headers.substring(clIdx + 15, eol).trim());
			for (int i = 0; i < cl; i++) {
				int b = is.read();
				if (b == -1) break;
				sb.append((char) b);
			}
		}
		return sb.toString();
	}

	@Test
	public void maxKeepAliveRequestsForcesCloseTest() throws IOException {
		// A keep-alive connection must be gracefully closed once it has served MAX_KEEP_ALIVE_REQUESTS
		// requests (bounds per-connection resource use / monopolisation).
		int original = org.deftserver.web.http.HttpProtocol.MAX_KEEP_ALIVE_REQUESTS;
		org.deftserver.web.http.HttpProtocol.MAX_KEEP_ALIVE_REQUESTS = 2;
		try (Socket socket = new Socket("localhost", PORT)) {
			socket.setSoTimeout(5000);
			java.io.OutputStream os = socket.getOutputStream();
			InputStream is = socket.getInputStream();
			String r1 = writeAndReadOneKeepAliveResponse(os, is,
				"GET /lastmod HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertTrue("request 1 should keep the connection alive:\n" + r1,
				r1.toLowerCase(java.util.Locale.ROOT).contains("connection: keep-alive"));
			String r2 = writeAndReadOneKeepAliveResponse(os, is,
				"GET /lastmod HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertTrue("request 2 (at the cap) must signal close:\n" + r2,
				r2.toLowerCase(java.util.Locale.ROOT).contains("connection: close"));
		} finally {
			org.deftserver.web.http.HttpProtocol.MAX_KEEP_ALIVE_REQUESTS = original;
		}
	}

	@Test
	public void deferredConnectionCloseResponseClosesConnectionTest() throws IOException {
		// A keep-alive HTTP/1.1 *request* arms the keep-alive timeout, but the *response* declares
		// Connection: close with a body large enough to defer the write to OP_WRITE. Once that
		// deferred write completes the server must CLOSE the connection (honouring its own header),
		// not re-register for read (which — with the keep-alive timeout present — would leave the
		// client hanging). We detect the close as EOF after the full body (P41).
		try (Socket socket = new Socket("localhost", PORT)) {
			socket.setSoTimeout(8000);
			socket.getOutputStream().write(
				"GET /largeclose HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(
					java.nio.charset.StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
			InputStream is = socket.getInputStream();
			byte[] buf = new byte[65536];
			long total = 0;
			int n;
			boolean eof = false;
			// Read to EOF. If the server wrongly kept the connection open, this would block past the
			// body and the 8s SO-timeout would fire (failing the test) instead of returning -1.
			while ((n = is.read(buf)) != -1) {
				total += n;
			}
			eof = true;
			assertTrue("connection must reach EOF (server closed it)", eof);
			// headers + ~1,000,000-byte body
			assertTrue("expected the full large body, got only " + total + " bytes", total > 1_000_000);
		}
	}

	@Test
	public void hungAsyncHandlerIsBoundedByProcessingTimeoutTest() throws IOException {
		long original = org.deftserver.web.http.HttpProtocol.REQUEST_PROCESSING_TIMEOUT_MS;
		org.deftserver.web.http.HttpProtocol.REQUEST_PROCESSING_TIMEOUT_MS = 400;
		try {
			// Connection: close — no keep-alive timeout, so the standalone processing timeout must
			// bound the hung async handler (and send a best-effort 408) rather than leaking forever.
			try (Socket socket = new Socket("localhost", PORT)) {
				socket.setSoTimeout(5000);
				socket.getOutputStream().write(
					"GET /async_hang HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(
						java.nio.charset.StandardCharsets.ISO_8859_1));
				socket.getOutputStream().flush();
				InputStream is = socket.getInputStream();
				byte[] buf = new byte[256];
				int n = is.read(buf); // blocks until the processing timeout fires (~400ms), not forever
				String resp = n > 0 ? new String(buf, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1) : "";
				assertTrue("hung async (close) must be timed out with 408, got: " + resp,
					resp.startsWith("HTTP/1.1 408"));
			}
			// Keep-alive — the (extended) keep-alive timer bounds it; the connection must reach EOF
			// rather than hang.
			try (Socket socket = new Socket("localhost", PORT)) {
				socket.setSoTimeout(5000);
				socket.getOutputStream().write(
					"GET /async_hang HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(
						java.nio.charset.StandardCharsets.ISO_8859_1));
				socket.getOutputStream().flush();
				InputStream is = socket.getInputStream();
				// Drain to EOF — the server must close the hung connection, not block past SO timeout.
				byte[] buf = new byte[256];
				while (is.read(buf) != -1) { /* until close */ }
			}
		} finally {
			org.deftserver.web.http.HttpProtocol.REQUEST_PROCESSING_TIMEOUT_MS = original;
		}
	}

	@Test
	public void preEncodedResponseNotDoubleGzippedTest() throws IOException {
		// A handler that already set Content-Encoding must not be re-gzipped by the framework.
		String response = sendRawRequest(
			"GET /preencoded HTTP/1.1\r\nHost: localhost\r\n" +
			"Accept-Encoding: gzip\r\nConnection: close\r\n\r\n");
		assertTrue("expected 200, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 200"));
		// Exactly one Content-Encoding: gzip (the handler's), and the body sent identity (not chunked
		// by the framework's gzip path).
		int firstCE = response.indexOf("Content-Encoding: gzip");
		assertTrue("handler's Content-Encoding must be present", firstCE != -1);
		assertEquals("must not have a second Content-Encoding (no double-encode)",
			-1, response.indexOf("Content-Encoding: gzip", firstCE + 1));
		assertFalse("framework must not chunk-frame a pre-encoded body:\n" + response,
			response.contains("Transfer-Encoding: chunked"));
		assertTrue("identity body must carry the handler's payload",
			response.contains("already-encoded-bytes"));
	}

	@Test
	public void partialContentNotGzippedTest() throws IOException {
		// Even when the client offers gzip and the body is text, a 206 Partial Content response must
		// be sent identity — gzipping it would invalidate the byte range / Content-Range header.
		String response = sendRawRequest(
			"GET /partial206 HTTP/1.1\r\nHost: localhost\r\n" +
			"Accept-Encoding: gzip\r\nConnection: close\r\n\r\n");
		assertTrue("expected 206, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 206"));
		assertFalse("206 must not be gzipped:\n" + response,
			response.contains("Content-Encoding: gzip"));
		assertFalse("206 must not be chunked:\n" + response,
			response.contains("Transfer-Encoding: chunked"));
	}

	@Test
	public void tooManyHeadersInMultipartPartReturns431Test() throws IOException {
		// A single multipart part with an excessive header section must be rejected (431) rather
		// than amplified into a vast per-part header map (OOM/DoS).
		String boundary = "BND";
		StringBuilder part = new StringBuilder("--" + boundary + "\r\n");
		for (int i = 0; i < 150; i++) {
			part.append("X-H-").append(i).append(": a\r\n");
		}
		part.append("Content-Disposition: form-data; name=\"f\"\r\n\r\ndata\r\n");
		part.append("--").append(boundary).append("--\r\n");
		String body = part.toString();
		String response = sendRawRequest(
			"POST /post HTTP/1.1\r\nHost: localhost\r\n" +
			"Content-Type: multipart/form-data; boundary=" + boundary + "\r\n" +
			"Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body);
		assertTrue("expected 431, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 431"));
	}

	@Test
	public void tooManyChunkTrailersReturns431Test() throws IOException {
		// A chunked body with an excessive number of trailer fields must be rejected (431) rather
		// than amplified into a huge trailer map (OOM/DoS), mirroring the header-count limit.
		StringBuilder trailers = new StringBuilder();
		for (int i = 0; i < 150; i++) {
			trailers.append("X-T-").append(i).append(": a\r\n");
		}
		String response = sendRawRequest(
			"POST /post HTTP/1.1\r\nHost: localhost\r\n" +
			"Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n" +
			"0\r\n" + trailers + "\r\n");
		assertTrue("expected 431, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 431"));
	}

	@Test
	public void tooManyFormParametersReturns413Test() throws IOException {
		// A form body with an excessive number of parameters must be rejected (413) rather than
		// amplified into millions of map entries (OOM/DoS). The parse runs eagerly for every form POST.
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < 10050; i++) {
			if (i > 0) body.append('&');
			body.append("a=1");
		}
		String response = sendRawRequest(
			"POST /post HTTP/1.1\r\nHost: localhost\r\n" +
			"Content-Type: application/x-www-form-urlencoded\r\n" +
			"Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body);
		assertTrue("expected 413, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 413"));
	}

	@Test
	public void leadingEmptyLineBeforeRequestLineIsIgnoredTest() throws IOException {
		// RFC 9112 §2.2: a server SHOULD ignore an empty line received before the request-line.
		String response = sendRawRequest(
			"\r\nGET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
		assertTrue("leading CRLF must be ignored (expect 200), got: "
			+ response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 200"));
	}

	@Test
	public void http11WithoutHostReturns400Test() throws IOException {
		// RFC 9112 §3.2: a server MUST reject (400) any HTTP/1.1 request that lacks a Host header.
		String response = sendRawRequest(
			"GET / HTTP/1.1\r\nConnection: close\r\n\r\n");
		assertTrue("expected 400, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 400"));
	}

	@Test
	public void http10WithoutHostIsAllowedTest() throws IOException {
		// HTTP/1.0 has no Host requirement, so a 1.0 request without Host must still be served.
		String response = sendRawRequest(
			"GET / HTTP/1.0\r\nConnection: close\r\n\r\n");
		assertTrue("expected 200, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.0 200") || response.startsWith("HTTP/1.1 200"));
	}

	@Test
	public void slowHeaderClientReceives408Test() throws IOException {
		// A client that opens a connection and dribbles an incomplete header section must be timed
		// out with a best-effort 408 Request Timeout (RFC 9110 §15.5.9), not silently dropped.
		long original = org.deftserver.web.http.HttpProtocol.HEADER_READ_TIMEOUT_MS;
		org.deftserver.web.http.HttpProtocol.HEADER_READ_TIMEOUT_MS = 300;
		try (Socket socket = new Socket("localhost", PORT)) {
			socket.setSoTimeout(4000);
			// Send a partial request header that never terminates (no final CRLF CRLF).
			socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n".getBytes(
				java.nio.charset.StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
			InputStream is = socket.getInputStream();
			byte[] buf = new byte[256];
			int n = is.read(buf); // blocks until the server writes the 408 then closes
			String response = n > 0 ? new String(buf, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1) : "";
			assertTrue("expected 408, got: " + response, response.startsWith("HTTP/1.1 408"));
			assertTrue("408 must close the connection", response.contains("Connection: close"));
		} finally {
			org.deftserver.web.http.HttpProtocol.HEADER_READ_TIMEOUT_MS = original;
		}
	}

	@Test
	public void unsupportedTransferCodingReturns501Test() throws IOException {
		// "gzip, chunked": chunked IS final so framing is unambiguous, but we cannot decode the
		// gzip transfer-coding layer. RFC 9112 §6.1 → 501 Not Implemented (rather than silently
		// handing the handler still-gzip-encoded bytes).
		String response = sendRawRequest(
			"POST /post HTTP/1.1\r\nHost: localhost\r\n" +
			"Transfer-Encoding: gzip, chunked\r\nConnection: close\r\n\r\n");
		assertTrue("expected 501, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 501"));
	}

	@Test
	public void invalidContentLengthRejectionTest() throws IOException {
		// Non-digit Content-Length (leading '+') must be rejected (request-smuggling defence).
		String response = sendRawRequest(
			"POST /post HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Content-Length: +5\r\n\r\n" +
			"hello"
		);
		assertTrue("expected 400 for invalid Content-Length, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 400"));
	}

	@Test
	public void whitespaceBeforeColonRejectionTest() throws IOException {
		String response = sendRawRequest(
			"GET / HTTP/1.1\r\n" +
			"Host : localhost\r\n\r\n"
		);
		assertTrue(response.startsWith("HTTP/1.1 400"));
	}

	@Test
	public void keepAliveProtocolDefaultTest() throws IOException {
		// HTTP/1.0 defaults to Close
		String resp10 = sendRawRequest(
			"GET / HTTP/1.0\r\n\r\n"
		);
		assertTrue(resp10.contains("Connection: Close"));

		// HTTP/1.0 with keep-alive header stays alive
		String resp10Keep = sendRawRequest(
			"GET / HTTP/1.0\r\n" +
			"Connection: keep-alive\r\n\r\n"
		);
		assertTrue(resp10Keep.contains("Connection: Keep-Alive"));

		// HTTP/1.1 defaults to Keep-Alive
		String resp11 = sendRawRequest(
			"GET / HTTP/1.1\r\n" +
			"Host: localhost\r\n\r\n"
		);
		assertTrue(resp11.contains("Connection: Keep-Alive"));
	}

	@Test
	public void corsPreflightVaryHeaderTest() throws IOException {
		String response = sendRawRequest(
			"OPTIONS /any/route HTTP/1.1\r\n" +
			"Host: localhost\r\n" +
			"Origin: http://example.com\r\n" +
			"Access-Control-Request-Method: PUT\r\n\r\n"
		);
		assertTrue(response.startsWith("HTTP/1.1 204"));
		assertTrue(response.contains("Vary: Origin"));
	}

	@Test
	public void AsynchronousHttpClientRobustHeaderParsingAndErrorTest() throws InterruptedException {
		// 1. Verify response builder correctly constructs chunked response.
		org.deftserver.web.http.client.Response testResponse = new org.deftserver.web.http.client.Response(System.currentTimeMillis());
		testResponse.addChunk("hello");
		testResponse.addChunk(" ");
		testResponse.addChunk("world");
		assertEquals("hello world", testResponse.getBody());

		// 2. Verify that connection failure is cleanly passed to onFailure callback instead of hanging.
		final CountDownLatch latch = new CountDownLatch(1);
		final AsynchronousHttpClient http = new AsynchronousHttpClient();
		// Connect to a port that is guaranteed to not be listening (e.g. 54321)
		final String badUrl = "http://localhost:54321/bad-route";
		IOLoop.INSTANCE.addCallback(new AsyncCallback() {
			@Override
			public void onCallback() {
				http.fetch(badUrl, new AsyncResult<org.deftserver.web.http.client.Response>() {
					@Override
					public void onSuccess(org.deftserver.web.http.client.Response response) {
						// shouldn't happen
					}

					@Override
					public void onFailure(Throwable e) {
						latch.countDown();
					}
				});
			}
		});
		latch.await(5, TimeUnit.SECONDS);
		assertEquals(0, latch.getCount());
	}

	@Test
	public void EtagHashingCorrectnessTest() {
		byte[] data = "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		
		// 1. Create a larger buffer populated with 'data' and filled with trailing zeros.
		byte[] largeBuffer = new byte[100];
		System.arraycopy(data, 0, largeBuffer, 0, data.length);
		
		// 2. Compute ETags using the new overloaded method.
		String expectedEtag = org.deftserver.util.HttpUtil.getEtag(data);
		String actualEtag = org.deftserver.util.HttpUtil.getEtag(largeBuffer, 0, data.length);
		
		assertEquals(expectedEtag, actualEtag);
	}

	@Test
	public void UrlUtilJoinTest() throws Exception {
		java.net.URL baseUrl = new java.net.URL("http://tt.se/sub/path/");
		
		// 1. Relative with leading slash
		assertEquals("http://tt.se/start", org.deftserver.util.UrlUtil.urlJoin(baseUrl, "/start"));
		
		// 2. Relative without leading slash
		assertEquals("http://tt.se/sub/path/start", org.deftserver.util.UrlUtil.urlJoin(baseUrl, "start"));
		
		// 3. Dot segment relative
		assertEquals("http://tt.se/sub/start", org.deftserver.util.UrlUtil.urlJoin(baseUrl, "../start"));
		
		// 4. Absolute HTTP
		assertEquals("http://google.com/index.html", org.deftserver.util.UrlUtil.urlJoin(baseUrl, "http://google.com/index.html"));
	}

	@Test
	public void AbsoluteStaticContentDirectoryAndSecurityTest() throws Exception {
		// 1. Verify that absolute static folders resolve correctly.
		// Get absolute path of standard test resources directory
		String absStaticDir = new java.io.File("src/test/resources").getCanonicalPath();
		
		// Configure a test Application and handler to serve static absolute content
		java.util.Map<String, RequestHandler> handlers = new java.util.HashMap<>();
		Application testApp = new Application(handlers);
		testApp.setStaticContentDir(absStaticDir);
		
		// Check that the request path is routed correctly by the test Application
		// (URL contains absolute folder matching prefix)
		String testFileUrlPath = absStaticDir + "/test.txt";
		RequestHandler resolved = testApp.getHandler(testFileUrlPath);
		assertEquals(org.deftserver.web.handler.StaticContentHandler.getInstance(), resolved);
		
		// 2. Assert direct request for resource outside static Content boundary triggers 403 Forbidden.
		// (Requesting pom.xml which exists outside test resources)
		String outsideFileUrlPath = new java.io.File("pom.xml").getCanonicalPath();
		
		// Setup mock HTTP request and response
		HttpRequest outsideReq = new HttpRequest("GET " + outsideFileUrlPath + " HTTP/1.1", java.util.Map.of("Host", "localhost"));
		org.deftserver.web.http.HttpResponse outsideResp = new org.deftserver.web.http.HttpResponse(null, null, false);
		
		try {
			org.deftserver.web.handler.StaticContentHandler.getInstance().get(outsideReq, outsideResp);
			org.junit.Assert.fail("Should have thrown 403 Forbidden");
		} catch (HttpException he) {
			assertEquals(403, he.getStatusCode());
		}
	}

	@Test
	public void symlinkEscapeIsForbiddenTest() throws Exception {
		// A symlink inside the webroot pointing OUTSIDE it must not be servable (the boundary
		// check resolves real paths, not lexical ones). Regression for the symlink-traversal hole.
		java.nio.file.Path webroot = java.nio.file.Files.createTempDirectory("dreft-webroot");
		java.nio.file.Path outside = java.nio.file.Files.createTempDirectory("dreft-outside");
		java.nio.file.Path secret = outside.resolve("secret.txt");
		java.nio.file.Files.writeString(secret, "TOPSECRET");
		java.nio.file.Path link = webroot.resolve("link.txt");
		try {
			java.nio.file.Files.createSymbolicLink(link, secret);
		} catch (UnsupportedOperationException | IOException e) {
			org.junit.Assume.assumeNoException("symbolic links not supported on this platform", e);
			return;
		}

		org.deftserver.web.handler.StaticContentHandler handler = org.deftserver.web.handler.StaticContentHandler.getInstance();
		try {
			handler.setStaticContentDir(webroot.toRealPath().toString());
			String reqPath = webroot.toRealPath().resolve("link.txt").toString();
			HttpRequest req = new HttpRequest("GET " + reqPath + " HTTP/1.1", java.util.Map.of("Host", "localhost"));
			org.deftserver.web.http.HttpResponse resp = new org.deftserver.web.http.HttpResponse(null, null, false);
			try {
				handler.get(req, resp);
				org.junit.Assert.fail("Symlink escaping the webroot must be 403 Forbidden");
			} catch (HttpException he) {
				assertEquals(403, he.getStatusCode());
			}
		} finally {
			// Restore the shared singleton's static dir for the other (HTTP) static tests.
			handler.setStaticContentDir("src/test/resources");
		}
	}

	@Test
	public void staticFileMimeTypeAliasesTest() throws Exception {
		java.net.http.HttpClient httpclient = java.net.http.HttpClient.newHttpClient();
		
		// Setup file extension to expected content type mappings
		java.util.Map<String, String> testCases = java.util.Map.ofEntries(
			java.util.Map.entry("htm", "text/html; charset=utf-8"),
			java.util.Map.entry("html", "text/html; charset=utf-8"),
			java.util.Map.entry("jpg", "image/jpeg"),
			java.util.Map.entry("jpeg", "image/jpeg"),
			java.util.Map.entry("tif", "image/tiff"),
			java.util.Map.entry("tiff", "image/tiff"),
			java.util.Map.entry("mpg", "video/mpeg"),
			java.util.Map.entry("mpeg", "video/mpeg"),
			java.util.Map.entry("mp4", "video/mp4"),
			java.util.Map.entry("m4v", "video/mp4"),
			java.util.Map.entry("cjs", "application/javascript"),
			java.util.Map.entry("xht", "application/xhtml+xml"),
			java.util.Map.entry("webm", "video/webm"),
			java.util.Map.entry("mov", "video/quicktime"),
			java.util.Map.entry("wav", "audio/wav"),
			java.util.Map.entry("mkv", "video/x-matroska"),
			java.util.Map.entry("mp3", "audio/mpeg"),
			java.util.Map.entry("ogg", "audio/ogg"),
			java.util.Map.entry("ogv", "video/ogg"),
			java.util.Map.entry("weba", "audio/webm"),
			java.util.Map.entry("aac", "audio/aac"),
			java.util.Map.entry("flac", "audio/flac"),
			java.util.Map.entry("webmanifest", "application/manifest+json"),
			java.util.Map.entry("woff", "font/woff"),
			java.util.Map.entry("woff2", "font/woff2"),
			java.util.Map.entry("ttf", "font/ttf"),
			java.util.Map.entry("otf", "font/otf"),
			java.util.Map.entry("wasm", "application/wasm"),
			java.util.Map.entry("csv", "text/csv; charset=utf-8"),
			java.util.Map.entry("7z", "application/x-7z-compressed"),
			java.util.Map.entry("tar", "application/x-tar"),
			java.util.Map.entry("gz", "application/gzip"),
			java.util.Map.entry("rar", "application/vnd.rar"),
			java.util.Map.entry("tar.gz", "application/x-gtar"),
			java.util.Map.entry("tgz", "application/x-gtar"),
			java.util.Map.entry("tar.bz2", "application/x-bzip2"),
			java.util.Map.entry("tbz2", "application/x-bzip2"),
			java.util.Map.entry("tar.xz", "application/x-xz"),
			java.util.Map.entry("txz", "application/x-xz"),
			java.util.Map.entry("bz2", "application/x-bzip2"),
			java.util.Map.entry("xz", "application/x-xz")
		);

		java.io.File resourcesDir = new java.io.File("src/test/resources");
		
		for (java.util.Map.Entry<String, String> entry : testCases.entrySet()) {
			String ext = entry.getKey();
			String expectedMime = entry.getValue();
			java.io.File tempFile = new java.io.File(resourcesDir, "mime_alias_test." + ext);
			try {
				// Create a simple temp file
				java.nio.file.Files.writeString(tempFile.toPath(), "test content");
				
				java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
					.uri(URI.create("http://localhost:" + PORT + "/src/test/resources/mime_alias_test." + ext))
					.GET()
					.build();
				java.net.http.HttpResponse<String> response = httpclient.send(req, HttpResponse.BodyHandlers.ofString());
				
				assertNotNull(response);
				assertEquals(200, response.statusCode());
				String actualMime = response.headers().firstValue("Content-Type").orElse(null);
				boolean match = false;
				if (ext.equals("gz")) {
					match = actualMime.equals("application/gzip") || actualMime.equals("application/x-gzip");
				} else if (ext.equals("rar")) {
					match = actualMime.equals("application/vnd.rar") || actualMime.equals("application/x-rar-compressed") || actualMime.equals("application/x-rar");
				} else if (ext.equals("tar.gz") || ext.equals("tgz")) {
					match = actualMime.equals("application/x-gtar") || actualMime.equals("application/x-gzip") || actualMime.equals("application/gzip");
				} else if (ext.equals("tar.bz2") || ext.equals("tbz2") || ext.equals("bz2")) {
					match = actualMime.equals("application/x-bzip2") || actualMime.equals("application/x-bzip") || actualMime.equals("application/bz2") || actualMime.equals("application/x-bz2");
				} else if (ext.equals("tar.xz") || ext.equals("txz") || ext.equals("xz")) {
					match = actualMime.equals("application/x-xz") || actualMime.equals("application/x-xz-compressed") || actualMime.equals("application/xz");
				} else if (ext.equals("m4v")) {
					match = actualMime.equals("video/mp4") || actualMime.equals("video/x-m4v");
				} else if (ext.equals("mkv")) {
					match = actualMime.equals("video/x-matroska") || actualMime.equals("video/mkv") || actualMime.equals("video/x-mkv");
				} else if (ext.equals("webm")) {
					match = actualMime.equals("video/webm") || actualMime.equals("video/x-webm");
				} else if (ext.equals("mov")) {
					match = actualMime.equals("video/quicktime") || actualMime.equals("video/x-quicktime");
				} else if (ext.equals("wav")) {
					match = actualMime.equals("audio/wav") || actualMime.equals("audio/x-wav") || actualMime.equals("audio/wave") || actualMime.equals("audio/x-pn-wav") || actualMime.equals("audio/vnd.wave");
				} else if (ext.equals("mp3")) {
					match = actualMime.equals("audio/mpeg") || actualMime.equals("audio/mp3") || actualMime.equals("audio/x-mpeg");
				} else if (ext.equals("ogg")) {
					match = actualMime.equals("audio/ogg") || actualMime.equals("audio/x-ogg") || actualMime.equals("application/ogg");
				} else if (ext.equals("ogv")) {
					match = actualMime.equals("video/ogg") || actualMime.equals("video/x-ogv");
				} else if (ext.equals("weba")) {
					match = actualMime.equals("audio/webm") || actualMime.equals("audio/x-weba") || actualMime.equals("audio/x-webm");
				} else if (ext.equals("aac")) {
					match = actualMime.equals("audio/aac") || actualMime.equals("audio/x-aac");
				} else if (ext.equals("flac")) {
					match = actualMime.equals("audio/flac") || actualMime.equals("audio/x-flac");
				} else {
					match = actualMime.equals(expectedMime);
				}
				assertTrue("Expected " + expectedMime + " for " + ext + " but got " + actualMime, match);
				// body consumed via BodyHandlers.ofString()
			} finally {
				if (tempFile.exists()) {
					tempFile.delete();
				}
			}
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void requestParameterParserTreeTest() {
		org.deftserver.web.http.HttpRequest request = new org.deftserver.web.http.HttpRequest(
			"GET /?formel1[0][0]=Satoshi&formel1[0][1]=DeepMind&formel1[bio]=philosophy&formel1[hobbies][]=NIO&formel1[hobbies][]=SSL HTTP/1.1",
			java.util.Collections.emptyMap()
		);

		java.util.Map<String, Object> nested = request.getParametersTree();
		assertNotNull(nested);
		assertTrue(nested.containsKey("formel1"));
		
		java.util.Map<String, Object> formel1 = (java.util.Map<String, Object>) nested.get("formel1");
		assertNotNull(formel1);
		
		java.util.Map<String, Object> zero = (java.util.Map<String, Object>) formel1.get("0");
		assertNotNull(zero);
		assertEquals("Satoshi", zero.get("0"));
		assertEquals("DeepMind", zero.get("1"));
		
		assertEquals("philosophy", formel1.get("bio"));
		
		java.util.Map<String, Object> hobbies = (java.util.Map<String, Object>) formel1.get("hobbies");
		assertNotNull(hobbies);
		assertEquals("NIO", hobbies.get("0"));
		assertEquals("SSL", hobbies.get("1"));
	}

	@Test
	public void postWithoutContentLengthReturns411Test() throws IOException {
		// POST, PUT, and PATCH without Content-Length or Transfer-Encoding must be 411.
		String response = sendRawRequest(
			"POST /post HTTP/1.1\r\n" +
			"Host: localhost\r\n\r\n"
		);
		assertTrue("expected 411 for POST, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 411"));
	}

	@Test
	public void putWithoutContentLengthReturns411Test() throws IOException {
		String response = sendRawRequest(
			"PUT /put HTTP/1.1\r\n" +
			"Host: localhost\r\n\r\n"
		);
		assertTrue("expected 411 for PUT, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 411"));
	}

	@Test
	public void patchWithoutContentLengthReturns411Test() throws IOException {
		String response = sendRawRequest(
			"PATCH /post HTTP/1.1\r\n" +
			"Host: localhost\r\n\r\n"
		);
		assertTrue("expected 411 for PATCH, got: " + response.substring(0, Math.min(40, response.length())),
			response.startsWith("HTTP/1.1 411"));
	}

}

