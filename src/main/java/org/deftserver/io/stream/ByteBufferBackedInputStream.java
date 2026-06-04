package org.deftserver.io.stream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/** Adapts a {@link ByteBuffer} as a read-only {@link InputStream} over its remaining bytes (used to
 *  feed buffer slices to readers, e.g. parsing multipart part headers). Consumes the buffer's position. */
public class ByteBufferBackedInputStream extends InputStream {

	ByteBuffer buf;

	/** Wraps the buffer; reads consume from its current position to its limit. */
	public ByteBufferBackedInputStream(ByteBuffer buf) {
		this.buf = buf;
	}

	/** Reads one byte (0–255), or -1 at end of buffer. */
	public int read() {
		if (!buf.hasRemaining()) {
			return -1;
		}
		return buf.get() & 0xFF;
	}

	/** Reads up to {@code len} bytes into {@code bytes} at {@code off}; returns the count, or -1 at end. */
	public int read(byte[] bytes, int off, int len) throws IOException {
		if (!buf.hasRemaining()) {
			return -1;
		}

		len = Math.min(len, buf.remaining());
		buf.get(bytes, off, len);
		return len;
	}
}