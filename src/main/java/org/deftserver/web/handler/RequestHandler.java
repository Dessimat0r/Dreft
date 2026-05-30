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

	public org.deftserver.web.http.CorsConfig getCorsConfig() {
		return corsConfig;
	}

	public void setCorsConfig(org.deftserver.web.http.CorsConfig corsConfig) {
		this.corsConfig = corsConfig;
	}

	public RequestHandler() {
		Map<HttpVerb, Boolean> asyncV = new HashMap<>();
		Map<HttpVerb, Boolean> authV = new HashMap<>();
		for (HttpVerb verb : HttpVerb.values()) {
			authV.put(verb, isMethodAnnotated(verb, Authenticated.class));
			asyncV.put(verb, isMethodAnnotated(verb, Asynchronous.class));
		}
		asynchVerbs = Map.copyOf(asyncV);
		authVerbs = Map.copyOf(authV);
	}

	private boolean isMethodAnnotated(HttpVerb verb, Class<? extends Annotation> annotation) {
		try {
			Class<?>[] parameterTypes = {HttpRequest.class, HttpResponse.class};
			return getClass().getMethod(verb.toString().toLowerCase(java.util.Locale.ROOT), parameterTypes).getAnnotation(annotation) != null;
		} catch (NoSuchMethodException nsme) {
			return false;
		}
	}
	
	public boolean isMethodAsynchronous(HttpVerb verb) {
		HttpVerb effective = (verb == HttpVerb.HEAD) ? HttpVerb.GET : verb;
		return asynchVerbs.get(effective);
	}
	
	public boolean isMethodAuthenticated(HttpVerb verb) {
		HttpVerb effective = (verb == HttpVerb.HEAD) ? HttpVerb.GET : verb;
		return authVerbs.get(effective);
	}

	//Default implementation of HttpMethods return a 501 page
	public void get(HttpRequest request, HttpResponse response) throws IOException { 
		response.setStatusCode(501);
		response.write("");
	}

	public void post(HttpRequest request, HttpResponse response) throws IOException { 
		response.setStatusCode(501);
		response.write("");
	}

	public void put(HttpRequest request, HttpResponse response) throws IOException { 
		response.setStatusCode(501);
		response.write("");
	}

	public void patch(HttpRequest request, HttpResponse response) throws IOException {
		response.setStatusCode(501);
		response.write("");
	}

	public void delete(HttpRequest request, HttpResponse response) throws IOException { 
		response.setStatusCode(501);
		response.write("");
	}

	public void head(HttpRequest request, HttpResponse response) throws IOException { 
		get(request, response);
	}

	public void options(HttpRequest request, HttpResponse response) throws IOException { 
		response.setStatusCode(501);
		response.write("");
	}

	public void trace(HttpRequest request, HttpResponse response) throws IOException { 
		response.setStatusCode(501);
		response.write("");
	}

	public void connect(HttpRequest request, HttpResponse response) throws IOException { 
		response.setStatusCode(501);
		response.write("");
	}
	
	public String getCurrentUser(HttpRequest request) { 
		return null; 
	}

}
