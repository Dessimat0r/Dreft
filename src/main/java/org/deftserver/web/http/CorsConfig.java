package org.deftserver.web.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CorsConfig {

	private final List<String> allowedOrigins = new ArrayList<>();
	private final List<String> allowedMethods = new ArrayList<>();
	private final List<String> allowedHeaders = new ArrayList<>();
	private boolean allowCredentials = false;
	private Long maxAge;

	public List<String> getAllowedOrigins() {
		return Collections.unmodifiableList(allowedOrigins);
	}

	public void setAllowedOrigins(String... origins) {
		this.allowedOrigins.clear();
		this.allowedOrigins.addAll(Arrays.asList(origins));
	}

	public List<String> getAllowedMethods() {
		return Collections.unmodifiableList(allowedMethods);
	}

	public void setAllowedMethods(String... methods) {
		this.allowedMethods.clear();
		this.allowedMethods.addAll(Arrays.asList(methods));
	}

	public List<String> getAllowedHeaders() {
		return Collections.unmodifiableList(allowedHeaders);
	}

	public void setAllowedHeaders(String... headers) {
		this.allowedHeaders.clear();
		this.allowedHeaders.addAll(Arrays.asList(headers));
	}

	public boolean isAllowCredentials() {
		return allowCredentials;
	}

	public void setAllowCredentials(boolean allowCredentials) {
		this.allowCredentials = allowCredentials;
	}

	public Long getMaxAge() {
		return maxAge;
	}

	/** Sets the {@code Access-Control-Max-Age} (preflight cache lifetime in seconds; must be >= 0). */
	public void setMaxAge(Long maxAge) {
		if (maxAge != null && maxAge < 0) {
			throw new IllegalArgumentException("maxAge must be non-negative");
		}
		this.maxAge = maxAge;
	}

	public boolean isOriginAllowed(String origin) {
		if (origin == null) return false;
		if (allowedOrigins.contains("*")) return true;
		return allowedOrigins.contains(origin);
	}
}
