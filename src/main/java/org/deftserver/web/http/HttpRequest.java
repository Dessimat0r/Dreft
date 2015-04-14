package org.deftserver.web.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.deftserver.io.IOLoop;
import org.deftserver.io.stream.ByteBufferBackedInputStream;
import org.deftserver.web.Application;
import org.deftserver.web.HttpVerb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMultimap;

public class HttpRequest {
	private IOLoop ioLoop;
	private static final Logger logger = LoggerFactory.getLogger(HttpRequest.class);
	
	private final String requestLine;
	private final HttpVerb method;
	private final String requestedPath;	// correct name?
	private final String version; 
	private final Map<String, String> headers;
	private final Map<String, String> um_headers;
	private final ImmutableMultimap<String, String> parameters;
	private String body = null;
	private final boolean keepAlive;
	private InetAddress remoteHost;
	private InetAddress serverHost;
	private int remotePort;
	private int serverPort;
	private long flipRemain = -1;
	
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
	public static final byte[] HTTP_HEAD_TERM_BYTES = HTTP_HEAD_TERM.getBytes(Charsets.ISO_8859_1);
	
	public static final String MP_START_TERM       = "\r\n";
	public static final byte[] MP_START_TERM_BYTES = MP_START_TERM.getBytes(Charsets.ISO_8859_1);
	
	public static final byte[] SPACE_BYTES = " ".getBytes(Charsets.ISO_8859_1);
	public static final byte[] MP_SEP_BYTES = "--".getBytes(Charsets.ISO_8859_1);
	public static final byte[] MP_SEP_END_BYTES = "--\r\n".getBytes(Charsets.ISO_8859_1);
	public static final byte[] MP_END_BYTES = "\r\n".getBytes(Charsets.ISO_8859_1);
	
	protected ByteBuffer rawBody            = null;
	protected int        contentLength      = -1;
	protected String[]   contentTypeArr     = null;
	protected boolean    multipart          = false;
	protected String     multipartBoundary  = null;
	protected byte[]     multipartBoundaryB = null;
	protected byte[]     mpBoundaryBStart   = null;
	protected byte[]     mpBoundaryBPre     = null;
	protected byte[]     mpBoundaryBActual  = null;
	protected byte[]     mpBoundaryBFinish  = null;
	protected boolean    complete           = false;
	protected boolean    parsingBoundary    = false;
	protected boolean    mpFinished         = false;
	
	protected Map<String, Part> mpParts     = null;
	protected Map<String, Part> um_mpParts  = null;
	protected Part              currPart    = null;
	protected final int         requestNum;
	
