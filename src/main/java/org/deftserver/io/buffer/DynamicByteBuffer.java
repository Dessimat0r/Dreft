package org.deftserver.io.buffer;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class DynamicByteBuffer {
	
	private final static Logger logger = LoggerFactory.getLogger(DynamicByteBuffer.class);

	private ByteBuffer backend;

	/** Wraps an existing backing {@link ByteBuffer}; use {@link #allocate(int)} to create one. */
	private DynamicByteBuffer(ByteBuffer bb) {
		this.backend = bb;
	}
	
	/**
	 * Allocate a new {@code DynamicByteBuffer} that will be using a {@ByteBuffer} internally.
	 * @param capacity initial capacity
	 */
	public static DynamicByteBuffer allocate(int capacity) {
		return new DynamicByteBuffer(ByteBuffer.allocate(capacity));
	}

	/**
	 * Append the data. Will reallocate if needed.
	 */
	public void put(byte[] src) {
		ensureCapacity(src.length);
		backend.put(src);
	}

	/**
	 * Append the data. Will reallocate if needed.
	 */
	public void put(ByteBuffer src) {
		ensureCapacity(src.remaining());
		backend.put(src);
	}	
	
	/**
	 * Prepend the data. Will reallocate if needed.
	 * <p>
	 * Encodes with ISO-8859-1: this is only ever used to prepend the HTTP status line + header block,
	 * and HTTP header field values are Latin-1/obs-text (RFC 9110 §5.5), one octet per character.
	 * Using UTF-8 here would mis-encode a non-ASCII header octet (e.g. a Content-Disposition filename
	 * byte) into a multi-byte sequence, corrupting the header on the wire. (The message body, which
	 * may legitimately be UTF-8, is added separately via {@code write(String)}.)
	 */
	public long prepend(String data) {
		int headerLen = data.length();
		int bodyLen = backend.position();
		ensureCapacity(headerLen, true);
		
		System.arraycopy(backend.array(), 0, backend.array(), headerLen, bodyLen);
		
		byte[] arr = backend.array();
		for (int i = 0; i < headerLen; i++) {
			arr[i] = (byte) data.charAt(i);
		}
		
		backend.position(headerLen + bodyLen);
		return headerLen;
	}

	/**
	 * Prepend raw bytes. Will reallocate if needed.
	 */
	public void prepend(byte[] bytes) {
		int headerLen = bytes.length;
		int bodyLen = backend.position();
		ensureCapacity(headerLen, true);
		
		// Shift existing body bytes to the right by headerLen
		System.arraycopy(backend.array(), 0, backend.array(), headerLen, bodyLen);
		
		// Copy prepended bytes to the beginning
		System.arraycopy(bytes, 0, backend.array(), 0, headerLen);
		
		// Update position
		backend.position(headerLen + bodyLen);
	}
	
	/** Ensures room to append {@code size} bytes, growing with the default 1.5× padding. */
	private void ensureCapacity(int size) {
		ensureCapacity(size, false);
	}

	/**
	 * Ensures that its safe to append size data to backend. 
	 * @param size The size of the data that is about to be appended.
	 * @param exact If true, reallocates to exactly the required size. If false, reallocates with 1.5x padding.
	 */
	/** Largest array the JVM can allocate (a few bytes of header reserved, per common practice). */
	private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

	private void ensureCapacity(int size, boolean exact) {
		int remaining = backend.remaining();
		if (size > remaining) {
			logger.debug("allocating new DynamicByteBuffer, old capacity {}: ", backend.capacity());
			int missing = size - remaining;                       // size >= remaining ⇒ missing >= 0
			// Compute in long: capacity()+missing can approach Integer.MAX_VALUE, and the ×1.5
			// growth factor would otherwise overflow a 32-bit int to a negative value, producing a
			// NegativeArraySizeException (a crash/DoS on a very large response). Clamp to the JVM's
			// max array size; only fail if the *minimum* required size genuinely can't be allocated.
			long required = (long) backend.capacity() + missing;
			long newSize = exact ? required : (long) (required * 1.5);
			if (newSize > MAX_ARRAY_SIZE) {
				newSize = required;                               // ×1.5 overflowed the cap; try exact
				if (newSize > MAX_ARRAY_SIZE) {
					throw new OutOfMemoryError(
						"DynamicByteBuffer cannot grow to " + required + " bytes (exceeds max array size)");
				}
			}
			reallocate((int) newSize);
		}
	}
	
	// Preserves position.
	private void reallocate(int newCapacity) {
		int oldPosition = backend.position();
		byte[] newBuffer = new byte[newCapacity];
		System.arraycopy(backend.array(), 0, newBuffer, 0, backend.position());
		backend = ByteBuffer.wrap(newBuffer);
		backend.position(oldPosition);
		logger.debug("allocated new DynamicByteBuffer, new capacity: {}", backend.capacity());
	}
	
	/**
	 * Returns the {@code ByteBuffer} that is used internally by this {@DynamicByteBufer}.
	 * Changes made to the returned {@code ByteBuffer} will be incur modifications in this {@DynamicByteBufer}.
	 */
	public ByteBuffer getByteBuffer() {
		return backend;
	}

	/**
	 * See {@link ByteBuffer#flip}
	 */
	public void flip() {
		backend.flip();
	}

	/**
	 * See {@link ByteBuffer#limit}
	 */
	public int limit() {
		return backend.limit();
	}

	/**
	 * See {@link ByteBuffer#position}
	 */
	public int position() {
		return backend.position();
	}

	/**
	 * See {@link ByteBuffer#array}
	 *
	 * <p><b>Warning:</b> callers must not modify the returned array — it is the internal
	 * backing storage and mutation corrupts the buffer's state.</p>
	 */	
	public byte[] array() {
		return backend.array();
	}

	/**
	 * See {@link ByteBuffer#capacity}
	 */
	public int capacity() {
		return backend.capacity();
	}

	/**
	 * See {@link ByteBuffer#hasRemaining}
	 */
	public boolean hasRemaining() {
		return backend.hasRemaining();
	}

	/**
	 * See {@link ByteBuffer#compact}
	 */
	public DynamicByteBuffer compact() {
		backend.compact();
		return this;
	}

	/**
	 * See {@link ByteBuffer#clear}
	 */
	public DynamicByteBuffer clear() {
		backend.clear();
		return this;
	}
}
