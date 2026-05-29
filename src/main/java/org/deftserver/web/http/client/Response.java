package org.deftserver.web.http.client;

import java.util.Map;

import java.util.HashMap;

public class Response {
	
	private final long requestTime;
	private String statusLine;
	private final Map<String, String> headers = new HashMap<>();
	private final StringBuilder bodyBuilder = new StringBuilder();
	private String body = null;
	
	public Response(long requestStarted) {
		requestTime = System.currentTimeMillis() - requestStarted;
	}
	
	public void setStatuLine(String statusLine) {
		this.statusLine = statusLine;
	}
	
	public String getStatusLine() {
		return statusLine;
	}
	
	public void setHeader(String key, String value) {
		headers.put(key, value);
	}
	
	public String getHeader(String key) {
		return headers.get(key);
	}
	
	public void setBody(String body) {
		this.body = body;
	}
	
	public String getBody() {
		if (body == null) {
			body = bodyBuilder.toString();
		}
		return body;
	}
	
	/**
	 * @return The total execution time of the request/response round trip.
	 */
	public long getRequestTime() {
		return requestTime;
	}
	
	@Override
	public String toString() {
		return "HttpResponse [body=" + getBody() + ", headers=" + headers
				+ "\n, statusLine=" + statusLine + "]\n" + ", request time: " + requestTime +"ms";
	}

	public void addChunk(String chunk) {
		bodyBuilder.append(chunk);
	}
	
}
