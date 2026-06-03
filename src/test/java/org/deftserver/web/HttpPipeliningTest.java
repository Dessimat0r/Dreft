package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.HttpVerb;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class HttpPipeliningTest {

	private static HttpServer server;
	private static int PORT;
	private static SSLSocketFactory trustAllFactory;
	private static SSLContext serverSslContext;

	static {
		try {
			SSLContext clientCtx = SSLContext.getInstance("TLS");
			clientCtx.init(null, new TrustManager[]{new X509TrustManager() {
				public void checkClientTrusted(X509Certificate[] c, String a) {}
				public void checkServerTrusted(X509Certificate[] c, String a) {}
				public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
			}}, new SecureRandom());
			trustAllFactory = clientCtx.getSocketFactory();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static class FastHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("fast");
		}
	}

	private static class EchoHandler extends RequestHandler {
		@Override
		public void post(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write(request.getBody());
		}
	}

	private static class SlowAsyncHandler extends RequestHandler {
		@Override
		public boolean isMethodAsynchronous(HttpVerb verb) {
			return true;
		}

		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			Thread.startVirtualThread(() -> {
				try {
					Thread.sleep(150);
					response.write("slow");
					response.finish();
				} catch (Exception e) {
					try { response.getProtocol().closeChannel(response.getChannel()); } catch (Exception ignore) {}
				}
			});
		}
	}

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
			serverChannel.bind(new InetSocketAddress(0));
			PORT = serverChannel.socket().getLocalPort();
		}

		Map<String, RequestHandler> reqHandlers = new HashMap<>();
		reqHandlers.put("/fast", new FastHandler());
		reqHandlers.put("/echo", new EchoHandler());
		reqHandlers.put("/slow-async", new SlowAsyncHandler());

		KeyStore ks = KeyStore.getInstance("PKCS12");
		try (FileInputStream fis = new FileInputStream("src/test/resources/keystore.p12")) {
			ks.load(fis, "password".toCharArray());
		}
		KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
		kmf.init(ks, "password".toCharArray());
		serverSslContext = SSLContext.getInstance("TLS");
		serverSslContext.init(kmf.getKeyManagers(), null, null);

		server = new HttpServer(new Application(reqHandlers));
		server.bind(PORT);
		server.start(1);

		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		server.stop();
		Thread.sleep(100);
	}

	private List<String> readResponses(InputStream in, int count) throws Exception {
		List<String> responses = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
			int b;
			while (true) {
				b = in.read();
				if (b == -1) {
					throw new EOFException("Unexpected EOF while reading headers");
				}
				headerBytes.write(b);
				byte[] current = headerBytes.toByteArray();
				if (current.length >= 4 &&
					current[current.length - 4] == '\r' &&
					current[current.length - 3] == '\n' &&
					current[current.length - 2] == '\r' &&
					current[current.length - 1] == '\n') {
					break;
				}
			}
			String headers = headerBytes.toString(StandardCharsets.ISO_8859_1);
			int contentLength = 0;
			for (String line : headers.split("\r\n")) {
				if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
					contentLength = Integer.parseInt(line.substring(line.indexOf(":") + 1).trim());
				}
			}
			byte[] bodyBytes = new byte[contentLength];
			int read = 0;
			while (read < contentLength) {
				int r = in.read(bodyBytes, read, contentLength - read);
				if (r == -1) {
					throw new EOFException("Unexpected EOF while reading body");
				}
				read += r;
			}
			responses.add(new String(bodyBytes, StandardCharsets.UTF_8));
		}
		return responses;
	}

	@Test
	public void testBasicPipelining() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			String request = "GET /fast HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
					+ "GET /fast HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
					+ "GET /fast HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";

			out.write(request.getBytes(StandardCharsets.US_ASCII));
			out.flush();

			List<String> responses = readResponses(in, 3);
			assertEquals(3, responses.size());
			assertEquals("fast", responses.get(0));
			assertEquals("fast", responses.get(1));
			assertEquals("fast", responses.get(2));
		}
	}

	@Test
	public void testPipeliningWithBody() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			String request = "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nConnection: keep-alive\r\n\r\nhello"
					+ "GET /fast HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
					+ "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nConnection: close\r\n\r\nworld";

			out.write(request.getBytes(StandardCharsets.US_ASCII));
			out.flush();

			List<String> responses = readResponses(in, 3);
			assertEquals(3, responses.size());
			assertEquals("hello", responses.get(0));
			assertEquals("fast", responses.get(1));
			assertEquals("world", responses.get(2));
		}
	}

	@Test
	public void testPipeliningWithAsyncAndSync() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			long start = System.currentTimeMillis();

			String request = "GET /slow-async HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
					+ "GET /fast HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
					+ "GET /fast HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";

			out.write(request.getBytes(StandardCharsets.US_ASCII));
			out.flush();

			List<String> responses = readResponses(in, 3);
			long duration = System.currentTimeMillis() - start;

			assertTrue("Duration should reflect async sleep time", duration >= 140);
			assertEquals(3, responses.size());
			assertEquals("slow", responses.get(0));
			assertEquals("fast", responses.get(1));
			assertEquals("fast", responses.get(2));
		}
	}

	@Test
	public void testTlsPipelining() throws Exception {
		int sslPort;
		try (ServerSocketChannel sslServerChannel = ServerSocketChannel.open()) {
			sslServerChannel.bind(new InetSocketAddress(0));
			sslPort = sslServerChannel.socket().getLocalPort();
		}
		
		HttpServer sslServer = new HttpServer(new Application(Map.of("/fast", new FastHandler())));
		sslServer.enableSSL(serverSslContext);
		sslServer.bind(sslPort);
		sslServer.start(1);
		TestServerSupport.awaitListening(sslPort);

		try (SSLSocket sslSocket = (SSLSocket) trustAllFactory.createSocket("localhost", sslPort)) {
			sslSocket.startHandshake();
			OutputStream out = sslSocket.getOutputStream();
			InputStream in = sslSocket.getInputStream();

			String request = "GET /fast HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
					+ "GET /fast HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
					+ "GET /fast HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";

			out.write(request.getBytes(StandardCharsets.US_ASCII));
			out.flush();

			List<String> responses = readResponses(in, 3);
			assertEquals(3, responses.size());
			assertEquals("fast", responses.get(0));
			assertEquals("fast", responses.get(1));
			assertEquals("fast", responses.get(2));
		} finally {
			sslServer.stop();
			Thread.sleep(100);
		}
	}
}
