package org.deftserver.io.buffer;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

public class DynamicByteBufferTest {

	private DynamicByteBuffer dbb;
	private static final int INITIAL_CAPACITY = 10;	// bytes
	
	@Before
	public void allocation() {
		this.dbb = DynamicByteBuffer.allocate(INITIAL_CAPACITY);
	}
	
	@Test
	public void testAllocation() {
		assertInternalState(INITIAL_CAPACITY, 0, INITIAL_CAPACITY, INITIAL_CAPACITY);
	}
	
	private void assertInternalState(int expectedCapacity, int expectedPosition, int expectedLimit, int arrayLength) {
		assertEquals(expectedCapacity, dbb.capacity());
		assertEquals(expectedPosition, dbb.position());
		assertEquals(expectedLimit, dbb.limit());
		assertEquals(arrayLength, dbb.array().length);
	}
	
	@Test
	public void testNoReallactionPut() {
		byte[] data = new byte[] {'q', 'w', 'e', 'r', 't', 'y'};
		dbb.put(data);
		assertInternalState(INITIAL_CAPACITY, data.length, INITIAL_CAPACITY, INITIAL_CAPACITY);
	}
	
	@Test
	public void testReallocationTriggeredPut() {
		byte[] data = new byte[] {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A'};
		dbb.put(data);
		assertInternalState(16, 11, 16, 16);
		
		dbb.put(data);
		assertInternalState(33, 22, 33, 33);
		
		dbb.put(data);
		dbb.put(data);
		assertInternalState(66, 44, 66, 66);
	}
	
	@Test
	public void testPrepend() {
		byte[] data = new byte[] {'[', 'q', 'w', 'e', 'r', 't', 'y', ']'};
		dbb.put(data);
		
		String initial = "|HTTP/1.1 200 OK|";
		int initialLength = initial.length();
		dbb.prepend(initial);
		
		int expectedCapacity = data.length + initialLength;
		int expectedPosition = data.length + initialLength;
		int expectedLimit 	 = data.length + initialLength;
		int arrayLength 	 = data.length + initialLength;
		assertInternalState(expectedCapacity, expectedPosition, expectedLimit, arrayLength);
		
		dbb.put(data);
		expectedCapacity = 49;	
		expectedPosition += data.length;
		expectedLimit 	 = 49;
		arrayLength 	 = 49;
		assertInternalState(expectedCapacity, expectedPosition, expectedLimit, arrayLength);
	}

	@Test
	public void testPrependEncodesHeaderAsLatin1NotUtf8() {
		// prepend(String) carries the HTTP status line + headers, which are ISO-8859-1 (one octet per
		// char). A non-ASCII header octet (here U+00E9 'é') must serialize to the single byte 0xE9,
		// not the two-byte UTF-8 sequence 0xC3 0xA9 — otherwise the header is corrupted on the wire.
		String header = "X-File: é\r\n"; // 'é' as a Latin-1 header octet
		dbb.prepend(header);
		byte[] backing = dbb.array();
		int eIndex = "X-File: ".length();
		assertEquals("non-ASCII header octet must be a single Latin-1 byte 0xE9",
			(byte) 0xE9, backing[eIndex]);
		// And the total prepended length must be the octet count (10), not the UTF-8 byte count (11).
		assertEquals(header.length(), dbb.position());
	}

	@Test
	public void testDataIntegrityAcrossManyReallocations() {
		// Append a known pattern in many small chunks, forcing repeated growth, and verify every
		// byte survives the reallocate() array copies intact (guards against off-by-one / lost data).
		java.io.ByteArrayOutputStream expected = new java.io.ByteArrayOutputStream();
		for (int i = 0; i < 500; i++) {
			byte[] chunk = ("chunk-" + i + ";").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
			dbb.put(chunk);
			expected.write(chunk, 0, chunk.length);
		}
		byte[] exp = expected.toByteArray();
		assertEquals(exp.length, dbb.position());
		dbb.flip();
		byte[] actual = new byte[dbb.limit()];
		dbb.getByteBuffer().get(actual);
		org.junit.Assert.assertArrayEquals(exp, actual);
	}

	@Test
	public void testReallocationLimit() {
		byte[] data = "0123456".getBytes();
		dbb.put(data);
		assertInternalState(10, 7, 10, 10);
		dbb.put("0123456789".getBytes());
		assertInternalState(25, 17, 25, 25);
	}
	
}
