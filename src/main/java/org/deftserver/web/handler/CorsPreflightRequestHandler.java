package org.deftserver.web.handler;

import java.io.IOException;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;

public class CorsPreflightRequestHandler extends RequestHandler {

	private final static CorsPreflightRequestHandler instance = new CorsPreflightRequestHandler();
	
	private CorsPreflightRequestHandler() { }
	
	public static final CorsPreflightRequestHandler getInstance() {
		return instance;
	}
	
	@Override
	public void options(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(204);
		response.setHeader("Vary", "Origin");
		String origin = request.getHeader("Origin");
		response.setHeader("Access-Control-Allow-Origin", origin != null ? origin : "*");
		response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
		
		String reqHeaders = request.getHeader("Access-Control-Request-Headers");
		response.setHeader("Access-Control-Allow-Headers", reqHeaders != null ? reqHeaders : "*");
		
		response.setHeader("Access-Control-Max-Age", "86400");
		response.setHeader("Content-Length", "0");
	}
}
