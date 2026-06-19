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

	private static final NotFoundRequestHandler NOT_FOUND = NotFoundRequestHandler.getInstance();
	private static final BadRequestRequestHandler BAD_REQUEST = BadRequestRequestHandler.getInstance();
	private static final ForbiddenRequestHandler FORBIDDEN = ForbiddenRequestHandler.getInstance();
	private static final CorsPreflightRequestHandler CORS_PREFLIGHT = CorsPreflightRequestHandler.getInstance();
	private static final org.deftserver.web.handler.ServerOptionsRequestHandler SERVER_OPTIONS =
		org.deftserver.web.handler.ServerOptionsRequestHandler.getInstance();

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
		return rh != null ? rh : NOT_FOUND;
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
			return BAD_REQUEST;
		}
		if (!HttpUtil.verifyRequest(request)) {
			return BAD_REQUEST;
		}
		
		// Server-wide "OPTIONS *" applies to the whole server, not a resource.
		if (request.getMethod() == HttpVerb.OPTIONS && "*".equals(request.getRequestedPath())) {
			return SERVER_OPTIONS;
		}

		RequestHandler rh = getHandler(request.getRequestedPath());

		// Intercept CORS preflight request
		if (request.getMethod() == HttpVerb.OPTIONS && 
			request.getHeader("Origin") != null && 
			request.getHeader("Access-Control-Request-Method") != null) {
			if (rh != null && rh != NOT_FOUND && rh.getCorsConfig() != null) {
				return rh;
			}
			return CORS_PREFLIGHT;
		}
		
		// if @Authenticated annotation is present, make sure that the request/user is authenticated 
		// (i.e RequestHandler.getCurrentUser() != null).
		if (rh == null) return NOT_FOUND;
		if (rh.isMethodAuthenticated(request.getMethod()) && rh.getCurrentUser(request) == null) {
			return FORBIDDEN;
		}
		return rh;
	}
	
	/** True if a path's final segment is a capturing-group pattern ({@code (...)}); also validates
	 *  the pattern compiles (throwing at startup if it doesn't). */
	private boolean containsCapturingGroup(String group) {
		// A capturing-group segment is wrapped in parentheses, e.g. "([0-9]+)". Use startsWith/endsWith
		// rather than group.matches("^\\(.*\\)$"): identical for path segments (which can't contain a
		// newline, the only case where "." would differ) but avoids compiling a throwaway regex on
		// every registered route. Length >= 2 is implied by needing both a '(' and a ')'.
		boolean containsGroup = group.startsWith("(") && group.endsWith(")") && group.length() >= 2;
		// Only validate-compile a segment that is actually a capturing group (wrapped in parens) and
		// will therefore be used as a regex. A literal route segment is just an exact-match map key —
		// compiling it unconditionally would throw PatternSyntaxException for perfectly legal literal
		// paths that happen to contain an unbalanced regex metacharacter (e.g. "/files/data[1"),
		// crashing Application construction / server startup on a valid configuration.
		if (containsGroup) {
			Pattern.compile(group); // throws PatternSyntaxException if the group is a malformed regex
		}
		return containsGroup;
	}

	private static final ThreadLocal<Map<Pattern, java.util.regex.Matcher>> threadLocalMatchers =
		ThreadLocal.withInitial(java.util.HashMap::new);

	/** Resolves a path against the capturing-group handlers: matches the path's final segment against
	 *  the regex registered for its prefix. Returns the handler on a full match, else null. */
	private static class SubCharSequence implements CharSequence {
		private final String str;
		private final int start;
		private final int end;

		SubCharSequence(String str, int start, int end) {
			this.str = str;
			this.start = start;
			this.end = end;
		}

		@Override
		public int length() {
			return end - start;
		}

		@Override
		public char charAt(int index) {
			return str.charAt(start + index);
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			return new SubCharSequence(str, this.start + start, this.start + end);
		}

		@Override
		public String toString() {
			return str.substring(start, end);
		}
	}

	private RequestHandler getCapturingHandler(String path) {
		int index = path.lastIndexOf("/");
		if (index != -1) {
			for (Map.Entry<String, RequestHandler> entry : capturingHandlers.entrySet()) {
				String key = entry.getKey();
				if (key.length() == index + 1 && path.regionMatches(0, key, 0, key.length())) {
					RequestHandler handler = entry.getValue();
					Pattern regex = patterns.get(handler);
					if (regex != null) {
						java.util.regex.Matcher m = threadLocalMatchers.get().computeIfAbsent(regex, r -> r.matcher(""));
						SubCharSequence group = new SubCharSequence(path, index + 1, path.length());
						if (m.reset(group).matches()) {
							return handler;
						}
					}
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
		
		String prefix = staticContentDirIsAbsolute ? staticContentDir : "/" + staticContentDir;
		if (!path.equals(prefix) && !path.startsWith(prefix + "/")) {
			return null;
		}
		
		if (path.contains("..") || path.contains("\0")) {
			return null;
		}
		
		return StaticContentHandler.getInstance();
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

	public void setStaticContentCacheDir(String path) {
		StaticContentHandler.getInstance().setCacheDir(path);
	}

}
