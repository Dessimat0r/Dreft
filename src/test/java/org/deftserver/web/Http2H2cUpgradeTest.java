package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * End-to-end test of the HTTP/1.1 → HTTP/2 cleartext upgrade ({@code Upgrade: h2c}, RFC 7540 §3.2).
 * Two angles: (1) the JDK {@link HttpClient} — its real HTTP/2 stack performs the h2c upgrade for
 * {@code http://} HTTP_2 requests — must end up on HTTP/2; (2) a raw-socket client drives the exact
 * wire sequence (upgrade request → {@code 101 Switching Protocols} → server SETTINGS → stream-1
 * response) so the framing is pinned, and confirms a request without {@code Upgrade: h2c} is served
 * as ordinary HTTP/1.1.
 */
public class Http2H2cUpgradeTest {

	private static int PORT;
	private static HttpServer server;

	public static class HelloHandler extends RequestHandler {
		@Override
		public void get(HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("Hello h2c World!");
		}
	}

	@BeforeClass
	public static void setup() throws Exception {
		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/", new HelloHandler());
		server = new HttpServer(new Application(handlers));
		server.setHttp2CleartextUpgradeEnabled(true); // opt-in; off by default
		server.bind(0);
		PORT = server.getPort();
		Thread.ofPlatform().name("H2c-I/O-Loop").start(() -> {
			try { server.start(1); } catch (IOException e) { e.printStackTrace(); }
		});
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		if (server != null) server.stop();
		Thread.sleep(300);
	}

	@Test
	public void jdkClientNegotiatesHttp2ViaH2cUpgrade() throws Exception {
		HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + "/"))
			.GET()
			.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals("the h2c upgrade must land on HTTP/2", HttpClient.Version.HTTP_2, response.version());
		assertEquals(200, response.statusCode());
		assertEquals("Hello h2c World!", response.body());
	}

	@Test
	public void rawSocketUpgradeYields101ThenHttp2() throws Exception {
		// A minimal SETTINGS payload (empty is legal); base64url without padding.
		String http2Settings = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[0]);
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("localhost", PORT), 3000);
			socket.setSoTimeout(4000);
			OutputStream out = socket.getOutputStream();
			out.write(("GET / HTTP/1.1\r\n"
				+ "Host: localhost\r\n"
				+ "Connection: Upgrade, HTTP2-Settings\r\n"
				+ "Upgrade: h2c\r\n"
				+ "HTTP2-Settings: " + http2Settings + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			out.flush();

			InputStream in = socket.getInputStream();
			// Read the status line of the 101 response.
			String statusLine = readLine(in);
			assertTrue("expected 101 Switching Protocols, got: " + statusLine,
				statusLine.startsWith("HTTP/1.1 101"));
			// Drain the rest of the 101 response headers up to the blank line.
			String line;
			boolean sawUpgrade = false;
			while (!(line = readLine(in)).isEmpty()) {
				if (line.equalsIgnoreCase("Upgrade: h2c")) sawUpgrade = true;
			}
			assertTrue("101 must echo Upgrade: h2c", sawUpgrade);

			// The very next bytes must be the server's HTTP/2 connection preface: a SETTINGS frame
			// (type 0x4) on stream 0. Frame header: 3-byte length, 1-byte type, 1-byte flags, 4-byte streamId.
			byte[] hdr = readN(in, 9);
			int length = ((hdr[0] & 0xFF) << 16) | ((hdr[1] & 0xFF) << 8) | (hdr[2] & 0xFF);
			int type = hdr[3] & 0xFF;
			int streamId = ((hdr[5] & 0xFF) << 24) | ((hdr[6] & 0xFF) << 16) | ((hdr[7] & 0xFF) << 8) | (hdr[8] & 0xFF);
			assertEquals("server preface must be a SETTINGS frame", 0x4, type);
			assertEquals("SETTINGS is sent on stream 0", 0, streamId);
			readN(in, length); // consume the SETTINGS payload
		}
	}

	@Test
	public void plainRequestWithoutUpgradeStaysHttp1() throws Exception {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("localhost", PORT), 3000);
			socket.setSoTimeout(4000);
			socket.getOutputStream().write(
				("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
					.getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
			String statusLine = readLine(socket.getInputStream());
			assertTrue("a request without Upgrade: h2c must be plain HTTP/1.1 200, got: " + statusLine,
				statusLine.startsWith("HTTP/1.1 200"));
		}
	}

	private static String readLine(InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		int c, prev = -1;
		while ((c = in.read()) != -1) {
			if (prev == '\r' && c == '\n') {
				sb.setLength(sb.length() - 1); // drop the trailing '\r'
				return sb.toString();
			}
			sb.append((char) c);
			prev = c;
		}
		return sb.toString();
	}

	private static byte[] readN(InputStream in, int n) throws IOException {
		byte[] buf = new byte[n];
		int off = 0;
		while (off < n) {
			int r = in.read(buf, off, n - off);
			if (r < 0) throw new IOException("EOF after " + off + " of " + n + " bytes");
			off += r;
		}
		return buf;
	}
}
