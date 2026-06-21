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

	private final HttpProtocol protocol;
	private final SelectionKey key;
	private final SocketChannel channel;
	private final Hpack.Reader hpackReader = new Hpack.Reader(4096);
	private final Hpack.Writer hpackWriter = new Hpack.Writer(4096);
	private final Map<Integer, Http2Stream> streams = new HashMap<>();

	private final DynamicByteBuffer inboundBuffer = DynamicByteBuffer.allocate(65536);
	private final DynamicByteBuffer writeBuffer = DynamicByteBuffer.allocate(65536);

	private boolean prefaceReceived = false;
	private int maxFrameSize = 16384;
	private int connectionOutboundWindowSize = 65535;
	private int initialStreamOutboundWindowSize = 65535;
	private int connectionInboundWindowSize = 65535;
	private int initialStreamInboundWindowSize = 65535;

	private int currentHeaderStreamId = -1;
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
							logger.warn("Preface mismatch, closing channel");
							protocol.closeChannel(channel);
							return;
						}
					}
					prefaceReceived = true;
					logger.debug("Preface matched, sending initial settings");
					sendInitialSettings();
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
	}

	private void processFrame(Http2Frame frame) throws IOException {
		if (currentHeaderStreamId != -1 && frame.type != Http2Frame.TYPE_CONTINUATION) {
			logger.warn("Expected CONTINUATION frame but got type={}", frame.type);
			protocol.closeChannel(channel);
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
				logger.warn("Control frame rate limit exceeded, closing channel");
				protocol.closeChannel(channel);
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
					logger.warn("RST_STREAM limit exceeded, closing channel");
					protocol.closeChannel(channel);
					return;
				}
				handleRstStream(frame);
				break;
			case Http2Frame.TYPE_GOAWAY:
				if (frame.payload.length < 8) {
					logger.warn("GOAWAY frame length must be at least 8 bytes");
					protocol.closeChannel(channel);
					return;
				}
				logger.debug("Received GOAWAY, closing channel");
				protocol.closeChannel(channel);
				break;
			case Http2Frame.TYPE_PRIORITY:
				if (frame.payload.length != 5) {
					logger.warn("PRIORITY frame length must be exactly 5 bytes");
					protocol.closeChannel(channel);
					return;
				}
				break;
		}
	}

	private void handleSettings(Http2Frame frame) throws IOException {
		if ((frame.flags & Http2Frame.FLAG_ACK) != 0) {
			if (frame.payload.length != 0) {
				logger.warn("SETTINGS ACK frame must have length 0");
				protocol.closeChannel(channel);
				return;
			}
			logger.debug("Received SETTINGS ACK");
			return;
		}
		if (frame.payload.length % 6 != 0) {
			logger.warn("SETTINGS frame length must be a multiple of 6");
			protocol.closeChannel(channel);
			return;
		}
		ByteBuffer buf = ByteBuffer.wrap(frame.payload);
		while (buf.remaining() >= 6) {
			int id = buf.getShort() & 0xFFFF;
			int value = buf.getInt();
			if (id == 2) {
				if (value != 0 && value != 1) {
					logger.warn("Invalid SETTINGS_ENABLE_PUSH: {}", value);
					protocol.closeChannel(channel);
					return;
				}
			} else if (id == 4) {
				if (value < 0) {
					logger.warn("Invalid SETTINGS_INITIAL_WINDOW_SIZE: {}", value);
					protocol.closeChannel(channel);
					return;
				}
				int diff = value - initialStreamOutboundWindowSize;
				for (Http2Stream stream : streams.values()) {
					stream.outboundWindowSize += diff;
				}
				initialStreamOutboundWindowSize = value;
			} else if (id == 5) {
				if (value < 16384 || value > 16777215) {
					logger.warn("Invalid SETTINGS_MAX_FRAME_SIZE: {}", value);
					protocol.closeChannel(channel);
					return;
				}
				maxFrameSize = value;
			}
		}
		writeFrame(Http2Frame.TYPE_SETTINGS, Http2Frame.FLAG_ACK, 0, null);
	}

	private void handleHeaders(Http2Frame frame) throws IOException {
		if (frame.streamId % 2 != 0 && frame.streamId <= maxClientStreamId) {
			logger.warn("Stream ID {} is not greater than max seen {}", frame.streamId, maxClientStreamId);
			protocol.closeChannel(channel);
			return;
		}
		if (frame.streamId % 2 != 0) {
			maxClientStreamId = frame.streamId;
		}
		int offset = 0;
		int padLength = 0;
		if ((frame.flags & Http2Frame.FLAG_PADDED) != 0) {
			padLength = frame.payload[offset++] & 0xFF;
		}
		if ((frame.flags & Http2Frame.FLAG_PRIORITY) != 0) {
			offset += 5;
		}
		int headerBlockLength = frame.payload.length - offset - padLength;
		if (headerBlockLength < 0) {
			protocol.closeChannel(channel);
			return;
		}
		if (headerBlockLength > 65536) {
			logger.warn("Headers block too large: {} bytes", headerBlockLength);
			protocol.closeChannel(channel);
			return;
		}
		headerBlockBuilder.reset();
		headerBlockBuilder.write(frame.payload, offset, headerBlockLength);
		currentHeaderStreamId = frame.streamId;

		if ((frame.flags & Http2Frame.FLAG_END_HEADERS) != 0) {
			finishHeaders(frame.streamId, (frame.flags & Http2Frame.FLAG_END_STREAM) != 0);
		}
	}

	private void handleContinuation(Http2Frame frame) throws IOException {
		if (frame.streamId != currentHeaderStreamId) {
			protocol.closeChannel(channel);
			return;
		}
		if (headerBlockBuilder.size() + frame.payload.length > 65536) {
			logger.warn("Headers block size exceeded 64 KiB cap during CONTINUATION");
			protocol.closeChannel(channel);
			return;
		}
		headerBlockBuilder.write(frame.payload);
		if ((frame.flags & Http2Frame.FLAG_END_HEADERS) != 0) {
			finishHeaders(frame.streamId, false);
		}
	}

	private void finishHeaders(int streamId, boolean endStream) throws IOException {
		currentHeaderStreamId = -1;
		byte[] block = headerBlockBuilder.toByteArray();
		headerBlockBuilder.reset();

		List<Hpack.HeaderField> fields = hpackReader.readHeaders(ByteBuffer.wrap(block));
		if (!streams.containsKey(streamId) && streamId % 2 != 0) {
			if (streams.size() >= 100) {
				logger.warn("Exceeded max concurrent streams limit (100), refusing stream={}", streamId);
				writeFrame(Http2Frame.TYPE_RST_STREAM, 0, streamId, intToBytes(7));
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
		Http2Stream stream = streams.get(frame.streamId);
		if (stream == null) {
			protocol.closeChannel(channel);
			return;
		}
		int offset = 0;
		int padLength = 0;
		if ((frame.flags & Http2Frame.FLAG_PADDED) != 0) {
			padLength = frame.payload[offset++] & 0xFF;
		}
		int dataLength = frame.payload.length - offset - padLength;
		if (dataLength < 0) {
			protocol.closeChannel(channel);
			return;
		}
		if (stream.requestBody.size() + dataLength > 16777216) {
			logger.warn("Payload size exceeds maximum allowed limit (16 MiB) on stream {}", frame.streamId);
			writeFrame(Http2Frame.TYPE_RST_STREAM, 0, frame.streamId, intToBytes(11));
			protocol.closeChannel(channel);
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
			logger.warn("WINDOW_UPDATE frame length must be exactly 4 bytes");
			protocol.closeChannel(channel);
			return;
		}
		int increment = ByteBuffer.wrap(frame.payload).getInt() & 0x7FFFFFFF;
		if (increment == 0) {
			logger.warn("WINDOW_UPDATE increment must be non-zero");
			protocol.closeChannel(channel);
			return;
		}
		if (frame.streamId == 0) {
			long newSize = (long) connectionOutboundWindowSize + increment;
			if (newSize > Integer.MAX_VALUE) {
				logger.warn("Connection window overflow, closing channel");
				protocol.closeChannel(channel);
				return;
			}
			connectionOutboundWindowSize = (int) newSize;
		} else {
			Http2Stream stream = streams.get(frame.streamId);
			if (stream != null) {
				long newSize = (long) stream.outboundWindowSize + increment;
				if (newSize > Integer.MAX_VALUE) {
					logger.warn("Stream window overflow, closing stream={}", frame.streamId);
					writeFrame(Http2Frame.TYPE_RST_STREAM, 0, frame.streamId, intToBytes(3));
					stream.setState(Http2Stream.STATE_CLOSED);
					streams.remove(frame.streamId);
					return;
				}
				stream.outboundWindowSize = (int) newSize;
			}
		}
		flushPendingData();
	}

	private void handlePing(Http2Frame frame) throws IOException {
		if (frame.payload.length != 8) {
			logger.warn("PING frame length must be exactly 8 bytes");
			protocol.closeChannel(channel);
			return;
		}
		if ((frame.flags & Http2Frame.FLAG_ACK) == 0) {
			writeFrame(Http2Frame.TYPE_PING, Http2Frame.FLAG_ACK, 0, frame.payload);
		}
	}

	private void handleRstStream(Http2Frame frame) throws IOException {
		if (frame.payload.length != 4) {
			logger.warn("RST_STREAM frame length must be exactly 4 bytes");
			protocol.closeChannel(channel);
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
		String authority = null;
		Map<String, String> headers = new HashMap<>();

		for (Hpack.HeaderField field : stream.requestHeaders) {
			String name = field.name;
			if (name.equals(":method")) {
				method = field.value;
			} else if (name.equals(":path")) {
				path = field.value;
			} else if (name.equals(":authority")) {
				authority = field.value;
			} else if (name.startsWith(":")) {
				continue;
			} else {
				headers.put(name, field.value);
			}
		}

		if (authority != null && !headers.containsKey("host")) {
			headers.put("host", authority);
		}

		if (method == null || path == null) {
			logger.warn("Missing pseudo-headers: method={}, path={}", method, path);
			writeFrame(Http2Frame.TYPE_RST_STREAM, 0, stream.streamId, intToBytes(1));
			return;
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
				byte[] chunk = new byte[toSend];
				System.arraycopy(payload, offset, chunk, 0, toSend);
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
				writeFrame(type, flags, streamId, chunk);
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
			int availableStream = stream.outboundWindowSize;
			int availableConn = connectionOutboundWindowSize;
			int limit = Math.min(availableStream, availableConn);
			limit = Math.min(limit, maxFrameSize);
			if (limit <= 0 && pending.data.length > 0) {
				continue;
			}
			int toSend = Math.min(pending.data.length - pending.offset, limit);
			byte[] chunk = new byte[toSend];
			System.arraycopy(pending.data, pending.offset, chunk, 0, toSend);
			pending.offset += toSend;
			boolean last = pending.endStream && (pending.offset == pending.data.length);
			int flags = last ? Http2Frame.FLAG_END_STREAM : 0;

			stream.outboundWindowSize -= toSend;
			connectionOutboundWindowSize -= toSend;

			writeFrame(Http2Frame.TYPE_DATA, flags, pending.streamId, chunk);
			if (last) {
				stream.setState(Http2Stream.STATE_CLOSED);
				streams.remove(pending.streamId);
			}
			if (pending.offset == pending.data.length) {
				iterator.remove();
			}
		}
	}

	private void writeFrame(int type, int flags, int streamId, byte[] payload) {
		if (logger.isDebugEnabled()) {
			logger.debug("Writing frame: type={}, flags={}, streamId={}, len={}", type, flags, streamId, payload == null ? 0 : payload.length);
		}
		byte[] frameBytes = Http2Frame.toBytes(type, flags, streamId, payload);
		writeBuffer.put(frameBytes);
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
}
