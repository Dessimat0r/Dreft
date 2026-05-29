package org.deftserver.web;

import org.deftserver.web.handler.RequestHandler;
import org.junit.Test;

import java.util.HashMap;


public class HttpServerTest {
	
	private final Application application = new Application(new HashMap<>());
	
	@Test(expected=IllegalArgumentException.class)
	public void testPortInRange_low() throws Exception {
		int port = -1;
		HttpServer server = new HttpServer(application);
		server.listen(port);
	}
	

	@Test(expected=IllegalArgumentException.class)
	public void testPortInRange_high() throws Exception {
		int port = 65536;
		HttpServer server = new HttpServer(application);
		server.listen(port);
	}
	
	@Test
	public void testPortInRange_ok() throws Exception {
		int port = freePort();
		HttpServer server = new HttpServer(application);
		server.listen(port);
		server.stop();
	}

	private static int freePort() throws java.io.IOException {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
	
}
