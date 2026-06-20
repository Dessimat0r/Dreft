package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Regression for V3-63: when a plaintext response's header write stalls mid-header (send buffer full),
 * the unwritten header remainder is stashed as an O(1) pending prefix and drained BEFORE the body on the
 * next OP_WRITE — the body is never shifted to prepend it. This test forces the path with a very large
 * header value and a small-receive-buffer, slow-reading client, then asserts the complete response — the
 * big header AND the body, in order — arrives intact.
 */
public class PartialHeaderWriteTest {

	private static HttpServer server;
	private static int PORT;
	private static final int HEADER_SIZE = 2 * 1024 * 1024; // exceeds any socket send buffer → header write stalls
	private static final String BODY = "BODY-START<<<the response body marker>>>BODY-END";

	private static class BigHeaderHandler extends RequestHandler {
		@Override public void get(HttpRequest request, HttpResponse response) {
			StringBuilder big = new StringBuilder(HEADER_SIZE);
			for (int i = 0; i < HEADER_SIZE; i++) big.append('A');
			response.setHeader("X-Large-Header", big.toString());
			response.write(BODY);
		}
	}

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
			serverChannel.bind(new InetSocketAddress(0));
			PORT = serverChannel.socket().getLocalPort();
		}
		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/bigheader", new BigHeaderHandler());
		server = new HttpServer(new Application(handlers));
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
	public void bigHeaderWithStalledReaderArrivesIntactAndInOrder() throws Exception {
		long stashesBefore = org.deftserver.web.http.HttpProtocol.TEST_PREFIX_STASH_COUNT;
		try (Socket socket = new Socket()) {
			socket.setReceiveBufferSize(4096); // tiny client window → keeps the server's send buffer full
			socket.connect(new InetSocketAddress("localhost", PORT), 3000);
			socket.setSoTimeout(8000);

			OutputStream out = socket.getOutputStream();
			out.write("GET /bigheader HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
				.getBytes(StandardCharsets.ISO_8859_1));
			out.flush();

			// Do NOT read for a moment: let the server attempt to write the whole (multi-MB-header) response
			// and block mid-header once its send buffer + the tiny client window fill — forcing the header
			// write to go partial and the O(1) pending-prefix path to engage. Then drain everything slowly.
			Thread.sleep(400);

			InputStream in = socket.getInputStream();
			java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
			byte[] chunk = new byte[1024];
			int n;
			int reads = 0;
			while ((n = in.read(chunk)) != -1) {
				buf.write(chunk, 0, n);
				if ((reads++ & 0x3f) == 0) {
					try { Thread.sleep(1); } catch (InterruptedException ignore) {}
				}
			}
			String resp = new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);

			assertTrue("expected 200, got: " + resp.substring(0, Math.min(40, resp.length())),
				resp.startsWith("HTTP/1.1 200"));

			// The full 300 KiB header value must be present and intact.
			int hdrIdx = resp.indexOf("X-Large-Header: ");
			assertTrue("large header missing", hdrIdx >= 0);
			String repeated = "A".repeat(HEADER_SIZE);
			assertTrue("header value truncated/corrupted", resp.contains("X-Large-Header: " + repeated));

			// The body must be present, intact, and AFTER the header block (order preserved).
			int bodyIdx = resp.indexOf(BODY);
			assertTrue("body missing/corrupted", bodyIdx >= 0);
			assertTrue("body arrived before the header remainder (out of order)", bodyIdx > hdrIdx);

			// And the header/body separator must come before the body.
			int sepIdx = resp.indexOf("\r\n\r\n");
			assertTrue("header/body separator missing", sepIdx >= 0);
			assertTrue("body not after header block", bodyIdx > sepIdx);

			// Prove the O(1) pending-prefix path actually engaged (the header write went partial), so this
			// test isn't trivially passing through the common single-shot write.
			long stashesAfter = org.deftserver.web.http.HttpProtocol.TEST_PREFIX_STASH_COUNT;
			assertTrue("the partial-header (pending-prefix) path never engaged — test is not exercising V3-63",
				stashesAfter > stashesBefore);
		}
	}
}
