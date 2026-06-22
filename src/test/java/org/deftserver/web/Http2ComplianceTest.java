package org.deftserver.web;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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

/**
 * RFC 7540 conformance for connection- and stream-level error handling: a protocol violation must
 * produce a {@code GOAWAY} (connection error) or {@code RST_STREAM} (stream error) carrying the
 * correct error code — not a bare socket close. Driven over raw sockets with hand-built frames.
 * Companion to the human-readable checklist in {@code HTTP2_COMPLIANCE.md}.
 */
public class Http2ComplianceTest {

	private static final int NO_ERROR = 0x0, PROTOCOL_ERROR = 0x1, FLOW_CONTROL_ERROR = 0x3,
		STREAM_CLOSED = 0x5, FRAME_SIZE_ERROR = 0x6, COMPRESSION_ERROR = 0x9;

	private static int PORT;
	private static HttpServer server;

	public static class OkHandler extends RequestHandler {
		@Override public void get(org.deftserver.web.http.HttpRequest req, org.deftserver.web.http.HttpResponse resp) { resp.write("ok"); }
		@Override public void post(org.deftserver.web.http.HttpRequest req, org.deftserver.web.http.HttpResponse resp) { resp.write("ok"); }
	}

	@BeforeClass
	public static void setup() throws Exception {
		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/", new OkHandler());
		server = new HttpServer(new Application(handlers));
		server.bind(0);
		PORT = server.getPort();
		Thread.ofPlatform().name("H2-Compliance-Loop").start(() -> {
			try { server.start(1); } catch (IOException e) { e.printStackTrace(); }
		});
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		if (server != null) server.stop();
		Thread.sleep(300);
	}

	// ---- frame I/O helpers ----

	private static Http2Frame readFrame(InputStream in) throws IOException {
		byte[] h = new byte[9];
		if (in.readNBytes(h, 0, 9) < 9) throw new IOException("EOF reading frame header");
		int length = ((h[0] & 0xFF) << 16) | ((h[1] & 0xFF) << 8) | (h[2] & 0xFF);
		int type = h[3] & 0xFF, flags = h[4] & 0xFF;
		int streamId = ((h[5] & 0x7F) << 24) | ((h[6] & 0xFF) << 16) | ((h[7] & 0xFF) << 8) | (h[8] & 0xFF);
		byte[] payload = new byte[length];
		if (length > 0 && in.readNBytes(payload, 0, length) < length) throw new IOException("EOF reading payload");
		return new Http2Frame(length, type, flags, streamId, payload);
	}

