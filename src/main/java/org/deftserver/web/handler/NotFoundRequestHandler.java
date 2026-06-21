package org.deftserver.web.handler;

import java.io.IOException;

import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;


/** Terminal handler returning 404 Not Found (with an escaped, nosniff body) for every method, used
 *  when routing finds no match. */
public class NotFoundRequestHandler extends RequestHandler {

	private final static NotFoundRequestHandler instance = new NotFoundRequestHandler();

	private NotFoundRequestHandler() { }

	/** The shared singleton instance. */
	public static final NotFoundRequestHandler getInstance() {
		return instance;
	}

	/** Trivial terminal handler — never worth offloading to a virtual thread. */
	@Override public boolean isOffloadable() { return false; }

	/** Writes the 404 response (HTML-escaped reflected path, nosniff, Connection: close). */
	@Override
	public void get(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(404);
		response.setHeader("Connection", "close");
		// Set an explicit content type and nosniff so the body is never MIME-sniffed as HTML
		// when no type would otherwise be sent, and HTML-escape the reflected path to prevent
		// reflected XSS (the path is attacker-controlled).
		response.setHeader("Content-Type", "text/html; charset=utf-8");
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.write("<html><head><title>404: Not found</title></head><body>Requested resource: <tt>"
			+ org.deftserver.util.HttpUtil.escapeHtml(request.getRequestedPath()) + "</tt> was not found.</body>");
	}

	// A non-existent resource is 404 for every method, not just GET — otherwise the base
	// class would answer e.g. POST/PUT/DELETE to an unknown path with a misleading 501.
	@Override public void post(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void put(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void patch(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void delete(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void options(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void trace(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }
	@Override public void connect(HttpRequest request, HttpResponse response) throws IOException { get(request, response); }

}
