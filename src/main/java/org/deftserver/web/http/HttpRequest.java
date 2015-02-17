package org.deftserver.web.http;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.deftserver.io.IOLoop;
import org.deftserver.io.stream.ByteBufferBackedInputStream;
import org.deftserver.web.HttpVerb;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMultimap;

public class HttpRequest {
	private IOLoop ioLoop;
	
	private final String requestLine;
	private final HttpVerb method;
	private final String requestedPath;	// correct name?
	private final String version; 
	private Map<String, String> headers;
	private ImmutableMultimap<String, String> parameters;
	private String body = null;
	private boolean keepAlive;
	private InetAddress remoteHost;
	private InetAddress serverHost;
	private int remotePort;
	private int serverPort;

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
	
	protected ByteBuffer rawBody           = null;
	protected int        contentLength     = -1;
	protected String[]   contentTypeArr    = null;
	protected boolean    multipart         = false;
	protected String     multipartBoundary = null;
	protected boolean    complete          = false;
	
	/**
	 * Creates a new HttpRequest 
	 * @param requestLine The Http request text line
	 * @param headers The Http request headers
	 */
	public HttpRequest(String requestLine, Map<String, String> headers) {
		this.requestLine = requestLine;
		String[] elements = REQUEST_LINE_PATTERN.split(requestLine);
		method = HttpVerb.valueOf(elements[0]);
		String[] pathFrags = QUERY_STRING_PATTERN.split(elements[1]);
		requestedPath = pathFrags[0];
		version = elements[2];
		this.headers = headers;
		
		if (method == HttpVerb.POST) {
			String ctype = headers.get("content-type");
			if (ctype == null) throw new IllegalArgumentException("no content type for POST");
			contentTypeArr = HEADER_VAL_SPLIT_PATTERN.split(ctype);
			contentTypeArr[0] = contentTypeArr[0].trim();
			if (contentTypeArr[0].equals("multipart/form-data")) {
				multipartBoundary = contentTypeArr[1].trim();
				multipart = true;
			}
			String clen = headers.get("content-length");
			if (clen == null) throw new IllegalArgumentException("no content length for POST");
			contentLength = Integer.parseInt(clen);
			rawBody = ByteBuffer.allocate(contentLength);
		} else {
			complete = true;
		}
		initKeepAlive();
		parameters = parseParameters(elements[1]);
	}
	
	public static String streamHeadersToString(final InputStream inputStream) throws IOException {
		try (final BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
			return br.lines().parallel().collect(Collectors.joining("\r\n"));
		}
	}
	
	public boolean isComplete() {
		return complete;
	}
	
	public static HttpRequest of(ByteBuffer buffer) {
		try {
			String raw = new String(buffer.array(), 0, buffer.limit(), Charsets.ISO_8859_1);
			System.out.println("raw httpreq. (of), buf: " + raw);
			
			Map<String, String> generalHeaders = new HashMap<String, String>();
			try (
				ByteBufferBackedInputStream bbbis = new ByteBufferBackedInputStream(buffer);
				InputStreamReader isr = new InputStreamReader(bbbis);
				BufferedReader br = new BufferedReader(isr);
			) {
				String line = br.readLine(); // request line
				String requestLine = line;
				System.out.println("got req. line: " + requestLine);
				while ((line = br.readLine()) != null) {
					if (line.contains(":")) {
						//TODO: optimise this
						String[] splitLine = HEADER_VALUE_PATTERN.split(line);
						String   key       = splitLine[0].trim().toLowerCase();
						String   val       = splitLine[1].trim();
						generalHeaders.put(key, val);
					}
					if (line.isEmpty()) {
						// continue parsing after blank line
						HttpRequest req = new HttpRequest(requestLine, generalHeaders);
						// only do this for POST
						if (req.getMethod() == HttpVerb.POST) {
							System.out.println("buffer pos: " + buffer.position());
							req.putContentData(false, buffer);
						}
						return req;
					}
				}
			}
			throw new IOException("Excepected body seperator, still parsing headers");
		} catch (Exception t) {
			System.out.println("malformed http req. (of): exception");
			t.printStackTrace();
		}
		return MalFormedHttpRequest.instance;
	}
	
	/**
	 * Returns true if got all content data
	 * @param buffer
	 * @return
	 */
	public boolean putContentData(boolean continuing, ByteBuffer buffer) {
		if (method != HttpVerb.POST) {
			throw new IllegalStateException("Method not POST");
		}
		String raw = new String(buffer.array(), 0, buffer.limit(), Charsets.ISO_8859_1);
		System.out.println("raw httpreq. (putContentData), buf: " + raw);
		
		rawBody.put(buffer);
		
		if (!rawBody.hasRemaining()) {
			rawBody.flip();
			//TODO: add multipart parsing code
			if (!multipart) {
				body = new String(rawBody.array(), 0, rawBody.limit(), Charsets.ISO_8859_1);
			}
			complete = true;
			return true;
		}
		
		return false;
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
		return Collections.unmodifiableMap(headers);
	}
	
	public String getHeader(String name) {
		return headers.get(name.toLowerCase());
	}
	
	public HttpVerb getMethod() {
		return method;
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
	
	
	private void initKeepAlive() {
		String connection = getHeader("Connection");
		if ("keep-alive".equalsIgnoreCase(connection)) { 
			keepAlive = true;
		} else if ("close".equalsIgnoreCase(connection) || requestLine.contains("1.0")) {
			keepAlive = false;
		} else {
			keepAlive = true;
		}
	}

}
