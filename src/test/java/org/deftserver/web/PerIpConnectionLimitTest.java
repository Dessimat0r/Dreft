package org.deftserver.web;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.util.HashMap;
import java.util.Map;

import org.deftserver.web.handler.RequestHandler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class PerIpConnectionLimitTest {

	private static HttpServer server;
	private static int PORT;

	private static class DumbHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("ok");
		}
	}

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
			serverChannel.bind(new InetSocketAddress(0));
			PORT = serverChannel.socket().getLocalPort();
		}
		Map<String, RequestHandler> reqHandlers = new HashMap<>();
		reqHandlers.put("/", new DumbHandler());
		server = new HttpServer(new Application(reqHandlers));
		server.setMaxConnectionsPerIp(2); // at most 2 simultaneous connections per IP

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
	public void thirdConnectionFromSameIpIsRejected() throws Exception {
		// Two held-open connections from 127.0.0.1, then a third must be rejected immediately.
		Socket s1 = new Socket("127.0.0.1", PORT);
		s1.getOutputStream().write("GET / HTTP/1.1\r\n".getBytes());
		s1.getOutputStream().flush();
		Socket s2 = new Socket("127.0.0.1", PORT);
		s2.getOutputStream().write("GET / HTTP/1.1\r\n".getBytes());
		s2.getOutputStream().flush();

		Thread.sleep(150);

		Socket s3 = new Socket("127.0.0.1", PORT);
		s3.getOutputStream().write("GET / HTTP/1.1\r\n".getBytes());
		s3.getOutputStream().flush();

		int read;
		try {
			read = s3.getInputStream().read();
		} catch (IOException e) {
			read = -1; // connection reset is also a valid rejection
		}
		assertEquals(-1, read);

		s1.close();
		s2.close();
		s3.close();
	}
}
