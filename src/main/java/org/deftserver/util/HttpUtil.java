package org.deftserver.util;

import java.io.File;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.deftserver.web.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class HttpUtil {

	private static final Logger logger = LoggerFactory.getLogger(HttpUtil.class);

	/* MessageDigest are not thread-safe and are expensive to create. 
	 * Do it lazily for each thread that need access to one.*/

	private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

	private static final java.util.concurrent.ConcurrentHashMap<String, java.nio.charset.Charset> charsets = new java.util.concurrent.ConcurrentHashMap<>();
	static {
		charsets.put("UTF-8", java.nio.charset.StandardCharsets.UTF_8);
		charsets.put("ISO-8859-1", java.nio.charset.StandardCharsets.ISO_8859_1);
		charsets.put("US-ASCII", java.nio.charset.StandardCharsets.US_ASCII);
	}

	public static java.nio.charset.Charset getCharset(String name) {
		if (name == null) return java.nio.charset.StandardCharsets.UTF_8;
		if (name.equalsIgnoreCase("UTF-8") || name.equalsIgnoreCase("UTF8")) {
			return java.nio.charset.StandardCharsets.UTF_8;
		}
		if (name.equalsIgnoreCase("ISO-8859-1")) {
			return java.nio.charset.StandardCharsets.ISO_8859_1;
		}
		if (name.equalsIgnoreCase("US-ASCII")) {
			return java.nio.charset.StandardCharsets.US_ASCII;
		}
		return charsets.computeIfAbsent(name.toUpperCase(java.util.Locale.ROOT), java.nio.charset.Charset::forName);
	}
	private static final String _101_SWITCHING_PROTOCOLS 	= "HTTP/1.1 101 Switching Protocols\r\n";
	private static final String _200_OK 		 			= "HTTP/1.1 200 OK\r\n"; 
	private static final String _201_CREATED 		 		= "HTTP/1.1 201 Created\r\n"; 
	private static final String _202_ACCEPTED 		 		= "HTTP/1.1 202 Accepted\r\n"; 
	private static final String _203_NON_AUTHORITATIVE_INFO = "HTTP/1.1 203 Non-Authoritative Information\r\n"; 
	private static final String _204_NO_CONTENT 			= "HTTP/1.1 204 No Content\r\n"; 
	private static final String _205_RESET_CONTENT 			= "HTTP/1.1 205 Reset Content\r\n"; 
	private static final String _206_PARTIAL_CONTENT 		= "HTTP/1.1 206 Partial Content\r\n"; 
	private static final String _300_MULTIPLE_CHOICES 		= "HTTP/1.1 300 Multiple Choices\r\n"; 
	private static final String _301_MOVED_PERMANENTLY 		= "HTTP/1.1 301 Moved Permanently\r\n"; 
	private static final String _302_FOUND		 		= "HTTP/1.1 302 Found\r\n"; 
	private static final String _303_SEE_OTHER		 		= "HTTP/1.1 303 See Other\r\n"; 
	private static final String _304_NOT_MODIFIED 		 	= "HTTP/1.1 304 Not Modified\r\n"; 
	private static final String _305_USE_PROXY 		 		= "HTTP/1.1 305 Use Proxy\r\n"; 
	private static final String _307_TEMPORARY_REDIRECT 	= "HTTP/1.1 307 Temporary Redirect\r\n"; 
	private static final String _308_PERMANENT_REDIRECT 	= "HTTP/1.1 308 Permanent Redirect\r\n"; 
	private static final String _400_BAD_REQUEST			= "HTTP/1.1 400 Bad Request\r\n"; 
	private static final String _401_UNAUTHORIZED			= "HTTP/1.1 401 Unauthorized\r\n"; 
	private static final String _403_FORBIDDEN 			 	= "HTTP/1.1 403 Forbidden\r\n"; 
	private static final String _404_NOT_FOUND 	 			= "HTTP/1.1 404 Not Found\r\n"; 
	private static final String _405_METHOD_NOT_ALLOWED 	= "HTTP/1.1 405 Method Not Allowed\r\n"; 
	private static final String _406_NOT_ACCEPTABLE		 	= "HTTP/1.1 406 Not Acceptable\r\n"; 
	private static final String _407_PROXY_AUTH_REQUIRED	= "HTTP/1.1 407 Proxy Authentication Required\r\n"; 
	private static final String _408_REQUEST_TIMEOUT		= "HTTP/1.1 408 Request Timeout\r\n"; 
	private static final String _409_CONFLICT				= "HTTP/1.1 409 Conflict\r\n"; 
	private static final String _410_GONE					= "HTTP/1.1 410 Gone\r\n"; 
	private static final String _411_LENGTH_REQUIRED		= "HTTP/1.1 411 Length Required\r\n"; 
	private static final String _412_PRECONDITION_FAILED	= "HTTP/1.1 412 Precondition Failed\r\n"; 
	private static final String _413_PAYLOAD_TOO_LARGE		= "HTTP/1.1 413 Payload Too Large\r\n"; 
	private static final String _414_URI_TOO_LONG			= "HTTP/1.1 414 URI Too Long\r\n"; 
	private static final String _415_UNSUPPORTED_MEDIA_TYPE	= "HTTP/1.1 415 Unsupported Media Type\r\n"; 
	private static final String _416_REQUEST_RANGE_NOT_SAT	= "HTTP/1.1 416 Requested Range Not Satisfiable\r\n"; 
	private static final String _417_EXPECTATION_FAILED		= "HTTP/1.1 417 Expectation Failed\r\n";
	private static final String _421_MISDIRECTED_REQUEST	= "HTTP/1.1 421 Misdirected Request\r\n";
	private static final String _425_TOO_EARLY				= "HTTP/1.1 425 Too Early\r\n";
	private static final String _426_UPGRADE_REQUIRED		= "HTTP/1.1 426 Upgrade Required\r\n";
	private static final String _429_TOO_MANY_REQUESTS		= "HTTP/1.1 429 Too Many Requests\r\n";
	private static final String _431_HEADERS_TOO_LARGE		= "HTTP/1.1 431 Request Header Fields Too Large\r\n"; 
	private static final String _500_INTERNAL_SERVER_ERROR	= "HTTP/1.1 500 Internal Server Error\r\n"; 
	private static final String _501_NOT_IMPLEMENTED		= "HTTP/1.1 501 Not Implemented\r\n"; 
	private static final String _502_BAD_GATEWAY			= "HTTP/1.1 502 Bad Gateway\r\n"; 
	private static final String _503_SERVICE_UNAVAILABLE	= "HTTP/1.1 503 Service Unavailable\r\n"; 
	private static final String _504_GATEWAY_TIMEOUT		= "HTTP/1.1 504 Gateway Timeout\r\n"; 
	private static final String _505_VERSION_NOT_SUPPORTED	= "HTTP/1.1 505 HTTP Version Not Supported\r\n"; 

	// e.g. HTTP/1.0 200 OK or HTTP/1.0 404 Not Found (HTTP version + response status code + reason phrase)
	public static String createInitialLine(int statusCode) {

		switch (statusCode) {
		case 101:
			return _101_SWITCHING_PROTOCOLS;
		case 200:
			return _200_OK;
		case 201:
			return _201_CREATED;
		case 202:
			return _202_ACCEPTED;
		case 203:
			return _203_NON_AUTHORITATIVE_INFO;
		case 204:
			return _204_NO_CONTENT;
		case 205:
			return _205_RESET_CONTENT;
		case 206:
			return _206_PARTIAL_CONTENT;
		case 300:
			return _300_MULTIPLE_CHOICES;
		case 301:
			return _301_MOVED_PERMANENTLY;
		case 302:
			return _302_FOUND;
		case 303:
			return _303_SEE_OTHER;
		case 304:
			return _304_NOT_MODIFIED;
		case 305:
			return _305_USE_PROXY;
		case 307:
			return _307_TEMPORARY_REDIRECT;
		case 308:
			return _308_PERMANENT_REDIRECT;
		case 400:
			return _400_BAD_REQUEST;
		case 401:
			return _401_UNAUTHORIZED;
		case 403:
			return _403_FORBIDDEN;
		case 404:
			return _404_NOT_FOUND;
		case 405:
			return _405_METHOD_NOT_ALLOWED;
		case 406:
			return _406_NOT_ACCEPTABLE;
		case 407:
			return _407_PROXY_AUTH_REQUIRED;
		case 408:
			return _408_REQUEST_TIMEOUT;
		case 409:
			return _409_CONFLICT;
		case 410:
			return _410_GONE;
		case 411:
			return _411_LENGTH_REQUIRED;
		case 412:
			return _412_PRECONDITION_FAILED;
		case 413:
			return _413_PAYLOAD_TOO_LARGE;
		case 414:
			return _414_URI_TOO_LONG;
		case 415:
			return _415_UNSUPPORTED_MEDIA_TYPE;
		case 416:
			return _416_REQUEST_RANGE_NOT_SAT;
		case 417:
			return _417_EXPECTATION_FAILED;
		case 421:
			return _421_MISDIRECTED_REQUEST;
		case 425:
			return _425_TOO_EARLY;
		case 426:
			return _426_UPGRADE_REQUIRED;
		case 429:
			return _429_TOO_MANY_REQUESTS;
		case 431:
			return _431_HEADERS_TOO_LARGE;
		case 500:
			return _500_INTERNAL_SERVER_ERROR;
		case 501:
			return _501_NOT_IMPLEMENTED;
		case 502:
			return _502_BAD_GATEWAY;
		case 503:
			return _503_SERVICE_UNAVAILABLE;
		case 504:
			return _504_GATEWAY_TIMEOUT;
		case 505:
			return _505_VERSION_NOT_SUPPORTED;
		default:
			// Never throw while serializing a response — an unknown/extension status code
			// must still yield a valid status line rather than aborting the write and
			// leaking the connection. Emit a syntactically valid line with a best-effort
			// reason phrase.
			if (statusCode < 100 || statusCode > 599) {
				logger.error("Out-of-range HTTP status code {}, coercing to 500", statusCode);
				return _500_INTERNAL_SERVER_ERROR;
			}
			logger.warn("No canned reason phrase for HTTP status code {}; using generic line", statusCode);
			return "HTTP/1.1 " + statusCode + " " + genericReasonPhrase(statusCode) + "\r\n";
		}
	}

	/** A generic reason phrase by status class (Informational/Success/.../Server Error), used as a
	 *  fallback for status codes that have no canned phrase. */
	private static String genericReasonPhrase(int statusCode) {
		switch (statusCode / 100) {
			case 1: return "Informational";
			case 2: return "Success";
			case 3: return "Redirection";
			case 4: return "Client Error";
			case 5: return "Server Error";
			default: return "Unknown";
		}
	}


	/**
	 * Escapes a string for safe inclusion in HTML text/attribute context. Used for any
	 * user-controlled value (e.g. the requested path) reflected into an error page, to prevent
	 * reflected XSS.
	 */
	public static String escapeHtml(String s) {
		if (s == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(s.length() + 16);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '&': sb.append("&amp;"); break;
				case '<': sb.append("&lt;"); break;
				case '>': sb.append("&gt;"); break;
				case '"': sb.append("&quot;"); break;
				case '\'': sb.append("&#x27;"); break;
				default: sb.append(c);
			}
		}
		return sb.toString();
	}

	/** Basic request validity check: rejects the malformed-request sentinel, an HTTP/1.1 request
	 *  without a Host, and any unsupported version (→ routed to a 400). */
	public static boolean verifyRequest(HttpRequest request) {
		// The malformed-request sentinel is never a valid request (it carries dummy contents that
		// happen to look well-formed). Reject it explicitly instead of inferring invalidity from
		// those contents.
		if (request instanceof org.deftserver.web.http.MalFormedHttpRequest) {
			return false;
		}
		String version = request.getVersion();
		boolean requestOk = true;
		if ("HTTP/1.1".equals(version)) {
			String host = request.getHeader("host");
			requestOk = host != null && !host.isBlank();
		}
		if (!"HTTP/1.1".equals(version) && !"HTTP/1.0".equals(version)) {
			requestOk = false;
		}

		return requestOk;
	}


	/** A deterministic ETag (unquoted hex MD5) for the whole byte array. */
	public static String getEtag(byte[] bytes) {
		try {
			return getEtag(MessageDigest.getInstance("MD5"), bytes, 0, bytes.length);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	public static String getEtag(byte[] bytes, int offset, int length) {
		try {
			return getEtag(MessageDigest.getInstance("MD5"), bytes, offset, length);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	public static String getEtag(MessageDigest digestInstance, byte[] bytes, int offset, int length) {
		digestInstance.reset();
		digestInstance.update(bytes, offset, length);
		byte[] digest = digestInstance.digest();
		char[] result = new char[32];
		for (int i = 0; i < 16; i++) {
			int b = digest[i] & 0xFF;
			result[i * 2] = HEX_CHARS[b >>> 4];
			result[i * 2 + 1] = HEX_CHARS[b & 0x0F];
		}
		return new String(result);
	}

	public static String getEtag(File file) {
		try {
			return getEtag(MessageDigest.getInstance("MD5"), file);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	public static String getEtag(MessageDigest digestInstance, File file) {
		if (file == null || !file.exists()) {
			return "";
		}
		String info = file.getAbsolutePath() + ":" + file.lastModified() + ":" + file.length();
		return "\"" + getEtag(digestInstance, info.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, info.length()) + "\"";
	}

	/**
	 * Returns true if the Accept-Encoding header value indicates that gzip is
	 * acceptable (q > 0). Uses a zero-allocation linear scan — no List, no split,
	 * no sort — because this is on the response hot path.
	 */
	public static boolean isGzipAcceptable(String acceptEncoding) {
		if (acceptEncoding == null) return false;
		int len = acceptEncoding.length();
		int i = 0;
		while (i < len) {
			while (i < len && acceptEncoding.charAt(i) == ' ') i++;
			int tokenStart = i;
			while (i < len && acceptEncoding.charAt(i) != ',' && acceptEncoding.charAt(i) != ';') i++;
			int tokenEnd = i;
			while (tokenEnd > tokenStart && acceptEncoding.charAt(tokenEnd - 1) == ' ') tokenEnd--;
			
			int tokenLen = tokenEnd - tokenStart;
			boolean isGzipOrWild = (tokenLen == 1 && acceptEncoding.charAt(tokenStart) == '*') ||
				(tokenLen == 4 && acceptEncoding.regionMatches(true, tokenStart, "gzip", 0, 4));
				
			double q = 1.0;
			while (i < len && acceptEncoding.charAt(i) == ';') {
				i++;
				while (i < len && acceptEncoding.charAt(i) == ' ') i++;
				if (i + 2 < len && acceptEncoding.charAt(i) == 'q' && acceptEncoding.charAt(i + 1) == '=') {
					i += 2;
					while (i < len && acceptEncoding.charAt(i) == ' ') i++;
					int qStart = i;
					while (i < len && acceptEncoding.charAt(i) != ',' && acceptEncoding.charAt(i) != ';') i++;
					int qEnd = i;
					while (qEnd > qStart && acceptEncoding.charAt(qEnd - 1) == ' ') qEnd--;
					try {
						q = Double.parseDouble(acceptEncoding.substring(qStart, qEnd));
					} catch (NumberFormatException e) {
						q = 0.0;
					}
				} else {
					while (i < len && acceptEncoding.charAt(i) != ',' && acceptEncoding.charAt(i) != ';') i++;
				}
			}
			if (isGzipOrWild && q > 0.0) return true;
			if (i < len && acceptEncoding.charAt(i) == ',') i++;
		}
		return false;
	}

	/** Parses an {@code Accept}-family header into the acceptable values ordered by descending
	 *  q-value (a stable sort, so equal-q items keep header order), dropping any {@code q=0} entries. */
	public static java.util.List<String> parseAcceptHeader(String acceptHeader) {
		if (acceptHeader == null || acceptHeader.trim().isEmpty()) {
			return java.util.Collections.emptyList();
		}
		
		class AcceptItem implements Comparable<AcceptItem> {
			final String type;
			final double q;

			AcceptItem(String type, double q) {
				this.type = type.trim();
				this.q = q;
			}

			@Override
			public int compareTo(AcceptItem o) {
				return Double.compare(o.q, this.q); // Descending order
			}
		}

		java.util.List<AcceptItem> items = new java.util.ArrayList<>();
		int len = acceptHeader.length();
		int idx = 0;
		while (idx < len) {
			int nextComma = acceptHeader.indexOf(',', idx);
			int endSegment = (nextComma == -1) ? len : nextComma;
			
			int firstSemi = acceptHeader.indexOf(';', idx);
			int endType = (firstSemi == -1 || firstSemi > endSegment) ? endSegment : firstSemi;
			
			String mediaType = acceptHeader.substring(idx, endType).trim();
			if (!mediaType.isEmpty()) {
				double q = 1.0;
				if (firstSemi != -1 && firstSemi < endSegment) {
					int pIdx = firstSemi + 1;
					while (pIdx < endSegment) {
						int nextSemi = acceptHeader.indexOf(';', pIdx);
						int endParam = (nextSemi == -1 || nextSemi > endSegment) ? endSegment : nextSemi;
						String param = acceptHeader.substring(pIdx, endParam).trim();
						if (param.startsWith("q=")) {
							try {
								q = Double.parseDouble(param.substring(2).trim());
							} catch (NumberFormatException e) {
								q = 0.0;
							}
							if (Double.isNaN(q)) {
								q = 0.0;
							} else if (q > 1.0) {
								q = 1.0;
							} else if (q < 0.0) {
								q = 0.0;
							}
						}
						pIdx = endParam + 1;
					}
				}
				if (q > 0.0) {
					items.add(new AcceptItem(mediaType, q));
				}
			}
			idx = endSegment + 1;
		}
		java.util.Collections.sort(items);

		java.util.List<String> result = new java.util.ArrayList<>();
		for (AcceptItem item : items) {
			result.add(item.type);
		}
		return result;
	}

	private static final boolean BROTLI_SUPPORTED;
	private static final boolean ZSTD_SUPPORTED;

	static {
		// Brotli is intentionally disabled. The bundled `brotjli` 0.1.0 codec emits a stream that is
		// NOT RFC 7932 brotli: the reference `brotli` decoder (and browsers) recover only a few bytes
		// of a multi-KB body from it — it round-trips only with brotjli's own decoder. Advertising or
		// serving `Content-Encoding: br` with it therefore corrupts the response for every real client,
		// and it compresses far worse than gzip/zstd anyway (e.g. 10 KB vs 63 B for the same input).
		// Clients are served the standard gzip/zstd codecs instead. Re-enable only behind a real,
		// RFC 7932-compliant brotli library whose output is verified to decode with a standard decoder.
		BROTLI_SUPPORTED = false;

		boolean zstd;
		try {
			Class.forName("com.github.luben.zstd.ZstdOutputStream");
			zstd = true;
		} catch (ClassNotFoundException e) {
			zstd = false;
		}
		ZSTD_SUPPORTED = zstd;
	}

	public static boolean isBrotliSupported() {
		return BROTLI_SUPPORTED;
	}

	public static boolean isZstdSupported() {
		return ZSTD_SUPPORTED;
	}

	private static class ZstdCodec {
		static void compress(byte[] buf, int offset, int length, java.io.OutputStream out) throws java.io.IOException {
			try (com.github.luben.zstd.ZstdOutputStream zos = new com.github.luben.zstd.ZstdOutputStream(out)) {
				zos.write(buf, offset, length);
			}
		}

		static java.io.InputStream decompress(java.io.InputStream in) throws java.io.IOException {
			return new com.github.luben.zstd.ZstdInputStream(in);
		}
	}

	private static class BrotliCodec {
		static void compress(byte[] buf, int offset, int length, java.io.OutputStream out) throws java.io.IOException {
			try (com.brotjli.stream.BrotliOutputStream bos = new com.brotjli.stream.BrotliOutputStream(out)) {
				bos.write(buf, offset, length);
			}
		}

		static java.io.InputStream decompress(java.io.InputStream in) throws java.io.IOException {
			return new com.brotjli.stream.BrotliInputStream(in);
		}
	}

	public static java.io.OutputStream createZstdOutputStream(java.io.OutputStream out) throws java.io.IOException {
		return new com.github.luben.zstd.ZstdOutputStream(out);
	}

	public static java.io.OutputStream createBrotliOutputStream(java.io.OutputStream out) throws java.io.IOException {
		return new com.brotjli.stream.BrotliOutputStream(out);
	}

	public static String getPreferredCompression(String acceptEncoding) {
		if (acceptEncoding == null || acceptEncoding.isBlank()) {
			return null;
		}
		double bestQ = 0.0;
		String bestType = null;
		int bestPref = -1;
		int len = acceptEncoding.length();
		int idx = 0;
		while (idx < len) {
			int nextComma = acceptEncoding.indexOf(',', idx);
			int endSegment = (nextComma == -1) ? len : nextComma;
			
			int firstSemi = acceptEncoding.indexOf(';', idx);
			int endType = (firstSemi == -1 || firstSemi > endSegment) ? endSegment : firstSemi;
			
			int tStart = idx;
			while (tStart < endType && (acceptEncoding.charAt(tStart) == ' ' || acceptEncoding.charAt(tStart) == '\t')) {
				tStart++;
			}
			int tEnd = endType;
			while (tEnd > tStart && (acceptEncoding.charAt(tEnd - 1) == ' ' || acceptEncoding.charAt(tEnd - 1) == '\t')) {
				tEnd--;
			}
			
			int encLen = tEnd - tStart;
			if (encLen > 0) {
				double q = 1.0;
				if (firstSemi != -1 && firstSemi < endSegment) {
					int pIdx = firstSemi + 1;
					while (pIdx < endSegment) {
						int nextSemi = acceptEncoding.indexOf(';', pIdx);
						int endParam = (nextSemi == -1 || nextSemi > endSegment) ? endSegment : nextSemi;
						int pStart = pIdx;
						while (pStart < endParam && (acceptEncoding.charAt(pStart) == ' ' || acceptEncoding.charAt(pStart) == '\t')) {
							pStart++;
						}
						int pEnd = endParam;
						while (pEnd > pStart && (acceptEncoding.charAt(pEnd - 1) == ' ' || acceptEncoding.charAt(pEnd - 1) == '\t')) {
							pEnd--;
						}
						if (pEnd - pStart > 2 && acceptEncoding.charAt(pStart) == 'q' && acceptEncoding.charAt(pStart + 1) == '=') {
							int qStart = pStart + 2;
							while (qStart < pEnd && (acceptEncoding.charAt(qStart) == ' ' || acceptEncoding.charAt(qStart) == '\t')) {
								qStart++;
							}
							try {
								q = Double.parseDouble(acceptEncoding.substring(qStart, pEnd));
							} catch (NumberFormatException e) {
								q = 0.0;
							}
							if (Double.isNaN(q)) {
								q = 0.0;
							} else if (q > 1.0) {
								q = 1.0;
							} else if (q < 0.0) {
								q = 0.0;
							}
						}
						pIdx = endParam + 1;
					}
				}
				if (q > 0.0) {
					int pref = 0;
					String type = null;
					if (encLen == 2 && acceptEncoding.regionMatches(true, tStart, "br", 0, 2) && BROTLI_SUPPORTED) {
						pref = 3;
						type = "br";
					} else if (encLen == 4 && acceptEncoding.regionMatches(true, tStart, "zstd", 0, 4) && ZSTD_SUPPORTED) {
						pref = 2;
						type = "zstd";
					} else if (encLen == 4 && acceptEncoding.regionMatches(true, tStart, "gzip", 0, 4)) {
						pref = 1;
						type = "gzip";
					} else if (encLen == 1 && acceptEncoding.charAt(tStart) == '*') {
						pref = 1;
						type = "gzip";
					}
					if (pref > 0) {
						if (q > bestQ) {
							bestQ = q;
							bestType = type;
							bestPref = pref;
						} else if (q == bestQ && pref > bestPref) {
							bestType = type;
							bestPref = pref;
						}
					}
				}
			}
			idx = endSegment + 1;
		}
		return bestType;
	}

	public static byte[] compress(byte[] buf, int offset, int length, String encoding) throws java.io.IOException {
		if (encoding == null) {
			if (offset == 0 && length == buf.length) return buf;
			byte[] copy = new byte[length];
			System.arraycopy(buf, offset, copy, 0, length);
			return copy;
		}
		String enc = encoding.trim().toLowerCase(java.util.Locale.ROOT);
		java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
		if (enc.equals("gzip")) {
			try (java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(baos)) {
				gzos.write(buf, offset, length);
			}
		} else if (enc.equals("zstd")) {
			if (ZSTD_SUPPORTED) {
				ZstdCodec.compress(buf, offset, length, baos);
			} else {
				throw new UnsupportedOperationException("Zstd not supported");
			}
		} else if (enc.equals("br")) {
			if (BROTLI_SUPPORTED) {
				BrotliCodec.compress(buf, offset, length, baos);
			} else {
				throw new UnsupportedOperationException("Brotli not supported");
			}
		} else {
			if (offset == 0 && length == buf.length) return buf;
			byte[] copy = new byte[length];
			System.arraycopy(buf, offset, copy, 0, length);
			return copy;
		}
		return baos.toByteArray();
	}

	public static byte[] compress(byte[] bytes, String encoding) throws java.io.IOException {
		return compress(bytes, 0, bytes.length, encoding);
	}

	public static byte[] decompress(byte[] bytes, String encoding) throws java.io.IOException {
		if (encoding == null) {
			return bytes;
		}
		String enc = encoding.trim().toLowerCase(java.util.Locale.ROOT);
		java.io.InputStream in = null;
		if (enc.equals("gzip")) {
			in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes));
		} else if (enc.equals("zstd")) {
			if (ZSTD_SUPPORTED) {
				in = ZstdCodec.decompress(new java.io.ByteArrayInputStream(bytes));
			} else {
				throw new UnsupportedOperationException("Zstd not supported");
			}
		} else if (enc.equals("br")) {
			if (BROTLI_SUPPORTED) {
				in = BrotliCodec.decompress(new java.io.ByteArrayInputStream(bytes));
			} else {
				throw new UnsupportedOperationException("Brotli not supported");
			}
		} else {
			return bytes;
		}
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int n;
		int total = 0;
		try (java.io.InputStream finalIn = in) {
			while ((n = finalIn.read(buf)) != -1) {
				total += n;
				if (total > org.deftserver.web.http.HttpRequest.MAX_BODY_SIZE) {
					throw new org.deftserver.web.http.HttpException(413, "Payload Too Large", "Decompressed body exceeds size limit");
				}
				out.write(buf, 0, n);
			}
		}
		return out.toByteArray();
	}

}
