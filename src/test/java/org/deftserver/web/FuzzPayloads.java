package org.deftserver.web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * A rich, deliberately-chaotic generator of (mostly malformed) HTTP request payloads for adversarial
 * traffic testing. The goal is maximum uncertainty: structural malformations, byte-level corruption
 * of otherwise-valid requests, framing-confusion attacks, resource-amplification attempts, line-
 * ending abuse, encoding tricks, protocol confusion, pipelined mixes, and pure noise. Every payload
 * must leave the server crash-free — that is the invariant the callers assert.
 */
public final class FuzzPayloads {

	private FuzzPayloads() { }

	private static final byte[] ISO(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }

	/** A handful of well-formed requests used as a base for byte-level mutation strategies. */
	private static final String[] VALID = {
		"GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
		"POST /post HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello",
		"POST /post HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n3\r\nabc\r\n0\r\n\r\n",
		"GET /ok?a=1&b=2 HTTP/1.1\r\nHost: localhost\r\nCookie: s=1; t=2\r\nConnection: close\r\n\r\n",
		"HEAD /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
	};

	private static final String[] VERBS = {"GET", "POST", "PUT", "HEAD", "DELETE", "OPTIONS", "TRACE",
		"CONNECT", "PATCH", "FOO", "", "GET GET", "g e t", "\u00C9CHO", "GETGETGETGET",
		"P\r\nET / HTTP/1.1", "\u0000GET", "GET\t/x HTTP/1.1", "\r\nGET"};
	private static final String[] VERSIONS = {"HTTP/1.1", "HTTP/1.0", "HTTP/9.9", "HTTP/1", "HTTP/2.0",
		"ICY/1", "RTSP/1.0", "", "http/1.1", "HTTP/1.1.1", "HTTP/0.9", "HTTP/3.0",
		"HTTP/1.1\r", "HTTP/1.1\r\nHost: evil"};
	private static final String[] PATHS = {"/", "/ok", "/post", "/../../etc/passwd", "/%", "/%zz",
		"/a%00b", "/" + "x".repeat(9000), "/a?b=c&d", "*", "//evil", "/%2e%2e%2f%2e%2e%2f",
		"/", "/\r\n", "/ ", "/a b c", "/" + "/".repeat(500), "/?" + "k=v&".repeat(5000),
		"/%FF%FE", "/%C0%AE", "/" + "\u0000", "/a?redirect=http://x?y=1&z=2",
		"/a?x=1&x=2&x=3", "/a?x[]=1&x[]=2", "/a?user[name]=Chris",
		"/a?" + "a".repeat(12000), "/a#fragment", "\r\n/ HTTP/1.1\r\n"};

	// --- Strategies (≈35 — was 20) ---

