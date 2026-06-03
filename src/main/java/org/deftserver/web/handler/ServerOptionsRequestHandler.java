package org.deftserver.web.handler;

import java.io.IOException;

import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;

/**
 * Handles the server-wide asterisk-form request "OPTIONS * HTTP/1.1", which queries the
 * capabilities of the server as a whole rather than a specific resource (RFC 9110 §9.3.7).
 * Responds 204 No Content with an {@code Allow} header.
 */
public class ServerOptionsRequestHandler extends RequestHandler {

	private static final ServerOptionsRequestHandler instance = new ServerOptionsRequestHandler();

	private ServerOptionsRequestHandler() { }

	/** The shared singleton instance. */
	public static ServerOptionsRequestHandler getInstance() {
		return instance;
	}

	/** Writes the 204 response advertising the server-wide {@code Allow} method set. */
	@Override
	public void options(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(204);
		response.setHeader("Allow", "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS");
		response.setHeader("Content-Length", "0");
	}
}
