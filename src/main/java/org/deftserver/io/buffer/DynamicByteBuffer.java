package org.deftserver.io.buffer;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class DynamicByteBuffer {
	
	private final static Logger logger = LoggerFactory.getLogger(DynamicByteBuffer.class);

	private ByteBuffer backend;

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
	 */
	public long prepend(String data) {
		byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
		int headerLen = bytes.length;
		int bodyLen = backend.position();
		ensureCapacity(headerLen, true);
		
		// Shift existing body bytes to the right by headerLen
		System.arraycopy(backend.array(), 0, backend.array(), headerLen, bodyLen);
		
		// Copy header bytes to the beginning
		System.arraycopy(bytes, 0, backend.array(), 0, headerLen);
		
		// Update position
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
	
	private void ensureCapacity(int size) {
		ensureCapacity(size, false);
	}

	/**
	 * Ensures that its safe to append size data to backend. 
	 * @param size The size of the data that is about to be appended.
	 * @param exact If true, reallocates to exactly the required size. If false, reallocates with 1.5x padding.
	 */
	private void ensureCapacity(int size, boolean exact) {
		int remaining = backend.remaining();
		if (size > remaining) {
			logger.debug("allocating new DynamicByteBuffer, old capacity {}: ", backend.capacity());
			int missing = size - remaining;
			int newSize = exact ? (backend.capacity() + missing) : (int) ((backend.capacity() + missing) * 1.5);
			reallocate(newSize);
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
