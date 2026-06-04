package org.deftserver.web.http;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.deftserver.io.IOLoop;
import org.deftserver.io.stream.ByteBufferBackedInputStream;
import org.deftserver.web.Application;
import org.deftserver.web.HttpVerb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class HttpRequest {
	private IOLoop ioLoop;
	private static final Logger logger = LoggerFactory.getLogger(HttpRequest.class);
	
	private final String requestLine;
	private final HttpVerb method;
	private final String requestedPath;	// correct name?
	private final String version; 
	private final Map<String, String> headers;
	private final Map<String, String> um_headers;
	private final Map<String, String> trailers = new HashMap<>();
	private final Map<String, String> um_trailers = Collections.unmodifiableMap(trailers);
	private final Map<String, List<String>> parameters;
	private String body = null;
	private final boolean keepAlive;
	private InetAddress remoteHost;
	private InetAddress serverHost;
	private int remotePort;
	private int serverPort;
	private long flipRemain = -1;
	private final boolean chunked;
	private ByteArrayOutputStream chunkedBody;
	
	public class HeadKeyVals {
		String key;
		String val;
		final Map<String, String> vals    = new LinkedHashMap<String, String>();
		final Map<String, String> um_vals = Collections.unmodifiableMap(vals);
		
		/** The parsed parameters of this header (e.g. {@code name}, {@code filename} from a
		 *  Content-Disposition), keyed by parameter name. */
		public Map<String, String> getVals() {
			return um_vals;
		}

		/** The header field name. */
		public String getKey() {
			return key;
		}

		/** The header field value (the part before any {@code ;} parameters). */
		public String getVal() {
			return val;
		}

		/** Debug representation: {@code key: val {params}}. */
		@Override
		public String toString() {
			return key + ": " + val + " {" + vals + "}";
		}
	}

	/** One part of a {@code multipart/form-data} body: its headers (notably Content-Disposition) plus
	 *  the raw body bytes between this part's header block and the next boundary. */
	public class Part {
		String      mapName                     = null;
		int         num                         = -1;
		Map<String, HeadKeyVals> headKeyVals    = new LinkedHashMap<String, HttpRequest.HeadKeyVals>();
		Map<String, HeadKeyVals> um_headKeyVals = Collections.unmodifiableMap(headKeyVals);
		byte[]      rawData                     = null;
		String      data                        = null;
		int         rawBufStartPos              = -1;
		int         rawBufEndPos                = -1;
		boolean     complete                    = false;
		
		/** The form field name this part was submitted under (the Content-Disposition {@code name}). */
		public String getMapName() {
			return mapName;
		}

		/** The zero-based index of this part within the multipart body. */
		public int getNum() {
			return num;
		}

		/** This part's headers, keyed by (original-case) header name. */
		public Map<String, HeadKeyVals> getHeadKeyVals() {
			return um_headKeyVals;
		}

		/** Case-insensitive lookup of a part header (HTTP header names are case-insensitive,
		 *  so a part sending e.g. "content-disposition" must resolve the same as the usual
		 *  "Content-Disposition"). */
		public HeadKeyVals getHeadKeyVal(String name) {
			for (Map.Entry<String, HeadKeyVals> e : headKeyVals.entrySet()) {
				if (e.getKey().equalsIgnoreCase(name)) {
					return e.getValue();
				}
			}
			return null;
		}

		/** This part's body as raw bytes (e.g. an uploaded file's contents). */
		public byte[] getRawData() {
			return rawData == null ? null : Arrays.copyOf(rawData, rawData.length);
		}

		/** This part's body decoded as an ISO-8859-1 string (convenient for text fields). */
		public String getData() {
			return data;
		}

		/** True once this part has been fully parsed (its terminating boundary was reached). */
		public boolean isComplete() {
			return complete;
		}
	}

	/** Regex to parse HttpRequest Request Line */
	public static final Pattern REQUEST_LINE_PATTERN = Pattern.compile(" ") ;
	/** Regex to parse out QueryString from HttpRequest */
	public static final Pattern QUERY_STRING_PATTERN = Pattern.compile("\\?") ;
	// NOTE: the old HEADERS_BODY_PATTERN / HEADER_VALUE_PATTERN / HEADER_VAL_SPLIT_PATTERN regexes
	// were removed — header parsing is now a bounded position-based indexOf scan (see of()), so the
	// split-based patterns were dead code.

	public static final String HTTP_HEAD_TERM       = "\r\n\r\n";
	public static final byte[] HTTP_HEAD_TERM_BYTES = HTTP_HEAD_TERM.getBytes(StandardCharsets.ISO_8859_1);
	private static final int MAX_REQUEST_LINE_SIZE = 8 * 1024;
	private static final int MAX_HEADER_SIZE = 64 * 1024;
	private static final int MAX_HEADER_COUNT = 100;
	/** Maximum request body size in bytes (16 MiB). Requests exceeding this → 413 Payload Too Large. */
	public static final int MAX_BODY_SIZE = 16 * 1024 * 1024;
	/** Cap on the number of multipart parts, to bound the object/memory amplification a body of
	 *  many tiny parts would otherwise cause (a 16 MiB body could hold ~300k minimal parts). */
	static final int MAX_MULTIPART_PARTS = 10000;
	/** Cap on the number of urlencoded (query/form) parameters parsed. A 16 MiB form body of tiny
	 *  "a=b&" pairs would otherwise amplify into millions of map entries / Strings (hundreds of MB),
	 *  an OOM/DoS vector — this parse runs eagerly for every form POST. (Cf. Tomcat maxParameterCount.) */
	static final int MAX_PARAMETERS = 10000;
	/** Initial size of the raw (undecoded) chunked-body buffer. It grows on demand toward the body
	 *  cap to fit a single oversized chunk, so a chunked request needn't reserve the full cap upfront. */
	static final int CHUNKED_RAWBODY_INITIAL_SIZE = 64 * 1024;
	
	public static final byte[] MP_SEP_END_BYTES = "--\r\n".getBytes(StandardCharsets.ISO_8859_1);
	public static final byte[] MP_END_BYTES = "\r\n".getBytes(StandardCharsets.ISO_8859_1);
	
	protected ByteBuffer rawBody            = null;
	protected int        contentLength      = -1;
	protected String     contentType        = null;
	protected boolean    multipart          = false;
	protected String     multipartBoundary  = null;
	protected byte[]     mpBoundaryBStart   = null;
	protected byte[]     mpBoundaryBPre     = null;
	protected boolean    complete           = false;
	
	protected Map<String, Part> mpParts     = null;
	protected Map<String, Part> um_mpParts  = null;
	// Every part in order, so parts that share a field name (multiple files, or a text field and a
	// file field with the same name) aren't lost — getMultiParts()'s name->Part map keeps only the
	// last for backwards compatibility, but getAllParts()/getParts(name) expose them all.
	protected final List<Part>  mpPartsAll     = new ArrayList<>();
	protected final List<Part>  um_mpPartsAll  = Collections.unmodifiableList(mpPartsAll);
	protected final int         requestNum;

	protected Map<String, List<String>> postParameters = null;
	
	/**
	 * Creates a new HttpRequest 
	 * @param requestLine The Http request text line
	 * @param headers The Http request headers
	 */
	public HttpRequest(String requestLine, Map<String, String> headers) {
		this.requestNum = 0;
		this.requestLine = requestLine;
		if (requestLine != null && headers != null) {
			String[] elements = REQUEST_LINE_PATTERN.split(requestLine.trim());
			if (elements.length != 3) {
				throw new HttpException(400, "Bad Request", "Invalid request line");
			}
			method = parseMethod(elements[0]);
			// The path is the request target up to the first '?' (the query starts there). Use
			// indexOf rather than a regex split that would materialise every '?'-delimited fragment
			// (only the first is ever used) on this per-request path.
			int qIdx = elements[1].indexOf('?');
			String pathPart = (qIdx == -1) ? elements[1] : elements[1].substring(0, qIdx);
			requestedPath = normalizeAndDecodePath(pathPart);

			// Verify Host header matches authority of absolute URI if present
			if (elements[1].startsWith("http://") || elements[1].startsWith("https://") || elements[1].startsWith("//")) {
				try {
					java.net.URI absoluteUri = new java.net.URI(elements[1]);
					String authority = absoluteUri.getRawAuthority();
					if (authority != null && !authority.isEmpty()) {
						String hostHeader = null;
						for (Map.Entry<String, String> entry : headers.entrySet()) {
							if ("host".equalsIgnoreCase(entry.getKey())) {
								hostHeader = entry.getValue();
								break;
							}
						}
						if (hostHeader == null || !normalizeAuthority(authority, absoluteUri.getScheme()).equalsIgnoreCase(normalizeAuthority(hostHeader, absoluteUri.getScheme()))) {
							throw new HttpException(400, "Bad Request", "Host header does not match absolute URI authority");
						}
					}
				} catch (HttpException he) {
					throw he;
				} catch (Exception e) {
					throw new HttpException(400, "Bad Request", "Malformed absolute URI");
				}
			}

			version = elements[2];
			if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
				throw new HttpException(505, "HTTP Version Not Supported", "The requested HTTP version is not supported");
			}
			this.headers = new HashMap<String, String>(headers);
			this.um_headers = Collections.unmodifiableMap(this.headers);
			parameters = parseParameters(elements[1]);
		} else {
			version = null;
			requestedPath = null;
			method = null;
			this.headers = null;
			this.um_headers = null;
			parameters = null;
		}
		
		String connection = getHeader("Connection");
		if ("HTTP/1.1".equals(version)) {
			// HTTP/1.1 defaults to keep-alive unless the Connection header lists "close". The
			// header is a comma-separated token list (e.g. "close, TE"), so check membership
			// rather than equality of the whole value.
			keepAlive = !connectionHasToken(connection, "close");
		} else { // HTTP/1.0
			// HTTP/1.0 defaults to close unless "keep-alive" is listed; an explicit "close" token
			// still wins if both are (contradictorily) present.
			keepAlive = connectionHasToken(connection, "keep-alive") && !connectionHasToken(connection, "close");
		}
		chunked = false;
	}

	/**
	 * The primary constructor used by the live server (via {@link #of(Application, ByteBuffer)}):
	 * validates the request line, version (505 for unsupported), absolute-URI authority and the
	 * mandatory Host header (RFC 9112 §3.2), determines body framing (Content-Length vs chunked,
	 * rejecting smuggling combinations) and keep-alive disposition, then begins consuming the body.
	 */
	public HttpRequest(int requestNum, String requestLine, Map<String, String> headers, ByteBuffer buffer) throws IOException {
		this.requestNum  = requestNum;
		this.requestLine = requestLine;
		if (requestLine != null && headers != null) {
			String[] elements = REQUEST_LINE_PATTERN.split(requestLine);
			if (elements.length != 3) {
				throw new ProtocolException("Invalid request line");
			}
			method = parseMethod(elements[0]);
			// The path is the request target up to the first '?' (the query starts there). Use
			// indexOf rather than a regex split that would materialise every '?'-delimited fragment
			// (only the first is ever used) on this per-request path.
			int qIdx = elements[1].indexOf('?');
			String pathPart = (qIdx == -1) ? elements[1] : elements[1].substring(0, qIdx);
			requestedPath = normalizeAndDecodePath(pathPart);

			// Verify Host header matches authority of absolute URI if present
			if (elements[1].startsWith("http://") || elements[1].startsWith("https://") || elements[1].startsWith("//")) {
				try {
					java.net.URI absoluteUri = new java.net.URI(elements[1]);
					String authority = absoluteUri.getRawAuthority();
					if (authority != null && !authority.isEmpty()) {
						String hostHeader = null;
						for (Map.Entry<String, String> entry : headers.entrySet()) {
							if ("host".equalsIgnoreCase(entry.getKey())) {
								hostHeader = entry.getValue();
								break;
							}
						}
						if (hostHeader == null || !normalizeAuthority(authority, absoluteUri.getScheme()).equalsIgnoreCase(normalizeAuthority(hostHeader, absoluteUri.getScheme()))) {
							throw new HttpException(400, "Bad Request", "Host header does not match absolute URI authority");
						}
					}
				} catch (HttpException he) {
					throw he;
				} catch (Exception e) {
					throw new HttpException(400, "Bad Request", "Malformed absolute URI");
				}
			}

			version = elements[2];
			logger.debug("request line: [{}], version: [{}]", requestLine, version);
			if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
				logger.debug("unsupported HTTP version [{}], returning 505", version);
				throw new HttpException(505, "HTTP Version Not Supported", "The requested HTTP version is not supported");
			}
			this.headers = new HashMap<String, String>(headers);
			this.um_headers = Collections.unmodifiableMap(this.headers);

			// RFC 9112 §3.2: a server MUST reject (400) any HTTP/1.1 request that lacks a Host
			// header field or supplies an empty one. (Header keys are stored lower-cased. An
			// absolute-form target's authority is additionally matched against Host above.)
			if ("HTTP/1.1".equals(version) && (headers.get("host") == null || headers.get("host").isEmpty())) {
				throw new HttpException(400, "Bad Request", "HTTP/1.1 request is missing the required Host header");
			}

			parameters = parseParameters(elements[1]);
			chunked = parseTransferEncoding(headers.get("transfer-encoding"));
			String clen = headers.get("content-length");
			if (clen != null && !clen.isEmpty()) {
				// RFC 9110 §8.6: Content-Length is 1*DIGIT. Reject anything else (e.g. "+5",
				// "-5", "0x5", "5,5") strictly — Integer.parseInt would otherwise accept a
				// leading sign, a classic request-smuggling discrepancy with stricter proxies.
				for (int i = 0; i < clen.length(); i++) {
					char c = clen.charAt(i);
					if (c < '0' || c > '9') {
						throw new ProtocolException("Invalid Content-Length: " + clen);
					}
				}
				try {
					contentLength = Integer.parseInt(clen);
				} catch (NumberFormatException e) {
					throw new ProtocolException("Invalid Content-Length: " + clen);
				}
				if (contentLength < 0) {
					throw new ProtocolException("Invalid Content-Length: " + clen);
				}
				if (contentLength > MAX_BODY_SIZE) {
					// An over-large declared body is 413 Payload Too Large, not 400.
					throw new HttpException(413, "Payload Too Large", "The request body exceeds the maximum permitted size");
				}
			}
			if (chunked && contentLength >= 0) {
				throw new ProtocolException("Transfer-Encoding and Content-Length cannot both frame a request body");
			}
			if (method != null && (method == HttpVerb.POST || method == HttpVerb.PUT || method == HttpVerb.PATCH) && contentLength < 0 && !chunked) {
				throw new HttpException(411, "Length Required", "A Content-Length or Transfer-Encoding header is required");
			}
			if (hasRequestBody()) {
				String ctype = headers.get("content-type");
				// Quote-aware, ';'-separated parse (handles "type;param" without a space and
				// quoted parameter values). The media type is the first segment.
				List<String> ctParts = ctype == null ? java.util.List.of("") : splitParamsRespectingQuotes(ctype);
				contentType = ctParts.get(0).trim().toLowerCase(Locale.ROOT);
				if (!contentType.isEmpty()) {
					if (contentType.equals("multipart/form-data")) {
						// Find the boundary parameter (case-insensitive name, may be quoted, and
						// need not be the first parameter — a charset may precede it).
						String boundary = null;
						for (int i = 1; i < ctParts.size(); i++) {
							String param = ctParts.get(i).trim();
							int eq = param.indexOf('=');
							if (eq < 0) continue;
							if (param.substring(0, eq).trim().equalsIgnoreCase("boundary")) {
								String pVal = param.substring(eq + 1).trim();
								if (pVal.length() >= 2 && pVal.charAt(0) == '"' && pVal.charAt(pVal.length() - 1) == '"') {
									pVal = pVal.substring(1, pVal.length() - 1);
								}
								boundary = pVal;
								break;
							}
						}
						if (boundary == null || boundary.isEmpty()) {
							throw new ProtocolException("Expected multipart boundary");
						}
						multipartBoundary = boundary;
						logger.debug("got multipart boundary: {}", multipartBoundary);
						mpBoundaryBPre    = ("\r\n--" + multipartBoundary).getBytes(StandardCharsets.ISO_8859_1);
						mpBoundaryBStart  = ("--" + multipartBoundary + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
						multipart = true;
						mpParts = new LinkedHashMap<String, HttpRequest.Part>();
						um_mpParts = Collections.unmodifiableMap(mpParts);
					} else if (contentType.equals("application/x-www-form-urlencoded")) {
						multipart = false;
					}
				}
				if (chunked) {
					// Start modest and grow on demand (see putContentData) rather than reserving the
					// full 16 MiB cap upfront for every chunked request — most chunked bodies are far
					// smaller, and reserving the max per connection is a needless memory amplification.
					rawBody = ByteBuffer.allocate(Math.min(CHUNKED_RAWBODY_INITIAL_SIZE, MAX_BODY_SIZE));
					chunkedBody = new ByteArrayOutputStream();
				} else if (contentLength > 0) {
					rawBody = ByteBuffer.allocate(contentLength);
				}
			}
		} else {
			version = null;
			requestedPath = null;
			method = null;
			this.headers = null;
			this.um_headers = null;
			parameters = null;
			chunked = false;
		}
		String connection = getHeader("Connection");
		if ("HTTP/1.1".equals(version)) {
			// HTTP/1.1 defaults to keep-alive unless the Connection header lists "close". The
			// header is a comma-separated token list (e.g. "close, TE"), so check membership
			// rather than equality of the whole value.
			keepAlive = !connectionHasToken(connection, "close");
		} else { // HTTP/1.0
			// HTTP/1.0 defaults to close unless "keep-alive" is listed; an explicit "close" token
			// still wins if both are (contradictorily) present.
			keepAlive = connectionHasToken(connection, "keep-alive") && !connectionHasToken(connection, "close");
		}
		putContentData(false, buffer);
	}
	
	/** Bytes of body still expected before the request is complete: 0 if complete, -1 if chunked
	 *  (length not known ahead of time), else the remaining capacity of the Content-Length buffer. */
	public long getRemaining() {
		if (complete) return 0;
		if (chunked) return -1;
		return rawBody == null ? 0 : rawBody.remaining();
	}

	/** True once the full request (line, headers and body) has been received. */
	public boolean isComplete() {
		return complete;
	}

	/** Parses a request from a buffer without an {@link Application} (used by tests/standalone parsing);
	 *  any parse failure yields the {@link MalFormedHttpRequest} sentinel rather than throwing. */
	public static HttpRequest of(ByteBuffer buffer) {
		try {
			return of(new Application(Collections.emptyMap()), buffer);
		} catch (IOException | RuntimeException e) {
			return MalFormedHttpRequest.instance;
		}
	}

	/** Parses the request line and header block from {@code buffer} and constructs the request (which
	 *  also begins body framing). The live server entry point — strict parsing here is the
	 *  security-critical surface; a thrown {@link HttpException} maps to the corresponding error status. */
	public static HttpRequest of(Application app, ByteBuffer buffer) throws IOException {
		String requestLine = null;
		Map<String, String> generalHeaders = null;
		if (buffer.hasRemaining()) {
			int oldpos = buffer.position();
			boolean foundSep = findInBB(buffer, HTTP_HEAD_TERM_BYTES);
			if (!foundSep) {
				String raw = new String(buffer.array(), 0, buffer.limit(), java.nio.charset.StandardCharsets.ISO_8859_1);
				logger.debug("raw httpreq #1. (of), pos: {}, limit: {}, buf: {}", buffer.position(), buffer.limit(), raw);
				throw new ProtocolException("Expected body seperator for initial HTTP req, none found");
			}
			int bodystartpos = buffer.position();
			int headerLen = bodystartpos - oldpos;
			if (headerLen > MAX_HEADER_SIZE) {
				// Oversized header section is 431, not a generic 400 (RFC 9110 §15.5.18).
				throw new HttpException(431, "Request Header Fields Too Large", "The request header section is too large");
			}
			byte[] headerBytes = new byte[headerLen];
			buffer.position(oldpos);
			buffer.get(headerBytes);
			buffer.position(bodystartpos); // Keep exactly at start of body

			String headerStr = new String(headerBytes, StandardCharsets.ISO_8859_1);
			// Iterate the header block line-by-line via indexOf("\r\n") rather than split("\r\n"):
			// split would materialise every line of the (up to 64 KiB) header block up front, before
			// the MAX_HEADER_COUNT cap could apply — the same materialise-before-cap pattern as P50,
			// bounded here by the header cap. This stops after at most MAX_HEADER_COUNT header lines.
			int hlen = headerStr.length();
			int scan = 0;
			// RFC 9112 §2.2: a server SHOULD ignore empty line(s) received before the request-line
			// (some clients prepend a stray CRLF). (Two consecutive CRLFs form the header terminator
			// and are handled upstream, so in practice this only ever skips a single leading CRLF.)
			while (scan + 1 < hlen && headerStr.charAt(scan) == '\r' && headerStr.charAt(scan + 1) == '\n') {
				scan += 2;
			}
			int rlEnd = headerStr.indexOf("\r\n", scan);
			String requestLineRaw = (rlEnd == -1) ? headerStr.substring(scan) : headerStr.substring(scan, rlEnd);
			if (requestLineRaw.trim().isEmpty()) {
				throw new ProtocolException("Request line is empty/missing!");
			}
			requestLine = requestLineRaw.trim();
			validateRequestLine(requestLine);
			logger.debug("got req. line: {}", requestLine);
			scan = (rlEnd == -1) ? hlen : rlEnd + 2;

			generalHeaders = new HashMap<>();
			int headerCount = 0;
			while (scan < hlen) {
				int lineEnd = headerStr.indexOf("\r\n", scan);
				int lineStop = (lineEnd == -1) ? hlen : lineEnd;
				if (lineStop == scan) {
					break; // blank line → end of the header section
				}
				String line = headerStr.substring(scan, lineStop);
				scan = (lineEnd == -1) ? hlen : lineEnd + 2;
				// RFC 7230 §3.2.4: reject obsolete line folding (a header line starting with
				// SP/HT is an obs-fold continuation). Accepting it risks parser disagreement
				// with upstream proxies (request smuggling), so we reject with 400.
				char first = line.charAt(0);
				if (first == ' ' || first == '\t') {
					throw new ProtocolException("Obsolete header line folding is not allowed");
				}
				if (++headerCount > MAX_HEADER_COUNT) {
					// Too many header fields is 431, not a generic 400 (RFC 9110 §15.5.18).
					throw new HttpException(431, "Request Header Fields Too Large", "Too many request header fields");
				}
				int colon = line.indexOf(':');
				if (colon <= 0) {
					throw new ProtocolException("Invalid header line");
				}
				String rawKey = line.substring(0, colon);
				if (rawKey.endsWith(" ") || rawKey.endsWith("\t")) {
					throw new ProtocolException("Whitespace before header colon is forbidden");
				}
				String key = rawKey.trim().toLowerCase(Locale.ROOT);
				String val = line.substring(colon + 1).trim();
				if (!isHeaderName(key)) {
					throw new ProtocolException("Invalid header name");
				}
				// Reject embedded control characters (e.g. a bare CR or NUL that survived CRLF
				// line splitting) in the value — HT is the only permitted control char in a
				// field-value (RFC 9110 §5.5). Defends against header-injection/smuggling.
				for (int c = 0; c < val.length(); c++) {
					char ch = val.charAt(c);
					if ((ch < 0x20 && ch != '\t') || ch == 0x7f) {
						throw new ProtocolException("Control character in header value");
					}
				}
				addHeader(generalHeaders, key, val);
			}
			logger.debug("buffer remaining: {}", buffer.remaining());
		}
		return new HttpRequest(app.nextHttpReqNum(), requestLine, generalHeaders, buffer);
	}
	
	/** Multipart parts keyed by field name (last-wins for duplicate names; use {@link #getAllParts()}
	 *  or {@link #getParts(String)} to see every part). */
	public Map<String, Part> getMultiParts() {
		return um_mpParts;
	}

	/** All multipart parts in wire order, including ones that share a field name (which the
	 *  name-keyed {@link #getMultiParts()} map would otherwise collapse to the last). */
	public List<Part> getAllParts() {
		return um_mpPartsAll;
	}

	/** All parts submitted under the given field name (empty if none / not multipart). */
	public List<Part> getParts(String name) {
		if (name == null) return Collections.emptyList();
		List<Part> matches = new ArrayList<>();
		for (Part p : mpPartsAll) {
			if (name.equals(p.getMapName())) {
				matches.add(p);
			}
		}
		return matches;
	}
	
	/** True if the request body is {@code multipart/form-data}. */
	public boolean isMultipart() {
		return multipart;
	}

	/** The server-assigned sequence number for this request. */
	public int getRequestNum() {
		return requestNum;
	}
	
	/**
	 * Returns true if got all content data
	 * @param buffer
	 * @return
	 */
	public boolean putContentData(boolean continuing, ByteBuffer buffer) throws IOException {
		if (complete) throw new IllegalStateException("Already complete");
		if (buffer == null || !buffer.hasRemaining()) {
			if (!hasRequestBody()) {
				if (!continuing) {
					complete = true;
					return true;
				}
			}
			return false;
		}
		if (!hasRequestBody()) {
			complete = true;
			return true;			
		}
		if (chunked) {
			// Grow the raw (undecoded) buffer on demand to hold the already-buffered undecoded bytes
			// plus the incoming data — capped at the body limit — so a single call can consume what's
			// available (a chunk larger than the current buffer must fit to be decoded). This replaces
			// reserving the full 16 MiB cap upfront for every chunked request. When the buffer reaches
			// the cap and is still full, readAvailableBodyBytes returns 413.
			long want = (long) rawBody.position() + buffer.remaining();
			if (want > rawBody.capacity() && rawBody.capacity() < MAX_BODY_SIZE) {
				int newCap = (int) Math.min(Math.max(want, (long) rawBody.capacity() * 2), MAX_BODY_SIZE);
				ByteBuffer grown = ByteBuffer.allocate(newCap);
				rawBody.flip();
				grown.put(rawBody);
				rawBody = grown;
			}
			rawBody.put(readAvailableBodyBytes(buffer, rawBody.remaining()));
			rawBody.flip();
			if (tryDecodeChunkedBody()) {
				complete = true;
			}
			rawBody.compact();
			if (!complete) return false;
		} else {
			// put the buffer into rawbody, then put the limit to the current position, position back to before the buffer was fed in
			// to set it up for reading that new data.
			// Guard the per-read body dump: the new String(buffer.array(), ...) eagerly materialises the
			// whole buffer (a large allocation on the hot body-read path) — and calls array(), which would
			// throw on a non-array buffer — even when DEBUG logging is disabled, since Java evaluates the
			// argument regardless of level. Only build it when DEBUG is actually on.
			if (logger.isDebugEnabled()) {
				logger.debug("raw buffer: {}", new String(buffer.array(), buffer.position(), buffer.remaining(), StandardCharsets.ISO_8859_1));
				logger.debug("rawbody pos (pre buffer dump): {}, limit: {}", rawBody.position(), rawBody.limit());
				logger.debug("buffer remaining: {}, rawbody new pos: {}", buffer.remaining(), rawBody.position() + buffer.remaining());
			}
			int remainingBefore = buffer.remaining();
			rawBody.put(readAvailableBodyBytes(buffer, rawBody.remaining()));
			flipRemain = remainingBefore - (buffer.remaining());
			logger.debug("rawbody pos (post buffer dump): {}, limit: {}", rawBody.position(), rawBody.limit());
			if (!rawBody.hasRemaining()) {
				rawBody.flip();
				complete = true;
			}
			if (!complete) return false;
		}

		logger.debug("complete, now parsing; raw body length: {}", rawBody.remaining());

		byte[] rawBytes;
		if (chunked) {
			rawBytes = chunkedBody.toByteArray();
		} else {
			rawBytes = new byte[rawBody.limit()];
			System.arraycopy(rawBody.array(), 0, rawBytes, 0, rawBody.limit());
		}
		String contentEncoding = headers.get("content-encoding");
		if (contentEncoding != null && !contentEncoding.trim().isEmpty()) {
			rawBytes = org.deftserver.util.HttpUtil.decompress(rawBytes, contentEncoding);
		}
		if (!multipart) {
			body = new String(rawBytes, StandardCharsets.ISO_8859_1);
			postParameters = parseParameters2(body);
		} else {
			parseMultipartParts(ByteBuffer.wrap(rawBytes));
		}
		return true;
	}

	/**
	 * Parses multipart/form-data parts from the given body buffer (read mode, positioned at the
	 * start of the body). Shared by the Content-Length path (rawBody) and the chunked path (the
	 * dechunked bytes) so that chunked multipart uploads are parsed rather than silently dropped.
	 * The parameter is named {@code rawBody} so the parsing body below is identical to the
	 * original inline implementation.
	 */
	private void parseMultipartParts(ByteBuffer rawBody) throws IOException {
			boolean parsingBoundary = false;
			Part    currPart        = null;
			boolean mpFinished      = false;
			while (!mpFinished && rawBody.hasRemaining()) {
				boolean found = false;
				if (!parsingBoundary) {
					logger.debug("rawbody pos: {}, limit: {}", rawBody.position(), rawBody.limit());
					// RFC 2046 §5.1.1 permits an (ignored) preamble before the first boundary, and some
					// clients prepend a leading CRLF. Locate the first delimiter rather than demanding it
					// at byte 0 so such bodies parse instead of being rejected as malformed.
					found = findInBB(rawBody, mpBoundaryBStart);
					if (!found) {
						throw new ProtocolException("Expecting initial mp start boundary, but not found");
					}
					parsingBoundary = true;
				} else {
					int rbpos = -1;
					found = findInBB(rawBody, mpBoundaryBPre);
					if (!found) {
						throw new ProtocolException("next part/finish boundary marker not found");
					}
					int prepos = rawBody.position();
					// check if mp boundary start (newline) or end indicator (-- and newline)
					found = expectInBB(rawBody, MP_END_BYTES, true);
					if (found) {
						// mp boundary start
						rbpos = rawBody.position() - mpBoundaryBPre.length - MP_END_BYTES.length;
					} else {
						rawBody.position(prepos);
						// mp end indicator
						found = expectInBB(rawBody, MP_SEP_END_BYTES, true);
						if (!found) {
							throw new ProtocolException("Expecting mp end indicator when didn't find new line, but not found");
						}
						parsingBoundary = false;
						rbpos = rawBody.position() - mpBoundaryBPre.length - MP_SEP_END_BYTES.length;
						mpFinished = true;
					}
					// if we have a current part, finish it
					if (currPart != null) {
						currPart.rawBufEndPos = rbpos;
						currPart.rawData = Arrays.copyOfRange(rawBody.array(), currPart.rawBufStartPos, currPart.rawBufEndPos);
						currPart.data    = new String(currPart.rawData, StandardCharsets.ISO_8859_1);
						currPart.complete = true;
						mpParts.put(currPart.mapName, currPart);
						mpPartsAll.add(currPart);
						
						logger.debug(
							"Completed part header #{} (id: {}) ~ " +
							"rawBufStartPos: {}, " +
							"rawBufEndPos: {}, " +
							"hkvs: {}",
							currPart.num, currPart.mapName, currPart.rawBufStartPos,
							currPart.rawBufEndPos, currPart.headKeyVals
						);
						
						currPart = null;
					}
					if (mpFinished) {
						logger.debug("mp finished");
						rawBody.position(rawBody.limit());
						return;
					}
				}
				
				if (!parsingBoundary) throw new IllegalStateException("Not parsing boundary & adding part");

				if (mpPartsAll.size() >= MAX_MULTIPART_PARTS) {
					throw new ProtocolException("Too many multipart parts (max " + MAX_MULTIPART_PARTS + ")");
				}

				// add new part
				int oldlimit = rawBody.limit();
				int headerStartPos = rawBody.position();
				found = findInBB(rawBody, HTTP_HEAD_TERM_BYTES);
				if (!found) {
					throw new ProtocolException("Couldn't find multipart header separator");
				}
				int datapos = rawBody.position();
				rawBody.limit(datapos);
				rawBody.position(headerStartPos);
					
				// parse mp headers
				try (
					ByteBufferBackedInputStream bbbis = new ByteBufferBackedInputStream(rawBody);
					InputStreamReader isr = new InputStreamReader(bbbis, StandardCharsets.ISO_8859_1);
					BufferedReader br = new BufferedReader(isr)
				) {			
					currPart = new Part();
					currPart.num = mpParts.size();
					currPart.rawBufStartPos = datapos;
					
					boolean gotSep = false;
					String currMpLine = null;
					int partHeaderCount = 0;
					while ((currMpLine = br.readLine()) != null) {
						logger.debug("mp req line: {}", currMpLine.isEmpty() ? "(empty)" : currMpLine);
						if (currMpLine.isEmpty()) {
							gotSep = true;
							break;
						}
						// Bound per-part header lines like the top-level header count — a single part
						// with a huge header section would otherwise amplify into a vast map (OOM).
						if (++partHeaderCount > MAX_HEADER_COUNT) {
							throw new HttpException(431, "Request Header Fields Too Large",
								"Too many headers in a multipart part");
						}
						HeadKeyVals hkv = parseHeadKeyVals(currMpLine);
						currPart.headKeyVals.put(hkv.key, hkv);
					}
					// check we got content-disposition.. (case-insensitive per HTTP)
					HeadKeyVals hkv = currPart.getHeadKeyVal("Content-Disposition");
					if (hkv == null) {
						throw new ProtocolException("Content-Disposition line doesn't exist in part header");
					}
					currPart.mapName = hkv.vals.get("name");
					if (currPart.mapName == null) currPart.mapName = "#" + currPart.num;
					
					logger.debug(
						"Created part header #{} (id: {}) ~ " +
						"rawBufStartPos: {}, " +
						"rawBufEndPos: {}, " +
						"hkvs: {}",
						currPart.num, currPart.mapName, currPart.rawBufStartPos,
						currPart.rawBufEndPos, currPart.headKeyVals
					);
					
					rawBody.limit(oldlimit);
					rawBody.position(datapos);
				}
			}
	}
	
	/** Associates the owning I/O loop (used by async handlers to marshal work back onto it). */
	protected void setIOLoop(IOLoop ioLoop) {
		this.ioLoop = ioLoop;
	}

	/** The owning I/O loop. */
	public IOLoop getIOLoop() {
		return ioLoop;
	}

	/** The raw request line (method, target and version), trimmed. */
	public String getRequestLine() {
		return requestLine;
	}

	/** The decoded, normalised request path (percent-decoded, traversal-safe). */
	public String getRequestedPath() {
		return requestedPath;
	}

	/** The HTTP version token, e.g. {@code HTTP/1.1}. */
	public String getVersion() {
		return version;
	}

	/** All request headers as an unmodifiable map (names stored lower-cased). */
	public Map<String, String> getHeaders() {
		return um_headers;
	}

	/** Case-insensitive lookup of a single header value, or null if absent. */
	public String getHeader(String name) {
		if (headers == null) return null;
		// Headers are stored lowercased with Locale.ROOT; look up with the same locale to
		// avoid the Turkish-locale dotted/dotless-I mismatch (e.g. "Content-Length").
		return headers.get(name.toLowerCase(Locale.ROOT));
	}

	/** The request method (an {@link HttpVerb}; {@code UNKNOWN} for an unrecognised but valid token). */
	public HttpVerb getMethod() {
		return method;
	}

	/** Parsed {@code application/x-www-form-urlencoded} body parameters (empty if none). */
	@SuppressWarnings("unchecked")
	public Map<String, Collection<String>> getPostParameters() {
		return postParameters == null ? Collections.emptyMap() : Collections.unmodifiableMap((Map) postParameters);
	}
	
	/** Parses one multipart part-header line (e.g. Content-Disposition) into its name, base value and
	 *  quote-aware {@code ;}-separated parameters. */
	HeadKeyVals parseHeadKeyVals(String line) throws IllegalArgumentException {
		logger.debug("hv line: {}", line);
		// Split the header name from its value at the first ':' (OWS after the colon is
		// optional per RFC, so don't require a space). Then parse the ';'-separated parameters
		// in a quote-aware way so a parameter value containing "; " (e.g. a filename) isn't
		// mis-split.
		int colon = line.indexOf(':');
		if (colon < 0) {
			throw new IllegalArgumentException("Expecting id and val for header line");
		}
		HeadKeyVals hkv = new HeadKeyVals();
		hkv.key = line.substring(0, colon).trim();
		String rest = line.substring(colon + 1).trim();
		List<String> segments = splitParamsRespectingQuotes(rest);
		hkv.val = segments.isEmpty() ? "" : segments.get(0).trim();
		for (int i = 1; i < segments.size(); i++) {
			String seg = segments.get(i).trim();
			if (seg.isEmpty()) {
				continue;
			}
			int eq = seg.indexOf('=');
			if (eq < 0) {
				throw new IllegalArgumentException("Expecting id and val in header line (for sub-val)");
			}
			String pName = seg.substring(0, eq).trim();
			String pVal = seg.substring(eq + 1).trim();
			// Strip surrounding DQUOTEs (guard the length so a lone '"' doesn't throw).
			if (pVal.length() >= 2 && pVal.charAt(0) == '"' && pVal.charAt(pVal.length() - 1) == '"') {
				pVal = pVal.substring(1, pVal.length() - 1);
			}
			hkv.vals.put(pName, pVal);
		}
		logger.debug("hkv: {}", hkv);
		return hkv;
	}

	/** Splits a header value on ';' while ignoring ';' inside double-quoted strings, so quoted
	 *  parameter values (e.g. {@code filename="a; b.txt"}) survive intact. */
	private static List<String> splitParamsRespectingQuotes(String s) {
		List<String> parts = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean inQuotes = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '"') {
				inQuotes = !inQuotes;
				cur.append(c);
			} else if (c == ';' && !inQuotes) {
				parts.add(cur.toString());
				cur.setLength(0);
			} else {
				cur.append(c);
			}
		}
		parts.add(cur.toString());
		return parts;
	}
	
	/** Number of body bytes consumed from the most recent buffer during incremental framing (used to
	 *  reposition a partially-consumed read buffer). */
	public long getFlipRemain() {
		return flipRemain;
	}

	/**
	 * Returns the value of a request parameter as a String, or null if the parameter does not exist.
	 *
	 * You should only use this method when you are sure the parameter has only one value. If the parameter 
	 * might have more than one value, use getParameterValues(java.lang.String).
     * If you use this method with a multi-valued parameter, the value returned is equal to the first value in
     * the array returned by getParameterValues. 
	 */
	public String getParameter(String name) {
		Collection<String> values = parameters.get(name);		
		return (values == null || values.isEmpty()) ? null : values.iterator().next();
	}
	
	/**
	 * Returns the value of a post parameter as a String, or null if the parameter does not exist. 
	 *
	 * You should only use this method when you are sure the parameter has only one value. If the parameter 
	 * might have more than one value, use getParameterValues(java.lang.String).
     * If you use this method with a multi-valued parameter, the value returned is equal to the first value in
     * the array returned by getParameterValues. 
	 */
	public String getPostParameter(String name) {
		if (postParameters == null) return null;
		Collection<String> values = postParameters.get(name);		
		return (values == null || values.isEmpty()) ? null : values.iterator().next();
	}	
	
	/** All query-string parameters as an unmodifiable multi-valued map. */
	@SuppressWarnings("unchecked")
	public Map<String, Collection<String>> getParameters() {
		return Collections.unmodifiableMap((Map) parameters);
	}

	/** The raw request body as a string (ISO-8859-1), or null if there is no body. */
	public String getBody() {
		return body;
	}

	/** The client's remote address (the actual peer socket address, not a proxy header). */
	public InetAddress getRemoteHost() {
		return remoteHost;
	}

	/** The local address the connection was accepted on. */
	public InetAddress getServerHost() {
		return serverHost;
	}

	/** The client's remote port. */
	public int getRemotePort() {
		return remotePort;
	}

	/** The local port the connection was accepted on. */
	public int getServerPort() {
		return serverPort;
	}

	/** Records the client's remote address (set by the protocol layer after accept). */
	protected void setRemoteHost(InetAddress host) {
		remoteHost = host;
	}

	/** Records the local accepting address (set by the protocol layer). */
	protected void setServerHost(InetAddress host) {
		serverHost = host;
	}

	/** Records the client's remote port (set by the protocol layer). */
	protected void setRemotePort(int port) {
		remotePort = port;
	}

	/** Records the local accepting port (set by the protocol layer). */
	protected void setServerPort(int port) {
		serverPort = port;
	}

	private boolean secure = false;

	/** Marks whether this request arrived over a TLS connection (set by the protocol layer). */
	protected void setSecure(boolean secure) {
		this.secure = secure;
	}

	/** True if the request arrived over TLS (HTTPS). */
	public boolean isSecure() {
		return secure;
	}

	/**
	 * Returns a collection of all values associated with the provided parameter.
	 * If no values are found an empty collection is returned.
	 */
	public Collection<String> getParameterValues(String name) {
		Collection<String> values = parameters.get(name);
		return values != null ? values : Collections.emptyList();
	}
	
	/**
	 * Returns a collection of all values associated with the provided post parameter.
	 * If no values are found an empty collection is returned.
	 */
	public Collection<String> getPostParameterValues(String name) {
		if (postParameters == null) return Collections.emptyList();
		Collection<String> values = postParameters.get(name);
		return values != null ? values : Collections.emptyList();
	}
	
	/** True if this connection should be kept alive after the response (per the request's version and
	 *  Connection header). */
	public boolean isKeepAlive() {
		return keepAlive;
	}

	/** The declared Content-Length, or -1 if none was sent (e.g. a chunked or bodiless request). */
	public int getContentLength() {
		return contentLength;
	}

	/** Debug representation: method, version, path, headers and parameters. */
	@Override
	public String toString() {
		String result = "METHOD: " + method + "\n";
		result += "VERSION: " + version + "\n";
		result += "PATH: " + requestedPath + "\n";
		
		result += "--- HEADER --- \n";
		for (String key : headers.keySet()) {
			String value = headers.get(key);
			result += key + ":" + value + "\n";
		}
		
		result += "--- PARAMETERS --- \n";
		for (String key : parameters.keySet()) {
			Collection<String> values = parameters.get(key);
			for (String value : values) {
				result += key + ":" + value + "\n";
			}
		}
		return result;
	}


	
	/** Extracts and parses the query string (the part after the first {@code ?}) of a request target
	 *  into a multi-valued parameter map. */
	private Map<String, List<String>> parseParameters(String requestLine) {
		// Split on the FIRST '?' only: everything after it is the query string, including any
		// further '?' characters (which are legal inside the query and may appear in values,
		// e.g. ?redirect=http://x?y=1). Splitting on every '?' would drop those.
		String[] str = QUERY_STRING_PATTERN.split(requestLine, 2);
		//Parameters exist
		if (str.length > 1) {
			return parseParameters2(str[1]);
		}
		return Collections.emptyMap();
	}
	
	/** Parses an {@code &}/{@code =}-delimited urlencoded parameter string (query or form body) into a
	 *  multi-valued map: percent-decodes keys/values, drops empty values, and caps the parameter count
	 *  ({@link #MAX_PARAMETERS}) to bound memory. */
	private Map<String, List<String>> parseParameters2(String line) {
		Map<String, List<String>> result = new LinkedHashMap<>();
		// Single O(n) char-scan over the &/;-separated pairs rather than a regex split on "&|;":
		// split() would eagerly materialise EVERY segment of a (16 MiB) form body into a String array
		// BEFORE the MAX_PARAMETERS cap could apply — a millions-of-Strings OOM amplification. Here we
		// track only the first '=' of the current segment and allocate (substring/urlDecode) solely
		// for segments that actually carry a value, stopping at the cap with 413 Payload Too Large.
		int paramCount = 0;
		int len = line.length();
		int start = 0;
		int eqPos = -1; // index of the first '=' in the current segment, or -1
		for (int i = 0; i <= len; i++) {
			char c = (i < len) ? line.charAt(i) : '&'; // treat end-of-string as a final delimiter
			if (c == '&' || c == ';') {
				// Segment is line[start, i); a parameter only counts if it has '=' and a non-empty
				// value (value = line[eqPos+1, i), non-empty iff eqPos < i-1) — matching the previous
				// "split('=', limit 2) with non-empty [1]" behaviour, including empty/flag-only drops.
				if (eqPos != -1 && eqPos < i - 1) {
					if (++paramCount > MAX_PARAMETERS) {
						throw new HttpException(413, "Payload Too Large",
							"The request has too many parameters");
					}
					String key = urlDecode(line.substring(start, eqPos));
					String value = urlDecode(line.substring(eqPos + 1, i));
					result.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
				}
				start = i + 1;
				eqPos = -1;
			} else if (c == '=' && eqPos == -1) {
				eqPos = i; // first '=' of this segment splits key from value
			}
		}
		// Make it fully unmodifiable for safety
		Map<String, List<String>> unmodifiableResult = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> entry : result.entrySet()) {
			unmodifiableResult.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
		}
		return Collections.unmodifiableMap(unmodifiableResult);
	}	

	/** Validates the method token and maps it to an {@link HttpVerb} ({@code UNKNOWN} for a valid but
	 *  unrecognised method); throws {@link IllegalArgumentException} for a non-token method. */
	private static HttpVerb parseMethod(String method) {
		// The method is an RFC 9110 §5.6.2 token (1*tchar). Validate with a zero-allocation char
		// loop rather than String.matches(), which recompiles the regex (and allocates a Matcher)
		// on every request — this is a per-request hot path.
		if (method.isEmpty()) {
			throw new IllegalArgumentException("Invalid HTTP method: " + method);
		}
		for (int i = 0; i < method.length(); i++) {
			if (!isTchar(method.charAt(i))) {
				throw new IllegalArgumentException("Invalid HTTP method: " + method);
			}
		}
		try {
			return HttpVerb.valueOf(method);
		} catch (IllegalArgumentException e) {
			return HttpVerb.UNKNOWN;
		}
	}

	/** RFC 9110 §5.6.2 tchar: any VCHAR except delimiters. */
	private static boolean isTchar(char c) {
		if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
			return true;
		}
		switch (c) {
			case '!': case '#': case '$': case '%': case '&': case '\'': case '*':
			case '+': case '-': case '.': case '^': case '_': case '`': case '|': case '~':
				return true;
			default:
				return false;
		}
	}

	/** Strictly validates the request line: length (414 if over the limit), no control characters,
	 *  exactly three single-SP-separated tokens, a valid method, and a supported version (505 else). */
	private static void validateRequestLine(String requestLine) throws ProtocolException {
		if (requestLine.length() > MAX_REQUEST_LINE_SIZE) {
			// An over-long request line (dominated by the request-target) is 414, not 400.
			throw new HttpException(414, "URI Too Long", "The request line / URI is too long");
		}
		for (int i = 0; i < requestLine.length(); i++) {
			char c = requestLine.charAt(i);
			if (c < 0x20 || c == 0x7f) {
				throw new ProtocolException("Request line contains control character");
			}
		}
		String[] elements = REQUEST_LINE_PATTERN.split(requestLine);
		if (elements.length != 3 || elements[1].isEmpty()) {
			throw new ProtocolException("Invalid request line");
		}
		try {
			parseMethod(elements[0]);
		} catch (IllegalArgumentException e) {
			throw new ProtocolException(e.getMessage());
		}
		if (!"HTTP/1.1".equals(elements[2]) && !"HTTP/1.0".equals(elements[2])) {
			throw new HttpException(505, "HTTP Version Not Supported", "The requested HTTP version is not supported");
		}
	}

	/** Normalises an authority for comparison by dropping the default port for the scheme (80 for
	 *  http, 443 for https), so {@code example.com:80} and {@code example.com} match. Handles IPv6
	 *  literal addresses by skipping colons inside brackets. */
	private static String normalizeAuthority(String authority, String scheme) {
		String host = authority;
		String portStr = null;
		if (authority.startsWith("[")) {
			int closeBracket = authority.indexOf(']');
			if (closeBracket > 0) {
				host = authority.substring(0, closeBracket + 1);
				if (closeBracket + 1 < authority.length() && authority.charAt(closeBracket + 1) == ':') {
					portStr = authority.substring(closeBracket + 2);
				}
			}
		} else {
			int colon = authority.lastIndexOf(':');
			if (colon != -1) {
				host = authority.substring(0, colon);
				portStr = authority.substring(colon + 1);
			}
		}
		if (portStr == null) {
			return host;
		}
		if (portStr.equals("80") && "http".equalsIgnoreCase(scheme)) {
			return host;
		}
		if (portStr.equals("443") && "https".equalsIgnoreCase(scheme)) {
			return host;
		}
		return authority;
	}

	/** True if a header name must not appear in a chunked trailer (framing/routing/auth-critical
	 *  fields), whose presence there would be a request-smuggling vector. */
	private static boolean isProhibitedTrailer(String key) {
		return "host".equals(key) ||
		       "transfer-encoding".equals(key) || 
		       "content-length".equals(key) || 
		       "trailer".equals(key) || 
		       "connection".equals(key) || 
		       "authorization".equals(key) || 
		       "expect".equals(key) || 
		       "keep-alive".equals(key);
	}

	/** Chunked-encoding trailer fields received after the final chunk (unmodifiable; empty if none). */
	public Map<String, String> getTrailers() {
		return um_trailers;
	}

	/** Case-insensitive lookup of a single trailer field value, or null if absent. */
	public String getTrailer(String name) {
		if (name == null) return null;
		return trailers.get(name.toLowerCase(Locale.ROOT));
	}

	/** Content negotiation: picks the best of {@code supportedLanguages} for the request's
	 *  {@code Accept-Language} (honouring q-order, {@code *}, and prefix matches like {@code en} →
	 *  {@code en-US}); falls back to the first supported when the header is absent, or null if none
	 *  match. */
	public String getPreferredLanguage(List<String> supportedLanguages) {
		String acceptLanguage = getHeader("Accept-Language");
		if (acceptLanguage == null || acceptLanguage.trim().isEmpty()) {
			return supportedLanguages.isEmpty() ? null : supportedLanguages.get(0);
		}
		List<String> parsed = org.deftserver.util.HttpUtil.parseAcceptHeader(acceptLanguage);
		for (String accepted : parsed) {
			if (accepted.equals("*")) {
				return supportedLanguages.isEmpty() ? null : supportedLanguages.get(0);
			}
			for (String supported : supportedLanguages) {
				if (accepted.equalsIgnoreCase(supported)) {
					return supported;
				}
				if (supported.toLowerCase(Locale.ROOT).startsWith(accepted.toLowerCase(Locale.ROOT) + "-")) {
					return supported;
				}
			}
		}
		return null;
	}

	/** Content negotiation: picks the best of {@code supportedCharsets} for the request's
	 *  {@code Accept-Charset} (q-order, {@code *}); first supported if absent, null if none match. */
	public String getPreferredCharset(List<String> supportedCharsets) {
		String acceptCharset = getHeader("Accept-Charset");
		if (acceptCharset == null || acceptCharset.trim().isEmpty()) {
			return supportedCharsets.isEmpty() ? null : supportedCharsets.get(0);
		}
		List<String> parsed = org.deftserver.util.HttpUtil.parseAcceptHeader(acceptCharset);
		for (String accepted : parsed) {
			if (accepted.equals("*")) {
				return supportedCharsets.isEmpty() ? null : supportedCharsets.get(0);
			}
			for (String supported : supportedCharsets) {
				if (accepted.equalsIgnoreCase(supported)) {
					return supported;
				}
			}
		}
		return null;
	}

	/** Content negotiation: picks the best of {@code supportedEncodings} for the request's
	 *  {@code Accept-Encoding} (q-order, {@code *}), treating {@code identity} as acceptable by
	 *  default unless explicitly excluded; null if nothing acceptable matches. */
	public String getPreferredEncoding(List<String> supportedEncodings) {
		String acceptEncoding = getHeader("Accept-Encoding");
		if (acceptEncoding == null || acceptEncoding.trim().isEmpty()) {
			return supportedEncodings.isEmpty() ? null : supportedEncodings.get(0);
		}
		List<String> parsed = org.deftserver.util.HttpUtil.parseAcceptHeader(acceptEncoding);
		for (String accepted : parsed) {
			if (accepted.equals("*")) {
				return supportedEncodings.isEmpty() ? null : supportedEncodings.get(0);
			}
			for (String supported : supportedEncodings) {
				if (accepted.equalsIgnoreCase(supported)) {
					return supported;
				}
			}
		}
		// identity is acceptable by default, but NOT if the client explicitly excluded it via
		// "identity;q=0" or "*;q=0" (RFC 9110 §12.5.3).
		if (supportedEncodings.contains("identity") && identityAcceptable(acceptEncoding)) {
			return "identity";
		}
		return null;
	}

	/** Whether the "identity" coding is acceptable given a raw Accept-Encoding header (RFC 9110
	 *  §12.5.3): a specific "identity" entry wins; otherwise a "*" entry applies; otherwise it is
	 *  acceptable by default. */
	private static boolean identityAcceptable(String acceptEncoding) {
		double identityQ = -1, starQ = -1;
		for (String part : acceptEncoding.split(",")) {
			part = part.trim();
			if (part.isEmpty()) continue;
			String[] cv = part.split(";", 2);
			String coding = cv[0].trim();
			double q = 1.0;
			if (cv.length > 1) {
				String p = cv[1].trim();
				if (p.startsWith("q=")) {
					try {
						q = Double.parseDouble(p.substring(2).trim());
					} catch (NumberFormatException e) {
						q = 0.0;
					}
				}
			}
			if (coding.equalsIgnoreCase("identity")) {
				identityQ = q;
			} else if (coding.equals("*")) {
				starQ = q;
			}
		}
		if (identityQ >= 0) return identityQ > 0;
		if (starQ >= 0) return starQ > 0;
		return true;
	}

	/** True if {@code name} is a valid (non-empty) RFC 9110 token, i.e. a well-formed header field name. */
	private static boolean isHeaderName(String name) {
		// A field-name is an RFC 9110 §5.6.2 token (1*tchar). Zero-allocation char loop rather than
		// String.matches() — this runs for every header line of every request. (Names reach here
		// already lower-cased; isTchar is a correct superset of the lower-case token set.)
		if (name.isEmpty()) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			if (!isTchar(name.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/** Adds a (lower-cased) header, combining duplicates per RFC: comma-joins repeats, but rejects a
	 *  conflicting duplicate Content-Length and any duplicate Host (smuggling defences) and uses
	 *  {@code "; "} to join Cookie values. */
	private static void addHeader(Map<String, String> headers, String key, String val) throws ProtocolException {
		String existing = headers.get(key);
		if (existing == null) {
			headers.put(key, val);
			return;
		}
		if ("content-length".equals(key)) {
			if (!existing.equals(val)) {
				throw new ProtocolException("Conflicting Content-Length headers");
			}
			return;
		}
		if ("host".equals(key)) {
			throw new ProtocolException("Multiple Host headers");
		}
		if ("cookie".equals(key)) {
			headers.put(key, existing + "; " + val);
		} else {
			headers.put(key, existing + ", " + val);
		}
	}

	/** True if the comma-separated Connection header lists the given (case-insensitive) token,
	 *  using {@link java.util.Locale#ROOT} to avoid Turkish-locale case-mapping bugs. */
	private static boolean connectionHasToken(String connectionHeader, String token) {
		if (connectionHeader == null) return false;
		for (String t : connectionHeader.split(",")) {
			if (t.trim().toLowerCase(java.util.Locale.ROOT).equals(token.toLowerCase(java.util.Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Interprets the request Transfer-Encoding and returns whether the body is chunked. Per RFC
	 * 9112 §6.1/§6.3, for a request the chunked coding MUST be the FINAL coding — otherwise the
	 * message length can't be determined and the request is a smuggling hazard, so we reject (400).
	 * "chunked" appearing more than once is likewise rejected.
	 */
	private static boolean parseTransferEncoding(String transferEncoding) throws ProtocolException {
		if (transferEncoding == null || transferEncoding.trim().isEmpty()) {
			return false;
		}
		// Collect non-empty codings (empty list elements from stray/trailing commas are ignored).
		java.util.List<String> codings = new ArrayList<>();
		int chunkedCount = 0;
		for (String raw : transferEncoding.split(",")) {
			String coding = raw.trim();
			if (coding.isEmpty()) {
				continue;
			}
			codings.add(coding);
			if (coding.equalsIgnoreCase("chunked")) {
				chunkedCount++;
			}
		}
		if (codings.isEmpty()) {
			return false;
		}
		if (chunkedCount > 1) {
			throw new ProtocolException("chunked Transfer-Encoding applied more than once");
		}
		// chunked MUST be the final coding (RFC 9112 §6.1/§6.3); otherwise the body length is
		// undeterminable — a request-smuggling hazard — so reject with 400.
		if (chunkedCount == 0 || !codings.get(codings.size() - 1).equalsIgnoreCase("chunked")) {
			throw new ProtocolException("chunked must be the final Transfer-Encoding coding");
		}
		// chunked is present and final, so the framing is unambiguous — but if any *other* coding
		// precedes it (e.g. "gzip, chunked") we cannot decode that transfer-coding layer and would
		// otherwise hand the handler still-encoded bytes. RFC 9112 §6.1: an unsupported transfer
		// coding is 501 Not Implemented. (chunked is the only coding we decode.)
		if (codings.size() > 1) {
			throw new HttpException(501, "Not Implemented", "Unsupported transfer-coding");
		}
		return true;
	}

	/** True if the request is expected to carry a body (chunked, or a positive Content-Length). */
	private boolean hasRequestBody() {
		return chunked || contentLength > 0;
	}

	/** Slices up to {@code maxBytes} of the available body bytes from {@code source} (advancing it);
	 *  a non-positive {@code maxBytes} means the body has exceeded its cap → 413. */
	private static ByteBuffer readAvailableBodyBytes(ByteBuffer source, int maxBytes) throws ProtocolException {
		if (maxBytes <= 0) {
			throw new HttpException(413, "Payload Too Large", "The request body exceeds the maximum permitted size");
		}
		int bytes = Math.min(source.remaining(), maxBytes);
		ByteBuffer slice = source.slice();
		slice.limit(bytes);
		source.position(source.position() + bytes);
		return slice;
	}

	/** Decodes as many complete chunks as are currently buffered into {@code chunkedBody}, enforcing
	 *  strict chunk-size syntax and the body-size cap. Returns true when the terminating 0-chunk (and
	 *  its trailers) have been consumed, false if more data is needed (position restored). */
	private boolean tryDecodeChunkedBody() throws ProtocolException {
		while (true) {
			int mark = rawBody.position();
			String sizeLine = readAsciiLine(rawBody);
			if (sizeLine == null) {
				rawBody.position(mark);
				return false;
			}
			int semicolon = sizeLine.indexOf(';');
			String sizeText = (semicolon == -1 ? sizeLine : sizeLine.substring(0, semicolon)).trim();
			// RFC 9112 §7.1: chunk-size is 1*HEXDIG. Validate strictly — Integer.parseInt(.,16)
			// would otherwise accept a leading sign ("+5"), a classic smuggling discrepancy with
			// stricter proxies. (.trim() above only absorbs RFC-permitted BWS before the chunk-ext.)
			if (sizeText.isEmpty()) {
				throw new ProtocolException("Invalid chunk size");
			}
			for (int i = 0; i < sizeText.length(); i++) {
				char c = sizeText.charAt(i);
				if ((c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) {
					throw new ProtocolException("Invalid chunk size");
				}
			}
			final int chunkSize;
			try {
				chunkSize = Integer.parseInt(sizeText, 16);
			} catch (NumberFormatException e) {
				// A syntactically-valid but oversized hex value (> Integer.MAX_VALUE) overflows int;
				// it necessarily exceeds the 16 MiB body cap, so report 413 rather than 400.
				throw new HttpException(413, "Payload Too Large", "The request body exceeds the maximum permitted size");
			}
			// Use long arithmetic: a near-Integer.MAX_VALUE chunk size would otherwise overflow
			// the int addition to a negative value and slip past the size cap.
			if (chunkSize < 0) {
				throw new ProtocolException("Invalid chunk size");
			}
			if ((long) chunkedBody.size() + chunkSize > MAX_BODY_SIZE) {
				// Decoded chunked body exceeds the cap → 413 Payload Too Large.
				throw new HttpException(413, "Payload Too Large", "The request body exceeds the maximum permitted size");
			}
			if (chunkSize == 0) {
				if (!consumeChunkTerminator(rawBody)) {
					rawBody.position(mark);
					return false;
				}
				return true;
			}
			if (rawBody.remaining() < chunkSize + 2) {
				rawBody.position(mark);
				return false;
			}
			chunkedBody.write(rawBody.array(), rawBody.arrayOffset() + rawBody.position(), chunkSize);
			rawBody.position(rawBody.position() + chunkSize);
			if (rawBody.get() != '\r' || rawBody.get() != '\n') {
				throw new ProtocolException("Invalid chunk terminator");
			}
		}
	}

	/** Reads one CRLF-terminated US-ASCII line from the buffer (consuming the CRLF), or returns null
	 *  and restores the position if a complete line isn't yet available. */
	private static String readAsciiLine(ByteBuffer buffer) {
		int start = buffer.position();
		while (buffer.remaining() >= 2) {
			byte b = buffer.get();
			if (b == '\r' && buffer.get(buffer.position()) == '\n') {
				int end = buffer.position() - 1;
				byte[] bytes = new byte[end - start];
				buffer.position(start);
				buffer.get(bytes);
				buffer.get(); // \r
				buffer.get(); // \n
				return new String(bytes, StandardCharsets.US_ASCII);
			}
		}
		buffer.position(start);
		return null;
	}

	/** Consumes the trailer block that follows the final 0-chunk, validating and committing the
	 *  (count-capped, prohibited-field-filtered) trailers only once the whole block has arrived;
	 *  returns false (restoring position) if it is not yet complete. */
	private boolean consumeChunkTerminator(ByteBuffer buffer) throws ProtocolException {
		// Accumulate trailers into a local map and only commit once the full trailer block
		// (ending in a blank line) has been read. If the block is split across socket reads
		// the caller resets the buffer position and re-invokes us; committing incrementally
		// would otherwise re-add (and duplicate) already-parsed trailer values on each retry.
		Map<String, String> pending = new LinkedHashMap<>();
		String trailerOrBlank;
		int trailerCount = 0;
		do {
			trailerOrBlank = readAsciiLine(buffer);
			if (trailerOrBlank == null) return false;
			if (!trailerOrBlank.isEmpty()) {
				// Bound the trailer count just like the header count — a 16 MiB chunked body of
				// tiny trailers would otherwise amplify into millions of map entries (OOM).
				if (++trailerCount > MAX_HEADER_COUNT) {
					throw new HttpException(431, "Request Header Fields Too Large",
						"Too many chunk trailer fields");
				}
				int colon = trailerOrBlank.indexOf(':');
				if (colon <= 0) {
					throw new ProtocolException("Invalid chunk trailer");
				}
				String rawKey = trailerOrBlank.substring(0, colon);
				if (rawKey.endsWith(" ") || rawKey.endsWith("\t")) {
					throw new ProtocolException("Whitespace before trailer colon is forbidden");
				}
				String key = rawKey.trim().toLowerCase(Locale.ROOT);
				String val = trailerOrBlank.substring(colon + 1).trim();
				if (!isHeaderName(key)) {
					throw new ProtocolException("Invalid trailer name");
				}
				if (isProhibitedTrailer(key)) {
					throw new ProtocolException("Prohibited header in chunk trailer: " + key);
				}
				addHeader(pending, key, val);
			}
		} while (!trailerOrBlank.isEmpty());
		trailers.putAll(pending);
		return true;
	}

	/**
	 * Turns a raw request target into a safe path: handles {@code OPTIONS *} and absolute-form
	 * URIs, percent-decodes (UTF-8, preserving a literal {@code +}), rejects null bytes, collapses
	 * dot segments and duplicate slashes, and blocks directory traversal — throwing 400/403 on
	 * attack patterns.
	 * <p>
	 * <b>Security note:</b> percent-decoding includes {@code %2F} (encoded slash), which
	 * decodes to {@code '/'}. If this server is deployed behind a reverse proxy that does
	 * <b>not</b> decode {@code %2F}, the two systems will disagree on the path structure — a
	 * front-end proxy treats {@code /public/%2F../admin} as a single path segment while Dreft
	 * decodes it to {@code /public/../admin} → {@code /admin}, bypassing proxy-level access
	 * controls. Ensure your proxy normalises percent-encoding before forwarding, or configure it
	 * to reject/monitor {@code %2F} in paths.</p>
	 */
	public static String normalizeAndDecodePath(String path) throws HttpException {
		if (path == null || path.isEmpty()) {
			return "/";
		}
		// The asterisk-form request-target (used by "OPTIONS * HTTP/1.1") is not a path and must
		// be passed through verbatim rather than fed to the URI normalizer (which would reject it).
		if (path.equals("*")) {
			return "*";
		}
		if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("//")) {
			try {
				java.net.URI absoluteUri = new java.net.URI(path);
				path = absoluteUri.getRawPath();
				if (path == null || path.isEmpty()) {
					path = "/";
				}
			} catch (Exception e) {
				throw new HttpException(400, "Bad Request", "Malformed absolute URI target");
			}
		}
		try {
			// 1. Percent-decode the path using UTF-8. NOTE: we must NOT use URLDecoder here —
			// it applies form (application/x-www-form-urlencoded) semantics and would turn a
			// literal '+' into a space, which is wrong for a URL path (RFC 3986). Only %XX
			// escapes are decoded; '+' is preserved verbatim.
			String decoded = percentDecodePath(path);

			// 2. Protect against null byte injection
			if (decoded.indexOf('\0') != -1) {
				throw new HttpException(400, "Bad Request", "Null bytes in path are forbidden");
			}
			
			// 3. Collapse duplicate slashes and normalize dot segments (/a/../b)
			java.net.URI uri = new java.net.URI("http", "localhost", decoded, null);
			String normalized = uri.normalize().getPath();
			if (normalized == null || normalized.isEmpty()) {
				normalized = "/";
			}
			
			// Collapse any consecutive slashes (e.g. // -> /)
			normalized = normalized.replaceAll("/{2,}", "/");
			
			// 4. Defend against directory traversal attacks (resolving outside root)
			if (decoded.contains("/../") || decoded.contains("/..") || decoded.endsWith("/..") || decoded.startsWith("../") || decoded.equals("..") ||
				normalized.equals("/..") || normalized.startsWith("/../") || normalized.startsWith("../") || normalized.equals("..")) {
				throw new HttpException(403, "Forbidden", "Directory traversal attempt blocked");
			}
			
			return normalized;
		} catch (HttpException he) {
			throw he;
		} catch (Exception e) {
			throw new HttpException(400, "Bad Request", "Malformed URI path");
		}
	}

	/** Percent-decodes a query/form parameter value using form semantics ({@code +} → space, UTF-8);
	 *  a malformed escape throws {@link IllegalArgumentException} (surfaced as a 400). */
	private static String urlDecode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	/**
	 * Percent-decodes a URL path. Unlike {@link URLDecoder}, a literal '+' is preserved (it
	 * is an ordinary path character per RFC 3986, not an encoded space). The incoming string
	 * carries raw request bytes as ISO-8859-1 chars (0-255); decoded bytes are reinterpreted
	 * as UTF-8. Malformed/truncated escapes raise a 400.
	 */
	private static String percentDecodePath(String path) throws HttpException {
		int len = path.length();
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(len);
		for (int i = 0; i < len; i++) {
			char c = path.charAt(i);
			if (c == '%') {
				if (i + 2 >= len) {
					throw new HttpException(400, "Bad Request", "Truncated percent-encoding in path");
				}
				int hi = Character.digit(path.charAt(i + 1), 16);
				int lo = Character.digit(path.charAt(i + 2), 16);
				if (hi < 0 || lo < 0) {
					throw new HttpException(400, "Bad Request", "Invalid percent-encoding in path");
				}
				out.write((hi << 4) | lo);
				i += 2;
			} else {
				out.write(c & 0xFF);
			}
		}
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}
	
	/** Searches the buffer from its current position for the byte sequence {@code bytes}; on a match
	 *  positions the buffer just after it and returns true, otherwise positions at the limit and
	 *  returns false. */
	public static final boolean findInBB(ByteBuffer buffer, byte[] bytes) {
		int len = bytes.length;
		if (len == 0) return true;
		int start = buffer.position();
		int limit = buffer.limit();
		
		for (int i = start; i <= limit - len; i++) {
			boolean match = true;
			for (int j = 0; j < len; j++) {
				if (buffer.get(i + j) != bytes[j]) {
					match = false;
					break;
				}
			}
			if (match) {
				buffer.position(i + len);
				return true;
			}
		}
		buffer.position(limit);
		return false;
	}
	
	/** Tests whether the buffer's bytes at the current position equal {@code bytes}; on a match (and
	 *  always, when long enough) advances past them. {@code advanceIfUndersized} controls whether the
	 *  position jumps to the limit when there aren't enough bytes. */
	public static final boolean expectInBB(ByteBuffer buffer, byte[] bytes, boolean advanceIfUndersized) {
		int len = bytes.length;
		if (buffer.remaining() < len) {
			if (advanceIfUndersized) buffer.position(buffer.limit());
			return false;
		}
		int pos = buffer.position();
		for (int i = 0; i < len; i++) {
			if (buffer.get(pos + i) != bytes[i]) {
				buffer.position(pos + len);
				return false;
			}
		}
		buffer.position(pos + len);
		return true;
	}

	private Map<String, String> parsedCookies = null;

	/** Parses (lazily, then caches) the request {@code Cookie} header into a name → value map,
	 *  unquoting double-quoted values. */
	public Map<String, String> getCookies() {
		if (parsedCookies == null) {
			String cookieHeader = getHeader("Cookie");
			if (cookieHeader == null || cookieHeader.trim().isEmpty()) {
				parsedCookies = Collections.emptyMap();
			} else {
				Map<String, String> cookies = new LinkedHashMap<>();
				// Single O(n) char-scan over the ';'-separated cookie pairs instead of split(";"),
				// which would materialise every segment up front (same amplification pattern as P50,
				// here bounded by the 64 KiB header cap). Only pairs containing '=' allocate.
				int len = cookieHeader.length();
				int start = 0;
				int eqPos = -1; // index of the first '=' in the current pair, or -1
				for (int i = 0; i <= len; i++) {
					char c = (i < len) ? cookieHeader.charAt(i) : ';';
					if (c == ';') {
						if (eqPos != -1) {
							String key = cookieHeader.substring(start, eqPos).trim();
							String val = cookieHeader.substring(eqPos + 1, i).trim();
							if (val.length() >= 2 && val.charAt(0) == '"' && val.charAt(val.length() - 1) == '"') {
								val = val.substring(1, val.length() - 1);
							}
							cookies.put(key, val);
						}
						start = i + 1;
						eqPos = -1;
					} else if (c == '=' && eqPos == -1) {
						eqPos = i;
					}
				}
				parsedCookies = Collections.unmodifiableMap(cookies);
			}
		}
		return parsedCookies;
	}

	/** The value of a single request cookie by name, or null if absent. */
	public String getCookie(String name) {
		return getCookies().get(name);
	}

	/** Content negotiation: picks the best of {@code supportedTypes} for the request's {@code Accept}
	 *  header (honouring q-order and {@code type/*} / {@code *​/*} wildcards), or null if none match. */
	public String getPreferredContentType(List<String> supportedTypes) {
		String accept = getHeader("Accept");
		if (accept == null || accept.trim().isEmpty()) {
			return supportedTypes.isEmpty() ? null : supportedTypes.get(0);
		}
		List<String> parsed = org.deftserver.util.HttpUtil.parseAcceptHeader(accept);
		for (String accepted : parsed) {
			if (accepted.equals("*/*")) {
				return supportedTypes.isEmpty() ? null : supportedTypes.get(0);
			}
			for (String supported : supportedTypes) {
				if (accepted.equalsIgnoreCase(supported)) {
					return supported;
				}
				if (accepted.endsWith("/*")) {
					String prefix = accepted.substring(0, accepted.length() - 1);
					if (supported.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
						return supported;
					}
				}
			}
		}
		return null;
	}

	/** The client IP, preferring proxy headers ({@code X-Forwarded-For} first hop, then
	 *  {@code X-Real-IP}) and falling back to the actual socket address. NB these headers are
	 *  client-supplied and spoofable — trust them only behind a trusted proxy. */
	public String getClientIP() {
		String xff = getHeader("X-Forwarded-For");
		if (xff != null && !xff.isEmpty()) {
			String[] ips = xff.split(",");
			return ips[0].trim();
		}
		String xri = getHeader("X-Real-IP");
		if (xri != null && !xri.isEmpty()) {
			return xri.trim();
		}
		return remoteHost != null ? remoteHost.getHostAddress() : null;
	}

	/** The request scheme ({@code http}/{@code https}), preferring {@code X-Forwarded-Proto}, then the
	 *  authoritative TLS flag, then a well-known-port heuristic. (Proxy header is spoofable.) */
	public String getScheme() {
		String xfp = getHeader("X-Forwarded-Proto");
		if (xfp != null && !xfp.isEmpty()) {
			return xfp.trim().toLowerCase(Locale.ROOT);
		}
		// Authoritative: the protocol layer tells us if this connection is TLS, so https is correct
		// even on a non-standard port. Fall back to the well-known-port heuristic otherwise.
		if (secure) {
			return "https";
		}
		return (serverPort == 8443 || serverPort == 443) ? "https" : "http";
	}

	/** The request host, preferring {@code X-Forwarded-Host} (spoofable — trust only behind a proxy)
	 *  and falling back to the {@code Host} header. */
	public String getHost() {
		String xfh = getHeader("X-Forwarded-Host");
		if (xfh != null && !xfh.isEmpty()) {
			return xfh.trim();
		}
		return getHeader("Host");
	}

	/**
	 * Parses flat parameter maps containing PHP-style nested bracket notations
	 * (e.g. "formel1[0][1]", "formel1[hobbies][]") into a real, nested multidimensional Map hierarchy structure.
	 * Combines GET query parameters, POST urlencoded body parameters, and POST multipart fields natively.
	 */
	@SuppressWarnings("unchecked")
	public Map<String, Object> getParametersTree() {
		Map<String, List<String>> combined = new LinkedHashMap<>();
		if (parameters != null) {
			for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
				combined.put(entry.getKey(), new ArrayList<>(entry.getValue()));
			}
		}
		if (postParameters != null) {
			for (Map.Entry<String, List<String>> entry : postParameters.entrySet()) {
				combined.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
			}
		}
		if (mpParts != null) {
			// Iterate every part (not the name-keyed map) so multiple values for the same field
			// name are all included in the tree.
			for (Part part : mpPartsAll) {
				String key = part.getMapName();
				HeadKeyVals cd = part.getHeadKeyVal("Content-Disposition");
				boolean isFile = cd != null && cd.getVals().containsKey("filename");
				if (!isFile && part.getData() != null) {
					combined.computeIfAbsent(key, k -> new ArrayList<>()).add(part.getData());
				}
			}
		}
		return parseParametersTree(combined);
	}

	/** Builds the nested map from flat bracket-notation parameter names by splitting each into
	 *  segments ({@link #parseSegments}) and inserting its value(s) ({@link #insertValue}). */
	private Map<String, Object> parseParametersTree(Map<String, ? extends Collection<String>> flatParams) {
		Map<String, Object> root = new LinkedHashMap<>();
		// Per-map running "next append index" (= 1 + the largest non-negative integer key inserted so
		// far). Lets an empty "[]" segment resolve its index in O(1) instead of re-scanning every key
		// of the map on each append (the old getNextIntegerKey), which was O(n^2) for a form posting a
		// large array under one name (e.g. thousands of a[]=...). Identity-keyed: each nested map is a
		// distinct node. Kept exactly equivalent to the old max-key+1 behaviour.
		Map<Map<String, Object>, Integer> autoIndex = new java.util.IdentityHashMap<>();
		for (Map.Entry<String, ? extends Collection<String>> entry : flatParams.entrySet()) {
			String rawName = entry.getKey();
			Collection<String> values = entry.getValue();
			List<String> segments = parseSegments(rawName);
			for (String val : values) {
				insertValue(root, segments, 0, val, autoIndex);
			}
		}
		return root;
	}

	/** Cap on PHP-style bracket nesting depth. {@link #insertValue} recurses once per segment,
	 *  so without a bound an attacker-supplied name like {@code a[x][x]...} (thousands of
	 *  brackets) would overflow the stack — a StackOverflowError is an Error, not a
	 *  RuntimeException, so it would bypass the dispatcher's catch and kill the I/O loop. */
	private static final int MAX_PARAM_NESTING_DEPTH = 32;

	/** Splits a PHP-style bracketed parameter name (e.g. {@code user[address][city]}) into its path
	 *  segments ({@code [user, address, city]}); an empty {@code []} segment means "append". Stops at
	 *  {@link #MAX_PARAM_NESTING_DEPTH}, keeping the rest as one segment, to bound recursion. */
	private List<String> parseSegments(String rawName) {
		List<String> segments = new ArrayList<>();
		int firstBracket = rawName.indexOf('[');
		if (firstBracket == -1) {
			segments.add(rawName);
			return segments;
		}
		segments.add(rawName.substring(0, firstBracket));
		int i = firstBracket;
		while (i < rawName.length()) {
			if (rawName.charAt(i) == '[') {
				// Stop nesting at the depth cap: keep the remainder of the name as a single
				// final segment so no data is lost and recursion stays bounded.
				if (segments.size() >= MAX_PARAM_NESTING_DEPTH) {
					segments.add(rawName.substring(i));
					break;
				}
				int close = rawName.indexOf(']', i);
				if (close != -1) {
					String segment = rawName.substring(i + 1, close);
					segments.add(segment);
					i = close + 1;
				} else {
					segments.add(rawName.substring(i + 1));
					break;
				}
			} else {
				i++;
			}
		}
		return segments;
	}

	/** Inserts {@code value} into the nested map at the path given by {@code segments} from
	 *  {@code index}, creating intermediate maps and auto-numbering empty ({@code []}) segments.
	 *  Recurses once per segment (bounded by the segment count from {@link #parseSegments}). */
	@SuppressWarnings("unchecked")
	private void insertValue(Map<String, Object> currentMap, List<String> segments, int index, String value,
			Map<Map<String, Object>, Integer> autoIndex) {
		String segment = segments.get(index);
		String key;
		if (segment.isEmpty()) {
			// Array-style "[]" append: take the running next index for this map in O(1).
			int nextKey = autoIndex.getOrDefault(currentMap, 0);
			key = String.valueOf(nextKey);
			autoIndex.put(currentMap, nextKey + 1);
		} else {
			key = segment;
			// Keep the running index in step with an explicit non-negative integer key, so a later
			// "[]" append uses max(existing int keys)+1 — exactly the old getNextIntegerKey behaviour.
			int parsed = asNonNegativeIntKey(segment);
			if (parsed >= 0) {
				int cur = autoIndex.getOrDefault(currentMap, 0);
				if (parsed + 1 > cur) {
					autoIndex.put(currentMap, parsed + 1);
				}
			}
		}
		if (index == segments.size() - 1) {
			currentMap.put(key, value);
			return;
		}
		Object child = currentMap.get(key);
		if (!(child instanceof Map)) {
			child = new LinkedHashMap<String, Object>();
			currentMap.put(key, child);
		}
		insertValue((Map<String, Object>) child, segments, index + 1, value, autoIndex);
	}

	/** Parses {@code s} as a non-negative {@code int} key, or returns -1 if it isn't one. Matches the
	 *  classification the old {@code getNextIntegerKey} got from {@link Integer#parseInt} (values that
	 *  overflow {@code int} return -1, just as the old code's caught NumberFormatException ignored
	 *  them), but cheaply rejects the common non-numeric key on its first character so no exception is
	 *  thrown on the hot path. */
	private static int asNonNegativeIntKey(String s) {
		if (s.isEmpty()) {
			return -1;
		}
		char c0 = s.charAt(0);
		if (c0 < '0' || c0 > '9') {
			return -1;
		}
		try {
			int v = Integer.parseInt(s);
			return v >= 0 ? v : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
