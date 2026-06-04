package org.deftserver.web.http.client;

import java.util.Map;

import java.util.HashMap;

/** A response received by the async HTTP {@link AsynchronousHttpClient} (status line, headers and the
 *  accumulated body), plus the round-trip time. */
public class Response {

	private final long requestTime;
	private String statusLine;
	private final Map<String, String> headers = new HashMap<>();
	private final StringBuilder bodyBuilder = new StringBuilder();
	private String body = null;

	/** Starts timing relative to {@code requestStarted} (epoch ms when the request was issued). */
	public Response(long requestStarted) {
		requestTime = System.currentTimeMillis() - requestStarted;
	}

	/** Sets the response status line. */
	public void setStatuLine(String statusLine) {
		this.statusLine = statusLine;
	}

	/** The response status line. */
	public String getStatusLine() {
		return statusLine;
	}

	/** Sets a response header. */
	public void setHeader(String key, String value) {
		headers.put(key, value);
	}

	/** A response header value, or null if absent. */
	public String getHeader(String key) {
		return headers.get(key);
	}

	/** Sets the full response body explicitly. */
	public void setBody(String body) {
		this.body = body;
	}

	/** The response body (materialising the accumulated chunks on first call). */
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

	/** Debug representation including body, headers, status line and round-trip time. */
	@Override
	public String toString() {
		return "HttpResponse [body=" + getBody() + ", headers=" + headers
				+ "\n, statusLine=" + statusLine + "]\n" + ", request time: " + requestTime +"ms";
	}

	/** Current accumulated body length (chunks received so far). Unlike {@link #getBody()} this does
	 *  NOT materialise/cache the body, so it is safe to call mid-stream (e.g. for a size-cap check)
	 *  without freezing the still-growing body to an incomplete value. */
	public int currentBodyLength() {
		return body != null ? body.length() : bodyBuilder.length();
	}

	/** Appends a received body chunk (used while streaming/dechunking the response). */
	public void addChunk(String chunk) {
		bodyBuilder.append(chunk);
	}
	
}
