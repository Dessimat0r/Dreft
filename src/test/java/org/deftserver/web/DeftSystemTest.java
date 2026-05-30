package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
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

		final Application application = new Application(reqHandlers);
		application.setStaticContentDir("src/test/resources");

		// start deft instance from a new thread because the start invocation is blocking 
		// (invoking thread will be I/O loop thread)
		Thread.ofPlatform().name("I/O-LOOP").start(() -> {
			try {
				new HttpServer(application).listen(PORT);
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
		IOLoop.INSTANCE.addCallback(new AsyncCallback() { @Override public void onCallback() { IOLoop.INSTANCE.stop(); }});
		Thread.sleep(20);
		org.deftserver.io.AsynchronousSocket.setDnsResolver(java.net.InetSocketAddress::new);
	}

	@Test
	public void simpleGetRequestTest() throws ClientProtocolException, IOException {
		doSimpleGetRequest();
	}

	private void doSimpleGetRequest() throws ClientProtocolException, IOException {
		List<Header> headers = new LinkedList<Header>();
		headers.add(new BasicHeader("Connection", "Close"));
		HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().setDefaultHeaders(headers).build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/");
		HttpResponse response = httpclient.execute(httpget);
		List<String> expectedHeaders = Arrays.asList(new String[] {"Server", "Date", "Content-Length", "Etag", "Connection"});

		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());

		assertEquals(expectedHeaders.size(), response.getAllHeaders().length);

		for (String header : expectedHeaders) {
			assertTrue(response.getFirstHeader(header) != null);
		}

		assertEquals(expectedPayload, convertStreamToString(response.getEntity().getContent()).trim());
		assertEquals(expectedPayload.length()+"", response.getFirstHeader("Content-Length").getValue());
	}

	/**
	 * Test a RH that does a single write
	 * @throws ClientProtocolException
	 * @throws IOException
	 */
	@Test
	public void wTest() throws ClientProtocolException, IOException {
		List<Header> headers = new LinkedList<Header>();
		headers.add(new BasicHeader("Connection", "Close"));
		HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().setDefaultHeaders(headers).build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/w");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("1", payLoad);
		assertEquals(5, response.getAllHeaders().length);
		assertEquals("1", response.getFirstHeader("Content-Length").getValue());
	}


	@Test
	public void wwTest() throws ClientProtocolException, IOException {
		List<Header> headers = new LinkedList<Header>();
		headers.add(new BasicHeader("Connection", "Close"));
		HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().setDefaultHeaders(headers).build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/ww");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("12", payLoad);
		assertEquals(5, response.getAllHeaders().length);
		assertEquals("2", response.getFirstHeader("Content-Length").getValue());
	}

	@Test
	public void wwfwTest() throws ClientProtocolException, IOException {
		List<Header> headers = new LinkedList<Header>();
		headers.add(new BasicHeader("Connection", "Close"));
		HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().setDefaultHeaders(headers).build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/wwfw");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("123", payLoad);
		assertEquals(3, response.getAllHeaders().length);
	}

	@Test
	public void wfwfTest() throws ClientProtocolException, IOException {
		List<Header> headers = new LinkedList<Header>();
		headers.add(new BasicHeader("Connection", "Close"));
		HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().setDefaultHeaders(headers).build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/wfwf");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("12", payLoad);
		assertEquals(3, response.getAllHeaders().length);
	}
	
	@Test
	public void wfffwfffTest() throws ClientProtocolException, IOException {
		List<Header> headers = new LinkedList<Header>();
		headers.add(new BasicHeader("Connection", "Close"));
		HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().setDefaultHeaders(headers).build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/wfffwfff");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("12", payLoad);
		assertEquals(3, response.getAllHeaders().length);
	}

	@Test
	public void deleteTest() throws ClientProtocolException, IOException {
		List<Header> headers = new LinkedList<Header>();
		headers.add(new BasicHeader("Connection", "Close"));
		HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().setDefaultHeaders(headers).build();
		HttpDelete httpdelete = new HttpDelete("http://localhost:" + PORT + "/delete");
		HttpResponse response = httpclient.execute(httpdelete);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("delete", payLoad);
	}

	@Test
	public void PostTest() throws ClientProtocolException, IOException {
		List<Header> headers = new LinkedList<Header>();
		headers.add(new BasicHeader("Connection", "Close"));
		HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().setDefaultHeaders(headers).build();
		HttpPost httppost = new HttpPost("http://localhost:" + PORT + "/post");
		HttpResponse response = httpclient.execute(httppost);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("post", payLoad);
	}

	@Test
	public void putTest() throws ClientProtocolException, IOException {
		List<Header> headers = new LinkedList<Header>();
		headers.add(new BasicHeader("Connection", "Close"));
		HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().setDefaultHeaders(headers).build();
		HttpPut httpput = new HttpPut("http://localhost:" + PORT + "/put");
		HttpResponse response = httpclient.execute(httpput);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("put", payLoad);
	}

	@Test
	public void capturingTest() throws ClientProtocolException, IOException {
		List<Header> headers = new LinkedList<Header>();
		headers.add(new BasicHeader("Connection", "Close"));
		HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().setDefaultHeaders(headers).build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/capturing/1911");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("/capturing/1911", payLoad);
	}

	@Test
	public void erroneousCapturingTest() throws ClientProtocolException, IOException {
		List<Header> headers = new LinkedList<Header>();
		headers.add(new BasicHeader("Connection", "Close"));
		HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().setDefaultHeaders(headers).build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/capturing/r1911");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(404, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("Not Found", response.getStatusLine().getReasonPhrase());
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("<html><head><title>404: Not found</title></head><body>Requested resource: <tt>/capturing/r1911</tt> was not found.</body>", payLoad);
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
	public void keepAliveRequestTest() throws ClientProtocolException, IOException {
		List<Header> headers = new LinkedList<Header>();
		headers.add(new BasicHeader("Connection", "Keep-Alive"));
		org.apache.http.client.HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().setDefaultHeaders(headers).build();

		for (int i = 1; i <= 5; i++) {
			doKeepAliveRequestTest(httpclient);
		}
	}

	private void doKeepAliveRequestTest(org.apache.http.client.HttpClient httpclient)
	throws IOException, ClientProtocolException {
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		assertEquals(5, response.getAllHeaders().length);
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals(expectedPayload, payLoad);
	}

	@Test
	public void HTTP_1_0_noConnectionHeaderTest() throws ClientProtocolException, IOException {
		org.apache.http.client.HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/");
		httpget.setProtocolVersion(new ProtocolVersion("HTTP", 1, 0));
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		assertEquals(5, response.getAllHeaders().length);
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals(expectedPayload, payLoad);
	}


	@Test
	public void httpExceptionTest() throws ClientProtocolException, IOException {
		org.apache.http.client.HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/throw");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(500, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("Internal Server Error", response.getStatusLine().getReasonPhrase());
		// text/plain body is gzipped, which now also emits Vary: Accept-Encoding (7 headers).
		assertEquals(7, response.getAllHeaders().length);
		assertTrue(response.getFirstHeader("Vary").getValue().toLowerCase().contains("accept-encoding"));
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("exception message", payLoad);
	}

	@Test
	public void asyncHttpExceptionTest() throws ClientProtocolException, IOException {
		org.apache.http.client.HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/async_throw");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(500, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("Internal Server Error", response.getStatusLine().getReasonPhrase());
		// text/plain body is gzipped, which now also emits Vary: Accept-Encoding (7 headers).
		assertEquals(7, response.getAllHeaders().length);
		assertTrue(response.getFirstHeader("Vary").getValue().toLowerCase().contains("accept-encoding"));
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("exception message", payLoad);
	}

	@Test
	public void staticFileRequestTest() throws ClientProtocolException, IOException {
		org.apache.http.client.HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/src/test/resources/test.txt");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		assertEquals(9, response.getAllHeaders().length);
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("test.txt", payLoad);
	}

	@Test
	public void pictureStaticFileRequestTest() throws ClientProtocolException, IOException {
		org.apache.http.client.HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/src/test/resources/n792205362_2067.jpg");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		assertEquals(9, response.getAllHeaders().length);
		assertEquals("54963", response.getFirstHeader("Content-Length").getValue());
		assertEquals("image/jpeg", response.getFirstHeader("Content-Type").getValue());
		assertNotNull(response.getFirstHeader("Last-Modified"));
	}
	
	@Test
	public void pictureStaticLargeFileRequestTest() throws ClientProtocolException, IOException {
		org.apache.http.client.HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/src/test/resources/f4_impact_1_original.jpg");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		assertEquals(9, response.getAllHeaders().length);
		//assertEquals("2145094", response.getFirstHeader("Content-Length").getValue()); // my mb says 2145066, imac says 2145094
		assertEquals("image/jpeg", response.getFirstHeader("Content-Type").getValue());
		assertNotNull(response.getFirstHeader("Last-Modified"));
		// TODO RS 101026 Verify that the actual body/entity is 2145094 bytes big (when we have support for "large" file)
	}

	@Test
	public void noBodyRequest() throws ClientProtocolException, IOException {
		HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/no_body");
		HttpResponse response = httpclient.execute(httpget);
		List<String> expectedHeaders = Arrays.asList(new String[] {"Server", "Date", "Content-Length", "Connection"});

		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());

		assertEquals(expectedHeaders.size(), response.getAllHeaders().length);

		for (String header : expectedHeaders) {
			assertTrue(response.getFirstHeader(header) != null);
		}

		assertEquals("", convertStreamToString(response.getEntity().getContent()).trim());
		assertEquals("0", response.getFirstHeader("Content-Length").getValue());
	}

	@Test
	public void movedPermanentlyRequest() throws ClientProtocolException, IOException {
		HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/moved_perm");
		HttpResponse response = httpclient.execute(httpget);
		List<String> expectedHeaders = Arrays.asList(new String[] {"Server", "Date", "Content-Length", "Connection", "Etag"});

		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());

		assertEquals(expectedHeaders.size(), response.getAllHeaders().length);

		for (String header : expectedHeaders) {
			assertTrue(response.getFirstHeader(header) != null);
		}

		assertEquals(expectedPayload, convertStreamToString(response.getEntity().getContent()).trim());
		assertEquals(expectedPayload.length()+"", response.getFirstHeader("Content-Length").getValue());
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
	public void userDefinedStaticContentHandlerTest() throws ClientProtocolException, IOException {
		// /static_file_handler
		org.apache.http.client.HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/static_file_handler");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		assertEquals(4, response.getAllHeaders().length);
		assertEquals("8", response.getFirstHeader("Content-Length").getValue());
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
	public void keyValueStoreClientTest() throws ClientProtocolException, IOException {
		org.apache.http.client.HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/keyvalue");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		assertEquals(5, response.getAllHeaders().length);
		assertEquals("7", response.getFirstHeader("Content-Length").getValue());
		assertEquals("kickass", convertStreamToString(response.getEntity().getContent()).trim());
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
	public void _450KBEntityTest() throws ClientProtocolException, IOException {
		org.apache.http.client.HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/450kb_body");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		assertEquals(5, response.getAllHeaders().length);
		//assertEquals(450*1024, Integer.parseInt(response.getFirstHeader("Content-Length").getValue())/8);
		//assertEquals(450*1024, _450KBResponseEntityRequestHandlr.entity.getBytes(Charsets.UTF_8).length);
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals(_450KBResponseEntityRequestHandler.entity, payLoad);
	}
	
	@Test
	public void smallHttpPostBodyWithUnusualCharactersTest() throws ClientProtocolException, IOException {
		final String body = "Räger Schildmäijår";
		
		org.apache.http.client.HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpPost httppost = new HttpPost("http://localhost:" + PORT + "/echo");
		httppost.setEntity(new StringEntity(body));	// HTTP 1.1 says that the default charset is ISO-8859-1
		HttpResponse response = httpclient.execute(httppost);	
		
		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		assertEquals(5, response.getAllHeaders().length);
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals(body, payLoad);
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
	public void authenticatedRequestHandlerTest() throws ClientProtocolException, IOException {
		org.apache.http.client.HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/authenticated");
		httpget.setHeader("user", "Roger Schildmeijer");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		assertEquals(5, response.getAllHeaders().length);
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("Roger Schildmeijer", payLoad);
	}
	
	@Test
	public void notAuthenticatedRequestHandlerTest() throws ClientProtocolException, IOException {
		org.apache.http.client.HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/authenticated");
		httpget.setHeader("wrong_header", "Roger Schildmeijer");
		HttpResponse response = httpclient.execute(httpget);

		assertNotNull(response);
		assertEquals(403, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("Forbidden", response.getStatusLine().getReasonPhrase());
		assertEquals(5, response.getAllHeaders().length);
		String payLoad = convertStreamToString(response.getEntity().getContent()).trim();
		assertEquals("Authentication failed", payLoad);
	}
	
	@Test
	public void queryParamsTest() throws ClientProtocolException, IOException {
		HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/query_params?key1=value1&key2=value2");
		HttpResponse response = httpclient.execute(httpget);
		List<String> expectedHeaders = Arrays.asList(new String[] {"Server", "Date", "Content-Length", "Etag", "Connection"});

		assertEquals(200, response.getStatusLine().getStatusCode());
		assertEquals(new ProtocolVersion("HTTP", 1, 1), response.getStatusLine().getProtocolVersion());
		assertEquals("OK", response.getStatusLine().getReasonPhrase());
		
		assertEquals(expectedHeaders.size(), response.getAllHeaders().length);

		for (String header : expectedHeaders) {
			assertTrue(response.getFirstHeader(header) != null);
		}

		final String expected = "value1 value2";
		assertEquals(expected, convertStreamToString(response.getEntity().getContent()).trim());
		assertEquals(expected.length()+"", response.getFirstHeader("Content-Length").getValue());
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
		// Two pipelined requests: the server answers the first and closes (RFC-compliant for a
		// non-pipelining server) rather than discarding the second and leaving the client hung.
		try (Socket socket = new Socket("localhost", PORT)) {
			socket.setSoTimeout(3000);
			String two = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n" +
			             "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
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
			assertTrue("first response should be 200, got: " + resp.substring(0, Math.min(40, resp.length())),
				resp.startsWith("HTTP/1.1 200"));
			assertTrue("response must declare Connection: Close:\n" + resp, resp.contains("Connection: Close"));
			// Exactly one response — the second pipelined request must NOT be answered (server closed).
			assertEquals("server must not pipeline a second response", -1, resp.indexOf("HTTP/1.1", 1));
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
		org.apache.http.client.HttpClient httpclient = org.apache.http.impl.client.HttpClients.custom().build();
		
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
				
				HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/src/test/resources/mime_alias_test." + ext);
				HttpResponse response = httpclient.execute(httpget);
				
				assertNotNull(response);
				assertEquals(200, response.getStatusLine().getStatusCode());
				String actualMime = response.getFirstHeader("Content-Type").getValue();
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
				org.apache.http.util.EntityUtils.consume(response.getEntity());
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

}

