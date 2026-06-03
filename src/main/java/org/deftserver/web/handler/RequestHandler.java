package org.deftserver.web.handler;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Map;

import org.deftserver.web.Asynchronous;
import org.deftserver.web.Authenticated;
import org.deftserver.web.HttpVerb;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;

import java.util.HashMap;

public abstract class RequestHandler {

	private final Map<HttpVerb, Boolean> asynchVerbs;
	private final Map<HttpVerb, Boolean> authVerbs;
	private org.deftserver.web.http.CorsConfig corsConfig;

	/** The per-resource CORS policy for this handler, or null if CORS is not configured. */
	public org.deftserver.web.http.CorsConfig getCorsConfig() {
		return corsConfig;
	}

	/** Attaches a per-resource CORS policy (enables CORS handling for this handler). */
	public void setCorsConfig(org.deftserver.web.http.CorsConfig corsConfig) {
		this.corsConfig = corsConfig;
	}

	private final java.util.EnumSet<HttpVerb> implementedVerbs;
	private final String allowHeader;

	/** Reflects (once, at construction) over the concrete subclass to record which verbs are
	 *  overridden and which carry the {@code @Asynchronous}/{@code @Authenticated} annotations, so
	 *  per-request dispatch (405/Allow, async offload, auth) needs no further reflection. */
	public RequestHandler() {
		Map<HttpVerb, Boolean> asyncV = new HashMap<>();
		Map<HttpVerb, Boolean> authV = new HashMap<>();
		for (HttpVerb verb : HttpVerb.values()) {
			authV.put(verb, isMethodAnnotated(verb, Authenticated.class));
			asyncV.put(verb, isMethodAnnotated(verb, Asynchronous.class));
		}
		asynchVerbs = Map.copyOf(asyncV);
		authVerbs = Map.copyOf(authV);

		// Detect which verbs the concrete handler actually implements (overrides), so the
		// dispatcher can answer a known-but-unimplemented method with 405 + Allow (RFC 9110
		// §15.5.6) and synthesize an OPTIONS response, rather than mislabelling it 501.
		java.util.EnumSet<HttpVerb> impl = java.util.EnumSet.noneOf(HttpVerb.class);
		for (HttpVerb verb : new HttpVerb[]{HttpVerb.GET, HttpVerb.POST, HttpVerb.PUT,
				HttpVerb.PATCH, HttpVerb.DELETE, HttpVerb.OPTIONS, HttpVerb.TRACE, HttpVerb.CONNECT}) {
			if (isVerbOverridden(verb)) {
				impl.add(verb);
			}
		}
		// HEAD is served by get() unless head() itself is overridden.
		if (impl.contains(HttpVerb.GET) || isVerbOverridden(HttpVerb.HEAD)) {
			impl.add(HttpVerb.HEAD);
		}
		// implementedVerbs holds only verbs the subclass actually overrides — this drives the
		// dispatcher's decision to *call* the handler method vs answer 405/synthesize OPTIONS.
		this.implementedVerbs = impl;

		// The Allow header additionally always advertises OPTIONS, which the dispatcher can answer
		// itself (204 + Allow) even when the handler doesn't override options(). Stable ordering.
		java.util.EnumSet<HttpVerb> advertised = java.util.EnumSet.copyOf(impl);
		advertised.add(HttpVerb.OPTIONS);
		StringBuilder allow = new StringBuilder();
		for (HttpVerb verb : new HttpVerb[]{HttpVerb.GET, HttpVerb.HEAD, HttpVerb.POST, HttpVerb.PUT,
				HttpVerb.PATCH, HttpVerb.DELETE, HttpVerb.OPTIONS, HttpVerb.TRACE, HttpVerb.CONNECT}) {
			if (advertised.contains(verb)) {
				if (allow.length() > 0) allow.append(", ");
				allow.append(verb.name());
			}
		}
		this.allowHeader = allow.toString();
	}

