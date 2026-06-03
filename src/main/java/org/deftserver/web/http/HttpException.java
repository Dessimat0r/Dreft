package org.deftserver.web.http;


/**
 * An exception carrying an HTTP status code, thrown from the parse/dispatch path to abort processing
 * and produce that status. As a {@link RuntimeException} it propagates past the IOException-only
 * catches in parsing and is turned into the corresponding error response by the protocol/dispatcher.
 */
public class HttpException extends RuntimeException {

	private final int statusCode;
	protected final String longHTMLMessage;

	/** Builds an exception whose brief and long messages are both the status number. */
	public HttpException(int statusCode) {
		this(statusCode, Integer.toString(statusCode), Integer.toString(statusCode));
	}

	/** Builds an exception with the given status and a message used for both the brief and long forms. */
	public HttpException(int statusCode, String message) {
		this(statusCode, message, message);
	}

	/** Builds an exception with a status, a brief (exception) message and a longer message rendered
	 *  into the error-page body. */
	public HttpException(int statusCode, String briefMsg, String longHTMLMessage) {
		super(briefMsg);
		this.statusCode = statusCode;
		this.longHTMLMessage = longHTMLMessage;
	}

	/** The HTTP status code to send for this failure. */
	public int getStatusCode() {
		return statusCode;
	}

	/** The longer, human-readable message rendered into the error-page body. */
	public String getLongHTMLMessage() {
		return longHTMLMessage;
	}
}
