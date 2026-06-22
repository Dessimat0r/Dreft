package org.deftserver.web.http.http2;

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.util.List;

import org.junit.Test;

/**
 * HPACK Huffman-decoding conformance against the authoritative RFC 7541 test vectors (Appendix C).
 * The hand-rolled integration clients in {@code Http2SystemTest} send header values as raw literals
 * (Huffman flag clear), so they never exercised this path; real HTTP/2 clients (the JDK's, browsers,
 * curl) Huffman-encode header values by default. These vectors pin the decoder to the spec, including
 * the end-of-string padding rule (RFC 7541 §5.2: up to 7 trailing EOS-prefix bits).
 */
public class HpackHuffmanTest {

	private static byte[] hex(String s) {
		s = s.replaceAll("\\s", "");
		byte[] out = new byte[s.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	/** RFC 7541 C.4.1: ":authority: www.example.com" with a Huffman-coded value. */
	@Test
	public void decodesHuffmanAuthorityValue() throws Exception {
		// 41 = literal w/ incremental indexing, name index 1 (:authority)
		// 8c = Huffman flag + length 12, then the Huffman-coded "www.example.com"
		byte[] block = hex("41 8c f1e3 c2e5 f23a 6ba0 ab90 f4ff");
		List<Hpack.HeaderField> fields = new Hpack.Reader(4096).readHeaders(ByteBuffer.wrap(block));
		assertEquals(1, fields.size());
		assertEquals(":authority", fields.get(0).name);
		assertEquals("www.example.com", fields.get(0).value);
	}

	/** RFC 7541 C.4.2: "cache-control: no-cache" with a Huffman-coded value. */
	@Test
	public void decodesHuffmanNoCacheValue() throws Exception {
		// 58 = literal w/ incremental indexing, name index 24 (cache-control)
		// 86 = Huffman flag + length 6, then the Huffman-coded "no-cache"
		byte[] block = hex("58 86 a8eb 1064 9cbf");
		List<Hpack.HeaderField> fields = new Hpack.Reader(4096).readHeaders(ByteBuffer.wrap(block));
		assertEquals(1, fields.size());
		assertEquals("cache-control", fields.get(0).name);
		assertEquals("no-cache", fields.get(0).value);
	}

	/** RFC 7541 C.4.3: "custom-key: custom-value" — both name and value Huffman-coded. */
	@Test
	public void decodesHuffmanCustomNameAndValue() throws Exception {
		// 40 = literal w/ incremental indexing, new name
		// 88 = Huffman + len 8  -> "custom-key"  (25a849e95ba97d7f)
		// 89 = Huffman + len 9  -> "custom-value"(25a849e95bb8e8b4bf)
		byte[] block = hex("40 88 25a8 49e9 5ba9 7d7f 89 25a8 49e9 5bb8 e8b4 bf");
		List<Hpack.HeaderField> fields = new Hpack.Reader(4096).readHeaders(ByteBuffer.wrap(block));
		assertEquals(1, fields.size());
		assertEquals("custom-key", fields.get(0).name);
		assertEquals("custom-value", fields.get(0).value);
	}
}
