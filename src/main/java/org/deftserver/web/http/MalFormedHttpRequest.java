package org.deftserver.web.http;

import java.io.IOException;

import com.google.common.collect.Maps;


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
		super(-1, "GET / Malformed request\r\n", Maps.<String, String>newHashMap(), null);
	}

}