	/** True if the concrete subclass overrides the verb's handler method (i.e. the declaring class of
	 *  e.g. {@code get(..)} is not {@code RequestHandler} itself). */
	private boolean isVerbOverridden(HttpVerb verb) {
		try {
			Class<?>[] parameterTypes = {HttpRequest.class, HttpResponse.class};
			return getClass().getMethod(verb.toString().toLowerCase(java.util.Locale.ROOT), parameterTypes)
				.getDeclaringClass() != RequestHandler.class;
		} catch (NoSuchMethodException nsme) {
			return false;
		}
	}

	/** True if the concrete handler actually overrides the given verb's method. */
	public boolean isVerbImplemented(HttpVerb verb) {
		return implementedVerbs.contains(verb);
	}

	/** Comma-separated list of supported methods for the Allow header (RFC 9110 §10.2.1). */
	public String getAllowHeader() {
		return allowHeader;
	}

	/** True if this handler's method for the given verb is annotated with the given annotation
	 *  (used at construction to pre-compute the async/auth verb sets). */
	private boolean isMethodAnnotated(HttpVerb verb, Class<? extends Annotation> annotation) {
		try {
			Class<?>[] parameterTypes = {HttpRequest.class, HttpResponse.class};
			return getClass().getMethod(verb.toString().toLowerCase(java.util.Locale.ROOT), parameterTypes).getAnnotation(annotation) != null;
		} catch (NoSuchMethodException nsme) {
			return false;
		}
	}

	/** True if the verb's handler is marked {@code @Asynchronous} (so the dispatcher must let it
	 *  finish the response itself). HEAD inherits GET's disposition. */
	public boolean isMethodAsynchronous(HttpVerb verb) {
		HttpVerb effective = (verb == HttpVerb.HEAD) ? HttpVerb.GET : verb;
		return asynchVerbs.get(effective);
	}

	/** True if the verb's handler is marked {@code @Authenticated} (so the dispatcher must enforce
	 *  {@link #getCurrentUser} != null). HEAD inherits GET's disposition. */
	public boolean isMethodAuthenticated(HttpVerb verb) {
		HttpVerb effective = (verb == HttpVerb.HEAD) ? HttpVerb.GET : verb;
		return authVerbs.get(effective);
	}

	// Default verb implementations return 501; a concrete handler overrides the verbs it supports.
	// (The dispatcher answers a known-but-unoverridden verb with 405 + Allow rather than calling
	// these, so in practice they are a fallback.)

	/** Handles a GET request (override to implement). Default: 501 Not Implemented. */
	public void get(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(501);
		response.write("");
	}

	/** Handles a POST request (override to implement). Default: 501 Not Implemented. */
	public void post(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(501);
		response.write("");
	}

	/** Handles a PUT request (override to implement). Default: 501 Not Implemented. */
	public void put(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(501);
		response.write("");
	}

	/** Handles a PATCH request (override to implement). Default: 501 Not Implemented. */
	public void patch(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(501);
		response.write("");
	}

	/** Handles a DELETE request (override to implement). Default: 501 Not Implemented. */
	public void delete(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(501);
		response.write("");
	}

	/** Handles a HEAD request; by default delegates to {@link #get} (the response layer suppresses
	 *  the body while preserving Content-Length). */
	public void head(HttpRequest request, HttpResponse response) throws IOException {
		get(request, response);
	}

	/** Handles an OPTIONS request (override to implement). Default: 501 — note the dispatcher
	 *  synthesizes a 204 + Allow for handlers that don't override this. */
	public void options(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(501);
		response.write("");
	}

	/** Handles a TRACE request (override to implement). Default: 501 (TRACE is disabled by default —
	 *  the dispatcher answers 405 for an unoverridden verb — which avoids Cross-Site Tracing). */
	public void trace(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(501);
		response.write("");
	}

	/** Handles a CONNECT request (override to implement). Default: 501 (the server is not a proxy). */
	public void connect(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(501);
		response.write("");
	}

	/** Returns the authenticated user for the request, or null if unauthenticated. Override to
	 *  integrate authentication; consulted for verbs annotated {@code @Authenticated}. */
	public String getCurrentUser(HttpRequest request) {
		return null;
	}

}
