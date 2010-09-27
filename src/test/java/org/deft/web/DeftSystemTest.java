package org.deft.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.deft.example.AsyncDbHandler;
import org.deft.web.handler.RequestHandler;
import org.junit.Before;
import org.junit.Test;


public class DeftSystemTest {
	
	private static final AtomicBoolean setupExecuted = new AtomicBoolean(false);

	private static final int PORT = 8081;

	private static class ExampleRequestHandler extends RequestHandler {
		@Override
		public void get(org.deft.web.protocol.HttpRequest request, org.deft.web.protocol.HttpResponse response) {
			response.write("hello test");
		}

	}

	@Before
	public void setup() {
		if (setupExecuted.get()) return;
		Map<String, RequestHandler> reqHandlers = new HashMap<String, RequestHandler>();
		reqHandlers.put("/", new ExampleRequestHandler());
		reqHandlers.put("/mySql", new AsyncDbHandler());

		final Application application = new Application(reqHandlers);

		// start deft instance from a new thread because the start invocation is blocking 
		// (invoking thread will be I/O loop thread)
		new Thread(new Runnable() {
			@Override public void run() { new HttpServer(application).listen(PORT).getIOLoop().start(); }
		}).start();
		setupExecuted.set(true);
		//		try { 
		//			Thread.sleep(10); //give Deft some time to start;
		//		} catch (InterruptedException e) { e.printStackTrace(); }	
	}

	@Test
	public void simpleGetRequestTest() throws ClientProtocolException, IOException {
		doSimpleGetRequest();
	}

	private void doSimpleGetRequest() throws ClientProtocolException, IOException {
		HttpParams params = new BasicHttpParams();
		params.setParameter(" Connection", "Close");
		HttpClient httpclient = new DefaultHttpClient(params);
		HttpGet httpget = new HttpGet("http://localhost:" + PORT + "/");
		HttpResponse response = httpclient.execute(httpget);
		List<String> expectedHeaders = Arrays.asList(new String[] {"Server", "Date"});

		assertEquals(response.getStatusLine().getStatusCode(), 200);
		assertEquals(response.getStatusLine().getProtocolVersion(), new ProtocolVersion("HTTP", 1, 1));
		assertEquals(response.getStatusLine().getReasonPhrase(), "OK");

		assertEquals(expectedHeaders.size(), response.getAllHeaders().length);

		for (String header : expectedHeaders) {
			assertTrue(response.getFirstHeader(header) != null);
		}
	}

	@Test
	public void simpleConcurrentGetRequest() {
		int nThreads = 8;
		int nRequests = 2048;
		ExecutorService executor = Executors.newFixedThreadPool(nThreads);

		for (int i = 1; i <= nRequests; i++) {
			executor.submit(new Runnable() {

				@Override
				public void run() {
					try {
						doSimpleGetRequest();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}

			});
		}
		try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

}