	/** Builds one random adversarial payload. */
	public static byte[] random(Random rnd) {
		return switch (rnd.nextInt(35)) {
			case 0 -> { byte[] p = new byte[rnd.nextInt(4096)]; rnd.nextBytes(p); yield p; }
			case 1 -> randomishRequest(rnd);
			case 2 -> ISO("GET / HTTP/1.1\r\nHost: localhost\r\nX-Big: " + "A".repeat(70000) + "\r\n\r\n");
			case 3 -> garbageChunked(rnd);
			case 4 -> ISO("POST /post HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + rnd.nextInt(100)
				+ "\r\nContent-Length: " + rnd.nextInt(100) + "\r\nTransfer-Encoding: chunked\r\n\r\njunk");
			case 5 -> ISO("GET / HTTP/1.1\r\nHost: localho"); // truncated mid-headers
			case 6 -> ISO("GET / HTTP/1.1\nHost: localhost\n\nbody"); // bare-LF
			case 7 -> ISO("POST /post HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
				+ (rnd.nextBoolean() ? "-5" : "99999999999999999999") + "\r\n\r\nx");
			case 8 -> ISO("POST /post HTTP/1.1\r\nHost: localhost\r\n"
				+ "Content-Type: multipart/form-data; boundary=xyz\r\nContent-Length: 20\r\n\r\n--xyz\r\nbroken");
			case 9 -> mutateBytes(rnd);
			case 10 -> headerFlood(rnd);
			case 11 -> ISO("GET / HTTP/1.1\r\nHost: localhost\r\nCookie: " + "k=v; ".repeat(8000) + "\r\n\r\n");
			case 12 -> ISO("GET /?" + "a[b][c][d][e][f][g][h]=1&".repeat(2000) + " HTTP/1.1\r\nHost: x\r\n\r\n");
			case 13 -> pipelined(rnd);
			case 14 -> crlfAbuse(rnd);
			case 15 -> ISO(pick(rnd, VERBS) + " " + pick(rnd, PATHS) + " " + pick(rnd, VERSIONS)
				+ "\r\nHost: localhost\r\n\r\n");
			case 16 -> ISO("GET / HTTP/1.1\r\nHost: localhost\r\n"
				+ "Transfer-Encoding: " + pick(rnd, new String[]{"chunked", "gzip", "gzip, chunked",
					"chunked, gzip", "CHUNKED", "chunked, chunked", "identity", "deflate"}) + "\r\n\r\n0\r\n\r\n");
			case 17 -> { byte[] head = ISO("POST /post HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10000\r\n\r\n");
				byte[] junk = new byte[rnd.nextInt(2000)]; rnd.nextBytes(junk); yield concat(head, junk); }
			case 18 -> ISO("GET /" + nul(rnd) + " HTTP/1.1\r\nHost: localhost\r\nX-\u0007Ctrl: a\bb\r\n\r\n");
			case 19 -> { byte[] junk = new byte[rnd.nextInt(64)]; rnd.nextBytes(junk);
				yield concat(ISO(pick(rnd, VALID)), junk); }

			// --- New strategies (20–34) ---
			case 20 -> postUrlencodedFlood(rnd);
			case 21 -> multipartAbuse(rnd);
			case 22 -> http09Style(rnd);
			case 23 -> rangeFuzzing(rnd);
			case 24 -> connectionHeaderAttacks(rnd);
			case 25 -> hiddenSmuggling(rnd);
			case 26 -> massHeaderDupes(rnd);
			case 27 -> protocolConfusion(rnd);
			case 28 -> bomAndEncodingMarkerAttacks(rnd);
			case 29 -> hugeRequestLine(rnd);
			case 30 -> expectAndTeAbuse(rnd);
			case 31 -> whitespaceStorm(rnd);
			case 32 -> spfNulGrenades(rnd);   // SP/NUL byte insertion at every possible position
			case 33 -> zeroByteBody(rnd);
			default -> doubleCrlfSwitchAttack(rnd);
		};
	}

	// --- New strategy implementations ---

	/** Massive urlencoded POST body with extreme nesting, many params, and oversized values. */
	private static byte[] postUrlencodedFlood(Random rnd) {
		StringBuilder b = new StringBuilder("POST /post HTTP/1.1\r\nHost: localhost\r\n"
			+ "Content-Type: application/x-www-form-urlencoded\r\n");
		int bodyLen = 1024 + rnd.nextInt(16 * 1024);
		StringBuilder body = new StringBuilder(bodyLen);
		for (int i = 0; body.length() < bodyLen; i++) {
			if (i > 0) body.append('&');
			if (rnd.nextInt(3) == 0) {
				// deeply nested bracket param
				int depth = 1 + rnd.nextInt(8);
				body.append("a");
				for (int d = 0; d < depth; d++) body.append("[x]");
				body.append('=').append(rnd.nextInt(999));
			} else if (rnd.nextInt(3) == 0) {
				// large value
				body.append("k").append(i).append('=').append("V".repeat(100 + rnd.nextInt(4096)));
			} else {
				// empty / flag param
				body.append("p").append(i).append(rnd.nextBoolean() ? "=" : "");
			}
		}
		b.append("Content-Length: ").append(body.length()).append("\r\nConnection: close\r\n\r\n");
		b.append(body);
		return ISO(b.toString());
	}

