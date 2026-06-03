package org.deftserver.web.handler;

import java.io.IOException;

import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;

/** Terminal handler returning 400 Bad Request for every method, used for malformed requests. */
public class BadRequestRequestHandler extends RequestHandler {

private final static BadRequestRequestHandler instance = new BadRequestRequestHandler();

	private BadRequestRequestHandler() { }

	/** The shared singleton instance. */
	public static final BadRequestRequestHandler getInstance() {
		return instance;
	}

	/** Writes the 400 response (Connection: close). */
	@Override
	public void get(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(400);
		response.setHeader("Connection", "close");
		response.write("HTTP 1.1 requests must include the Host: header");
	}

	// A malformed request is 400 regardless of method (the base class would otherwise
	// answer non-GET with a misleading 501).
	@Override public void post(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void put(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void patch(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void delete(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void options(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void trace(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void connect(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
}
