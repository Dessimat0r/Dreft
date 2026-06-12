package org.deftserver.web.http.http2;

import java.io.IOException;
import java.nio.ByteBuffer;

public final class Http2Frame {

	public static final int TYPE_DATA = 0x0;
	public static final int TYPE_HEADERS = 0x1;
	public static final int TYPE_PRIORITY = 0x2;
	public static final int TYPE_RST_STREAM = 0x3;
	public static final int TYPE_SETTINGS = 0x4;
	public static final int TYPE_PUSH_PROMISE = 0x5;
	public static final int TYPE_PING = 0x6;
	public static final int TYPE_GOAWAY = 0x7;
	public static final int TYPE_WINDOW_UPDATE = 0x8;
	public static final int TYPE_CONTINUATION = 0x9;

	public static final int FLAG_ACK = 0x01;
	public static final int FLAG_END_STREAM = 0x01;
	public static final int FLAG_END_HEADERS = 0x04;
	public static final int FLAG_PADDED = 0x08;
	public static final int FLAG_PRIORITY = 0x20;

	public final int length;
	public final int type;
	public final int flags;
	public final int streamId;
	public final byte[] payload;

	public Http2Frame(int length, int type, int flags, int streamId, byte[] payload) {
		this.length = length;
		this.type = type;
		this.flags = flags;
		this.streamId = streamId;
		this.payload = payload;
	}

	public static Http2Frame read(ByteBuffer buffer, int maxFrameSize) throws IOException {
		if (buffer.remaining() < 9) {
			return null;
		}
		buffer.mark();
		int len1 = buffer.get() & 0xFF;
		int len2 = buffer.get() & 0xFF;
		int len3 = buffer.get() & 0xFF;
		int length = (len1 << 16) | (len2 << 8) | len3;

		if (length > maxFrameSize) {
			throw new IOException("Frame size limit exceeded: " + length);
		}

		int type = buffer.get() & 0xFF;
		int flags = buffer.get() & 0xFF;
		int streamId = buffer.getInt() & 0x7FFFFFFF;

		if (buffer.remaining() < length) {
			buffer.reset();
			return null;
		}

		byte[] payload = new byte[length];
		buffer.get(payload);
		return new Http2Frame(length, type, flags, streamId, payload);
	}

	public static void write(ByteBuffer out, int type, int flags, int streamId, byte[] payload) {
		int length = payload == null ? 0 : payload.length;
		out.put((byte) ((length >>> 16) & 0xFF));
		out.put((byte) ((length >>> 8) & 0xFF));
		out.put((byte) (length & 0xFF));
		out.put((byte) (type & 0xFF));
		out.put((byte) (flags & 0xFF));
		out.putInt(streamId & 0x7FFFFFFF);
		if (payload != null) {
			out.put(payload);
		}
	}

	public static byte[] toBytes(int type, int flags, int streamId, byte[] payload) {
		int length = payload == null ? 0 : payload.length;
		byte[] frame = new byte[9 + length];
		frame[0] = (byte) ((length >>> 16) & 0xFF);
		frame[1] = (byte) ((length >>> 8) & 0xFF);
		frame[2] = (byte) (length & 0xFF);
		frame[3] = (byte) (type & 0xFF);
		frame[4] = (byte) (flags & 0xFF);
		frame[5] = (byte) ((streamId >>> 24) & 0x7F);
		frame[6] = (byte) ((streamId >>> 16) & 0xFF);
		frame[7] = (byte) ((streamId >>> 8) & 0xFF);
		frame[8] = (byte) (streamId & 0xFF);
		if (payload != null) {
			System.arraycopy(payload, 0, frame, 9, length);
		}
		return frame;
	}
}