	/** Multipart body designed to hit every weird case: false boundaries, path-like filenames,
	 *  binary in text parts, quoted/missing boundaries, missing headers. */
	private static byte[] multipartAbuse(Random rnd) {
		String boundary = "----For" + rnd.nextInt(9999);
		String target = "/post";
		StringBuilder b = new StringBuilder("POST " + target + " HTTP/1.1\r\nHost: localhost\r\n"
			+ "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n");
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		int parts = 1 + rnd.nextInt(6);
		for (int p = 0; p < parts; p++) {
			body.writeBytes(ISO("--" + boundary + "\r\n"));
			// sometimes omit/malform Content-Disposition
			if (rnd.nextInt(5) != 0) {
				body.writeBytes(ISO("Content-Disposition: form-data; name=\"field" + p + "\""));
				if (rnd.nextBoolean()) {
					// add filename — sometimes path-like, sometimes ../../evil
					String fn = pick(rnd, new String[]{"test.txt", "", "../../secret.php",
						"C:\\fakepath\\photo.jpg", "caf\u00E9.jpg", "a;b.txt",
						"\"a\\\"b\".txt"});
					body.writeBytes(ISO("; filename=\"" + fn + "\""));
				}
				body.writeBytes(ISO("\r\n"));
			}
			if (rnd.nextBoolean()) body.writeBytes(ISO("Content-Type: text/plain\r\n"));
			body.writeBytes(ISO("\r\n"));
			if (rnd.nextInt(3) == 0) {
				// binary-like content
				byte[] bin = new byte[1 + rnd.nextInt(256)];
				rnd.nextBytes(bin);
				body.writeBytes(bin);
			} else {
				body.writeBytes(ISO("part-body-" + p));
			}
			body.writeBytes(ISO("\r\n"));
		}
		body.writeBytes(ISO("--" + boundary + (rnd.nextBoolean() ? "--" : "\r\n") + "\r\n"));
		byte[] bodyBytes = body.toByteArray();
		b.append("Content-Length: ").append(bodyBytes.length).append("\r\nConnection: close\r\n\r\n");
		return concat(ISO(b.toString()), bodyBytes);
	}

	/** HTTP/0.9 style — no headers or just a single line. */
	private static byte[] http09Style(Random rnd) {
		return switch (rnd.nextInt(3)) {
			case 0 -> ISO("GET /ok\r\n");
			case 1 -> ISO("GET /\r\n\r\njunk");
			default -> ISO(pick(rnd, VERBS) + " " + pick(rnd, PATHS));
		};
	}

	/** Range header abuse: negative ranges, suffix-only, multi-range, If-Range, out-of-order. */
	private static byte[] rangeFuzzing(Random rnd) {
		String range = pick(rnd, new String[]{
			"bytes=-500", "bytes=500-", "bytes=0-99,200-299",
			"bytes=" + rnd.nextInt(999999) + "-" + rnd.nextInt(999999),
			"bytes=-" + rnd.nextInt(999999),
			"bytes=-0", "bytes=0--1", "bytes=5-3",
			"bytes=-" + Long.MAX_VALUE, "bytes=" + Long.MAX_VALUE + "-",
			"bytes=0-99999999999999999999",
			"bytes=0-1, " + (rnd.nextInt(100) - 50) + "-" + rnd.nextInt(500),
			"bytes=abc-def", "bytes=1-2;q=0.5",
			"bytes=*/999",
		});
		String extra = rnd.nextBoolean() ? "" :
			"If-Range: " + (rnd.nextBoolean() ? "\"abc123\"" : "Thu, 01 Jan 1970 00:00:00 GMT") + "\r\n";
		return ISO("GET /ok HTTP/1.1\r\nHost: localhost\r\nRange: " + range + "\r\n" + extra + "\r\n");
	}