	/**
	 * Creates a new HttpRequest 
	 * @param requestLine The Http request text line
	 * @param headers The Http request headers
	 */
	public HttpRequest(int requestNum, String requestLine, Map<String, String> headers, ByteBuffer buffer) throws IOException {
		this.requestNum  = requestNum;
		this.requestLine = requestLine;
		if (requestLine != null && headers != null) {
			String[] elements = REQUEST_LINE_PATTERN.split(requestLine);
			method = HttpVerb.valueOf(elements[0]);
			String[] pathFrags = QUERY_STRING_PATTERN.split(elements[1]);
			requestedPath = pathFrags[0];
			version = elements[2];
			this.headers = new HashMap<String, String>(headers);
			this.um_headers = Collections.unmodifiableMap(this.headers);
			
			if (method == HttpVerb.POST) {
				String ctype = headers.get("content-type");
				if (ctype == null) throw new ProtocolException("no content type for POST");
				contentTypeArr = HEADER_VAL_SPLIT_PATTERN.split(ctype);
				contentTypeArr[0] = contentTypeArr[0].trim();
				if (contentTypeArr[0].equals("multipart/form-data")) {
					String[] mparr = contentTypeArr[1].split("=");
					if (mparr.length < 2 || !mparr[0].equals("boundary")) {
						throw new ProtocolException("Expected mp boundary string, got " + contentTypeArr[1]);
					}
					multipartBoundary = mparr[1];
					logger.debug("got multipart boundary: {}", multipartBoundary);
					multipartBoundaryB = multipartBoundary.getBytes(Charsets.ISO_8859_1);
					mpBoundaryBPre    = ("\r\n--" + multipartBoundary).getBytes(Charsets.ISO_8859_1);
					mpBoundaryBStart  = ("--" + multipartBoundary + "\r\n").getBytes(Charsets.ISO_8859_1);
					mpBoundaryBActual = (mpBoundaryBPre + "\r\n").getBytes(Charsets.ISO_8859_1);
					mpBoundaryBFinish = (mpBoundaryBPre + "--\r\n").getBytes(Charsets.ISO_8859_1);
					multipart = true;
					mpParts = new LinkedHashMap<String, HttpRequest.Part>();
					um_mpParts = Collections.unmodifiableMap(mpParts);
				}
				String clen = headers.get("content-length");
				if (clen == null) throw new ProtocolException("no content length for POST");
				contentLength = Integer.parseInt(clen);
				if (contentLength <= 0) throw new ProtocolException("content-length <= 0!");
				rawBody = ByteBuffer.allocate(contentLength);
			}
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
		if (connection == null) {
			keepAlive = true;
		} else if ("keep-alive".equalsIgnoreCase(connection)) { 
			keepAlive = true;
		} else if ("close".equalsIgnoreCase(connection) || (requestLine != null && requestLine.contains("1.0"))) {
			keepAlive = false;
		} else {
			keepAlive = true;
		}
		putContentData(false, buffer);
	}
	
	public static String streamHeadersToString(final InputStream inputStream) throws IOException {
		try (final BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
			return br.lines().parallel().collect(Collectors.joining("\r\n"));
		}
	}
	
	public long getRemaining() {
		return complete ? 0 : rawBody.remaining();
	}
	
	public boolean isComplete() {
		return complete;
	}
	
	public static HttpRequest of(Application app, ByteBuffer buffer) throws IOException {
		String requestLine = null;
		Map<String, String> generalHeaders = null;
		if (buffer.hasRemaining()) {
			// not basic keep-alive
			String raw = new String(buffer.array(), 0, buffer.limit(), Charsets.ISO_8859_1);
			logger.debug("raw httpreq. (of), pos: {}, limit: {}, buf: {}", buffer.position(), buffer.limit(), raw);
			
			int oldpos = buffer.position();
			boolean foundSep = findInBB(buffer, HTTP_HEAD_TERM_BYTES);
			if (!foundSep) {
				raw = new String(buffer.array(), 0, buffer.limit(), Charsets.ISO_8859_1);
				logger.debug("raw httpreq #1. (of), pos: {}, limit: {}, buf: {}", buffer.position(), buffer.limit(), raw);
				throw new ProtocolException("Expected body seperator for initial HTTP req, none found");
			}
			int buflimit = buffer.limit();
			int bodystartpos = buffer.position();
			buffer.limit(bodystartpos);
			buffer.position(oldpos);
			
			generalHeaders = new HashMap<String, String>();
			try (
				ByteBufferBackedInputStream bbbis = new ByteBufferBackedInputStream(buffer);
			) {
				boolean sepfound = false;				
				try (
					InputStreamReader isr = new InputStreamReader(bbbis, Charsets.ISO_8859_1);
					BufferedReader br = new BufferedReader(isr)
				) {
					String line = br.readLine(); // request line
					requestLine = line;
					if (requestLine == null || (requestLine = requestLine.trim()).isEmpty()) {
						throw new ProtocolException("Request line is empty/missing!");
					}
					logger.debug("got req. line: {}", requestLine);
					while ((line = br.readLine()) != null) {
						if (line.contains(": ")) {
							//TODO: optimise this
							String[] splitLine = HEADER_VALUE_PATTERN.split(line);
							String   key       = splitLine[0].trim().toLowerCase();
							String   val       = splitLine[1].trim();
							generalHeaders.put(key, val);
						}
						if (line.isEmpty()) {
							sepfound = true;
							break;
						}
					}
				}
				if (!sepfound) throw new ProtocolException("Excepected body seperator, still parsing headers");
				
				buffer.limit(buflimit);
				buffer.position(bodystartpos);
				logger.debug("buffer remaining: {}", buffer.remaining());
			}
		}
		return new HttpRequest(app.nextHttpReqNum(), requestLine, generalHeaders, buffer);
	}
	
	public Map<String, Part> getMultiParts() {
		return um_mpParts;
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
		if ((buffer == null || !buffer.hasRemaining()) && !continuing) {
			complete = true;
			return true;
		}
		if (method != HttpVerb.POST) {
			throw new IllegalStateException("Method not POST");
		}
		// put the buffer into rawbody, then put the limit to the current position, position back to before the buffer was fed in
		// to set it up for reading that new data.
		int oldRawPos = rawBody.position();
		logger.debug("rawbody pos (pre buffer dump): {}, limit: {}", rawBody.position(), rawBody.limit());
		logger.debug("buffer remaining: {}, rawbody new pos: {}", buffer.remaining(), rawBody.position() + buffer.remaining());
		rawBody.put(buffer);
		logger.debug("rawbody pos (post buffer dump): {}, limit: {}", rawBody.position(), rawBody.limit());
		if (!rawBody.hasRemaining()) {
			flipRemain = buffer.remaining();
			rawBody.flip();
			if (!multipart) {
				body = new String(rawBody.array(), 0, rawBody.limit(), Charsets.ISO_8859_1);
			}
			complete = true;
		}
		if (!complete) return false;
		
		logger.debug("complete, now parsing");
		
		//String raw = new String(rawBody.array(), 0, rawBody.limit(), Charsets.ISO_8859_1);
		//System.out.println("raw putContentData. (of), pos: " + rawBody.position() + ", limit: " + rawBody.limit() + ", buf: " + raw);
		
		try (ByteBufferBackedInputStream bbbis = new ByteBufferBackedInputStream(rawBody)) {
			boolean initial = true;
			while (!mpFinished && rawBody.hasRemaining()) {
				if (!parsingBoundary) {
					logger.debug("finding 'actual' mp boundary in rawBody: --{}", multipartBoundary);
					logger.debug("rawbody pos: {}, limit: {}", rawBody.position(), rawBody.limit());
					boolean found = false;
					if (initial) found = expectInBB(rawBody, mpBoundaryBStart, true);
					else         found = findInBB  (rawBody, mpBoundaryBActual);
					if (!found) {
						throw new ProtocolException("Trying to find mp boundary but not found");
					}
					initial = false;
					int segstartpos = rawBody.position();
					// finding data start
					found = findInBB(rawBody, HTTP_HEAD_TERM_BYTES);
					if (!found) {
						throw new ProtocolException("Found no segment header/data separator");
					}
					parsingBoundary = true; // buffer will be set up to parse.
					int datapos = rawBody.position();
					int oldlimit = rawBody.limit();
					rawBody.limit(datapos);
					rawBody.position(segstartpos);
					
					// parse text
					try (
						InputStreamReader isr = new InputStreamReader(bbbis, Charsets.ISO_8859_1);
						BufferedReader br = new BufferedReader(isr)
					) {						
						currPart = new Part();
						currPart.num = mpParts.size() + 1;
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
						// check we got content-type and disposition..
						if (currPart.headKeyVals.get("Content-Disposition") == null) {
							throw new ProtocolException("content disp line doesn't exist in mp header");
						}
						if (currPart.headKeyVals.get("Content-Type") == null) {
							throw new ProtocolException("content type line doesn't exist in mp header");
						}
						
						HeadKeyVals hkv = currPart.headKeyVals.get("Content-Disposition");
						currPart.mapName = hkv.vals.get("name");
						if (currPart.mapName == null) currPart.mapName = "#" + currPart.num;
						
						logger.debug("==== Part header #" + currPart.num + " (id: " + currPart.mapName + ") ====");
						logger.debug("rawBufStartPos: " + currPart.rawBufStartPos);
						logger.debug("rawBufEndPos  : " + currPart.rawBufEndPos);
						logger.debug("" + currPart.headKeyVals);
						logger.debug("================================================");
						
						mpParts.put(currPart.mapName, currPart);
						rawBody.limit(oldlimit);
						rawBody.position(datapos);
						parsingBoundary = true;
					}
				}
				if (parsingBoundary) {
					int nextBoundaryPos = -1;
					// check for boundary end token
					
					int oldpos = rawBody.position();
					logger.debug("rawbody pos: {}, limit: {}", rawBody.position(), rawBody.limit());
					boolean found = findInBB(rawBody, mpBoundaryBPre);
					int oldpos2 = rawBody.position();
					if (found) {
						found = expectInBB(rawBody, MP_SEP_END_BYTES, true);
						if (!found) {
							logger.debug("not found expected MP_SEP_END_BYTES, checking for normal part end");
							rawBody.position(oldpos2);
							// check for normal end marker
							found = expectInBB(rawBody, MP_END_BYTES, true);
							if (found) {
								logger.debug("found MP_END_BYTES");
								nextBoundaryPos = rawBody.position() - MP_END_BYTES.length - mpBoundaryBPre.length;
							} else {
								logger.debug("not found expected MP_END_BYTES");
							}
							//TODO: we may have newline before mpart seg mark, detect if possible
						} else {
							logger.debug("found expected MP_SEP_END_BYTES, checking for total multipart end");
							found = expectInBB(rawBody, MP_END_BYTES, true);
							if (found) {
								logger.debug("found expected MP_END_BYTES");
								// multipart totally finished
								mpFinished = true;
								nextBoundaryPos = rawBody.position() - MP_SEP_END_BYTES.length - MP_END_BYTES.length - mpBoundaryBPre.length;
							} else {
								logger.debug("not found expected MP_END_BYTES");
							}
							//TODO: we may have newline before mpart finish mark, detect if possible							
						}
					}
					if (!found) throw new ProtocolException("Couldn't find multipart segment end.");
					currPart.rawBufEndPos = nextBoundaryPos;
					int rawBodyOldPos   = rawBody.position();
					int rawBodyOldLimit = rawBody.limit();
					rawBody.limit(currPart.rawBufEndPos);
					rawBody.position(currPart.rawBufStartPos);
					ByteBuffer bb = ByteBuffer.allocate(currPart.rawBufEndPos-currPart.rawBufStartPos);
					bb.put(rawBody);
					//XXX: check that array doesn't disappear when bytebuffer gets gc'ed
					currPart.rawData = bb.array();
					rawBody.limit(rawBodyOldLimit);
					rawBody.position(rawBodyOldPos);
					currPart.complete = true;
					parsingBoundary = false;
					if (mpFinished) {
						rawBody.position(rawBody.limit());
					}
				}
			}
			if (parsingBoundary) {
				throw new ProtocolException("Finished while still parsing multipart.");
			}
		}
		return true;
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
		return headers == null ? null : Collections.unmodifiableMap(headers);
	}
	
	public String getHeader(String name) {
		if (headers == null) return null;
		return headers.get(name.toLowerCase());
	}
	
	public HttpVerb getMethod() {
		return method;
	}
	
	HeadKeyVals parseHeadKeyVals(String line) throws IllegalArgumentException {
		logger.debug("hv line: {}", line);
		String[] lineSplit = line.split(": ", 2);
		if (lineSplit.length != 2) {
			throw new IllegalArgumentException("Expecting id and val for header line");
		}
		HeadKeyVals hkv = new HeadKeyVals();
		hkv.key = lineSplit[0];
		String[] lineSplit2 = lineSplit[1].split("; ");
		hkv.val = lineSplit2[0];
		boolean key = true;
		for (String val : lineSplit2) {
			if (key) {
				key = false;
				continue;
			}
			logger.debug("lnsp2 val: {}", val);
			String[] spl = val.split("=", 2);
			if (spl.length != 2) {
				throw new IllegalArgumentException("Expecting id and val in header line (for sub-val)");
			}
			String splval = spl[1];
			if (splval.startsWith("\"") && splval.endsWith("\"")) {
				splval = splval.substring(1, splval.length()-1);
			}
			hkv.vals.put(spl[0], splval);
		}
		logger.debug("hkv: {}", hkv);
		return hkv;
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
		return values.isEmpty() ? null : values.iterator().next();
	}
	
	public Map<String, Collection<String>> getParameters() {
		return parameters.asMap();
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
	 * If no values are found and empty collection is returned.
	 */
	public Collection<String> getParameterValues(String name) {
		return parameters.get(name);
	}
	
	public boolean isKeepAlive() {
		return keepAlive;
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


	
	private ImmutableMultimap<String, String> parseParameters(String requestLine) {
		ImmutableMultimap.Builder<String, String> builder = ImmutableMultimap.builder();
		String[] str = QUERY_STRING_PATTERN.split(requestLine);
		
		//Parameters exist
		if (str.length > 1) {
			String[] paramArray = PARAM_STRING_PATTERN.split(str[1]); 
			for (String keyValue : paramArray) {
				String[] keyValueArray = KEY_VALUE_PATTERN.split(keyValue);				
				//We need to check if the parameter has a value associated with it.
				if (keyValueArray.length > 1) {
					builder.put(keyValueArray[0], keyValueArray[1]); //name, value
				}
			}
		}
		return builder.build();
	}
	
	public static final boolean findInBB(ByteBuffer buffer, byte[] bytes) {
		byte[] temp = new byte[bytes.length];
		while (buffer.hasRemaining()) {
			System.arraycopy(temp, 1, temp, 0, temp.length-1); // shift temp
			temp[temp.length-1] = buffer.get();
			if (buffer.position() < temp.length) continue; // need temp[] to be full
			if (Arrays.equals(bytes, temp)) {
				return true;
			}
		}
		return false;
	}
	
	public static final boolean expectInBB(ByteBuffer buffer, byte[] bytes, boolean advanceIfUndersized) {
		byte[] temp = new byte[bytes.length];
		if (buffer.remaining() < bytes.length) {
			if (advanceIfUndersized) buffer.position(buffer.limit());
			return false;
		}
		buffer.get(temp);
		return Arrays.equals(bytes, temp);
	}
}
