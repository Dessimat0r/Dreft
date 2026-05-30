package org.deftserver.web.handler;

import java.io.IOException;

import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;


public class NotFoundRequestHandler extends RequestHandler {

	private final static NotFoundRequestHandler instance = new NotFoundRequestHandler();
	
	private NotFoundRequestHandler() { }
	
	public static final NotFoundRequestHandler getInstance() {
		return instance;
	}
	
	@Override
	public void get(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(404);
		response.setHeader("Connection", "close");
		response.write("<html><head><title>404: Not found</title></head><body>Requested resource: <tt>" + request.getRequestedPath() + "</tt> was not found.</body>");
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