	/** Connection header attacks: fake upgrades, unsupported tokens, TE injection. */
	private static byte[] connectionHeaderAttacks(Random rnd) {
		String conn = pick(rnd, new String[]{
			"close, close, close", "keep-alive, close, TE", "upgrade",
			"keep-alive, keep-alive, close", "close, TE, Upgrade", "TE",
			"keep-alive", "close \t ", " Close ",
			"keep-alive, transfer-encoding", "upgrade, websocket",
			"", "close, " + "x".repeat(500),
		});
		return ISO("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: " + conn + "\r\n\r\n");
	}

	/** Request-smuggling payloads: TE+CL with body that disagrees, obs-fold, double-CRLF tricks. */
	private static byte[] hiddenSmuggling(Random rnd) {
		return switch (rnd.nextInt(4)) {
			case 0 -> ISO("POST /post HTTP/1.1\r\nHost: localhost\r\n"
				+ "Transfer-Encoding: chunked\r\nContent-Length: 6\r\n\r\n"
				+ "0\r\n\r\nGET /admin HTTP/1.1\r\nHost: localhost\r\n\r\n");
			case 1 -> ISO("GET /ok HTTP/1.1\r\nHost: localhost\r\nContent-Length : 5\r\n\r\nabcde");
			case 2 -> ISO("GET /ok HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nTransfer-Encoding : chunked\r\n\r\n"
				+ "0\r\n\r\n");
			default -> ISO("GET /ok HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\n"
				+ "\r\nGET /evil HTTP/1.1\r\nHost: localhost\r\n\r\n");
		};
	}

	/** Many duplicated headers with different values — aiming to stress the parse/merge logic. */
	private static byte[] massHeaderDupes(Random rnd) {
		StringBuilder b = new StringBuilder("GET /ok HTTP/1.1\r\nHost: localhost\r\n");
		int count = 20 + rnd.nextInt(80);
		for (int i = 0; i < count; i++) {
			b.append("X-D").append(rnd.nextInt(5)).append(": ").append(rnd.nextInt(99999)).append("\r\n");
		}
		b.append("\r\n");
		return ISO(b.toString());
	}

	/** Protocol confusion — SMTP, POP3, SIP, RTSP, ICAP, FTP-looking lines. */
	private static byte[] protocolConfusion(Random rnd) {
		return ISO(pick(rnd, new String[]{
			"EHLO mail.example.com\r\n",
			"HELO localhost\r\n",
			"USER bob\r\nPASS secret\r\n",
			"INVITE sip:bob@example.com SIP/2.0\r\n",
			"OPTIONS rtsp://example.com/media.mp4 RTSP/1.0\r\n",
			"REQMOD icap://server/ ICAP/1.0\r\n",
			"USER anonymous\r\n",
			"SSH-2.0-OpenSSH_8.9\r\n",
			"GET /ok HTTP/1.1\r\nHost: localhost\r\nAccept: */a; q=.2\r\n\r\n",
			"GET\0 /ok HTTP/1.1\r\nHost: localhost\r\n\r\n",
			"\uFEFFGET /ok HTTP/1.1\r\nHost: localhost\r\n\r\n",
		}));
	}

