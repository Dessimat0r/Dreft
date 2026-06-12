package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
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
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class Http2SystemTest {

	private static int PORT;
	private static HttpServer server;
	private static File tempFile;
	private static final String FILE_CONTENT = "This is a temporary test file content for HTTP/2 file streaming validation.";

	public static class HelloWorldRequestHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("Hello HTTP/2 World!");
		}
	}

	public static class PostRequestHandler extends RequestHandler {
		@Override
		public void post(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			String body = request.getBody();
			response.write("Echo: " + body);
		}
	}

	public static class FileRequestHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write(tempFile);
		}
	}

	@BeforeClass
	public static void setup() throws Exception {
		tempFile = File.createTempFile("http2-test", ".txt");
		try (FileWriter writer = new FileWriter(tempFile)) {
			writer.write(FILE_CONTENT);
		}

		Map<String, RequestHandler> reqHandlers = new HashMap<>();
		reqHandlers.put("/", new HelloWorldRequestHandler());
		reqHandlers.put("/post", new PostRequestHandler());
		reqHandlers.put("/file", new FileRequestHandler());
		Application application = new Application(reqHandlers);

		server = new HttpServer(application);
		server.bind(0);
		PORT = server.getPort();

		Thread.ofPlatform()
			.name("HTTP2-I/O-Loop")
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
		if (tempFile != null && tempFile.exists()) {
			tempFile.delete();
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

	@Test
	public void testHttp2GetRequest() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

			byte[] initialSettings = Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]);
			out.write(initialSettings);

			Hpack.Writer writer = new Hpack.Writer(4096);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			List<Hpack.HeaderField> reqHeaders = new ArrayList<>();
			reqHeaders.add(new Hpack.HeaderField(":method", "GET"));
			reqHeaders.add(new Hpack.HeaderField(":path", "/"));
			reqHeaders.add(new Hpack.HeaderField(":scheme", "http"));
			reqHeaders.add(new Hpack.HeaderField(":authority", "localhost"));
			writer.writeHeaders(baos, reqHeaders);
			byte[] headersPayload = baos.toByteArray();

			byte[] getHeadersFrame = Http2Frame.toBytes(
				Http2Frame.TYPE_HEADERS,
				Http2Frame.FLAG_END_HEADERS | Http2Frame.FLAG_END_STREAM,
				1,
				headersPayload
			);
			out.write(getHeadersFrame);
			out.flush();

			Http2Frame settingsFrame = readFrame(in);
			assertEquals(Http2Frame.TYPE_SETTINGS, settingsFrame.type);

			byte[] settingsAck = Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, Http2Frame.FLAG_ACK, 0, new byte[0]);
			out.write(settingsAck);
			out.flush();

			Http2Frame responseHeaders = null;
			Http2Frame responseData = null;

			for (int i = 0; i < 5; i++) {
				Http2Frame frame = readFrame(in);
				if (frame.streamId == 1) {
					if (frame.type == Http2Frame.TYPE_HEADERS) {
						responseHeaders = frame;
					} else if (frame.type == Http2Frame.TYPE_DATA) {
						responseData = frame;
						break;
					}
				}
			}

			assertNotNull(responseHeaders);
			assertNotNull(responseData);

			Hpack.Reader reader = new Hpack.Reader(4096);
			List<Hpack.HeaderField> respHeaders = reader.readHeaders(ByteBuffer.wrap(responseHeaders.payload));
			boolean statusOk = false;
			for (Hpack.HeaderField field : respHeaders) {
				if (field.name.equals(":status") && field.value.equals("200")) {
					statusOk = true;
					break;
				}
			}
			assertTrue(statusOk);

			String body = new String(responseData.payload, java.nio.charset.StandardCharsets.UTF_8);
			assertEquals("Hello HTTP/2 World!", body);
		}
	}

	@Test
	public void testHttp2PostRequest() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

			byte[] initialSettings = Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]);
			out.write(initialSettings);

			Hpack.Writer writer = new Hpack.Writer(4096);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			List<Hpack.HeaderField> reqHeaders = new ArrayList<>();
			reqHeaders.add(new Hpack.HeaderField(":method", "POST"));
			reqHeaders.add(new Hpack.HeaderField(":path", "/post"));
			reqHeaders.add(new Hpack.HeaderField(":scheme", "http"));
			reqHeaders.add(new Hpack.HeaderField(":authority", "localhost"));
			reqHeaders.add(new Hpack.HeaderField("content-type", "application/x-www-form-urlencoded"));
			writer.writeHeaders(baos, reqHeaders);
			byte[] headersPayload = baos.toByteArray();

			byte[] getHeadersFrame = Http2Frame.toBytes(
				Http2Frame.TYPE_HEADERS,
				Http2Frame.FLAG_END_HEADERS,
				1,
				headersPayload
			);
			out.write(getHeadersFrame);

			byte[] bodyBytes = "hello post request body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			byte[] dataFrame = Http2Frame.toBytes(
				Http2Frame.TYPE_DATA,
				Http2Frame.FLAG_END_STREAM,
				1,
				bodyBytes
			);
			out.write(dataFrame);
			out.flush();

			Http2Frame settingsFrame = readFrame(in);
			assertEquals(Http2Frame.TYPE_SETTINGS, settingsFrame.type);

			byte[] settingsAck = Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, Http2Frame.FLAG_ACK, 0, new byte[0]);
			out.write(settingsAck);
			out.flush();

			Http2Frame responseHeaders = null;
			Http2Frame responseData = null;

			for (int i = 0; i < 5; i++) {
				Http2Frame frame = readFrame(in);
				if (frame.streamId == 1) {
					if (frame.type == Http2Frame.TYPE_HEADERS) {
						responseHeaders = frame;
					} else if (frame.type == Http2Frame.TYPE_DATA) {
						responseData = frame;
						break;
					}
				}
			}

			assertNotNull(responseHeaders);
			assertNotNull(responseData);

			Hpack.Reader reader = new Hpack.Reader(4096);
			List<Hpack.HeaderField> respHeaders = reader.readHeaders(ByteBuffer.wrap(responseHeaders.payload));
			boolean statusOk = false;
			for (Hpack.HeaderField field : respHeaders) {
				if (field.name.equals(":status") && field.value.equals("200")) {
					statusOk = true;
					break;
				}
			}
			assertTrue(statusOk);

			String body = new String(responseData.payload, java.nio.charset.StandardCharsets.UTF_8);
			assertEquals("Echo: hello post request body", body);
		}
	}

	@Test
	public void testHttp2FileRequest() throws Exception {
		try (Socket socket = new Socket("localhost", PORT)) {
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

			byte[] initialSettings = Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]);
			out.write(initialSettings);

			Hpack.Writer writer = new Hpack.Writer(4096);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			List<Hpack.HeaderField> reqHeaders = new ArrayList<>();
			reqHeaders.add(new Hpack.HeaderField(":method", "GET"));
			reqHeaders.add(new Hpack.HeaderField(":path", "/file"));
			reqHeaders.add(new Hpack.HeaderField(":scheme", "http"));
			reqHeaders.add(new Hpack.HeaderField(":authority", "localhost"));
			writer.writeHeaders(baos, reqHeaders);
			byte[] headersPayload = baos.toByteArray();

			byte[] getHeadersFrame = Http2Frame.toBytes(
				Http2Frame.TYPE_HEADERS,
				Http2Frame.FLAG_END_HEADERS | Http2Frame.FLAG_END_STREAM,
				1,
				headersPayload
			);
			out.write(getHeadersFrame);
			out.flush();

			Http2Frame settingsFrame = readFrame(in);
			assertEquals(Http2Frame.TYPE_SETTINGS, settingsFrame.type);

			byte[] settingsAck = Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, Http2Frame.FLAG_ACK, 0, new byte[0]);
			out.write(settingsAck);
			out.flush();

			Http2Frame responseHeaders = null;
			ByteArrayOutputStream dataAccumulator = new ByteArrayOutputStream();

			for (int i = 0; i < 10; i++) {
				Http2Frame frame = readFrame(in);
				if (frame.streamId == 1) {
					if (frame.type == Http2Frame.TYPE_HEADERS) {
						responseHeaders = frame;
					} else if (frame.type == Http2Frame.TYPE_DATA) {
						dataAccumulator.write(frame.payload);
						if ((frame.flags & Http2Frame.FLAG_END_STREAM) != 0) {
							break;
						}
					}
				}
			}

			assertNotNull(responseHeaders);
			assertTrue(dataAccumulator.size() > 0);

			Hpack.Reader reader = new Hpack.Reader(4096);
			List<Hpack.HeaderField> respHeaders = reader.readHeaders(ByteBuffer.wrap(responseHeaders.payload));
			boolean statusOk = false;
			boolean hasContentLength = false;
			for (Hpack.HeaderField field : respHeaders) {
				if (field.name.equals(":status") && field.value.equals("200")) {
					statusOk = true;
				}
				if (field.name.equals("content-length") && field.value.equals(String.valueOf(tempFile.length()))) {
					hasContentLength = true;
				}
			}
			assertTrue(statusOk);
			assertTrue(hasContentLength);

			String body = new String(dataAccumulator.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
			assertEquals(FILE_CONTENT, body);
		}
	}
}
