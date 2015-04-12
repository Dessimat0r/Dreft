package org.deftserver.web.http;

import java.io.IOException;

import org.deftserver.web.HttpVerb;
import org.deftserver.web.handler.RequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpRequestDispatcher {

	private static final Logger logger = LoggerFactory.getLogger(HttpRequestDispatcher.class);

	public static void dispatch(RequestHandler rh, HttpRequest request, HttpResponse response) throws IOException {
		HttpVerb method = request.getMethod();
		logger.debug("method: {}", method);
		try {
			switch (method) {
			case GET:
				rh.get(request, response);
				break;
			case POST:
				rh.post(request, response);
				break;
			case HEAD:
				rh.head(request, response);
				break;
			case PUT:
				rh.put(request, response);
				break;
			case DELETE:
				rh.delete(request, response);
				break;
			case OPTIONS: //Fall through
			case TRACE:
			case CONNECT:
			default:
				logger.warn("Unimplemented HTTP method received: {}", method);
				//TODO send "not supported page (501) back to client"
			}
		} catch (HttpException he) {
			response.setStatusCode(he.getStatusCode());
			response.setHeader("Content-Type", "text/html");
			response.write("<html><head><title>403" + (he.getMessage() != null ? ": " + he.getMessage() : "") + "</title></head>");
			response.write("<body>" + he.getLongHTMLMessage() + "</body>");
			if (rh.isMethodAsynchronous(request.getMethod())) {
				response.finish();
			}
		}
	}
}