	/** Connects, exchanges prefaces, and ACKs the server SETTINGS. Leaves the socket ready for frames. */
	private static Socket connect() throws IOException {
		Socket socket = new Socket("localhost", PORT);
		socket.setSoTimeout(4000);
		OutputStream out = socket.getOutputStream();
		out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
		out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, 0, 0, new byte[0]));
		out.flush();
		// Read the server SETTINGS preface and ACK it (skipping anything else).
		Http2Frame f = readFrame(socket.getInputStream());
		assertEquals("server preface must be SETTINGS", Http2Frame.TYPE_SETTINGS, f.type);
		out.write(Http2Frame.toBytes(Http2Frame.TYPE_SETTINGS, Http2Frame.FLAG_ACK, 0, new byte[0]));
		out.flush();
		return socket;
	}

	/** Reads frames until a frame of {@code type} arrives (skipping SETTINGS ACKs, WINDOW_UPDATEs, etc.). */
	private static Http2Frame readUntil(InputStream in, int type) throws IOException {
		for (int i = 0; i < 20; i++) {
			Http2Frame f = readFrame(in);
			if (f.type == type) return f;
		}
		throw new IOException("did not see frame type " + type);
	}

	private static int errorCodeOf(byte[] payload, int offset) {
		return ((payload[offset] & 0xFF) << 24) | ((payload[offset + 1] & 0xFF) << 16)
			| ((payload[offset + 2] & 0xFF) << 8) | (payload[offset + 3] & 0xFF);
	}

	/** Asserts the next GOAWAY carries {@code expectedCode}. */
	private static void expectGoAway(Socket socket, int expectedCode) throws IOException {
		Http2Frame f = readUntil(socket.getInputStream(), Http2Frame.TYPE_GOAWAY);
		assertEquals("GOAWAY error code", expectedCode, errorCodeOf(f.payload, 4));
	}

	/** Asserts the next RST_STREAM carries {@code expectedCode} on {@code streamId}. */
	private static void expectRstStream(Socket socket, int streamId, int expectedCode) throws IOException {
		Http2Frame f = readUntil(socket.getInputStream(), Http2Frame.TYPE_RST_STREAM);
		assertEquals("RST_STREAM stream", streamId, f.streamId);
		assertEquals("RST_STREAM error code", expectedCode, errorCodeOf(f.payload, 0));
	}

	private static byte[] encodeHeaders(List<Hpack.HeaderField> fields) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		new Hpack.Writer(4096).writeHeaders(baos, fields);
		return baos.toByteArray();
	}

	private static void writeFrame(OutputStream out, int type, int flags, int streamId, byte[] payload) throws IOException {
		out.write(Http2Frame.toBytes(type, flags, streamId, payload));
		out.flush();
	}

	// ---- connection-error cases (GOAWAY) ----

	@Test public void settingsOnNonZeroStream_protocolError() throws Exception {
		try (Socket s = connect()) {
			writeFrame(s.getOutputStream(), Http2Frame.TYPE_SETTINGS, 0, 1, new byte[0]);
			expectGoAway(s, PROTOCOL_ERROR);
		}
	}

	@Test public void pingOnNonZeroStream_protocolError() throws Exception {
		try (Socket s = connect()) {
			writeFrame(s.getOutputStream(), Http2Frame.TYPE_PING, 0, 3, new byte[8]);
			expectGoAway(s, PROTOCOL_ERROR);
		}
	}

	@Test public void pingWrongLength_frameSizeError() throws Exception {
		try (Socket s = connect()) {
			writeFrame(s.getOutputStream(), Http2Frame.TYPE_PING, 0, 0, new byte[7]);
			expectGoAway(s, FRAME_SIZE_ERROR);
		}
	}

	@Test public void windowUpdateZeroIncrementOnConnection_protocolError() throws Exception {
		try (Socket s = connect()) {
			writeFrame(s.getOutputStream(), Http2Frame.TYPE_WINDOW_UPDATE, 0, 0, new byte[4]); // increment 0
			expectGoAway(s, PROTOCOL_ERROR);
		}
	}

	@Test public void dataOnStreamZero_protocolError() throws Exception {
		try (Socket s = connect()) {
			writeFrame(s.getOutputStream(), Http2Frame.TYPE_DATA, 0, 0, new byte[] {1, 2, 3});
			expectGoAway(s, PROTOCOL_ERROR);
		}
	}

	@Test public void headersOnStreamZero_protocolError() throws Exception {
		try (Socket s = connect()) {
			byte[] block = encodeHeaders(validRequestHeaders());
			writeFrame(s.getOutputStream(), Http2Frame.TYPE_HEADERS,
				Http2Frame.FLAG_END_HEADERS | Http2Frame.FLAG_END_STREAM, 0, block);
			expectGoAway(s, PROTOCOL_ERROR);
		}
	}

	@Test public void evenClientStreamId_protocolError() throws Exception {
		try (Socket s = connect()) {
			byte[] block = encodeHeaders(validRequestHeaders());
			writeFrame(s.getOutputStream(), Http2Frame.TYPE_HEADERS,
				Http2Frame.FLAG_END_HEADERS | Http2Frame.FLAG_END_STREAM, 2, block); // even id
			expectGoAway(s, PROTOCOL_ERROR);
		}
	}

	@Test public void rstStreamOnStreamZero_protocolError() throws Exception {
		try (Socket s = connect()) {
			writeFrame(s.getOutputStream(), Http2Frame.TYPE_RST_STREAM, 0, 0, new byte[4]);
			expectGoAway(s, PROTOCOL_ERROR);
		}
	}

	@Test public void badInitialWindowSize_flowControlError() throws Exception {
		try (Socket s = connect()) {
			// SETTINGS_INITIAL_WINDOW_SIZE (id 4) = 0x80000000 (> 2^31-1).
			byte[] payload = {0, 4, (byte) 0x80, 0, 0, 0};
			writeFrame(s.getOutputStream(), Http2Frame.TYPE_SETTINGS, 0, 0, payload);
			expectGoAway(s, FLOW_CONTROL_ERROR);
		}
	}

	// ---- stream-error cases (RST_STREAM) ----

	@Test public void missingSchemePseudoHeader_malformed() throws Exception {
		try (Socket s = connect()) {
			List<Hpack.HeaderField> fields = new ArrayList<>();
			fields.add(new Hpack.HeaderField(":method", "GET"));
			fields.add(new Hpack.HeaderField(":path", "/"));
			// no :scheme
			sendRequestExpectRst(s, fields, PROTOCOL_ERROR);
		}
	}

	@Test public void connectionSpecificHeader_malformed() throws Exception {
		try (Socket s = connect()) {
			List<Hpack.HeaderField> fields = validRequestHeaders();
			fields.add(new Hpack.HeaderField("connection", "keep-alive"));
			sendRequestExpectRst(s, fields, PROTOCOL_ERROR);
		}
	}

	@Test public void uppercaseHeaderName_malformed() throws Exception {
		try (Socket s = connect()) {
			// Hpack.Writer/HeaderField lowercase names, so an uppercase name can't be produced via the
			// normal API — hand-encode a literal-without-indexing, new-name field with a raw uppercase
			// name and append it after the (valid) pseudo-header block.
			ByteArrayOutputStream block = new ByteArrayOutputStream();
			block.write(encodeHeaders(validRequestHeaders()));
			byte[] name = "X-Mixed-Case".getBytes(StandardCharsets.US_ASCII);
			byte[] value = "1".getBytes(StandardCharsets.US_ASCII);
			block.write(0x00);                 // literal header field without indexing, new name
			block.write(name.length);          // name length (no Huffman; lengths < 127)
			block.write(name);
			block.write(value.length);         // value length
			block.write(value);
			writeFrame(s.getOutputStream(), Http2Frame.TYPE_HEADERS,
				Http2Frame.FLAG_END_HEADERS | Http2Frame.FLAG_END_STREAM, 1, block.toByteArray());
			expectRstStream(s, 1, PROTOCOL_ERROR);
		}
	}

	@Test public void pseudoHeaderAfterRegular_malformed() throws Exception {
		try (Socket s = connect()) {
			List<Hpack.HeaderField> fields = new ArrayList<>();
			fields.add(new Hpack.HeaderField(":method", "GET"));
			fields.add(new Hpack.HeaderField(":scheme", "http"));
			fields.add(new Hpack.HeaderField("x-foo", "bar"));   // regular header
			fields.add(new Hpack.HeaderField(":path", "/"));      // pseudo after regular
			sendRequestExpectRst(s, fields, PROTOCOL_ERROR);
		}
	}

	// ---- stream-state machine ----

	@Test public void dataAfterEndStream_streamClosed() throws Exception {
		try (Socket s = connect()) {
			OutputStream out = s.getOutputStream();
			byte[] block = encodeHeaders(validRequestHeaders());
			// GET with END_STREAM closes the client half of stream 1...
			writeFrame(out, Http2Frame.TYPE_HEADERS,
				Http2Frame.FLAG_END_HEADERS | Http2Frame.FLAG_END_STREAM, 1, block);
			// ...so a following DATA on stream 1 is illegal.
			writeFrame(out, Http2Frame.TYPE_DATA, 0, 1, new byte[] {1, 2, 3});
			expectGoAway(s, STREAM_CLOSED);
		}
	}

	@Test public void windowUpdateOnIdleStream_protocolError() throws Exception {
		try (Socket s = connect()) {
			byte[] inc = {0, 0, 0, 1};
			writeFrame(s.getOutputStream(), Http2Frame.TYPE_WINDOW_UPDATE, 0, 5, inc); // stream 5 never opened
			expectGoAway(s, PROTOCOL_ERROR);
		}
	}

	@Test public void contentLengthMismatch_malformed() throws Exception {
		try (Socket s = connect()) {
			OutputStream out = s.getOutputStream();
			List<Hpack.HeaderField> fields = new ArrayList<>();
			fields.add(new Hpack.HeaderField(":method", "POST"));
			fields.add(new Hpack.HeaderField(":path", "/"));
			fields.add(new Hpack.HeaderField(":scheme", "http"));
			fields.add(new Hpack.HeaderField(":authority", "localhost"));
			fields.add(new Hpack.HeaderField("content-length", "100")); // lies: only 2 body bytes follow
			writeFrame(out, Http2Frame.TYPE_HEADERS, Http2Frame.FLAG_END_HEADERS, 1, encodeHeaders(fields));
			writeFrame(out, Http2Frame.TYPE_DATA, Http2Frame.FLAG_END_STREAM, 1, "ok".getBytes(StandardCharsets.US_ASCII));
			expectRstStream(s, 1, PROTOCOL_ERROR);
		}
	}

	@Test public void trailersAreAccepted() throws Exception {
		try (Socket s = connect()) {
			OutputStream out = s.getOutputStream();
			// Both header blocks share one HPACK writer so the client's dynamic table tracks the server's
			// single decoder (separate writers would desync the dynamic-table indices).
			Hpack.Writer writer = new Hpack.Writer(4096);
			List<Hpack.HeaderField> req = new ArrayList<>();
			req.add(new Hpack.HeaderField(":method", "POST"));
			req.add(new Hpack.HeaderField(":path", "/"));
			req.add(new Hpack.HeaderField(":scheme", "http"));
			req.add(new Hpack.HeaderField(":authority", "localhost"));
			// Initial HEADERS without END_STREAM (stream stays open for body + trailers).
			writeFrame(out, Http2Frame.TYPE_HEADERS, Http2Frame.FLAG_END_HEADERS, 1, encode(writer, req));
			writeFrame(out, Http2Frame.TYPE_DATA, 0, 1, "hello".getBytes(StandardCharsets.US_ASCII));
			// Trailer HEADERS (regular field only) with END_STREAM ends the request.
			List<Hpack.HeaderField> trailer = new ArrayList<>();
			trailer.add(new Hpack.HeaderField("x-checksum", "abc"));
			writeFrame(out, Http2Frame.TYPE_HEADERS,
				Http2Frame.FLAG_END_HEADERS | Http2Frame.FLAG_END_STREAM, 1, encode(writer, trailer));
			Http2Frame resp = readUntil(s.getInputStream(), Http2Frame.TYPE_HEADERS);
			assertEquals(1, resp.streamId);
		}
	}

	private static byte[] encode(Hpack.Writer writer, List<Hpack.HeaderField> fields) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		writer.writeHeaders(baos, fields);
		return baos.toByteArray();
	}

	// ---- CONTINUATION / PUSH_PROMISE / HPACK (regressions found by h2spec) ----

	/** A header block split across HEADERS + CONTINUATION must preserve the HEADERS frame's END_STREAM
	 *  flag (a split block previously lost it and the request was never dispatched → timeout). */
	@Test public void continuationPreservesEndStream() throws Exception {
		try (Socket s = connect()) {
			OutputStream out = s.getOutputStream();
			byte[] block = encodeHeaders(validRequestHeaders());
			int mid = block.length / 2;
			byte[] part1 = java.util.Arrays.copyOfRange(block, 0, mid);
			byte[] part2 = java.util.Arrays.copyOfRange(block, mid, block.length);
			// HEADERS with END_STREAM but NOT END_HEADERS, then the rest in a CONTINUATION (END_HEADERS).
			writeFrame(out, Http2Frame.TYPE_HEADERS, Http2Frame.FLAG_END_STREAM, 1, part1);
			writeFrame(out, Http2Frame.TYPE_CONTINUATION, Http2Frame.FLAG_END_HEADERS, 1, part2);
			Http2Frame resp = readUntil(s.getInputStream(), Http2Frame.TYPE_HEADERS);
			assertEquals(1, resp.streamId);
		}
	}

	@Test public void clientPushPromise_protocolError() throws Exception {
		try (Socket s = connect()) {
			// PUSH_PROMISE (type 0x5) from a client is illegal.
			writeFrame(s.getOutputStream(), Http2Frame.TYPE_PUSH_PROMISE,
				Http2Frame.FLAG_END_HEADERS, 1, new byte[] {0, 0, 0, 2});
			expectGoAway(s, PROTOCOL_ERROR);
		}
	}

	@Test public void hpackTableSizeUpdateAfterField_compressionError() throws Exception {
		try (Socket s = connect()) {
			ByteArrayOutputStream block = new ByteArrayOutputStream();
			block.write(encodeHeaders(validRequestHeaders()));
			block.write(0x20); // dynamic table size update — illegal AFTER header fields (RFC 7541 §4.2)
			writeFrame(s.getOutputStream(), Http2Frame.TYPE_HEADERS,
				Http2Frame.FLAG_END_HEADERS | Http2Frame.FLAG_END_STREAM, 1, block.toByteArray());
			expectGoAway(s, COMPRESSION_ERROR);
		}
	}

	/** A valid request must still get a normal response (sanity: the validations don't reject good traffic). */
	@Test public void validRequest_succeeds() throws Exception {
		try (Socket s = connect()) {
			byte[] block = encodeHeaders(validRequestHeaders());
			writeFrame(s.getOutputStream(), Http2Frame.TYPE_HEADERS,
				Http2Frame.FLAG_END_HEADERS | Http2Frame.FLAG_END_STREAM, 1, block);
			Http2Frame resp = readUntil(s.getInputStream(), Http2Frame.TYPE_HEADERS);
			assertEquals(1, resp.streamId);
		}
	}

	private static List<Hpack.HeaderField> validRequestHeaders() {
		List<Hpack.HeaderField> fields = new ArrayList<>();
		fields.add(new Hpack.HeaderField(":method", "GET"));
		fields.add(new Hpack.HeaderField(":path", "/"));
		fields.add(new Hpack.HeaderField(":scheme", "http"));
		fields.add(new Hpack.HeaderField(":authority", "localhost"));
		return fields;
	}

	private static void sendRequestExpectRst(Socket s, List<Hpack.HeaderField> fields, int code) throws IOException {
		byte[] block = encodeHeaders(fields);
		writeFrame(s.getOutputStream(), Http2Frame.TYPE_HEADERS,
			Http2Frame.FLAG_END_HEADERS | Http2Frame.FLAG_END_STREAM, 1, block);
		expectRstStream(s, 1, code);
	}
}