	/** BOM and encoding marker attacks — UTF-8 BOM, UTF-16 BOM, byte-order tricks. */
	private static byte[] bomAndEncodingMarkerAttacks(Random rnd) {
		byte[] bom = pick(rnd, new byte[][]{
			new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
			new byte[]{(byte) 0xFF, (byte) 0xFE},
			new byte[]{(byte) 0xFE, (byte) 0xFF},
			new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x00},
			new byte[]{(byte) 0x00, (byte) 0x00, (byte) 0xFE, (byte) 0xFF},
			new byte[]{},
		});
		byte[] req = ISO("GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\n");
		return concat(bom, req);
	}

	/** Request-line close to the 8 KiB limit, exercising the 414 path. */
	private static byte[] hugeRequestLine(Random rnd) {
		String path = "/ok?" + "k=v&".repeat(3800);
		return ISO("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
	}

	/** Expect and TE header abuse. */
	private static byte[] expectAndTeAbuse(Random rnd) {
		String expect = pick(rnd, new String[]{
			"100-continue", "200-ok", "", "100-Continue", " 100-continue ",
			"100-CONTINUE", "100-continue, 200-ok",
		});
		String te = rnd.nextBoolean() ? "" : "\r\nTE: " + pick(rnd, new String[]{
			"chunked", "trailers", "gzip, chunked", "deflate", "compress",
			"chunked, trailers, deflate;q=0.5",
		});
		String conn = rnd.nextBoolean() ? "\r\nConnection: close" : "";
		return ISO("POST /post HTTP/1.1\r\nHost: localhost\r\nExpect: " + expect + te + conn + "\r\nContent-Length: 5\r\n\r\nhello");
	}

	/** Whitespace-only prelude — tons of spaces/TABs/CRLFs before the request line. */
	private static byte[] whitespaceStorm(Random rnd) {
		StringBuilder b = new StringBuilder();
		int n = 1 + rnd.nextInt(100);
		for (int i = 0; i < n; i++) {
			b.append(pick(rnd, new String[]{" ", "\t", "\r\n", "  \t\r\n"}));
		}
		b.append("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
		return ISO(b.toString());
	}

	/** Space and NUL byte grenades at every parse-affecting position. */
	private static byte[] spfNulGrenades(Random rnd) {
		String[] templates = {
			"GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
			"POST /post HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc",
		};
		byte[] base = ISO(pick(rnd, templates));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte b : base) {
			// at each position, sometimes inject a SP, TAB, or NUL before the byte
			if (rnd.nextInt(8) == 0) {
				if (rnd.nextInt(3) == 0) out.write(0x00);
				else if (rnd.nextBoolean()) out.write(0x20);
				else out.write(0x09);
			}
			out.write(b);
		}
		return out.toByteArray();
	}

	/** Valid headers but body of exactly 0 bytes when CL says non-zero, or CL missing. */
	private static byte[] zeroByteBody(Random rnd) {
		return switch (rnd.nextInt(3)) {
			case 0 -> ISO("POST /post HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
			case 1 -> ISO("POST /post HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nConnection: close\r\n\r\n");
			default -> ISO("PUT /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
		};
	}

	/** Double-CRLF switch attack: the body starts with CRLFCRLF looking like end-of-headers. */
	private static byte[] doubleCrlfSwitchAttack(Random rnd) {
		return ISO("POST /post HTTP/1.1\r\nHost: localhost\r\nContent-Length: 40\r\n\r\n"
			+ "\r\n\r\nGET /admin HTTP/1.1\r\nHost: evil\r\n\r\nREALBODY");
	}

	// --- Original helper methods ---

	private static byte[] randomishRequest(Random rnd) {
		StringBuilder sb = new StringBuilder(pick(rnd, VERBS)).append(' ')
			.append(pick(rnd, PATHS)).append(' ').append(pick(rnd, VERSIONS)).append("\r\n");
		int hdrs = rnd.nextInt(30);
		for (int h = 0; h < hdrs; h++) {
			sb.append(rnd.nextBoolean() ? "X-" : "").append(rnd.nextInt(999));
			sb.append(rnd.nextBoolean() ? ": " : ":");          // sometimes no space
			int vlen = rnd.nextInt(60);
			for (int v = 0; v < vlen; v++) sb.append((char) (rnd.nextInt(96) + 31)); // may include DEL/ctrl-ish
			sb.append(rnd.nextBoolean() ? "\r\n" : "\n");        // sometimes bare LF
		}
		if (rnd.nextBoolean()) sb.append("\r\n");
		return ISO(sb.toString());
	}

	private static byte[] garbageChunked(Random rnd) {
		StringBuilder sb = new StringBuilder("POST /post HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n");
		int chunks = rnd.nextInt(12);
		for (int c = 0; c < chunks; c++) {
			// sometimes a valid hex size, sometimes negative/huge/non-hex
			String size = switch (rnd.nextInt(4)) {
				case 0 -> Integer.toHexString(rnd.nextInt(50000));
				case 1 -> "-" + rnd.nextInt(100);
				case 2 -> "zzzz";
				default -> "FFFFFFFFFFFFFFFF";
			};
			sb.append(size);
			if (rnd.nextBoolean()) sb.append(";ext=").append(rnd.nextInt(9));
			sb.append("\r\n");
			int datalen = rnd.nextInt(30);
			for (int d = 0; d < datalen; d++) sb.append((char) (rnd.nextInt(94) + 32));
			sb.append(rnd.nextBoolean() ? "\r\n" : "\n");
		}
		if (rnd.nextBoolean()) sb.append("0\r\n\r\n");
		return ISO(sb.toString());
	}

	private static byte[] headerFlood(Random rnd) {
		StringBuilder sb = new StringBuilder("GET / HTTP/1.1\r\nHost: localhost\r\n");
		int n = 200 + rnd.nextInt(2000);
		for (int i = 0; i < n; i++) sb.append("X-H").append(i).append(": ").append(rnd.nextInt(9)).append("\r\n");
		sb.append("\r\n");
		return ISO(sb.toString());
	}

	private static byte[] pipelined(Random rnd) {
		// Concatenate several requests on one connection (valid + simple garbage). Uses leaf
		// strategies only (never random()) to avoid unbounded self-recursion through this case.
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int n = 2 + rnd.nextInt(4);
		for (int i = 0; i < n; i++) {
			byte[] part = switch (rnd.nextInt(3)) {
				case 0 -> ISO(pick(rnd, VALID));
				case 1 -> mutateBytes(rnd);
				default -> randomishRequest(rnd);
			};
			out.writeBytes(part);
		}
		return out.toByteArray();
	}

	private static byte[] crlfAbuse(Random rnd) {
		String body = "GET / HTTP/1.1XHost: localhostXX";
		String sep = switch (rnd.nextInt(4)) {
			case 0 -> "\r";          // CR only
			case 1 -> "\n";          // LF only
			case 2 -> "\r\n\r\n\r\n"; // extra terminators
			default -> "\r\n";
		};
		return ISO(("\r\n\r\n" /* leading blank lines */ + body.replace("X", sep)));
	}

	/** Takes a valid request and corrupts it: random bit-flips, truncation, or control-char injection. */
	private static byte[] mutateBytes(Random rnd) {
		byte[] b = ISO(pick(rnd, VALID)).clone();
		switch (rnd.nextInt(3)) {
			case 0: { // bit flips
				int flips = 1 + rnd.nextInt(20);
				for (int i = 0; i < flips; i++) b[rnd.nextInt(b.length)] ^= (1 << rnd.nextInt(8));
				return b;
			}
			case 1: { // truncate at a random offset
				int len = rnd.nextInt(b.length + 1);
				byte[] t = new byte[len];
				System.arraycopy(b, 0, t, 0, len);
				return t;
			}
			default: { // inject NUL / control bytes at random positions
				int inj = 1 + rnd.nextInt(10);
				for (int i = 0; i < inj; i++) b[rnd.nextInt(b.length)] = (byte) rnd.nextInt(0x20);
				return b;
			}
		}
	}

	private static String nul(Random rnd) {
		return rnd.nextBoolean() ? "%00" : "";
	}

	static String pick(Random rnd, String[] a) { return a[rnd.nextInt(a.length)]; }
	private static byte[] pick(Random rnd, byte[][] a) { return a[rnd.nextInt(a.length)]; }

	static byte[] concat(byte[] a, byte[] b) {
		byte[] r = new byte[a.length + b.length];
		System.arraycopy(a, 0, r, 0, a.length);
		System.arraycopy(b, 0, r, a.length, b.length);
		return r;
	}
}
