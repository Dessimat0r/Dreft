package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.deftserver.io.IOLoop;
import org.deftserver.web.handler.RequestHandler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class AntiDosTimeoutTest {

	private static HttpServer server;
	private static int PORT;

	private static class DumbHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("dumb");
		}
	}

	@BeforeClass
	public static void setUp() throws Exception {
		// Find ephemeral port
		try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
			serverChannel.bind(new InetSocketAddress(0));
			PORT = serverChannel.socket().getLocalPort();
		}

		Map<String, RequestHandler> reqHandlers = new HashMap<>();
		reqHandlers.put("/", new DumbHandler());
		
		server = new HttpServer(new Application(reqHandlers));
		server.setMaxConnections(2); // Set active connections limit to 2

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
	public void testHeaderReadTimeout() throws Exception {
		Socket socket = new Socket("127.0.0.1", PORT);
		assertTrue(socket.isConnected());
		
		// Send incomplete request headers
		socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n".getBytes());
		socket.getOutputStream().flush();
		
		// Sleep for 6 seconds (timeout is 5 seconds)
		Thread.sleep(6000);
		
		// Assert that the server has closed the connection due to timeout
		int read = socket.getInputStream().read();
		assertEquals(-1, read);
		socket.close();
	}

	@Test
	public void testConnectionLimitThrottling() throws Exception {
		// Connection 1: Open and keep incomplete
		Socket s1 = new Socket("127.0.0.1", PORT);
		s1.getOutputStream().write("GET / HTTP/1.1\r\n".getBytes());
		s1.getOutputStream().flush();

		// Connection 2: Open and keep incomplete
		Socket s2 = new Socket("127.0.0.1", PORT);
		s2.getOutputStream().write("GET / HTTP/1.1\r\n".getBytes());
		s2.getOutputStream().flush();

		Thread.sleep(100);

		// Connection 3: Should exceed limit and get rejected/closed immediately
		Socket s3 = new Socket("127.0.0.1", PORT);
		s3.getOutputStream().write("GET / HTTP/1.1\r\n".getBytes());
		s3.getOutputStream().flush();

		// Reading from s3 should result in EOF (-1) because the server closes it immediately
		int read = 0;
		try {
			read = s3.getInputStream().read();
		} catch (IOException e) {
			read = -1; // Connection reset is also a valid rejection/closure
		}
		assertEquals(-1, read);

		s1.close();
		s2.close();
		s3.close();
	}
}
