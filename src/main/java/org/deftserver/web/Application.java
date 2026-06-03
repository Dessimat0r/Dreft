package org.deftserver.web;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.deftserver.util.HttpUtil;
import org.deftserver.web.handler.BadRequestRequestHandler;
import org.deftserver.web.handler.CorsPreflightRequestHandler;
import org.deftserver.web.handler.ForbiddenRequestHandler;
import org.deftserver.web.handler.NotFoundRequestHandler;
import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.handler.StaticContentHandler;
import org.deftserver.web.http.HttpRequest;

public class Application {
	public final AtomicInteger requestNum = new AtomicInteger(0);
	
	/**
	 * "Normal/Absolute" (non group capturing) RequestHandlers
	 * e.g. "/", "/persons"
	 */
	private final Map<String, RequestHandler> absoluteHandlers;

	/**
	 * Group capturing RequestHandlers
	 * e.g. "/persons/([0-9]+)", "/persons/(\\d{1,3})"  
	 */
	private final Map<String, RequestHandler> capturingHandlers;
	
	/**
	 * A mapping between group capturing RequestHandlers and their corresponding pattern ( e.g. "([0-9]+)" )
	 */
	private final Map<RequestHandler, Pattern> patterns;
	
	/**
	 * The directory where static content (files) will be served from.
	 */
	private String staticContentDir;
	private java.nio.file.Path staticContentRoot;
	private boolean staticContentDirIsAbsolute;
	
	/** Builds the routing table from a path → handler map, splitting it into exact-path handlers and
	 *  capturing-group handlers (paths whose final segment is a regex, e.g. {@code /persons/([0-9]+)}),
	 *  pre-compiling those patterns. */
	public Application(Map<String, RequestHandler> handlers) {
		Map<String, RequestHandler> builder = new HashMap<>();
		Map<String, RequestHandler> capturingBuilder = new HashMap<>();
		Map<RequestHandler, Pattern> patternsBuilder = new HashMap<>();

		for (String path : handlers.keySet()) {
			int index = path.lastIndexOf("/");
			String group = path.substring(index+1, path.length());
			if (containsCapturingGroup(group)) {
				// path ends with capturing group, e.g path == "/person/([0-9]+)"
				capturingBuilder.put(path.substring(0, index+1), handlers.get(path));
				patternsBuilder.put(handlers.get(path), Pattern.compile(group));
			} else {
				// "normal" path, e.g. path == "/"
				builder.put(path, handlers.get(path));
			}
		}
		absoluteHandlers = Map.copyOf(builder);
		capturingHandlers = Map.copyOf(capturingBuilder);
		patterns = Map.copyOf(patternsBuilder);
	}

	/**
	 * 
	 * @param path Requested path
	 * @return Returns the {@link RequestHandler} associated with the given path. If no mapping exists a 
	 * {@link NotFoundRequestHandler} is returned.
	 */
	public RequestHandler getHandler(String path) {
		RequestHandler rh = absoluteHandlers.get(path);
		if (rh == null) {
			// path could contain capturing groups which we could have a handler associated with.
			rh = getCapturingHandler(path);
			if (rh == null) {
				// path could be prefixed with the 'static content directory'
				rh = getStaticContentHandler(path);
			}
		}
		return rh != null ? rh : NotFoundRequestHandler.getInstance();	// TODO RS store in a final field for improved performance?
	}
	
