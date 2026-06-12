package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class UnixDomainSocketSystemTest {

	private static HttpServer server;
	private static Path socketPath;

	@BeforeClass
	public static void setUp() throws Exception {
		socketPath = Files.createTempFile("dreft-uds-", ".sock");
		Files.deleteIfExists(socketPath);
		
		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/test", new RequestHandler() {
			@Override
			public void get(HttpRequest request, HttpResponse response) {
				response.write("hello from uds");
			}
		});

		server = new HttpServer(new Application(handlers));
		server.bind(socketPath);
		server.start(1);
		
		Thread.sleep(100);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		if (server != null) {
			server.stop();
		}
		if (socketPath != null) {
			Files.deleteIfExists(socketPath);
		}
	}

	@Test
	public void testGetOverUnixDomainSocket() throws Exception {
		try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
			client.connect(UnixDomainSocketAddress.of(socketPath));
			
			String request = "GET /test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
			ByteBuffer writeBuf = ByteBuffer.wrap(request.getBytes(StandardCharsets.ISO_8859_1));
			while (writeBuf.hasRemaining()) {
				client.write(writeBuf);
			}

			ByteBuffer readBuf = ByteBuffer.allocate(1024);
			StringBuilder responseBuilder = new StringBuilder();
			while (client.read(readBuf) != -1) {
				readBuf.flip();
				responseBuilder.append(StandardCharsets.ISO_8859_1.decode(readBuf).toString());
				readBuf.clear();
			}

			String response = responseBuilder.toString();
			assertTrue(response.startsWith("HTTP/1.1 200 OK"));
			assertTrue(response.contains("hello from uds"));
		}
	}
}
