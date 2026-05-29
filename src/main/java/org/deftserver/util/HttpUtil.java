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
	private static final ThreadLocal<MessageDigest> md = new ThreadLocal<MessageDigest>();
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
	private static final String _302_NOT_FOUND		 		= "HTTP/1.1 302 Found\r\n"; 
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
	private static final String _413_REQUEST_ENTITY_LARGE	= "HTTP/1.1 413 Request Entity Too Large\r\n"; 
	private static final String _414_REQUEST_URI_TOO_LONG	= "HTTP/1.1 414 Request-URI Too Long\r\n"; 
	private static final String _415_UNSUPPORTED_MEDIA_TYPE	= "HTTP/1.1 415 Unsupported Media Type\r\n"; 
	private static final String _416_REQUEST_RANGE_NOT_SAT	= "HTTP/1.1 416 Requested Range Not Satisfiable\r\n"; 
	private static final String _417_EXPECTATION_FAILED		= "HTTP/1.1 417 Expectation Failed\r\n"; 
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
			return _302_NOT_FOUND;
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
			return _413_REQUEST_ENTITY_LARGE;
		case 414:
			return _414_REQUEST_URI_TOO_LONG;
		case 415:
			return _415_UNSUPPORTED_MEDIA_TYPE;
		case 416:
			return _416_REQUEST_RANGE_NOT_SAT;
		case 417:
			return _417_EXPECTATION_FAILED;
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
			logger.error("Uknonwn Http status code: " + statusCode);
			throw new IllegalArgumentException("Unknow Http status code: " + statusCode);
		}
	}


	public static boolean verifyRequest(HttpRequest request) {
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


	public static String getEtag(byte[] bytes) {
		return getEtag(bytes, 0, bytes.length);
	}

	public static String getEtag(byte[] bytes, int offset, int length) {
		if (md.get() == null) {
			try {
				md.set(MessageDigest.getInstance("MD5"));
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException("MD5 cryptographic algorithm is not available.", e);
			}
		}
		md.get().reset();
		md.get().update(bytes, offset, length);
		byte[] digest = md.get().digest();
		BigInteger number = new BigInteger(1, digest);
		return '0' + number.toString(16);	// prepend a '0' to get a proper MD5 hash 
	}


	public static String getEtag(File file) {
		if (file == null || !file.exists()) {
			return "";
		}
		String info = file.getAbsolutePath() + ":" + file.lastModified() + ":" + file.length();
		return "\"" + getEtag(info.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\"";
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
			// skip whitespace
			while (i < len && acceptEncoding.charAt(i) == ' ') i++;
			// match token
			int tokenStart = i;
			while (i < len && acceptEncoding.charAt(i) != ',' && acceptEncoding.charAt(i) != ';') i++;
			int tokenEnd = i;
			// trim trailing spaces from token
			while (tokenEnd > tokenStart && acceptEncoding.charAt(tokenEnd - 1) == ' ') tokenEnd--;
			String token = acceptEncoding.substring(tokenStart, tokenEnd);
			// check for wildcard or gzip
			boolean isGzipOrWild = token.equals("*") || token.equalsIgnoreCase("gzip");
			// now scan optional parameters for q=0
			double q = 1.0;
			while (i < len && acceptEncoding.charAt(i) == ';') {
				i++; // skip ';'
				while (i < len && acceptEncoding.charAt(i) == ' ') i++;
				if (i + 2 < len && acceptEncoding.charAt(i) == 'q' && acceptEncoding.charAt(i + 1) == '=') {
					i += 2;
					int qStart = i;
					while (i < len && acceptEncoding.charAt(i) != ',' && acceptEncoding.charAt(i) != ';') i++;
					try {
						q = Double.parseDouble(acceptEncoding.substring(qStart, i).trim());
					} catch (NumberFormatException e) {
						q = 0.0;
					}
				} else {
					while (i < len && acceptEncoding.charAt(i) != ',' && acceptEncoding.charAt(i) != ';') i++;
				}
			}
			if (isGzipOrWild && q > 0.0) return true;
			// advance past ','
			if (i < len && acceptEncoding.charAt(i) == ',') i++;
		}
		return false;
	}

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
		String[] parts = acceptHeader.split(",");
		for (String part : parts) {
			String[] mediaAndParams = part.split(";");
			String mediaType = mediaAndParams[0].trim();
			double q = 1.0;
			for (int i = 1; i < mediaAndParams.length; i++) {
				String param = mediaAndParams[i].trim();
				if (param.startsWith("q=")) {
					try {
						q = Double.parseDouble(param.substring(2).trim());
					} catch (NumberFormatException e) {
						q = 0.0;
					}
				}
			}
			if (q > 0.0) {
				items.add(new AcceptItem(mediaType, q));
			}
		}
		java.util.Collections.sort(items);

		java.util.List<String> result = new java.util.ArrayList<>();
		for (AcceptItem item : items) {
			result.add(item.type);
		}
		return result;
	}

}