	/** Resolves the handler for a parsed request, applying (in order): malformed → 400, invalid →
	 *  400, server-wide {@code OPTIONS *}, path routing, CORS preflight interception, and the
	 *  {@code @Authenticated} check (→ 403 when unauthenticated). */
	public RequestHandler getHandler(HttpRequest request) {
		// A malformed request (parse failure sentinel) is always a 400 — detect it explicitly by
		// type rather than relying on a side effect of its dummy contents (it previously depended on
		// the sentinel having no Host header so verifyRequest would reject it; that coupling broke
		// once the sentinel was given a valid Host to satisfy the constructor's RFC 9112 §3.2 check).
		if (request instanceof org.deftserver.web.http.MalFormedHttpRequest) {
			return BadRequestRequestHandler.getInstance();
		}
		if (!HttpUtil.verifyRequest(request)) {
			return BadRequestRequestHandler.getInstance();
		}
		
		// Server-wide "OPTIONS *" applies to the whole server, not a resource.
		if (request.getMethod() == HttpVerb.OPTIONS && "*".equals(request.getRequestedPath())) {
			return org.deftserver.web.handler.ServerOptionsRequestHandler.getInstance();
		}

		RequestHandler rh = getHandler(request.getRequestedPath());

		// Intercept CORS preflight request
		if (request.getMethod() == HttpVerb.OPTIONS && 
			request.getHeader("Origin") != null && 
			request.getHeader("Access-Control-Request-Method") != null) {
			if (rh != null && rh != NotFoundRequestHandler.getInstance() && rh.getCorsConfig() != null) {
				return rh;
			}
			return CorsPreflightRequestHandler.getInstance();
		}
		
		// if @Authenticated annotation is present, make sure that the request/user is authenticated 
		// (i.e RequestHandler.getCurrentUser() != null).
		if (rh == null) return NotFoundRequestHandler.getInstance();
		if (rh.isMethodAuthenticated(request.getMethod()) && rh.getCurrentUser(request) == null) {
			return ForbiddenRequestHandler.getInstance();
		}
		return rh;
	}
	
	/** True if a path's final segment is a capturing-group pattern ({@code (...)}); also validates
	 *  the pattern compiles (throwing at startup if it doesn't). */
	private boolean containsCapturingGroup(String group) {
		boolean containsGroup =  group.matches("^\\(.*\\)$");
		Pattern.compile(group);	// throws PatternSyntaxException if group is malformed regular expression
		return containsGroup;
	}

	/** Resolves a path against the capturing-group handlers: matches the path's final segment against
	 *  the regex registered for its prefix. Returns the handler on a full match, else null. */
	private RequestHandler getCapturingHandler(String path) {
		int index = path.lastIndexOf("/");
		if (index != -1) {
			String init = path.substring(0, index+1);	// path without its last segment
			String group = path.substring(index+1, path.length()); 
			RequestHandler handler = capturingHandlers.get(init);
			if (handler != null) {
				Pattern regex = patterns.get(handler);
				if (regex.matcher(group).matches()) {
					return handler;
				}
			}
		}
		return null;
	}
	
	/** Returns the next monotonically-increasing request sequence number (used to tag requests). */
	public int nextHttpReqNum() {
		return requestNum.getAndIncrement();
	}

	/** Returns the static-content handler if the requested path normalises to a real location inside
	 *  the configured static root (a containment check that, with the parse-time normalisation and the
	 *  handler's own {@code toRealPath} symlink check, defends against traversal); else null. */
	private RequestHandler getStaticContentHandler(String path) {
		if (staticContentDir == null || staticContentRoot == null || path.length() <= staticContentDir.length()) {
			return null;	// quick reject (no static dir or simple contradiction)
		}
		
		java.nio.file.Path requested;
		if (staticContentDirIsAbsolute) {
			requested = java.nio.file.Path.of(path).toAbsolutePath().normalize();
		} else {
			requested = java.nio.file.Path.of(path.substring(1)).toAbsolutePath().normalize();
		}
		
		if (requested.startsWith(staticContentRoot)) {
			return StaticContentHandler.getInstance();
		} else {
			return null;
		}
	}
	
	/** Enables (or with null, disables) static file serving from the given directory, resolving and
	 *  caching its canonical root for the containment check. */
	public void setStaticContentDir(String scd) {
		if (scd != null && scd.isEmpty()) {
			throw new IllegalArgumentException("staticContentDir must not be empty (use null to disable)");
		}
		this.staticContentDir = scd;
		if (scd != null) {
			this.staticContentRoot = java.nio.file.Path.of(scd).toAbsolutePath().normalize();
			this.staticContentDirIsAbsolute = java.nio.file.Path.of(scd).isAbsolute();
		} else {
			this.staticContentRoot = null;
			this.staticContentDirIsAbsolute = false;
		}
		StaticContentHandler.getInstance().setStaticContentDir(scd);
	}

}
