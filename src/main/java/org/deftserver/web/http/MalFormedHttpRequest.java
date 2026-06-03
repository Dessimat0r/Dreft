package org.deftserver.web.http;

import java.io.IOException;

import java.util.HashMap;


public class MalFormedHttpRequest extends HttpRequest {

	public static final MalFormedHttpRequest instance;
	static {
		try {
			instance = new MalFormedHttpRequest();
		} catch (IOException e) {
			throw new IllegalStateException("MalFormedHttpRequest instance couldn't be created");
		}
	}
	
	/* Dummy HttpRequest that represents a malformed client HTTP request */
	private MalFormedHttpRequest() throws IOException {
		super(-1, "GET / HTTP/1.1", defaultHeaders(), null);
	}

	/* The HttpRequest constructor enforces RFC 9112 §3.2 (an HTTP/1.1 request MUST carry a Host
	 * header); supply one so constructing this internal sentinel never throws. */
	private static HashMap<String, String> defaultHeaders() {
		HashMap<String, String> h = new HashMap<>();
		h.put("host", "localhost");
		return h;
	}

}
