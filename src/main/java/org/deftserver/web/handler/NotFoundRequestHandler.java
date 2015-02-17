package org.deftserver.web.handler;

import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;


public class NotFoundRequestHandler extends RequestHandler {

	private final static NotFoundRequestHandler instance = new NotFoundRequestHandler();
	
	private NotFoundRequestHandler() { }
	
	public static final NotFoundRequestHandler getInstance() {
		return instance;
	}
	
	@Override
	public void get(HttpRequest request, HttpResponse response) {
		response.setStatusCode(404);
		response.setHeader("Connection", "close");
		response.write("<html><head><title>404: Not found</title></head><body>Requested resource: <tt>" + request.getRequestedPath() + "</tt> was not found.</body>");
	}
	
}
