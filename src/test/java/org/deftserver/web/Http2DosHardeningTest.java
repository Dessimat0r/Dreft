package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.http2.Hpack;
import org.deftserver.web.http.http2.Http2Frame;
import org.deftserver.web.http.HttpServerDescriptor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class Http2DosHardeningTest {

	private static int PORT;
	private static HttpServer server;

	public static class DummyRequestHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("ok");
		}
		@Override
		public void post(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("ok");
		}
	}

	@BeforeClass
	public static void setup() throws Exception {
		Map<String, RequestHandler> reqHandlers = new HashMap<>();
		reqHandlers.put("/", new DummyRequestHandler());
		Application application = new Application(reqHandlers);

		server = new HttpServer(application);
		server.bind(0);
		PORT = server.getPort();

		Thread.ofPlatform()
			.name("HTTP2-DOS-I/O-Loop")
			.start(() -> {
				try {
					server.start(1);
				} catch (IOException e) {
					e.printStackTrace();
				}
			});

		Thread.sleep(1000);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		if (server != null) {
			server.stop();
		}
		Thread.sleep(500);
	}

	private static Http2Frame readFrame(InputStream in) throws IOException {
		byte[] header = new byte[9];
		int read = in.readNBytes(header, 0, 9);
		if (read < 9) {
			throw new IOException("EOF reading header");
		}
		int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
		int type = header[3] & 0xFF;
		int flags = header[4] & 0xFF;
		int streamId = ((header[5] & 0x7F) << 24) | ((header[6] & 0xFF) << 16) | ((header[7] & 0xFF) << 8) | (header[8] & 0xFF);
		byte[] payload = new byte[length];
		if (length > 0) {
			int payloadRead = in.readNBytes(payload, 0, length);
			if (payloadRead < length) {
				throw new IOException("EOF reading payload");
			}
		}
		return new Http2Frame(length, type, flags, streamId, payload);
	}

	private static void establishConnection(InputStream in, OutputStream out) throws IOException {
		Http2Frame settingsFrame = readFrame(in);
		assertEquals(Http2Frame.TYPE_SETTINGS, settingsFrame.type);
		out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, Http2Frame.FLAG_ACK, 0, new byte[0]));
		out.flush();
	}

	private static void assertConnectionClosed(Socket socket, InputStream in) throws IOException {
		socket.setSoTimeout(3000);
		try {
			while (true) {
				readFrame(in);
			}
		} catch (IOException e) {
		}
	}

	@Test
	public void testMaxConcurrentStreamsEnforced() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();

			establishConnection(in, out);

			Hpack.Writer writer = new Hpack.Writer(4096);
			List<Hpack.HeaderField> reqHeaders = new ArrayList<>();
			reqHeaders.add(new Hpack.HeaderField(":method", "GET"));
			reqHeaders.add(new Hpack.HeaderField(":path", "/"));
			reqHeaders.add(new Hpack.HeaderField(":scheme", "http"));
			reqHeaders.add(new Hpack.HeaderField(":authority", "localhost"));

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			writer.writeHeaders(baos, reqHeaders);
			byte[] headersPayload = baos.toByteArray();

			for (int i = 0; i < 100; i++) {
				int streamId = 2 * i + 1;
				out.write(Http2Frame.toBytes(Http2Frame.TYPE_HEADERS, Http2Frame.FLAG_END_HEADERS, streamId, headersPayload));
			}
			out.flush();

			int refusedStreamId = 201;
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_HEADERS, Http2Frame.FLAG_END_HEADERS, refusedStreamId, headersPayload));
			out.flush();

			Http2Frame rstFrame = null;
			for (int i = 0; i < 50; i++) {
				Http2Frame f = readFrame(in);
				if (f.type == Http2Frame.TYPE_RST_STREAM && f.streamId == refusedStreamId) {
					rstFrame = f;
					break;
				}
			}
			assertNotNull(rstFrame);
			int errorCode = ((rstFrame.payload[0] & 0xFF) << 24) | ((rstFrame.payload[1] & 0xFF) << 16) | ((rstFrame.payload[2] & 0xFF) << 8) | (rstFrame.payload[3] & 0xFF);
			assertEquals(7, errorCode);
		}
	}

	@Test
	public void testStreamIdStrictlyIncreasing() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();

			establishConnection(in, out);

			Hpack.Writer writer = new Hpack.Writer(4096);
			List<Hpack.HeaderField> reqHeaders = new ArrayList<>();
			reqHeaders.add(new Hpack.HeaderField(":method", "GET"));
			reqHeaders.add(new Hpack.HeaderField(":path", "/"));
			reqHeaders.add(new Hpack.HeaderField(":scheme", "http"));
			reqHeaders.add(new Hpack.HeaderField(":authority", "localhost"));

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			writer.writeHeaders(baos, reqHeaders);
			byte[] headersPayload = baos.toByteArray();

			out.write(Http2Frame.toBytes(Http2Frame.TYPE_HEADERS, Http2Frame.FLAG_END_HEADERS, 3, headersPayload));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_HEADERS, Http2Frame.FLAG_END_HEADERS, 1, headersPayload));
			out.flush();

			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testHeaderBlockSizeLimit() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();

			establishConnection(in, out);

			byte[] giantHeaders = new byte[70000];
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_HEADERS, 0, 1, giantHeaders));
			out.flush();

			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testRequestBodySizeLimit() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();

			establishConnection(in, out);

			Hpack.Writer writer = new Hpack.Writer(4096);
			List<Hpack.HeaderField> reqHeaders = new ArrayList<>();
			reqHeaders.add(new Hpack.HeaderField(":method", "POST"));
			reqHeaders.add(new Hpack.HeaderField(":path", "/"));
			reqHeaders.add(new Hpack.HeaderField(":scheme", "http"));
			reqHeaders.add(new Hpack.HeaderField(":authority", "localhost"));

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			writer.writeHeaders(baos, reqHeaders);
			byte[] headersPayload = baos.toByteArray();

			out.write(Http2Frame.toBytes(Http2Frame.TYPE_HEADERS, Http2Frame.FLAG_END_HEADERS, 1, headersPayload));
			out.flush();

			byte[] chunk = new byte[16384];
			for (int i = 0; i < 1025; i++) {
				out.write(Http2Frame.toBytes(Http2Frame.TYPE_DATA, 0, 1, chunk));
			}
			out.flush();

			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testRapidResetDefense() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();

			establishConnection(in, out);

			Hpack.Writer writer = new Hpack.Writer(4096);
			List<Hpack.HeaderField> reqHeaders = new ArrayList<>();
			reqHeaders.add(new Hpack.HeaderField(":method", "GET"));
			reqHeaders.add(new Hpack.HeaderField(":path", "/"));
			reqHeaders.add(new Hpack.HeaderField(":scheme", "http"));
			reqHeaders.add(new Hpack.HeaderField(":authority", "localhost"));

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			writer.writeHeaders(baos, reqHeaders);
			byte[] headersPayload = baos.toByteArray();

			for (int i = 0; i < 210; i++) {
				int streamId = 2 * i + 1;
				out.write(Http2Frame.toBytes(Http2Frame.TYPE_HEADERS, Http2Frame.FLAG_END_HEADERS, streamId, headersPayload));
				out.write(Http2Frame.toBytes(Http2Frame.TYPE_RST_STREAM, 0, streamId, new byte[] {0, 0, 0, 8}));
			}
			out.flush();

			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testControlFrameFlood() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();

			establishConnection(in, out);

			byte[] pingPayload = new byte[8];
			for (int i = 0; i < 600; i++) {
				out.write(Http2Frame.toBytes(Http2Frame.TYPE_PING, 0, 0, pingPayload));
			}
			out.flush();

			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testIdleTimeout() throws Exception {
		long original = HttpServerDescriptor.KEEP_ALIVE_TIMEOUT;
		HttpServerDescriptor.KEEP_ALIVE_TIMEOUT = 1000;
		try {
			Socket socket = new Socket("localhost", PORT);
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();

			establishConnection(in, out);

			Thread.sleep(2000);

			assertConnectionClosed(socket, in);
			socket.close();
		} finally {
			HttpServerDescriptor.KEEP_ALIVE_TIMEOUT = (int) original;
		}
	}

	@Test
	public void testInvalidSettingsAckLength() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();
			establishConnection(in, out);

			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, Http2Frame.FLAG_ACK, 0, new byte[6]));
			out.flush();
			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testInvalidSettingsFrameLength() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();
			establishConnection(in, out);

			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[5]));
			out.flush();
			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testInvalidSettingsEnablePush() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();
			establishConnection(in, out);

			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[] {0, 2, 0, 0, 0, 2}));
			out.flush();
			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testInvalidSettingsInitialWindow() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();
			establishConnection(in, out);

			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[] {0, 4, (byte) 0x80, 0, 0, 0}));
			out.flush();
			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testInvalidSettingsMaxFrameSize() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();
			establishConnection(in, out);

			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[] {0, 5, 0, 0, 0, 0}));
			out.flush();
			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testInvalidPingLength() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();
			establishConnection(in, out);

			out.write(Http2Frame.toBytes(Http2Frame.TYPE_PING, 0, 0, new byte[7]));
			out.flush();
			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testInvalidRstStreamLength() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();
			establishConnection(in, out);

			out.write(Http2Frame.toBytes(Http2Frame.TYPE_RST_STREAM, 0, 1, new byte[3]));
			out.flush();
			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testInvalidWindowUpdateLength() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();
			establishConnection(in, out);

			out.write(Http2Frame.toBytes(Http2Frame.TYPE_WINDOW_UPDATE, 0, 0, new byte[3]));
			out.flush();
			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testInvalidWindowUpdateIncrementZero() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();
			establishConnection(in, out);

			out.write(Http2Frame.toBytes(Http2Frame.TYPE_WINDOW_UPDATE, 0, 0, new byte[] {0, 0, 0, 0}));
			out.flush();
			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testInvalidGoawayLength() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();
			establishConnection(in, out);

			out.write(Http2Frame.toBytes(Http2Frame.TYPE_GOAWAY, 0, 0, new byte[7]));
			out.flush();
			assertConnectionClosed(socket, in);
		}
	}

	@Test
	public void testInvalidPriorityLength() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
			out.flush();
			establishConnection(in, out);

			out.write(Http2Frame.toBytes(Http2Frame.TYPE_PRIORITY, 0, 1, new byte[4]));
			out.flush();
			assertConnectionClosed(socket, in);
		}
	}
}
