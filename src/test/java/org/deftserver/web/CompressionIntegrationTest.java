package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.deftserver.web.http.HttpException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class CompressionIntegrationTest {

	private static HttpServer server;
	private static int PORT;

	private static class CompressHandler extends RequestHandler {
		@Override
		public void get(HttpRequest request, HttpResponse response) {
			response.setHeader("Content-Type", "text/plain; charset=utf-8");
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 1000; i++) {
				sb.append("hello compression ");
			}
			response.write(sb.toString());
		}
	}

	private static class DecompressHandler extends RequestHandler {
		@Override
		public void post(HttpRequest request, HttpResponse response) {
			response.write(request.getBody());
		}
	}

	private static class DecompressMultipartHandler extends RequestHandler {
		@Override
		public void post(HttpRequest request, HttpResponse response) {
			Map<String, HttpRequest.Part> parts = request.getMultiParts();
			StringBuilder sb = new StringBuilder();
			for (Map.Entry<String, HttpRequest.Part> entry : parts.entrySet()) {
				sb.append(entry.getKey()).append(":").append(entry.getValue().getRawData().length).append(";");
			}
			response.write(sb.toString());
		}
	}

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
			serverChannel.bind(new InetSocketAddress(0));
			PORT = serverChannel.socket().getLocalPort();
		}
		Map<String, RequestHandler> reqHandlers = new HashMap<>();
		reqHandlers.put("/compress", new CompressHandler());
		reqHandlers.put("/decompress", new DecompressHandler());
		reqHandlers.put("/decompress-multipart", new DecompressMultipartHandler());
		reqHandlers.put("/ping", new RequestHandler() {
			@Override public void get(HttpRequest request, HttpResponse response) { response.write("pong"); }
		});

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

	private static class RawResponse {
		Map<String, String> headers = new HashMap<>();
		byte[] body;
		int statusCode;
	}

	private RawResponse sendRequest(String reqBytes) throws Exception {
		return sendRequest(reqBytes.getBytes(StandardCharsets.ISO_8859_1));
	}

	private RawResponse sendRequest(byte[] reqBytes) throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			socket.setSoTimeout(3000);
			OutputStream out = socket.getOutputStream();
			out.write(reqBytes);
			out.flush();

			InputStream in = socket.getInputStream();
			ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
			int b;
			while (true) {
				b = in.read();
				if (b == -1) {
					throw new EOFException("EOF while reading headers");
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
			String headerStr = headerBytes.toString(StandardCharsets.ISO_8859_1);
			RawResponse resp = new RawResponse();
			String[] lines = headerStr.split("\r\n");
			String statusLine = lines[0];
			resp.statusCode = Integer.parseInt(statusLine.split(" ")[1]);

			for (int i = 1; i < lines.length; i++) {
				int colon = lines[i].indexOf(":");
				if (colon > 0) {
					resp.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
				}
			}

			if (resp.headers.containsKey("transfer-encoding") && "chunked".equalsIgnoreCase(resp.headers.get("transfer-encoding"))) {
				ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();
				while (true) {
					ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
					while (true) {
						int c = in.read();
						if (c == -1) throw new EOFException("EOF in chunk header");
						lineBuf.write(c);
						byte[] curr = lineBuf.toByteArray();
						if (curr.length >= 2 && curr[curr.length - 2] == '\r' && curr[curr.length - 1] == '\n') {
							break;
						}
					}
					String line = lineBuf.toString(StandardCharsets.ISO_8859_1).trim();
					int semi = line.indexOf(';');
					if (semi > 0) line = line.substring(0, semi);
					int size = Integer.parseInt(line, 16);
					if (size == 0) {
						// Consume the trailer section (optional header fields followed by a blank
						// CRLF line). Do NOT append trailer bytes into bodyOut — they are framing.
						int prev = -1;
						while (true) {
							int c = in.read();
							if (c == -1) throw new EOFException("EOF in chunk trailer");
							if (prev == '\r' && c == '\n') break;
							prev = c;
						}
						break;
					}
					byte[] chunk = new byte[size];
					int read = 0;
					while (read < size) {
						int r = in.read(chunk, read, size - read);
						if (r == -1) throw new EOFException("EOF in chunk body");
						read += r;
					}
					bodyOut.write(chunk);
					in.read(); // '\r'
					in.read(); // '\n'
				}
				resp.body = bodyOut.toByteArray();
			} else if (resp.headers.containsKey("content-length")) {
				int len = Integer.parseInt(resp.headers.get("content-length"));
				byte[] bodyBytes = new byte[len];
				int read = 0;
				while (read < len) {
					int r = in.read(bodyBytes, read, len - read);
					if (r == -1) throw new EOFException("EOF in body");
					read += r;
				}
				resp.body = bodyBytes;
			} else {
				resp.body = new byte[0];
			}
			return resp;
		}
	}

	@Test
	public void testOutboundCompressionGzip() throws Exception {
		String req = "GET /compress HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: gzip\r\nConnection: close\r\n\r\n";
		RawResponse resp = sendRequest(req);
		assertEquals(200, resp.statusCode);
		assertEquals("gzip", resp.headers.get("content-encoding"));
		byte[] decompressed = org.deftserver.util.HttpUtil.decompress(resp.body, "gzip");
		String body = new String(decompressed, StandardCharsets.UTF_8);
		assertTrue(body.startsWith("hello compression "));
		assertTrue(body.endsWith("hello compression "));
	}

	@Test
	public void testOutboundCompressionZstd() throws Exception {
		String req = "GET /compress HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: zstd, gzip;q=0.5\r\nConnection: close\r\n\r\n";
		RawResponse resp = sendRequest(req);
		assertEquals(200, resp.statusCode);
		assertEquals("zstd", resp.headers.get("content-encoding"));
		byte[] decompressed = org.deftserver.util.HttpUtil.decompress(resp.body, "zstd");
		String body = new String(decompressed, StandardCharsets.UTF_8);
		assertTrue(body.startsWith("hello compression "));
	}

	@Test
	public void testInboundDecompressionGzip() throws Exception {
		String payload = "hello server";
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(baos)) {
			gzos.write(payload.getBytes(StandardCharsets.UTF_8));
		}
		byte[] compressed = baos.toByteArray();
		ByteArrayOutputStream reqOut = new ByteArrayOutputStream();
		reqOut.write(("POST /decompress HTTP/1.1\r\n" +
					"Host: localhost\r\n" +
					"Content-Encoding: gzip\r\n" +
					"Content-Length: " + compressed.length + "\r\n" +
					"Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
		reqOut.write(compressed);

		RawResponse resp = sendRequest(reqOut.toByteArray());
		assertEquals(200, resp.statusCode);
		assertEquals(payload, new String(resp.body, StandardCharsets.UTF_8));
	}

	/**
	 * A corrupt Content-Encoding body must yield 400, NOT 500 — and crucially on the OFF-LOOP finalization
	 * path (any Content-Encoding marks the body "heavy" → its decompress runs on a virtual thread). The
	 * decompress IOException must be mapped to the MalFormedHttpRequest sentinel → 400 before dispatch.
	 */
	/**
	 * The whole point of off-loop finalization: a slow, heavy body finalize must NOT stall the single I/O
	 * loop. With a ~2 s artificial finalize delay injected, a heavy POST is in flight; a concurrent GET on
	 * the SAME single loop must still answer promptly (well under the 2 s finalize window) — proving the
	 * finalize runs on a virtual thread and the loop keeps servicing other connections.
	 */
	@Test
	public void offLoopFinalizeDoesNotStallTheLoop() throws Exception {
		byte[] heavyBody = new byte[300 * 1024]; // > FINALIZE_OFFLOAD_THRESHOLD → off-loop finalize
		java.util.Arrays.fill(heavyBody, (byte) 'x');
		ByteArrayOutputStream slowReq = new ByteArrayOutputStream();
		slowReq.write(("POST /decompress HTTP/1.1\r\n" +
					"Host: localhost\r\n" +
					"Content-Length: " + heavyBody.length + "\r\n" +
					"Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
		slowReq.write(heavyBody);

		org.deftserver.web.http.HttpRequest.TEST_FINALIZE_DELAY_MS = 2000;
		try {
			// The slow request signals this latch once its (heavy) body has been written, so the main thread
			// waits on that trigger instead of sleeping a guessed interval: the server has the full body and
			// is entering its delayed off-loop finalize by the time we fire /ping.
			final byte[] slowBytes = slowReq.toByteArray();
			final CountDownLatch slowDelivered = new CountDownLatch(1);
			Thread slow = new Thread(() -> {
				try (Socket s = new Socket("localhost", PORT)) {
					s.setSoTimeout(8000);
					s.getOutputStream().write(slowBytes);
					s.getOutputStream().flush();
					slowDelivered.countDown();       // body on the wire — server will now do its slow finalize
					s.getInputStream().readAllBytes(); // block while the server finalizes (slowly)
				} catch (Exception ignore) {
					slowDelivered.countDown();
				}
			});
			slow.start();
			assertTrue("slow request was not delivered in time", slowDelivered.await(3, TimeUnit.SECONDS));

			long t0 = System.nanoTime();
			RawResponse ping = sendRequest("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
			long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

			assertEquals(200, ping.statusCode);
			assertEquals("pong", new String(ping.body, StandardCharsets.UTF_8));
			assertTrue("/ping took " + elapsedMs + " ms — the loop was stalled by the off-loop finalize",
				elapsedMs < 1500);
			slow.join(5000);
		} finally {
			org.deftserver.web.http.HttpRequest.TEST_FINALIZE_DELAY_MS = 0;
		}
	}

	@Test
	public void testCorruptInboundGzipIsBadRequest() throws Exception {
		byte[] notGzip = "this is plainly not a valid gzip stream".getBytes(StandardCharsets.ISO_8859_1);
		ByteArrayOutputStream reqOut = new ByteArrayOutputStream();
		reqOut.write(("POST /decompress HTTP/1.1\r\n" +
					"Host: localhost\r\n" +
					"Content-Encoding: gzip\r\n" +
					"Content-Length: " + notGzip.length + "\r\n" +
					"Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
		reqOut.write(notGzip);

		RawResponse resp = sendRequest(reqOut.toByteArray());
		assertEquals(400, resp.statusCode);
	}

	@Test
	public void testInboundDecompressionZstd() throws Exception {
		String payload = "hello zstd server";
		byte[] compressed = org.zstjd.Zstd.compress(payload.getBytes(StandardCharsets.UTF_8));
		ByteArrayOutputStream reqOut = new ByteArrayOutputStream();
		reqOut.write(("POST /decompress HTTP/1.1\r\n" +
					"Host: localhost\r\n" +
					"Content-Encoding: zstd\r\n" +
					"Content-Length: " + compressed.length + "\r\n" +
					"Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
		reqOut.write(compressed);

		RawResponse resp = sendRequest(reqOut.toByteArray());
		assertEquals(200, resp.statusCode);
		assertEquals(payload, new String(resp.body, StandardCharsets.UTF_8));
	}

	@Test
	public void testInboundDecompressionMultipart() throws Exception {
		String boundary = "----WebKitFormBoundaryE1992";
		String partHeader = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file1\"; filename=\"a.txt\"\r\nContent-Type: text/plain\r\n\r\n";
		String partContent = "some file data here";
		String partFooter = "\r\n--" + boundary + "--\r\n";
		ByteArrayOutputStream payloadOut = new ByteArrayOutputStream();
		payloadOut.write(partHeader.getBytes(StandardCharsets.UTF_8));
		payloadOut.write(partContent.getBytes(StandardCharsets.UTF_8));
		payloadOut.write(partFooter.getBytes(StandardCharsets.UTF_8));

		byte[] compressed = org.zstjd.Zstd.compress(payloadOut.toByteArray());
		ByteArrayOutputStream reqOut = new ByteArrayOutputStream();
		reqOut.write(("POST /decompress-multipart HTTP/1.1\r\n" +
					"Host: localhost\r\n" +
					"Content-Type: multipart/form-data; boundary=" + boundary + "\r\n" +
					"Content-Encoding: zstd\r\n" +
					"Content-Length: " + compressed.length + "\r\n" +
					"Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
		reqOut.write(compressed);

		RawResponse resp = sendRequest(reqOut.toByteArray());
		assertEquals(200, resp.statusCode);
		assertEquals("file1:" + partContent.length() + ";", new String(resp.body, StandardCharsets.UTF_8));
	}

	@Test
	public void testZipBombDecompressionDefense() throws Exception {
		// A highly compressed payload of 1,000,000 zeros (small compressed, large expanded)
		byte[] largePayload = new byte[17 * 1024 * 1024]; // 17 MB (exceeds MAX_BODY_SIZE = 16 MB)
		byte[] compressed = org.zstjd.Zstd.compress(largePayload);
		ByteArrayOutputStream reqOut = new ByteArrayOutputStream();
		reqOut.write(("POST /decompress HTTP/1.1\r\n" +
					"Host: localhost\r\n" +
					"Content-Encoding: zstd\r\n" +
					"Content-Length: " + compressed.length + "\r\n" +
					"Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
		reqOut.write(compressed);

		RawResponse resp = sendRequest(reqOut.toByteArray());
		assertEquals(413, resp.statusCode);
	}
}
