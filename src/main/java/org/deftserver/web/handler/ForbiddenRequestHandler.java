package org.deftserver.web.handler;

import java.io.IOException;

import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;

/** Terminal handler returning 403 Forbidden for every method, used when an {@code @Authenticated}
 *  resource has no authenticated user. */
public class ForbiddenRequestHandler extends RequestHandler {

private final static ForbiddenRequestHandler instance = new ForbiddenRequestHandler();

	private ForbiddenRequestHandler() { }

	/** The shared singleton instance. */
	public static final ForbiddenRequestHandler getInstance() {
		return instance;
	}

	/** Writes the 403 response (Connection: close). */
	@Override
	public void get(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(403);
		response.setHeader("Connection", "close");
		response.write("Authentication failed");
	}

	// Authentication failure is 403 for every method (the base class would otherwise
	// answer non-GET with a misleading 501).
	@Override public void post(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void put(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void patch(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void delete(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void options(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void trace(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void connect(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
}