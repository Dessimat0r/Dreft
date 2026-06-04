package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

import org.deftserver.web.handler.BadRequestRequestHandler;
import org.deftserver.web.handler.NotFoundRequestHandler;
import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.junit.Test;


public class ApplicationTest {
	
	@Test
	public void simpleApplicationTest() {
		Map<String, RequestHandler> handlers = new HashMap<String, RequestHandler>();
		final RequestHandler handler1 = new RequestHandler() {
			@Override public void get(HttpRequest request, HttpResponse response) { }
		};
		final RequestHandler handler2 = new RequestHandler() {
			@Override public void get(HttpRequest request, HttpResponse response) { }
		};
		final RequestHandler handler3 = new RequestHandler() {
			@Override public void get(HttpRequest request, HttpResponse response) { }
		};
		final RequestHandler handler4 = new RequestHandler() {
			@Override public void get(HttpRequest request, HttpResponse response) { }
		};
		
		handlers.put("/", handler1);
		handlers.put("/persons/([0-9]+)", handler2);
		handlers.put("/persons/phone_numbers", handler3);
		handlers.put("/pets/([0-9]{0,3})", handler4);
		Application app = new Application(handlers);
		
		
		String requestLine = "GET / HTTP/1.1";
		Map<String, String> headers = new HashMap<String, String>();
		headers.put("host", "localhost");
		HttpRequest request = new HttpRequest(requestLine, headers);
		
		
		assertNotNull(app.getHandler(request));
		
		requestLine = "GET /persons/1911 HTTP/1.1";
		request = new HttpRequest(requestLine, headers);
		assertNotNull(app.getHandler(request));
		
		
		requestLine = "GET /persons/phone_numbers HTTP/1.1";
		request = new HttpRequest(requestLine, headers);
		assertNotNull(app.getHandler(request));
		
		requestLine = "GET /pets/123 HTTP/1.1";
		request = new HttpRequest(requestLine, headers);
		assertNotNull(app.getHandler(request));
		
		
		request = new HttpRequest("GET /missing HTTP/1.1", headers);
		assertEquals(NotFoundRequestHandler.getInstance(), app.getHandler(request));
		
		request = new HttpRequest("GET /persons HTTP/1.1", headers);
		assertEquals(NotFoundRequestHandler.getInstance(), app.getHandler(request));
		
		request = new HttpRequest("GET /persons/roger HTTP/1.1", headers);
		assertEquals(NotFoundRequestHandler.getInstance(), app.getHandler(request));
		
		request = new HttpRequest("GET /persons/123a HTTP/1.1", headers);
		assertEquals(NotFoundRequestHandler.getInstance(), app.getHandler(request));
		
		request = new HttpRequest("GET /persons/a123 HTTP/1.1", headers);
		assertEquals(NotFoundRequestHandler.getInstance(), app.getHandler(request));
		
		request = new HttpRequest("GET /pets/a123 HTTP/1.1", headers);
		assertEquals(NotFoundRequestHandler.getInstance(), app.getHandler(request));
		
		request = new HttpRequest("GET /pets/123a HTTP/1.1", headers);
		assertEquals(NotFoundRequestHandler.getInstance(), app.getHandler(request));
		
		request = new HttpRequest("GET /pets/1234 HTTP/1.1", headers);
		assertEquals(NotFoundRequestHandler.getInstance(), app.getHandler(request));
		
		request = new HttpRequest("GET / HTTP/1.1", headers);
		assertEquals(handler1, app.getHandler(request));
		
		request = new HttpRequest("GET /persons/1911 HTTP/1.1", headers);
		assertEquals(handler2, app.getHandler(request));
		
		request = new HttpRequest("GET /persons/phone_numbers HTTP/1.1", headers);
		assertEquals(handler3, app.getHandler(request));
		
		request = new HttpRequest("GET /pets/123 HTTP/1.1", headers);
		assertEquals(handler4, app.getHandler(request));
		
		//Verify that BadRequestRequestHandler is returned if request does not include Host header
		headers = new HashMap<String, String>();
		request = new HttpRequest("GET /pets/123 HTTP/1.1", headers);
		assertEquals(BadRequestRequestHandler.getInstance(), app.getHandler(request));
		
	}
	
	@Test
	public void literalPathWithRegexMetacharacterDoesNotCrashConstruction() {
		// Regression: a LITERAL route (not wrapped in parens, so not a capturing group) whose last
		// segment contains an unbalanced regex metacharacter — e.g. "[" — must NOT be compiled as a
		// regex. Compiling it threw PatternSyntaxException and crashed Application construction (and
		// thus server startup) on a perfectly legal route. It must register as an exact-match path.
		Map<String, RequestHandler> handlers = new HashMap<String, RequestHandler>();
		final RequestHandler literal = new RequestHandler() {
			@Override public void get(HttpRequest request, HttpResponse response) { }
		};
		handlers.put("/files/data[1", literal);   // unbalanced '[' — legal literal, invalid regex
		handlers.put("/api/v(1", literal);          // unbalanced '(' — also a legal literal
		Application app = new Application(handlers); // must not throw

		// '(' is a URI sub-delim (allowed in a path and preserved by normalisation), so this routes
		// as an exact match — proving the literal segment was registered rather than compiled.
		Map<String, String> headers = new HashMap<String, String>();
		headers.put("host", "localhost");
		HttpRequest request = new HttpRequest("GET /api/v(1 HTTP/1.1", headers);
		assertEquals("literal path with regex metachar must route as an exact match", literal, app.getHandler(request));
	}

	@Test(expected=PatternSyntaxException.class)
	public void malFormedRegularExpressionTest() {
		Map<String, RequestHandler> handlers = new HashMap<String, RequestHandler>();
		final RequestHandler handler1 = new RequestHandler() {
			@Override public void get(HttpRequest request, HttpResponse response) { }
		};
		
		handlers.put("/persons/([[0-9]{0,3})", handler1);	// path contains malformed (a '[' too much) regex
		Application app = new Application(handlers);

	}
}
