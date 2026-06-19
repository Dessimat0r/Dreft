package org.deftserver.web.http.http2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Hpack {

	public static class HeaderField {
		public final String name;
		public final String value;
		public final int size;

		private static String toLowerCaseIfNecessary(String s) {
			int len = s.length();
			for (int i = 0; i < len; i++) {
				char c = s.charAt(i);
				if (c >= 'A' && c <= 'Z') {
					return s.toLowerCase(Locale.ROOT);
				}
			}
			return s;
		}

		public HeaderField(String name, String value) {
			this.name = toLowerCaseIfNecessary(name);
			this.value = value;
			this.size = name.length() + value.length() + 32;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof HeaderField)) return false;
			HeaderField that = (HeaderField) o;
			return name.equals(that.name) && value.equals(that.value);
		}

		@Override
		public int hashCode() {
			return 31 * name.hashCode() + value.hashCode();
		}

		@Override
		public String toString() {
			return name + ": " + value;
		}
	}

	public static final HeaderField[] STATIC_TABLE = {
		new HeaderField(":authority", ""),
		new HeaderField(":method", "GET"),
		new HeaderField(":method", "POST"),
		new HeaderField(":path", "/"),
		new HeaderField(":path", "/index.html"),
		new HeaderField(":scheme", "http"),
		new HeaderField(":scheme", "https"),
		new HeaderField(":status", "200"),
		new HeaderField(":status", "204"),
		new HeaderField(":status", "206"),
		new HeaderField(":status", "304"),
		new HeaderField(":status", "400"),
		new HeaderField(":status", "404"),
		new HeaderField(":status", "500"),
		new HeaderField("accept-charset", ""),
		new HeaderField("accept-encoding", "gzip, deflate"),
		new HeaderField("accept-language", ""),
		new HeaderField("accept-ranges", ""),
		new HeaderField("accept", ""),
		new HeaderField("access-control-allow-origin", ""),
		new HeaderField("age", ""),
		new HeaderField("allow", ""),
		new HeaderField("authorization", ""),
		new HeaderField("cache-control", ""),
		new HeaderField("content-disposition", ""),
		new HeaderField("content-encoding", ""),
		new HeaderField("content-language", ""),
		new HeaderField("content-length", ""),
		new HeaderField("content-location", ""),
		new HeaderField("content-range", ""),
		new HeaderField("content-type", ""),
		new HeaderField("cookie", ""),
		new HeaderField("date", ""),
		new HeaderField("etag", ""),
		new HeaderField("expect", ""),
		new HeaderField("expires", ""),
		new HeaderField("from", ""),
		new HeaderField("host", ""),
		new HeaderField("if-match", ""),
		new HeaderField("if-modified-since", ""),
		new HeaderField("if-none-match", ""),
		new HeaderField("if-range", ""),
		new HeaderField("if-unmodified-since", ""),
		new HeaderField("last-modified", ""),
		new HeaderField("link", ""),
		new HeaderField("location", ""),
		new HeaderField("max-forwards", ""),
		new HeaderField("proxy-authenticate", ""),
		new HeaderField("proxy-authorization", ""),
		new HeaderField("range", ""),
		new HeaderField("referer", ""),
		new HeaderField("refresh", ""),
		new HeaderField("retry-after", ""),
		new HeaderField("server", ""),
		new HeaderField("set-cookie", ""),
		new HeaderField("strict-transport-security", ""),
		new HeaderField("transfer-encoding", ""),
		new HeaderField("user-agent", ""),
		new HeaderField("vary", ""),
		new HeaderField("via", ""),
		new HeaderField("www-authenticate", "")
	};

	private static final Map<String, Integer> STATIC_NAME_INDEXES;
	private static final Map<HeaderField, Integer> STATIC_EXACT_INDEXES;
	static {
		Map<String, Integer> map = new HashMap<>();
		for (int i = 0; i < STATIC_TABLE.length; i++) {
			String name = STATIC_TABLE[i].name;
			if (!map.containsKey(name)) {
				map.put(name, i + 1);
			}
		}
		STATIC_NAME_INDEXES = Collections.unmodifiableMap(map);

		Map<HeaderField, Integer> map2 = new HashMap<>();
		for (int i = 0; i < STATIC_TABLE.length; i++) {
			map2.put(STATIC_TABLE[i], i + 1);
		}
		STATIC_EXACT_INDEXES = Collections.unmodifiableMap(map2);
	}

	public static final class Reader {
		private final List<HeaderField> decodedHeaders = new ArrayList<>();
		private final List<HeaderField> dynamicTable = new ArrayList<>();
		private final int settingsMaxTableSize;
		private int dynamicTableLimit;
		private int currentTableSize = 0;

		public Reader(int maxTableSize) {
			this.settingsMaxTableSize = maxTableSize;
			this.dynamicTableLimit = maxTableSize;
		}

		public List<HeaderField> readHeaders(ByteBuffer buffer) throws IOException {
			decodedHeaders.clear();
			while (buffer.hasRemaining()) {
				if (decodedHeaders.size() >= 100) {
					throw new IOException("Too many headers (max 100)");
				}
				int b = buffer.get() & 0xFF;
				if (b == 0x80) {
					throw new IOException("Invalid index 0 in HPACK");
				} else if ((b & 0x80) == 0x80) {
					// Indexed Header Field
					int index = readInt(buffer, b, 0x7F);
					addIndexedHeader(index);
				} else if (b == 0x40) {
					// Literal Header Field with Incremental Indexing - New Name
					String name = readString(buffer);
					String value = readString(buffer);
					insertDynamic(new HeaderField(name, value));
				} else if ((b & 0x40) == 0x40) {
					// Literal Header Field with Incremental Indexing - Indexed Name
					int index = readInt(buffer, b, 0x3F);
					String name = getField(index).name;
					String value = readString(buffer);
					insertDynamic(new HeaderField(name, value));
				} else if ((b & 0x20) == 0x20) {
					// Dynamic Table Size Update
					dynamicTableLimit = readInt(buffer, b, 0x1F);
					if (dynamicTableLimit < 0 || dynamicTableLimit > settingsMaxTableSize) {
						throw new IOException("Invalid dynamic table size update: " + dynamicTableLimit);
					}
					shrinkDynamicTable();
				} else if (b == 0x10 || b == 0) {
					// Literal Header Field without Indexing - New Name
					String name = readString(buffer);
					String value = readString(buffer);
					decodedHeaders.add(new HeaderField(name, value));
				} else {
					// Literal Header Field without Indexing - Indexed Name
					int index = readInt(buffer, b, 0x0F);
					String name = getField(index).name;
					String value = readString(buffer);
					decodedHeaders.add(new HeaderField(name, value));
				}
			}
			return new ArrayList<>(decodedHeaders);
		}

		private void addIndexedHeader(int index) throws IOException {
			decodedHeaders.add(getField(index));
		}

		private HeaderField getField(int index) throws IOException {
			if (index <= STATIC_TABLE.length) {
				return STATIC_TABLE[index - 1];
			}
			int dynamicIndex = index - STATIC_TABLE.length - 1;
			if (dynamicIndex < 0 || dynamicIndex >= dynamicTable.size()) {
				throw new IOException("Invalid header index: " + index);
			}
			return dynamicTable.get(dynamicIndex);
		}

		private void insertDynamic(HeaderField entry) {
			decodedHeaders.add(entry);
			if (entry.size > dynamicTableLimit) {
				dynamicTable.clear();
				currentTableSize = 0;
				return;
			}
			dynamicTable.add(0, entry);
			currentTableSize += entry.size;
			shrinkDynamicTable();
		}

		private void shrinkDynamicTable() {
			while (currentTableSize > dynamicTableLimit && !dynamicTable.isEmpty()) {
				HeaderField evicted = dynamicTable.remove(dynamicTable.size() - 1);
				currentTableSize -= evicted.size;
			}
		}

		private int readInt(ByteBuffer buffer, int firstByte, int prefixMask) throws IOException {
			int prefix = firstByte & prefixMask;
			if (prefix < prefixMask) {
				return prefix;
			}
			int result = prefixMask;
			int shift = 0;
			while (true) {
				if (!buffer.hasRemaining()) {
					throw new IOException("Truncated integer in HPACK");
				}
				int b = buffer.get() & 0xFF;
				if (shift >= 28) {
					throw new IOException("Integer overflow in HPACK");
				}
				if ((b & 0x80) != 0) {
					result += (b & 0x7F) << shift;
					shift += 7;
				} else {
					result += b << shift;
					break;
				}
			}
			return result;
		}

		private static String getCachedString(byte[] raw) {
			int len = raw.length;
			if (len == 3) {
				if (raw[0] == 'G' && raw[1] == 'E' && raw[2] == 'T') return "GET";
				if (raw[0] == 'P' && raw[1] == 'U' && raw[2] == 'T') return "PUT";
				if (raw[0] == '2' && raw[1] == '0' && raw[2] == '0') return "200";
				if (raw[0] == '3' && raw[1] == '0' && raw[2] == '2') return "302";
				if (raw[0] == '4' && raw[1] == '0' && raw[2] == '4') return "404";
				if (raw[0] == '5' && raw[1] == '0' && raw[2] == '0') return "500";
			} else if (len == 4) {
				if (raw[0] == 'P' && raw[1] == 'O' && raw[2] == 'S' && raw[3] == 'T') return "POST";
				if (raw[0] == 'h' && raw[1] == 't' && raw[2] == 't' && raw[3] == 'p') return "http";
			} else if (len == 5) {
				if (raw[0] == 'h' && raw[1] == 't' && raw[2] == 't' && raw[3] == 'p' && raw[4] == 's') return "https";
				if (raw[0] == ':' && raw[1] == 'p' && raw[2] == 'a' && raw[3] == 't' && raw[4] == 'h') return ":path";
			} else if (len == 7) {
				if (raw[0] == ':' && raw[1] == 'm' && raw[2] == 'e' && raw[3] == 't' && raw[4] == 'h' && raw[5] == 'o' && raw[6] == 'd') return ":method";
				if (raw[0] == ':' && raw[1] == 's' && raw[2] == 'c' && raw[3] == 'h' && raw[4] == 'e' && raw[5] == 'm' && raw[6] == 'e') return ":scheme";
				if (raw[0] == ':' && raw[1] == 's' && raw[2] == 't' && raw[3] == 'a' && raw[4] == 't' && raw[5] == 'u' && raw[6] == 's') return ":status";
			} else if (len == 10) {
				if (raw[0] == ':' && raw[1] == 'a' && raw[2] == 'u' && raw[3] == 't' && raw[4] == 'h' && raw[5] == 'o' && raw[6] == 'r' && raw[7] == 'i' && raw[8] == 't' && raw[9] == 'y') return ":authority";
			}
			return null;
		}

		private String readString(ByteBuffer buffer) throws IOException {
			if (!buffer.hasRemaining()) {
				throw new IOException("Truncated string in HPACK");
			}
			int firstByte = buffer.get() & 0xFF;
			boolean huffman = (firstByte & 0x80) == 0x80;
			int length = readInt(buffer, firstByte, 0x7F);
			if (length < 0 || length > 65536) {
				throw new IOException("Invalid string length in HPACK: " + length);
			}
			if (buffer.remaining() < length) {
				throw new IOException("Truncated string value in HPACK");
			}
			byte[] raw = new byte[length];
			buffer.get(raw);
			if (huffman) {
				raw = Huffman.get().decode(raw);
			}
			String cached = getCachedString(raw);
			if (cached != null) {
				return cached;
			}
			return new String(raw, StandardCharsets.UTF_8);
		}
	}

	public static final class Writer {
		private final List<HeaderField> dynamicTable = new ArrayList<>();
		private final int dynamicTableLimit;
		private int currentTableSize = 0;

		public Writer(int maxTableSize) {
			this.dynamicTableLimit = maxTableSize;
		}

		public void writeHeaders(ByteArrayOutputStream out, List<HeaderField> headers) throws IOException {
			for (HeaderField header : headers) {
				String name = header.name;
				String value = header.value;
				int exactIndex = -1;
				int nameIndex = -1;

				Integer staticExact = STATIC_EXACT_INDEXES.get(header);
				if (staticExact != null) {
					exactIndex = staticExact;
				} else {
					Integer staticName = STATIC_NAME_INDEXES.get(name);
					if (staticName != null) {
						nameIndex = staticName;
					}
				}

				// Search dynamic table
				if (exactIndex == -1) {
					for (int i = 0; i < dynamicTable.size(); i++) {
						HeaderField dynamicEntry = dynamicTable.get(i);
						if (dynamicEntry.name.equals(name)) {
							if (nameIndex == -1) {
								nameIndex = STATIC_TABLE.length + i + 1;
							}
							if (dynamicEntry.value.equals(value)) {
								exactIndex = STATIC_TABLE.length + i + 1;
								break;
							}
						}
					}
				}

				if (exactIndex != -1) {
					// Indexed Header Field
					writeInt(out, exactIndex, 0x7F, 0x80);
				} else if (nameIndex == -1) {
					// Literal Header Field with Incremental Indexing - New Name
					out.write(0x40);
					writeString(out, name);
					writeString(out, value);
					insertDynamic(header);
				} else {
					// Literal Header Field with Incremental Indexing - Indexed Name
					writeInt(out, nameIndex, 0x3F, 0x40);
					writeString(out, value);
					insertDynamic(header);
				}
			}
		}

		private void insertDynamic(HeaderField entry) {
			if (entry.size > dynamicTableLimit) {
				dynamicTable.clear();
				currentTableSize = 0;
				return;
			}
			dynamicTable.add(0, entry);
			currentTableSize += entry.size;
			while (currentTableSize > dynamicTableLimit && !dynamicTable.isEmpty()) {
				HeaderField evicted = dynamicTable.remove(dynamicTable.size() - 1);
				currentTableSize -= evicted.size;
			}
		}

		private void writeInt(ByteArrayOutputStream out, int value, int prefixMask, int bits) {
			if (value < prefixMask) {
				out.write(bits | value);
				return;
			}
			out.write(bits | prefixMask);
			value -= prefixMask;
			while (value >= 0x80) {
				out.write((value & 0x7F) | 0x80);
				value >>>= 7;
			}
			out.write(value);
		}

		private void writeString(ByteArrayOutputStream out, String str) throws IOException {
			boolean isAscii = true;
			int len = str.length();
			for (int i = 0; i < len; i++) {
				if (str.charAt(i) >= 128) {
					isAscii = false;
					break;
				}
			}
			if (isAscii) {
				writeInt(out, len, 0x7F, 0x00);
				for (int i = 0; i < len; i++) {
					out.write((byte) str.charAt(i));
				}
			} else {
				byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
				writeInt(out, bytes.length, 0x7F, 0x00); // No Huffman encoding for outputs (simpler & compatible)
				out.write(bytes);
			}
		}
	}

	private static final class Huffman {
		private static final int[] CODES = {
			0x1ff8, 0x7fffd8, 0xfffffe2, 0xfffffe3, 0xfffffe4, 0xfffffe5, 0xfffffe6, 0xfffffe7, 0xfffffe8,
			0xffffea, 0x3ffffffc, 0xfffffe9, 0xfffffea, 0x3ffffffd, 0xfffffeb, 0xfffffec, 0xfffffed,
			0xfffffee, 0xfffffef, 0xffffff0, 0xffffff1, 0xffffff2, 0x3ffffffe, 0xffffff3, 0xffffff4,
			0xffffff5, 0xffffff6, 0xffffff7, 0xffffff8, 0xffffff9, 0xffffffa, 0xffffffb, 0x14, 0x3f8,
			0x3f9, 0xffa, 0x1ff9, 0x15, 0xf8, 0x7fa, 0x3fa, 0x3fb, 0xf9, 0x7fb, 0xfa, 0x16, 0x17, 0x18,
			0x0, 0x1, 0x2, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x5c, 0xfb, 0x7ffc, 0x20, 0xffb,
			0x3fc, 0x1ffa, 0x21, 0x5d, 0x5e, 0x5f, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
			0x69, 0x6a, 0x6b, 0x6c, 0x6d, 0x6e, 0x6f, 0x70, 0x71, 0x72, 0xfc, 0x73, 0xfd, 0x1ffb, 0x7fff0,
			0x1ffc, 0x3ffc, 0x22, 0x7ffd, 0x3, 0x23, 0x4, 0x24, 0x5, 0x25, 0x26, 0x27, 0x6, 0x74, 0x75,
			0x28, 0x29, 0x2a, 0x7, 0x2b, 0x76, 0x2c, 0x8, 0x9, 0x2d, 0x77, 0x78, 0x79, 0x7a, 0x7b, 0x7ffe,
			0x7fc, 0x3ffd, 0x1ffd, 0xffffffc, 0xfffe6, 0x3fffd2, 0xfffe7, 0xfffe8, 0x3fffd3, 0x3fffd4,
			0x3fffd5, 0x7fffd9, 0x3fffd6, 0x7fffda, 0x7fffdb, 0x7fffdc, 0x7fffdd, 0x7fffde, 0xffffeb,
			0x7fffdf, 0xffffec, 0xffffed, 0x3fffd7, 0x7fffe0, 0xffffee, 0x7fffe1, 0x7fffe2, 0x7fffe3,
			0x7fffe4, 0x1fffdc, 0x3fffd8, 0x7fffe5, 0x3fffd9, 0x7fffe6, 0x7fffe7, 0xffffef, 0x3fffda,
			0x1fffdd, 0xfffe9, 0x3fffdb, 0x3fffdc, 0x7fffe8, 0x7fffe9, 0x1fffde, 0x7fffea, 0x3fffdd,
			0x3fffde, 0xfffff0, 0x1fffdf, 0x3fffdf, 0x7fffeb, 0x7fffec, 0x1fffe0, 0x1fffe1, 0x3fffe0,
			0x1fffe2, 0x7fffed, 0x3fffe1, 0x7fffee, 0x7fffef, 0xfffea, 0x3fffe2, 0x3fffe3, 0x3fffe4,
			0x7ffff0, 0x3fffe5, 0x3fffe6, 0x7ffff1, 0x3ffffe0, 0x3ffffe1, 0xfffeb, 0x7fff1, 0x3fffe7,
			0x7ffff2, 0x3fffe8, 0x1ffffec, 0x3ffffe2, 0x3ffffe3, 0x3ffffe4, 0x7ffffde, 0x7ffffdf,
			0x3ffffe5, 0xfffff1, 0x1ffffed, 0x7fff2, 0x1fffe3, 0x3ffffe6, 0x7ffffe0, 0x7ffffe1, 0x3ffffe7,
			0x7ffffe2, 0xfffff2, 0x1fffe4, 0x1fffe5, 0x3ffffe8, 0x3ffffe9, 0xffffffd, 0x7ffffe3,
			0x7ffffe4, 0x7ffffe5, 0xfffec, 0xfffff3, 0xfffed, 0x1fffe6, 0x3fffe9, 0x1fffe7, 0x1fffe8,
			0x7ffff3, 0x3fffea, 0x3fffeb, 0x1ffffee, 0x1ffffef, 0xfffff4, 0xfffff5, 0x3ffffea, 0x7ffff4,
			0x3ffffeb, 0x7ffffe6, 0x3ffffec, 0x3ffffed, 0x7ffffe7, 0x7ffffe8, 0x7ffffe9, 0x7ffffea,
			0x7ffffeb, 0xffffffe, 0x7ffffec, 0x7ffffed, 0x7ffffee, 0x7ffffef, 0x7fffff0, 0x3ffffee
		};

		private static final byte[] CODE_LENGTHS = {
			13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 30,
			28, 28, 28, 28, 28, 28, 28, 28, 28, 6, 10, 10, 12, 13, 6, 8, 11, 10, 10, 8, 11, 8, 6, 6, 6, 5,
			5, 5, 6, 6, 6, 6, 6, 6, 6, 7, 8, 15, 6, 12, 10, 13, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
			7, 7, 7, 7, 7, 7, 7, 7, 8, 7, 8, 13, 19, 13, 14, 6, 15, 5, 6, 5, 6, 5, 6, 6, 6, 5, 7, 7, 6,
			6, 6, 5, 6, 7, 6, 5, 5, 6, 7, 7, 7, 7, 7, 15, 11, 14, 13, 28, 20, 22, 20, 20, 22, 22, 22, 23,
			22, 23, 23, 23, 23, 23, 24, 23, 24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23, 22, 23, 23,
			24, 22, 21, 20, 22, 22, 23, 23, 21, 23, 22, 22, 24, 21, 22, 23, 23, 21, 21, 22, 21, 23, 22,
			23, 23, 20, 22, 22, 22, 23, 22, 22, 23, 26, 26, 20, 19, 22, 23, 22, 25, 26, 26, 26, 27, 27,
			26, 24, 25, 19, 21, 26, 27, 27, 26, 27, 24, 21, 21, 26, 26, 28, 27, 27, 27, 20, 24, 20, 21,
			22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23, 26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27,
			27, 27, 27, 27, 26
		};

		private static final Huffman INSTANCE = new Huffman();

		public static Huffman get() {
			return INSTANCE;
		}

		private static final class Node {
			int symbol = -1;
			Node left;
			Node right;
		}

		private final Node root = new Node();

		private Huffman() {
			for (int i = 0; i < CODE_LENGTHS.length; i++) {
				addCode(i, CODES[i], CODE_LENGTHS[i]);
			}
		}

		private void addCode(int symbol, int code, int length) {
			Node current = root;
			for (int i = length - 1; i >= 0; i--) {
				int bit = (code >>> i) & 1;
				if (bit == 0) {
					if (current.left == null) {
						current.left = new Node();
					}
					current = current.left;
				} else {
					if (current.right == null) {
						current.right = new Node();
					}
					current = current.right;
				}
			}
			current.symbol = symbol;
		}

		private static final ThreadLocal<byte[]> decodeBuffer = ThreadLocal.withInitial(() -> new byte[65536]);

		public byte[] decode(byte[] data) throws IOException {
			byte[] out = decodeBuffer.get();
			int outPos = 0;
			Node current = root;
			for (byte b : data) {
				for (int i = 7; i >= 0; i--) {
					int bit = (b >>> i) & 1;
					current = (bit == 0) ? current.left : current.right;
					if (current == null) {
						throw new IOException("Invalid Huffman code sequence");
					}
					if (current.symbol != -1) {
						if (current.symbol == 256) {
							byte[] result = new byte[outPos];
							System.arraycopy(out, 0, result, 0, outPos);
							return result;
						}
						if (outPos >= 65536) {
							throw new IOException("Huffman decoded output too large");
						}
						out[outPos++] = (byte) current.symbol;
						current = root;
					}
				}
			}
			byte[] result = new byte[outPos];
			System.arraycopy(out, 0, result, 0, outPos);
			return result;
		}
	}
}
