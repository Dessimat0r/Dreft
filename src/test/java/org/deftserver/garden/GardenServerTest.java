package org.deftserver.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.deftserver.web.HttpServer;
import org.deftserver.web.TestServerSupport;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Verifies the HTTP Garden echo adapter ({@link GardenServer}) produces the Garden's standardized,
 * base64-encoded JSON shape so it can be dropped into the differential fuzzer with confidence. Boots the
 * real catch-all echo application on an ephemeral port and asserts the decoded round-trip for a plain
 * request, headers, a body, an arbitrary path/method, and that a malformed request is rejected (Dreft's
 * 400 passes through as the "rejected" signal) rather than echoed.
 */
public class GardenServerTest {

	private static HttpServer server;
	private static int PORT;

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
			serverChannel.bind(new InetSocketAddress(0));
			PORT = serverChannel.socket().getLocalPort();
		}
		server = new HttpServer(GardenServer.newGardenApplication());
		server.bind(PORT);
		server.start(1);
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		server.stop();
		Thread.sleep(100);
	}

	@Test
	public void echoesPlainRequest() throws Exception {
		Echo e = parseEcho(sendRaw("GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));
		assertEquals("GET", e.method);
		assertEquals("/hello", e.uri);
		assertEquals("HTTP/1.1", e.version);
		assertEquals("", e.body);
		assertEquals("localhost", e.headers.get("host"));
	}

	@Test
	public void echoesArbitraryPathAndMethod() throws Exception {
		// No routes are registered, so this exercises the catch-all: a deep, multi-segment path and a
		// non-GET method must still be echoed (Dreft's router would otherwise 404 it).
		Echo e = parseEcho(sendRaw(
			"DELETE /a/b/c/d?x=1&y=2 HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"));
		assertEquals("DELETE", e.method);
		assertEquals("/a/b/c/d?x=1&y=2", e.uri);
	}

	@Test
	public void echoesHeadersAndBody() throws Exception {
		String body = "field=value&other=thing";
		Echo e = parseEcho(sendRaw(
			"POST /submit HTTP/1.1\r\nHost: localhost\r\n"
			+ "Content-Type: application/x-www-form-urlencoded\r\n"
			+ "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body));
		assertEquals("POST", e.method);
		assertEquals(body, e.body);
		assertEquals("application/x-www-form-urlencoded", e.headers.get("content-type"));
	}

	@Test
	public void preservesRawBodyBytesExactly() throws Exception {
		// Bytes outside ASCII must survive the ISO-8859-1 round-trip through getBody()->base64.
		byte[] raw = new byte[] { (byte) 0x00, (byte) 0x7f, (byte) 0x80, (byte) 0xff, 'A', '\n' };
		StringBuilder req = new StringBuilder();
		req.append("POST /bin HTTP/1.1\r\nHost: localhost\r\nContent-Length: ").append(raw.length)
			.append("\r\nConnection: close\r\n\r\n");
		byte[] head = req.toString().getBytes(StandardCharsets.ISO_8859_1);
		byte[] full = new byte[head.length + raw.length];
		System.arraycopy(head, 0, full, 0, head.length);
		System.arraycopy(raw, 0, full, head.length, raw.length);

		Echo e = parseEcho(sendRawBytes(full));
		byte[] echoedBody = e.bodyBytes;
		assertEquals(raw.length, echoedBody.length);
		for (int i = 0; i < raw.length; i++) {
			assertEquals("body byte " + i + " corrupted", raw[i], echoedBody[i]);
		}
	}

	@Test
	public void malformedRequestIsRejectedNotEchoed() throws Exception {
		// Two Content-Length headers is a classic smuggling vector Dreft rejects — the Garden should see
		// Dreft's 400 ("rejected"), NOT a 200 echo.
		String resp = sendRaw(
			"POST /x HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nContent-Length: 6\r\n"
			+ "Connection: close\r\n\r\n12345");
		assertTrue("expected a 4xx rejection, got: " + resp.substring(0, Math.min(40, resp.length())),
			resp.startsWith("HTTP/1.1 4"));
	}

	// ---- helpers ----

	private static final class Echo {
		String version, method, uri, body;
		byte[] bodyBytes;
		final Map<String, String> headers = new LinkedHashMap<>();
	}

	/** Minimal parser for the Garden echo JSON (flat, machine-generated — no nesting beyond the headers
	 *  array of [b64,b64] pairs), decoding each base64 field back to its ISO-8859-1 string/bytes. */
	private static Echo parseEcho(String rawResponse) {
		int bodySep = rawResponse.indexOf("\r\n\r\n");
		assertTrue("no header/body separator in response", bodySep >= 0);
		String status = rawResponse.substring(0, rawResponse.indexOf("\r\n"));
		assertTrue("expected 200 echo, got status: " + status, status.startsWith("HTTP/1.1 200"));
		String json = rawResponse.substring(bodySep + 4);

		Echo e = new Echo();
		e.version = decodeStr(extractField(json, "version"));
		e.method = decodeStr(extractField(json, "method"));
		e.uri = decodeStr(extractField(json, "uri"));
		String b64Body = extractField(json, "body");
		e.bodyBytes = Base64.getDecoder().decode(b64Body);
		e.body = new String(e.bodyBytes, StandardCharsets.ISO_8859_1);

		// The only ["b64","b64"] pairs in the (flat, machine-generated) JSON are header entries — the
		// scalar fields are "name":"val" — so match pairs across the whole document. (Base64 contains no
		// brackets, so there's no ambiguity.)
		java.util.regex.Matcher m = java.util.regex.Pattern
			.compile("\\[\"([^\"]*)\",\"([^\"]*)\"\\]").matcher(json);
		while (m.find()) {
			e.headers.put(decodeStr(m.group(1)), decodeStr(m.group(2)));
		}
		return e;
	}

	private static String extractField(String json, String name) {
		String key = "\"" + name + "\":\"";
		int i = json.indexOf(key);
		assertTrue("field '" + name + "' missing from echo JSON", i >= 0);
		int start = i + key.length();
		int end = json.indexOf('"', start);
		return json.substring(start, end);
	}

	private static String decodeStr(String b64) {
		return new String(Base64.getDecoder().decode(b64), StandardCharsets.ISO_8859_1);
	}

	private static String sendRaw(String request) throws Exception {
		return sendRawBytes(request.getBytes(StandardCharsets.ISO_8859_1));
	}

	private static String sendRawBytes(byte[] request) throws Exception {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("localhost", PORT), 3000);
			socket.setSoTimeout(4000);
			OutputStream out = socket.getOutputStream();
			out.write(request);
			out.flush();
			InputStream in = socket.getInputStream();
			java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
			byte[] chunk = new byte[4096];
			int n;
			while ((n = in.read(chunk)) != -1) {
				buf.write(chunk, 0, n);
			}
			return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
		}
	}
}
