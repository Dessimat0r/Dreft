package org.deftserver.web.http;


public class HttpException extends RuntimeException {

	private final int statusCode;
	protected final String longHTMLMessage;

	public HttpException(int statusCode) {
		this(statusCode, Integer.toString(statusCode), Integer.toString(statusCode));
	}

	public HttpException(int statusCode, String briefMsg, String longHTMLMessage) {
		super(briefMsg);
		this.statusCode = statusCode;
		this.longHTMLMessage = longHTMLMessage;
	}
	
	public int getStatusCode() {
		return statusCode;
	}
	
	public String getLongHTMLMessage() {
		return longHTMLMessage;
	}
}
