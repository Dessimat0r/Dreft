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
		
		public Map<String, String> getVals() {
			return um_vals;
		}
		
		public String getKey() {
			return key;
		}
		
		public String getVal() {
			return val;
		}
		
		@Override
		public String toString() {
			return key + ": " + val + " {" + vals + "}";
		}
	}
	
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
		
		public String getMapName() {
			return mapName;
		}
		
		public int getNum() {
			return num;
		}
		
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

		public byte[] getRawData() {
			return rawData;
		}
		
		public String getData() {
			return data;
		}
		
		public boolean isComplete() {
			return complete;
		}
	}

	/** Regex to parse HttpRequest Request Line */
	public static final Pattern REQUEST_LINE_PATTERN = Pattern.compile(" ") ;
	/** Regex to parse out QueryString from HttpRequest */
	public static final Pattern QUERY_STRING_PATTERN = Pattern.compile("\\?") ;
	/** Regex to parse out parameters from query string */
	public static final Pattern PARAM_STRING_PATTERN = Pattern.compile("\\&|;");  //Delimiter is either & or ;
	/** Regex to parse out key/value pairs */
	public static final Pattern KEY_VALUE_PATTERN = Pattern.compile("=");
	/** Regex to parse raw headers and body */
	public static final Pattern RAW_VALUE_PATTERN = Pattern.compile("\\r\\n\\r\\n"); //TODO fix a better regexp for this
	/** Regex to parse raw headers from body */
	public static final Pattern HEADERS_BODY_PATTERN = Pattern.compile("\\r\\n");
	/** Regex to parse header name and value */
	public static final Pattern HEADER_VALUE_PATTERN = Pattern.compile(": ");
	/** Regex to parse header name and value */
	public static final Pattern HEADER_VAL_SPLIT_PATTERN = Pattern.compile("\\; ");	
	
	public static final String HTTP_HEAD_TERM       = "\r\n\r\n";
	public static final byte[] HTTP_HEAD_TERM_BYTES = HTTP_HEAD_TERM.getBytes(StandardCharsets.ISO_8859_1);
	private static final int MAX_REQUEST_LINE_SIZE = 8 * 1024;
	private static final int MAX_HEADER_SIZE = 64 * 1024;
	private static final int MAX_HEADER_COUNT = 100;
	static final int MAX_BODY_SIZE = 16 * 1024 * 1024;
	/** Cap on the number of multipart parts, to bound the object/memory amplification a body of
	 *  many tiny parts would otherwise cause (a 16 MiB body could hold ~300k minimal parts). */
	static final int MAX_MULTIPART_PARTS = 10000;
	
	public static final String MP_START_TERM       = "\r\n";
	public static final byte[] MP_START_TERM_BYTES = MP_START_TERM.getBytes(StandardCharsets.ISO_8859_1);
	
	public static final byte[] SPACE_BYTES = " ".getBytes(StandardCharsets.ISO_8859_1);
	public static final byte[] MP_SEP_BYTES = "--".getBytes(StandardCharsets.ISO_8859_1);
	public static final byte[] MP_SEP_END_BYTES = "--\r\n".getBytes(StandardCharsets.ISO_8859_1);
	public static final byte[] MP_END_BYTES = "\r\n".getBytes(StandardCharsets.ISO_8859_1);
	
	protected ByteBuffer rawBody            = null;
	protected int        contentLength      = -1;
	protected String     contentType        = null;
	protected boolean    multipart          = false;
	protected String     multipartBoundary  = null;
	protected byte[]     multipartBoundaryB = null;
	protected byte[]     mpBoundaryBStart   = null;
	protected byte[]     mpBoundaryBPre     = null;
	protected byte[]     mpBoundaryBActual  = null;
	protected byte[]     mpBoundaryBFinish  = null;
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
			String[] pathFrags = QUERY_STRING_PATTERN.split(elements[1]);
			requestedPath = normalizeAndDecodePath(pathFrags[0]);
			
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

	public HttpRequest(int requestNum, String requestLine, Map<String, String> headers, ByteBuffer buffer) throws IOException {
		this.requestNum  = requestNum;
		this.requestLine = requestLine;
		if (requestLine != null && headers != null) {
			String[] elements = REQUEST_LINE_PATTERN.split(requestLine);
			if (elements.length != 3) {
				throw new ProtocolException("Invalid request line");
			}
			method = parseMethod(elements[0]);
			String[] pathFrags = QUERY_STRING_PATTERN.split(elements[1]);
			requestedPath = normalizeAndDecodePath(pathFrags[0]);
			
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
			
			parameters = parseParameters(elements[1]);
			chunked = isChunked(headers.get("transfer-encoding"));
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
				if (contentLength < 0 || contentLength > MAX_BODY_SIZE) {
					throw new ProtocolException("Invalid Content-Length: " + clen);
				}
			}
			if (chunked && contentLength >= 0) {
				throw new ProtocolException("Transfer-Encoding and Content-Length cannot both frame a request body");
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
						multipartBoundaryB = multipartBoundary.getBytes(StandardCharsets.ISO_8859_1);
						mpBoundaryBPre    = ("\r\n--" + multipartBoundary).getBytes(StandardCharsets.ISO_8859_1);
						mpBoundaryBStart  = ("--" + multipartBoundary + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
						mpBoundaryBActual = ("\r\n--" + multipartBoundary + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
						mpBoundaryBFinish = ("\r\n--" + multipartBoundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
						multipart = true;
						mpParts = new LinkedHashMap<String, HttpRequest.Part>();
						um_mpParts = Collections.unmodifiableMap(mpParts);
					} else if (contentType.equals("application/x-www-form-urlencoded")) {
						multipart = false;
					}
				}
				if (chunked) {
					rawBody = ByteBuffer.allocate(MAX_BODY_SIZE);
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
	
	public long getRemaining() {
		if (complete) return 0;
		if (chunked) return -1;
		return rawBody == null ? 0 : rawBody.remaining();
	}
	
	public boolean isComplete() {
		return complete;
	}
	
	public static HttpRequest of(ByteBuffer buffer) {
		try {
			return of(new Application(Collections.emptyMap()), buffer);
		} catch (IOException | RuntimeException e) {
			return MalFormedHttpRequest.instance;
		}
	}

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
				throw new ProtocolException("Header section too large");
			}
			byte[] headerBytes = new byte[headerLen];
			buffer.position(oldpos);
			buffer.get(headerBytes);
			buffer.position(bodystartpos); // Keep exactly at start of body

			String headerStr = new String(headerBytes, StandardCharsets.ISO_8859_1);
			String[] lines = headerStr.split("\r\n");
			if (lines.length == 0 || lines[0].trim().isEmpty()) {
				throw new ProtocolException("Request line is empty/missing!");
			}
			requestLine = lines[0].trim();
			validateRequestLine(requestLine);
			logger.debug("got req. line: {}", requestLine);
			
			generalHeaders = new HashMap<>();
			int headerCount = 0;
			for (int i = 1; i < lines.length; i++) {
				String line = lines[i];
				if (line.isEmpty()) {
					break;
				}
				// RFC 7230 §3.2.4: reject obsolete line folding (a header line starting with
				// SP/HT is an obs-fold continuation). Accepting it risks parser disagreement
				// with upstream proxies (request smuggling), so we reject with 400.
				char first = line.charAt(0);
				if (first == ' ' || first == '\t') {
					throw new ProtocolException("Obsolete header line folding is not allowed");
				}
				if (++headerCount > MAX_HEADER_COUNT) {
					throw new ProtocolException("Too many headers");
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
	
	public boolean isMultipart() {
		return multipart;
	}
	
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
			rawBody.put(readAvailableBodyBytes(buffer, rawBody.remaining()));
			rawBody.flip();
			if (tryDecodeChunkedBody()) {
				complete = true;
			}
			rawBody.compact();
			if (!complete) return false;
			if (!multipart) {
				body = new String(chunkedBody.toByteArray(), StandardCharsets.ISO_8859_1);
				if ("application/x-www-form-urlencoded".equals(contentType)) {
					postParameters = parseParameters2(body);
				}
			} else {
				// Parse the dechunked multipart body. Previously chunked multipart uploads were
				// marked complete but never parsed, so getMultiParts() came back empty.
				parseMultipartParts(ByteBuffer.wrap(chunkedBody.toByteArray()));
			}
			return true;
		}
		
		// put the buffer into rawbody, then put the limit to the current position, position back to before the buffer was fed in
		// to set it up for reading that new data.
		int oldRawPos = rawBody.position();
		logger.debug("raw buffer: {}", new String(buffer.array(), buffer.position(), buffer.remaining(), StandardCharsets.ISO_8859_1));
		logger.debug("rawbody pos (pre buffer dump): {}, limit: {}", rawBody.position(), rawBody.limit());
		logger.debug("buffer remaining: {}, rawbody new pos: {}", buffer.remaining(), rawBody.position() + buffer.remaining());
		int remainingBefore = buffer.remaining();
		rawBody.put(readAvailableBodyBytes(buffer, rawBody.remaining()));
		flipRemain = remainingBefore - (buffer.remaining());
		logger.debug("rawbody pos (post buffer dump): {}, limit: {}", rawBody.position(), rawBody.limit());
		if (!rawBody.hasRemaining()) {
			rawBody.flip();
			if (!multipart) {
				body = new String(rawBody.array(), 0, rawBody.limit(), StandardCharsets.ISO_8859_1);
			}
			complete = true;
		}
		if (!complete) return false;
		
		logger.debug("complete, now parsing; raw body length: {}", rawBody.remaining());
		
		//String raw = new String(rawBody.array(), 0, rawBody.limit(), StandardCharsets.ISO_8859_1);
		//raw putContentData - uncomment for trace-level debugging
		
		if (!multipart) {
			postParameters = parseParameters2(body);
		} else {
			parseMultipartParts(rawBody);
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
					found = expectInBB(rawBody, mpBoundaryBStart, true);
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
					while ((currMpLine = br.readLine()) != null) {
						logger.debug("mp req line: {}", currMpLine.isEmpty() ? "(empty)" : currMpLine);
						if (currMpLine.isEmpty()) {
							gotSep = true;
							break;
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
	
	protected void setIOLoop(IOLoop ioLoop) {
		this.ioLoop = ioLoop;
	}
	
	public IOLoop getIOLoop() {
		return ioLoop;
	}
	
	public String getRequestLine() {
		return requestLine;
	}
	
	public String getRequestedPath() {
		return requestedPath;
	}

	public String getVersion() {
		return version;
	}
	
	public Map<String, String> getHeaders() {
		return um_headers;
	}
	
	public String getHeader(String name) {
		if (headers == null) return null;
		// Headers are stored lowercased with Locale.ROOT; look up with the same locale to
		// avoid the Turkish-locale dotted/dotless-I mismatch (e.g. "Content-Length").
		return headers.get(name.toLowerCase(Locale.ROOT));
	}
	
	public HttpVerb getMethod() {
		return method;
	}
	
	@SuppressWarnings("unchecked")
	public Map<String, Collection<String>> getPostParameters() {
		return postParameters == null ? Collections.emptyMap() : (Map) postParameters;
	}
	
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
	
	@SuppressWarnings("unchecked")
	public Map<String, Collection<String>> getParameters() {
		return (Map) parameters;
	}
	
	public String getBody() {
		return body;
	}	
	
	public InetAddress getRemoteHost() {
		return remoteHost;
	}

	public InetAddress getServerHost() {
		return serverHost;
	}

	public int getRemotePort() {
		return remotePort;
	}

	public int getServerPort() {
		return serverPort;
	}

	protected void setRemoteHost(InetAddress host) {
		remoteHost = host;
	}

	protected void setServerHost(InetAddress host) {
		serverHost = host;
	}

	protected void setRemotePort(int port) {
		remotePort = port;
	}

	protected void setServerPort(int port) {
		serverPort = port;
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
	
	public boolean isKeepAlive() {
		return keepAlive;
	}

	public int getContentLength() {
		return contentLength;
	}
	
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
	
	private Map<String, List<String>> parseParameters2(String line) {
		Map<String, List<String>> result = new LinkedHashMap<>();
		//Parameters exist
		String[] paramArray = PARAM_STRING_PATTERN.split(line);
		for (String keyValue : paramArray) {
			String[] keyValueArray = KEY_VALUE_PATTERN.split(keyValue, 2);
			//We need to check if the parameter has a value associated with it.
			if (keyValueArray.length > 1) {
				if (!keyValueArray[1].isEmpty()) {
					String key = urlDecode(keyValueArray[0]);
					String value = urlDecode(keyValueArray[1]);
					result.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
				}
			}
		}
		// Make it fully unmodifiable for safety
		Map<String, List<String>> unmodifiableResult = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> entry : result.entrySet()) {
			unmodifiableResult.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
		}
		return Collections.unmodifiableMap(unmodifiableResult);
	}	

	private static HttpVerb parseMethod(String method) {
		if (!method.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
			throw new IllegalArgumentException("Invalid HTTP method: " + method);
		}
		try {
			return HttpVerb.valueOf(method);
		} catch (IllegalArgumentException e) {
			return HttpVerb.UNKNOWN;
		}
	}

	private static void validateRequestLine(String requestLine) throws ProtocolException {
		if (requestLine.length() > MAX_REQUEST_LINE_SIZE) {
			throw new ProtocolException("Request line too large");
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

	private static String normalizeAuthority(String authority, String scheme) {
		int colon = authority.indexOf(':');
		if (colon == -1) {
			return authority;
		}
		String host = authority.substring(0, colon);
		String portStr = authority.substring(colon + 1);
		if (portStr.equals("80") && "http".equalsIgnoreCase(scheme)) {
			return host;
		}
		if (portStr.equals("443") && "https".equalsIgnoreCase(scheme)) {
			return host;
		}
		return authority;
	}

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

	public Map<String, String> getTrailers() {
		return um_trailers;
	}

	public String getTrailer(String name) {
		if (name == null) return null;
		return trailers.get(name.toLowerCase(Locale.ROOT));
	}

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
		if (supportedEncodings.contains("identity")) {
			return "identity";
		}
		return null;
	}

	private static boolean isHeaderName(String name) {
		return name.matches("[!#$%&'*+.^_`|~0-9a-z-]+");
	}

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

	/** True if the comma-separated Connection header lists the given (case-insensitive) token. */
	private static boolean connectionHasToken(String connectionHeader, String token) {
		if (connectionHeader == null) return false;
		for (String t : connectionHeader.split(",")) {
			if (t.trim().equalsIgnoreCase(token)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isChunked(String transferEncoding) {
		if (transferEncoding == null) return false;
		for (String value : transferEncoding.split(",")) {
			if ("chunked".equalsIgnoreCase(value.trim())) return true;
		}
		return false;
	}

	private boolean hasRequestBody() {
		return chunked || contentLength > 0;
	}

	private static ByteBuffer readAvailableBodyBytes(ByteBuffer source, int maxBytes) throws ProtocolException {
		if (maxBytes <= 0) {
			throw new ProtocolException("Request body too large");
		}
		int bytes = Math.min(source.remaining(), maxBytes);
		ByteBuffer slice = source.slice();
		slice.limit(bytes);
		source.position(source.position() + bytes);
		return slice;
	}

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
			final int chunkSize;
			try {
				chunkSize = Integer.parseInt(sizeText, 16);
			} catch (NumberFormatException e) {
				throw new ProtocolException("Invalid chunk size");
			}
			// Use long arithmetic: a near-Integer.MAX_VALUE chunk size would otherwise overflow
			// the int addition to a negative value and slip past the size cap.
			if (chunkSize < 0 || (long) chunkedBody.size() + chunkSize > MAX_BODY_SIZE) {
				throw new ProtocolException("Chunked body too large");
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

	private boolean consumeChunkTerminator(ByteBuffer buffer) throws ProtocolException {
		// Accumulate trailers into a local map and only commit once the full trailer block
		// (ending in a blank line) has been read. If the block is split across socket reads
		// the caller resets the buffer position and re-invokes us; committing incrementally
		// would otherwise re-add (and duplicate) already-parsed trailer values on each retry.
		Map<String, String> pending = new LinkedHashMap<>();
		String trailerOrBlank;
		do {
			trailerOrBlank = readAsciiLine(buffer);
			if (trailerOrBlank == null) return false;
			if (!trailerOrBlank.isEmpty()) {
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

	public Map<String, String> getCookies() {
		if (parsedCookies == null) {
			String cookieHeader = getHeader("Cookie");
			if (cookieHeader == null || cookieHeader.trim().isEmpty()) {
				parsedCookies = Collections.emptyMap();
			} else {
				Map<String, String> cookies = new LinkedHashMap<>();
				String[] pairs = cookieHeader.split(";");
				for (String pair : pairs) {
					int eq = pair.indexOf('=');
					if (eq != -1) {
						String key = pair.substring(0, eq).trim();
						String val = pair.substring(eq + 1).trim();
						if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
							val = val.substring(1, val.length() - 1);
						}
						cookies.put(key, val);
					}
				}
				parsedCookies = Collections.unmodifiableMap(cookies);
			}
		}
		return parsedCookies;
	}

	public String getCookie(String name) {
		return getCookies().get(name);
	}

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

	public String getScheme() {
		String xfp = getHeader("X-Forwarded-Proto");
		if (xfp != null && !xfp.isEmpty()) {
			return xfp.trim().toLowerCase(Locale.ROOT);
		}
		return (serverPort == 8443 || serverPort == 443) ? "https" : "http";
	}

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

	private Map<String, Object> parseParametersTree(Map<String, ? extends Collection<String>> flatParams) {
		Map<String, Object> root = new LinkedHashMap<>();
		for (Map.Entry<String, ? extends Collection<String>> entry : flatParams.entrySet()) {
			String rawName = entry.getKey();
			Collection<String> values = entry.getValue();
			List<String> segments = parseSegments(rawName);
			for (String val : values) {
				insertValue(root, segments, 0, val);
			}
		}
		return root;
	}

	/** Cap on PHP-style bracket nesting depth. {@link #insertValue} recurses once per segment,
	 *  so without a bound an attacker-supplied name like {@code a[x][x]...} (thousands of
	 *  brackets) would overflow the stack — a StackOverflowError is an Error, not a
	 *  RuntimeException, so it would bypass the dispatcher's catch and kill the I/O loop. */
	private static final int MAX_PARAM_NESTING_DEPTH = 32;

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

	@SuppressWarnings("unchecked")
	private void insertValue(Map<String, Object> currentMap, List<String> segments, int index, String value) {
		String segment = segments.get(index);
		if (index == segments.size() - 1) {
			if (segment.isEmpty()) {
				int nextKey = getNextIntegerKey(currentMap);
				currentMap.put(String.valueOf(nextKey), value);
			} else {
				currentMap.put(segment, value);
			}
			return;
		}
		String key = segment;
		if (segment.isEmpty()) {
			int nextKey = getNextIntegerKey(currentMap);
			key = String.valueOf(nextKey);
		}
		Object child = currentMap.get(key);
		if (!(child instanceof Map)) {
			child = new LinkedHashMap<String, Object>();
			currentMap.put(key, child);
		}
		insertValue((Map<String, Object>) child, segments, index + 1, value);
	}

	private int getNextIntegerKey(Map<String, Object> map) {
		int max = -1;
		for (String key : map.keySet()) {
			try {
				int val = Integer.parseInt(key);
				if (val > max) max = val;
			} catch (NumberFormatException e) {
				// ignore
			}
		}
		return max + 1;
	}
}
