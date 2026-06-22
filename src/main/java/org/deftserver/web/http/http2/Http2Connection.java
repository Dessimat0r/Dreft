package org.deftserver.web.http.http2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.deftserver.web.http.HttpProtocol;
import org.deftserver.web.http.HttpRequestDispatcher;
import org.deftserver.web.handler.RequestHandler;
import org.deftserver.io.buffer.DynamicByteBuffer;
import org.deftserver.io.timeout.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Http2Connection {

	private static final Logger logger = LoggerFactory.getLogger(Http2Connection.class);
	private static final byte[] HTTP2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

	// RFC 7540 §7 error codes.
	private static final int NO_ERROR = 0x0;
	private static final int PROTOCOL_ERROR = 0x1;
	private static final int INTERNAL_ERROR = 0x2;
	private static final int FLOW_CONTROL_ERROR = 0x3;
	private static final int STREAM_CLOSED = 0x5;
	private static final int FRAME_SIZE_ERROR = 0x6;
	private static final int REFUSED_STREAM = 0x7;
	private static final int COMPRESSION_ERROR = 0x9;
	private static final int ENHANCE_YOUR_CALM = 0xb;

	private final HttpProtocol protocol;
	private final SelectionKey key;
	private final SocketChannel channel;
	private final Hpack.Reader hpackReader = new Hpack.Reader(4096);
	private final Hpack.Writer hpackWriter = new Hpack.Writer(4096);
	private final Map<Integer, Http2Stream> streams = new HashMap<>();

	private final DynamicByteBuffer inboundBuffer = DynamicByteBuffer.allocate(65536);
	private final DynamicByteBuffer writeBuffer = DynamicByteBuffer.allocate(65536);

	private boolean prefaceReceived = false;
	private boolean initialSettingsSent = false;
	private int maxFrameSize = 16384;
	private int connectionOutboundWindowSize = 65535;
	private int initialStreamOutboundWindowSize = 65535;
	private int connectionInboundWindowSize = 65535;
	private int initialStreamInboundWindowSize = 65535;

	private int currentHeaderStreamId = -1;
	// The END_STREAM flag carried by a HEADERS frame whose header block continues across CONTINUATION
	// frames — remembered here so it is applied when the block completes (otherwise a split header
	// block would silently lose END_STREAM and the request would never be dispatched).
	private boolean pendingEndStream = false;
	private final ByteArrayOutputStream headerBlockBuilder = new ByteArrayOutputStream();
	private int maxClientStreamId = 0;
	private int recentRstCount = 0;
	private long lastControlFrameWindowStart = 0;
	private int controlFramesInWindow = 0;

	private static class PendingData {
		final int streamId;
		final byte[] data;
		final boolean endStream;
		int offset;
		PendingData(int streamId, byte[] data, boolean endStream) {
			this.streamId = streamId;
			this.data = data;
			this.endStream = endStream;
			this.offset = 0;
		}
	}
	private final List<PendingData> pendingDataQueue = new ArrayList<>();

	public Http2Connection(HttpProtocol protocol, SelectionKey key, SocketChannel channel, byte[] initialBytes) {
		this.protocol = protocol;
		this.key = key;
		this.channel = channel;
		if (initialBytes != null && initialBytes.length > 0) {
			inboundBuffer.put(initialBytes);
		}
	}

	public SelectionKey getSelectionKey() {
		return key;
	}

	public void onReadable() {
		try {
			ByteBuffer readBuf = protocol.readPlaintext(channel);
			if (readBuf == null) {
				logger.debug("readPlaintext returned null, closing channel");
				protocol.closeChannel(channel);
				return;
			}
			inboundBuffer.put(readBuf);
			inboundBuffer.flip();

			if (!prefaceReceived) {
				if (inboundBuffer.limit() >= HTTP2_PREFACE.length) {
					byte[] check = new byte[HTTP2_PREFACE.length];
					inboundBuffer.getByteBuffer().get(check);
					for (int i = 0; i < check.length; i++) {
						if (check[i] != HTTP2_PREFACE[i]) {
							// RFC 7540 §3.5: an invalid preface is a connection error — answer with GOAWAY
							// (best-effort) rather than a bare close so the peer learns why.
							connectionError(PROTOCOL_ERROR, "invalid connection preface");
							return;
						}
					}
					prefaceReceived = true;
					logger.debug("Preface matched, sending initial settings");
					// In the h2c-upgrade path the server preface (SETTINGS) was already sent right after
					// the 101, so don't send it twice — only send it here for the prior-knowledge path.
					if (!initialSettingsSent) {
						sendInitialSettings();
					}
				} else {
					logger.debug("Preface incomplete, waiting");
					inboundBuffer.compact();
					return;
				}
			}

			while (true) {
				Http2Frame frame = Http2Frame.read(inboundBuffer.getByteBuffer(), maxFrameSize);
				if (frame == null) {
					break;
				}
				processFrame(frame);
			}

			inboundBuffer.compact();
		} catch (IOException e) {
			logger.error("IOException in onReadable", e);
			protocol.closeChannel(channel);
		}
	}

	public void onWritable() {
		tryWrite();
	}

	private void sendInitialSettings() {
		byte[] payload = new byte[] {
			0, 3, 0, 0, 0, 100
		};
		writeFrame(Http2Frame.TYPE_SETTINGS, 0, 0, payload);
		initialSettingsSent = true;
	}

	/**
	 * Bootstraps this connection from an HTTP/1.1 {@code Upgrade: h2c} request (RFC 7540 §3.2). The
	 * caller has already written the {@code 101 Switching Protocols} response on the HTTP/1.1 wire.
	 * Here we: (1) apply the client's decoded {@code HTTP2-Settings}; (2) send the server connection
	 * preface (SETTINGS) — the first HTTP/2 frame the server sends; (3) materialise the upgraded
	 * request as stream 1, which is implicitly half-closed on the remote side (the client cannot send
	 * further data on it), and dispatch it. The client then sends its own connection preface
	 * (PRI/SETTINGS), which {@link #onReadable()} validates as usual (without re-sending settings).
	 *
	 * @param http1ResponsePrefix the raw {@code 101 Switching Protocols} bytes to emit ahead of any
	 *                            HTTP/2 frame, queued through this connection's ordered write buffer so
	 *                            a partial socket write can't interleave it with the SETTINGS frame
	 * @param clientSettings the decoded (raw, 6-bytes-per-setting) HTTP2-Settings payload, or empty
	 * @param stream1Headers the upgraded request's pseudo + regular headers, as HPACK header fields
	 * @param stream1Body    the upgraded request's body (may be empty)
	 */
	public void startH2cUpgrade(byte[] http1ResponsePrefix, byte[] clientSettings,
			List<Hpack.HeaderField> stream1Headers, byte[] stream1Body) {
		if (http1ResponsePrefix != null && http1ResponsePrefix.length > 0) {
			writeBuffer.put(http1ResponsePrefix); // ordered ahead of the server preface + stream-1 frames
		}
		if (clientSettings != null && clientSettings.length % 6 == 0 && !applySettingsPayload(clientSettings)) {
			return; // invalid settings — channel already closed
		}
		sendInitialSettings();
		// Stream 1 is reserved by the upgrade; subsequent client streams must use higher odd IDs.
		maxClientStreamId = 1;
		Http2Stream stream = streams.computeIfAbsent(1, id -> new Http2Stream(id, initialStreamOutboundWindowSize));
		stream.requestHeaders.addAll(stream1Headers);
		if (stream1Body != null && stream1Body.length > 0) {
			stream.requestBody.write(stream1Body, 0, stream1Body.length);
		}
		stream.setState(Http2Stream.STATE_HALF_CLOSED_REMOTE);
		dispatchRequest(stream);
	}

	private void processFrame(Http2Frame frame) throws IOException {
		// RFC 7540 §6.10: once a HEADERS block is open, the next frame MUST be a CONTINUATION on the same
		// stream — any other frame (type or stream) is a connection PROTOCOL_ERROR.
		if (currentHeaderStreamId != -1
				&& (frame.type != Http2Frame.TYPE_CONTINUATION || frame.streamId != currentHeaderStreamId)) {
			connectionError(PROTOCOL_ERROR, "expected CONTINUATION on stream " + currentHeaderStreamId);
			return;
		}

		if (frame.type == Http2Frame.TYPE_PING || frame.type == Http2Frame.TYPE_SETTINGS ||
			frame.type == Http2Frame.TYPE_RST_STREAM || frame.type == Http2Frame.TYPE_WINDOW_UPDATE) {
			long now = System.currentTimeMillis();
			if (now - lastControlFrameWindowStart > 1000) {
				lastControlFrameWindowStart = now;
				controlFramesInWindow = 0;
			}
			controlFramesInWindow++;
			if (controlFramesInWindow > 500) {
				connectionError(ENHANCE_YOUR_CALM, "control frame rate limit exceeded");
				return;
			}
		}

		switch (frame.type) {
			case Http2Frame.TYPE_SETTINGS:
				handleSettings(frame);
				break;
			case Http2Frame.TYPE_HEADERS:
				handleHeaders(frame);
				break;
			case Http2Frame.TYPE_CONTINUATION:
				handleContinuation(frame);
				break;
			case Http2Frame.TYPE_DATA:
				handleData(frame);
				break;
			case Http2Frame.TYPE_WINDOW_UPDATE:
				handleWindowUpdate(frame);
				break;
			case Http2Frame.TYPE_PING:
				handlePing(frame);
				break;
			case Http2Frame.TYPE_RST_STREAM:
				recentRstCount++;
				if (recentRstCount > 200) {
					connectionError(ENHANCE_YOUR_CALM, "RST_STREAM flood");
					return;
				}
				handleRstStream(frame);
				break;
			case Http2Frame.TYPE_GOAWAY:
				if (frame.streamId != 0) {
					connectionError(PROTOCOL_ERROR, "GOAWAY must be on stream 0");
					return;
				}
				if (frame.payload.length < 8) {
					connectionError(FRAME_SIZE_ERROR, "GOAWAY length must be >= 8");
					return;
				}
				logger.debug("Received GOAWAY, closing channel");
				protocol.closeChannel(channel);
				break;
			case Http2Frame.TYPE_PRIORITY:
				handlePriority(frame);
				break;
			case Http2Frame.TYPE_PUSH_PROMISE:
				// Clients must not send PUSH_PROMISE (only servers push, and this server never does).
				connectionError(PROTOCOL_ERROR, "PUSH_PROMISE not allowed from client");
				break;
		}
	}

	private void handlePriority(Http2Frame frame) {
		if (frame.streamId == 0) {
			connectionError(PROTOCOL_ERROR, "PRIORITY must not be on stream 0");
			return;
		}
		if (frame.payload.length != 5) {
			// RFC 7540 §6.3: a PRIORITY of the wrong length is a stream error.
			streamError(frame.streamId, FRAME_SIZE_ERROR);
			return;
		}
		// §5.3.1: a stream cannot depend on itself.
		int dependency = ByteBuffer.wrap(frame.payload).getInt() & 0x7FFFFFFF;
		if (dependency == frame.streamId) {
			streamError(frame.streamId, PROTOCOL_ERROR);
		}
	}

	private void handleSettings(Http2Frame frame) throws IOException {
		if (frame.streamId != 0) {
			connectionError(PROTOCOL_ERROR, "SETTINGS must be on stream 0");
			return;
		}
		if ((frame.flags & Http2Frame.FLAG_ACK) != 0) {
			if (frame.payload.length != 0) {
				connectionError(FRAME_SIZE_ERROR, "SETTINGS ACK must have length 0");
				return;
			}
			logger.debug("Received SETTINGS ACK");
			return;
		}
		if (frame.payload.length % 6 != 0) {
			connectionError(FRAME_SIZE_ERROR, "SETTINGS length must be a multiple of 6");
			return;
		}
		if (!applySettingsPayload(frame.payload)) {
			return; // applySettingsPayload already raised the connection error
		}
		writeFrame(Http2Frame.TYPE_SETTINGS, Http2Frame.FLAG_ACK, 0, null);
	}

	/**
	 * Applies a SETTINGS payload (6 bytes per setting) to this connection. Shared by the on-wire
	 * SETTINGS frame handler and the h2c-upgrade path (RFC 7540 §3.2.1), where the client's settings
	 * arrive base64url-encoded in the HTTP/1.1 {@code HTTP2-Settings} header rather than as a frame —
	 * in that case no SETTINGS ACK is owed (there was no frame to acknowledge). Returns {@code false}
	 * (after closing the channel) if a value is out of range; {@code true} on success.
	 */
	private boolean applySettingsPayload(byte[] payload) {
		ByteBuffer buf = ByteBuffer.wrap(payload);
		while (buf.remaining() >= 6) {
			int id = buf.getShort() & 0xFFFF;
			int value = buf.getInt();
			if (id == 2) {
				if (value != 0 && value != 1) {
					return connectionError(PROTOCOL_ERROR, "invalid SETTINGS_ENABLE_PUSH: " + value);
				}
			} else if (id == 4) {
				// INITIAL_WINDOW_SIZE max is 2^31-1; a value above that (read as a negative int) is a
				// flow-control error (RFC 7540 §6.5.2).
				if (value < 0) {
					return connectionError(FLOW_CONTROL_ERROR, "invalid SETTINGS_INITIAL_WINDOW_SIZE: " + value);
				}
				int diff = value - initialStreamOutboundWindowSize;
				for (Http2Stream stream : streams.values()) {
					stream.outboundWindowSize += diff;
				}
				initialStreamOutboundWindowSize = value;
			} else if (id == 5) {
				if (value < 16384 || value > 16777215) {
					return connectionError(PROTOCOL_ERROR, "invalid SETTINGS_MAX_FRAME_SIZE: " + value);
				}
				maxFrameSize = value;
			}
		}
		return true;
	}

	private void handleHeaders(Http2Frame frame) throws IOException {
		// RFC 7540 §6.2 / §5.1.1: HEADERS must be on a client-initiated (odd, non-zero) stream.
		if (frame.streamId == 0) {
			connectionError(PROTOCOL_ERROR, "HEADERS must not be on stream 0");
			return;
		}
		if (frame.streamId % 2 == 0) {
			connectionError(PROTOCOL_ERROR, "client stream id must be odd: " + frame.streamId);
			return;
		}
		// §5.1 stream-state check. A HEADERS frame either opens a brand-new stream (id strictly greater
		// than any seen) or carries trailers for an already-open one; anything else is an error.
		Http2Stream existing = streams.get(frame.streamId);
		if (existing != null) {
			if (!existing.isOpen()) {
				// half-closed (remote) or closed → the client already ended its half of the stream.
				connectionError(STREAM_CLOSED, "HEADERS on non-open stream " + frame.streamId);
				return;
			}
			// Trailers: a second HEADERS on an open stream MUST terminate it (END_STREAM).
			if ((frame.flags & Http2Frame.FLAG_END_STREAM) == 0) {
				connectionError(PROTOCOL_ERROR, "trailer HEADERS must set END_STREAM on stream " + frame.streamId);
				return;
			}
		} else {
			if (frame.streamId <= maxClientStreamId) {
				connectionError(PROTOCOL_ERROR, "stream id " + frame.streamId + " not greater than " + maxClientStreamId);
				return;
			}
			maxClientStreamId = frame.streamId;
		}
		int offset = 0;
		int padLength = 0;
		if ((frame.flags & Http2Frame.FLAG_PADDED) != 0) {
			if (frame.payload.length < 1) {
				connectionError(PROTOCOL_ERROR, "PADDED HEADERS missing pad length");
				return;
			}
			padLength = frame.payload[offset++] & 0xFF;
		}
		if ((frame.flags & Http2Frame.FLAG_PRIORITY) != 0) {
			if (frame.payload.length < offset + 5) {
				connectionError(PROTOCOL_ERROR, "PRIORITY-flagged HEADERS too short");
				return;
			}
			// §5.3.1: a stream must not depend on itself.
			int dependency = ((frame.payload[offset] & 0x7F) << 24) | ((frame.payload[offset + 1] & 0xFF) << 16)
				| ((frame.payload[offset + 2] & 0xFF) << 8) | (frame.payload[offset + 3] & 0xFF);
			if (dependency == frame.streamId) {
				connectionError(PROTOCOL_ERROR, "HEADERS stream depends on itself");
				return;
			}
			offset += 5;
		}
		int headerBlockLength = frame.payload.length - offset - padLength;
		if (headerBlockLength < 0) {
			connectionError(PROTOCOL_ERROR, "HEADERS pad length exceeds frame");
			return;
		}
		if (headerBlockLength > 65536) {
			connectionError(ENHANCE_YOUR_CALM, "header block too large: " + headerBlockLength);
			return;
		}
		headerBlockBuilder.reset();
		headerBlockBuilder.write(frame.payload, offset, headerBlockLength);
		currentHeaderStreamId = frame.streamId;
		// Remember END_STREAM so it survives a header block continued over CONTINUATION frames.
		pendingEndStream = (frame.flags & Http2Frame.FLAG_END_STREAM) != 0;

		if ((frame.flags & Http2Frame.FLAG_END_HEADERS) != 0) {
			finishHeaders(frame.streamId, pendingEndStream);
		}
	}

	private void handleContinuation(Http2Frame frame) throws IOException {
		// A CONTINUATION with no open header block, or on the wrong stream, is a connection PROTOCOL_ERROR
		// (the wrong-stream case is also caught earlier in processFrame).
		if (currentHeaderStreamId == -1 || frame.streamId != currentHeaderStreamId) {
			connectionError(PROTOCOL_ERROR, "unexpected CONTINUATION on stream " + frame.streamId);
			return;
		}
		if (headerBlockBuilder.size() + frame.payload.length > 65536) {
			connectionError(ENHANCE_YOUR_CALM, "header block exceeded 64 KiB during CONTINUATION");
			return;
		}
		headerBlockBuilder.write(frame.payload);
		if ((frame.flags & Http2Frame.FLAG_END_HEADERS) != 0) {
			// Apply the END_STREAM flag carried by the originating HEADERS frame.
			finishHeaders(frame.streamId, pendingEndStream);
		}
	}

	private void finishHeaders(int streamId, boolean endStream) throws IOException {
		currentHeaderStreamId = -1;
		byte[] block = headerBlockBuilder.toByteArray();
		headerBlockBuilder.reset();

		// HPACK applies to the whole connection (a shared dynamic table), so a decode failure is a
		// connection COMPRESSION_ERROR, not a stream error (RFC 7540 §4.3).
		List<Hpack.HeaderField> fields;
		try {
			fields = hpackReader.readHeaders(ByteBuffer.wrap(block));
		} catch (IOException hpackFailure) {
			connectionError(COMPRESSION_ERROR, "HPACK decode failed: " + hpackFailure.getMessage());
			return;
		}
		if (!streams.containsKey(streamId)) {
			if (streams.size() >= 100) {
				logger.warn("Exceeded max concurrent streams limit (100), refusing stream={}", streamId);
				streamError(streamId, REFUSED_STREAM);
				return;
			}
		}
		Http2Stream stream = streams.computeIfAbsent(streamId, id -> new Http2Stream(id, initialStreamOutboundWindowSize));
		stream.requestHeaders.addAll(fields);

		if (endStream) {
			stream.setState(Http2Stream.STATE_HALF_CLOSED_REMOTE);
			dispatchRequest(stream);
		} else {
			stream.setState(Http2Stream.STATE_OPEN);
		}
	}

	private void handleData(Http2Frame frame) throws IOException {
		if (frame.streamId == 0) {
			connectionError(PROTOCOL_ERROR, "DATA must not be on stream 0");
			return;
		}
		Http2Stream stream = streams.get(frame.streamId);
		if (stream == null) {
			// An id we've never opened is idle (PROTOCOL_ERROR); a lower id is a stream we already
			// closed and dropped (STREAM_CLOSED) — RFC 7540 §5.1.
			int code = frame.streamId > maxClientStreamId ? PROTOCOL_ERROR : STREAM_CLOSED;
			connectionError(code, "DATA on non-open stream " + frame.streamId);
			return;
		}
		if (!stream.isOpen()) {
			// The client already half-closed (END_STREAM) or the stream is closed: no more DATA allowed.
			connectionError(STREAM_CLOSED, "DATA on half-closed/closed stream " + frame.streamId);
			return;
		}
		int offset = 0;
		int padLength = 0;
		if ((frame.flags & Http2Frame.FLAG_PADDED) != 0) {
			if (frame.payload.length < 1) {
				connectionError(PROTOCOL_ERROR, "PADDED DATA missing pad length");
				return;
			}
			padLength = frame.payload[offset++] & 0xFF;
		}
		int dataLength = frame.payload.length - offset - padLength;
		if (dataLength < 0) {
			connectionError(PROTOCOL_ERROR, "DATA pad length exceeds frame");
			return;
		}
		if (stream.requestBody.size() + dataLength > 16777216) {
			logger.warn("Payload size exceeds maximum allowed limit (16 MiB) on stream {}", frame.streamId);
			streamError(frame.streamId, ENHANCE_YOUR_CALM);
			return;
		}
		stream.requestBody.write(frame.payload, offset, dataLength);

		connectionInboundWindowSize -= frame.length;
		stream.inboundWindowSize -= frame.length;

		if (connectionInboundWindowSize < 32768) {
			writeFrame(Http2Frame.TYPE_WINDOW_UPDATE, 0, 0, intToBytes(65536 - connectionInboundWindowSize));
			connectionInboundWindowSize = 65536;
		}
		if (stream.inboundWindowSize < 32768) {
			writeFrame(Http2Frame.TYPE_WINDOW_UPDATE, 0, stream.streamId, intToBytes(65536 - stream.inboundWindowSize));
			stream.inboundWindowSize = 65536;
		}

		if ((frame.flags & Http2Frame.FLAG_END_STREAM) != 0) {
			stream.setState(Http2Stream.STATE_HALF_CLOSED_REMOTE);
			dispatchRequest(stream);
		}
	}

	private void handleWindowUpdate(Http2Frame frame) throws IOException {
		if (frame.payload.length != 4) {
			connectionError(FRAME_SIZE_ERROR, "WINDOW_UPDATE length must be 4");
			return;
		}
		int increment = ByteBuffer.wrap(frame.payload).getInt() & 0x7FFFFFFF;
		if (increment == 0) {
			// §6.9: a zero increment is a stream error on a stream, a connection error on stream 0.
			if (frame.streamId == 0) {
				connectionError(PROTOCOL_ERROR, "WINDOW_UPDATE increment must be non-zero");
			} else {
				streamError(frame.streamId, PROTOCOL_ERROR);
			}
			return;
		}
		if (frame.streamId == 0) {
			long newSize = (long) connectionOutboundWindowSize + increment;
			if (newSize > Integer.MAX_VALUE) {
				connectionError(FLOW_CONTROL_ERROR, "connection flow-control window overflow");
				return;
			}
			connectionOutboundWindowSize = (int) newSize;
		} else {
			Http2Stream stream = streams.get(frame.streamId);
			if (stream == null) {
				// WINDOW_UPDATE on an idle stream (never opened) is a connection PROTOCOL_ERROR (§5.1);
				// on a recently-closed stream it's permitted and ignored.
				if (frame.streamId % 2 != 0 && frame.streamId > maxClientStreamId) {
					connectionError(PROTOCOL_ERROR, "WINDOW_UPDATE on idle stream " + frame.streamId);
					return;
				}
			} else {
				long newSize = (long) stream.outboundWindowSize + increment;
				if (newSize > Integer.MAX_VALUE) {
					streamError(frame.streamId, FLOW_CONTROL_ERROR);
					return;
				}
				stream.outboundWindowSize = (int) newSize;
			}
		}
		flushPendingData();
	}

	private void handlePing(Http2Frame frame) throws IOException {
		if (frame.streamId != 0) {
			connectionError(PROTOCOL_ERROR, "PING must be on stream 0");
			return;
		}
		if (frame.payload.length != 8) {
			connectionError(FRAME_SIZE_ERROR, "PING length must be 8");
			return;
		}
		if ((frame.flags & Http2Frame.FLAG_ACK) == 0) {
			writeFrame(Http2Frame.TYPE_PING, Http2Frame.FLAG_ACK, 0, frame.payload);
		}
	}

	private void handleRstStream(Http2Frame frame) throws IOException {
		if (frame.streamId == 0) {
			connectionError(PROTOCOL_ERROR, "RST_STREAM must not be on stream 0");
			return;
		}
		if (frame.payload.length != 4) {
			connectionError(FRAME_SIZE_ERROR, "RST_STREAM length must be 4");
			return;
		}
		// RST_STREAM for a stream that was never opened (id above the highest we've seen) is a
		// PROTOCOL_ERROR (RFC 7540 §5.1, idle state).
		if (frame.streamId % 2 != 0 && frame.streamId > maxClientStreamId) {
			connectionError(PROTOCOL_ERROR, "RST_STREAM on idle stream " + frame.streamId);
			return;
		}
		Http2Stream stream = streams.remove(frame.streamId);
		if (stream != null) {
			stream.setState(Http2Stream.STATE_CLOSED);
		}
	}

	private void dispatchRequest(Http2Stream stream) {
		String method = null;
		String path = null;
		String scheme = null;
		String authority = null;
		Map<String, String> headers = new HashMap<>();
		boolean seenRegularHeader = false;

		// RFC 7540 §8.1.2 message validation. Any violation makes the request malformed → a stream error
		// of type PROTOCOL_ERROR (the connection and its other streams are unaffected).
		String invalid = null;
		for (Hpack.HeaderField field : stream.requestHeaders) {
			String name = field.name;
			if (field.nameHadUppercase) { invalid = "uppercase header name"; break; }
			if (!name.isEmpty() && name.charAt(0) == ':') {
				// §8.1.2.1: all pseudo-headers must precede regular headers, must be known request
				// pseudo-headers, and must not be duplicated.
				if (seenRegularHeader) { invalid = "pseudo-header after regular header: " + name; break; }
				if (name.equals(":method")) {
					if (method != null) { invalid = "duplicate :method"; break; }
					method = field.value;
				} else if (name.equals(":path")) {
					if (path != null) { invalid = "duplicate :path"; break; }
					path = field.value;
				} else if (name.equals(":scheme")) {
					if (scheme != null) { invalid = "duplicate :scheme"; break; }
					scheme = field.value;
				} else if (name.equals(":authority")) {
					if (authority != null) { invalid = "duplicate :authority"; break; }
					authority = field.value;
				} else {
					invalid = "unknown pseudo-header: " + name; break;
				}
			} else {
				seenRegularHeader = true;
				// §8.1.2.2: connection-specific header fields are forbidden in HTTP/2; TE is allowed only
				// with the exact value "trailers".
				if (isConnectionSpecificHeader(name)) { invalid = "connection-specific header: " + name; break; }
				if (name.equals("te") && !field.value.equals("trailers")) { invalid = "TE must be 'trailers'"; break; }
				headers.put(name, field.value);
			}
		}

		// §8.1.2.3: a request must include :method, :scheme and :path (non-empty for the schemes we serve).
		if (invalid == null && (method == null || scheme == null || path == null || path.isEmpty())) {
			invalid = "missing/empty mandatory pseudo-header";
		}
		// §8.1.2.6: a declared Content-Length must equal the sum of the DATA frame payloads.
		if (invalid == null) {
			String cl = headers.get("content-length");
			if (cl != null) {
				long declared;
				try {
					declared = Long.parseLong(cl.trim());
				} catch (NumberFormatException nfe) {
					declared = -1;
				}
				if (declared != stream.requestBody.size()) {
					invalid = "content-length (" + cl + ") != body length " + stream.requestBody.size();
				}
			}
		}
		if (invalid != null) {
			malformed(stream, invalid);
			return;
		}

		if (authority != null && !headers.containsKey("host")) {
			headers.put("host", authority);
		}

		if (logger.isDebugEnabled()) {
			logger.debug("Dispatching stream={} method={} path={} headers={}", stream.streamId, method, path, headers);
		}
		Http2Request request = new Http2Request(method, path, headers, stream.requestBody.toByteArray());
		Http2Response response = new Http2Response(protocol, this, stream);
		stream.request = request;
		stream.response = response;

		try {
			RequestHandler rh = protocol.getApplication().getHandler(request);
			boolean isAsyncOrOffloaded = HttpRequestDispatcher.dispatch(rh, request, response);
			if (!isAsyncOrOffloaded) {
				response.finish();
			}
		} catch (Exception e) {
			logger.error("Error dispatching request", e);
			try {
				response.setStatusCode(500);
				response.finish();
			} catch (Exception ignored) {
			}
		}
	}

	public void sendHeaders(int streamId, List<Hpack.HeaderField> headers, boolean endStream) {
		if (logger.isDebugEnabled()) {
			logger.debug("sendHeaders: streamId={}, headers={}, endStream={}", streamId, headers, endStream);
		}
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			hpackWriter.writeHeaders(baos, headers);
			byte[] payload = baos.toByteArray();
			int offset = 0;
			boolean first = true;
			while (offset < payload.length || first) {
				int toSend = Math.min(payload.length - offset, maxFrameSize);
				int chunkStart = offset;
				offset += toSend;
				boolean last = offset == payload.length;
				int type = first ? Http2Frame.TYPE_HEADERS : Http2Frame.TYPE_CONTINUATION;
				int flags = 0;
				if (first && endStream) {
					flags |= Http2Frame.FLAG_END_STREAM;
				}
				if (last) {
					flags |= Http2Frame.FLAG_END_HEADERS;
				}
				writeFrameDirect(type, flags, streamId, payload, chunkStart, toSend);
				first = false;
			}
			if (endStream) {
				Http2Stream stream = streams.get(streamId);
				if (stream != null) {
					stream.setState(Http2Stream.STATE_CLOSED);
					streams.remove(streamId);
				}
			}
		} catch (IOException e) {
			logger.error("Error sending headers", e);
			protocol.closeChannel(channel);
		}
	}

	public void sendData(int streamId, byte[] data, boolean endStream) {
		PendingData pending = new PendingData(streamId, data, endStream);
		pendingDataQueue.add(pending);
		flushPendingData();
	}

	private void flushPendingData() {
		Iterator<PendingData> iterator = pendingDataQueue.iterator();
		while (iterator.hasNext()) {
			PendingData pending = iterator.next();
			Http2Stream stream = streams.get(pending.streamId);
			if (stream == null || stream.isClosed()) {
				iterator.remove();
				continue;
			}
			// Drain this stream as far as the flow-control windows allow, emitting as many
			// max-frame-size DATA frames as fit. Sending only a single frame per call (the old bug)
			// stalled any response larger than SETTINGS_MAX_FRAME_SIZE: the rest sat in the queue with
			// nothing to re-trigger a flush, since the client's window was not yet full.
			boolean done = false;
			while (true) {
				int remaining = pending.data.length - pending.offset;
				int limit = Math.min(Math.min(stream.outboundWindowSize, connectionOutboundWindowSize), maxFrameSize);
				if (remaining > 0 && limit <= 0) {
					break; // flow-controlled: wait for a WINDOW_UPDATE to resume
				}
				int toSend = remaining > 0 ? Math.min(remaining, limit) : 0;
				int chunkStart = pending.offset;
				pending.offset += toSend;
				done = (pending.offset == pending.data.length);
				boolean last = pending.endStream && done;
				int flags = last ? Http2Frame.FLAG_END_STREAM : 0;

				stream.outboundWindowSize -= toSend;
				connectionOutboundWindowSize -= toSend;

				writeFrameDirect(Http2Frame.TYPE_DATA, flags, pending.streamId, pending.data, chunkStart, toSend);
				if (last) {
					stream.setState(Http2Stream.STATE_CLOSED);
					streams.remove(pending.streamId);
				}
				if (done) {
					break; // whole body (incl. any END_STREAM) sent
				}
			}
			if (done) {
				iterator.remove();
			}
		}
	}

	// Reused per-connection 9-byte frame-header scratch (only ever touched on this connection's I/O-loop
	// thread). Lets writeFrameDirect emit the header straight into writeBuffer with no per-frame array.
	private final byte[] frameHeaderScratch = new byte[9];

	private void writeFrame(int type, int flags, int streamId, byte[] payload) {
		writeFrameDirect(type, flags, streamId, payload, 0, payload == null ? 0 : payload.length);
	}

	/**
	 * Writes a frame header followed by {@code src[off..off+len)} straight into the connection write
	 * buffer — no intermediate {@code toBytes()} array and no per-frame payload copy (the DATA and
	 * HEADERS/CONTINUATION hot paths slice directly out of the response payload). One copy total: into
	 * the write buffer.
	 */
	private void writeFrameDirect(int type, int flags, int streamId, byte[] src, int off, int len) {
		if (logger.isDebugEnabled()) {
			logger.debug("Writing frame: type={}, flags={}, streamId={}, len={}", type, flags, streamId, len);
		}
		byte[] h = frameHeaderScratch;
		h[0] = (byte) ((len >>> 16) & 0xFF);
		h[1] = (byte) ((len >>> 8) & 0xFF);
		h[2] = (byte) (len & 0xFF);
		h[3] = (byte) (type & 0xFF);
		h[4] = (byte) (flags & 0xFF);
		h[5] = (byte) ((streamId >>> 24) & 0x7F);
		h[6] = (byte) ((streamId >>> 16) & 0xFF);
		h[7] = (byte) ((streamId >>> 8) & 0xFF);
		h[8] = (byte) (streamId & 0xFF);
		writeBuffer.put(h, 0, 9);
		if (src != null && len > 0) {
			writeBuffer.put(src, off, len);
		}
		tryWrite();
	}

	private void tryWrite() {
		writeBuffer.flip();
		if (writeBuffer.hasRemaining()) {
			try {
				protocol.write(channel, writeBuffer.getByteBuffer());
			} catch (IOException e) {
				logger.error("IOException during write", e);
				protocol.closeChannel(channel);
				return;
			}
		}
		if (writeBuffer.hasRemaining()) {
			writeBuffer.compact();
			key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
		} else {
			writeBuffer.clear();
			key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
		}
	}

	private byte[] intToBytes(int val) {
		return new byte[] {
			(byte) ((val >>> 24) & 0xFF),
			(byte) ((val >>> 16) & 0xFF),
			(byte) ((val >>> 8) & 0xFF),
			(byte) (val & 0xFF)
		};
	}

	/**
	 * Raises a connection error (RFC 7540 §5.4.1): emits a {@code GOAWAY} carrying the last
	 * client-initiated stream id we processed and {@code errorCode}, then tears the connection down via
	 * the single cleanup point. The GOAWAY is written best-effort before the close so a conformant peer
	 * learns why; if the socket can't take it, the close still happens. Always returns {@code false} so
	 * callers can {@code return connectionError(...)} from a {@code boolean} method or
	 * {@code if (...) { connectionError(...); return; }} uniformly.
	 */
	private boolean connectionError(int errorCode, String reason) {
		if (logger.isDebugEnabled()) {
			logger.debug("HTTP/2 connection error {} ({}): {}", errorCode, reason, channel);
		}
		byte[] payload = new byte[8];
		int lastId = maxClientStreamId;
		payload[0] = (byte) ((lastId >>> 24) & 0x7F);
		payload[1] = (byte) ((lastId >>> 16) & 0xFF);
		payload[2] = (byte) ((lastId >>> 8) & 0xFF);
		payload[3] = (byte) (lastId & 0xFF);
		payload[4] = (byte) ((errorCode >>> 24) & 0xFF);
		payload[5] = (byte) ((errorCode >>> 16) & 0xFF);
		payload[6] = (byte) ((errorCode >>> 8) & 0xFF);
		payload[7] = (byte) (errorCode & 0xFF);
		try {
			writeFrame(Http2Frame.TYPE_GOAWAY, 0, 0, payload);
		} catch (RuntimeException flushFailed) {
			logger.debug("Failed to flush GOAWAY before close", flushFailed);
		}
		protocol.closeChannel(channel);
		return false;
	}

	/**
	 * Raises a stream error (RFC 7540 §5.4.2): emits a {@code RST_STREAM} with {@code errorCode} for the
	 * given stream and drops it. The connection itself stays open.
	 */
	private void streamError(int streamId, int errorCode) {
		writeFrame(Http2Frame.TYPE_RST_STREAM, 0, streamId, intToBytes(errorCode));
		Http2Stream s = streams.remove(streamId);
		if (s != null) {
			s.setState(Http2Stream.STATE_CLOSED);
		}
	}

	/** A malformed request (RFC 7540 §8.1.2.6) → stream error of type PROTOCOL_ERROR. Returns void so
	 *  callers can {@code return malformed(...)} from the void dispatch path. */
	private void malformed(Http2Stream stream, String reason) {
		if (logger.isDebugEnabled()) {
			logger.debug("malformed HTTP/2 request on stream {}: {}", stream.streamId, reason);
		}
		streamError(stream.streamId, PROTOCOL_ERROR);
	}

	/** Connection-specific (hop-by-hop) header fields that RFC 7540 §8.1.2.2 forbids in HTTP/2.
	 *  Names are compared lower-cased (as stored by {@link Hpack.HeaderField}). */
	private static boolean isConnectionSpecificHeader(String lowerName) {
		switch (lowerName) {
			case "connection": case "keep-alive": case "proxy-connection":
			case "transfer-encoding": case "upgrade":
				return true;
			default:
				return false;
		}
	}
}
