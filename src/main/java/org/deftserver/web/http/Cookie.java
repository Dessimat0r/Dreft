package org.deftserver.web.http;

public class Cookie {

	public enum SameSite {
		LAX("Lax"),
		STRICT("Strict"),
		NONE("None");

		private final String value;
		SameSite(String value) {
			this.value = value;
		}

		/** The wire form of this SameSite value (e.g. "Lax"). */
		public String getValue() {
			return value;
		}
	}

	private final String name;
	private final String value;
	private String domain;
	private String path = "/";
	private Long maxAge;
	private boolean secure = false;
	private boolean httpOnly = false;
	private SameSite sameSite;

	/** Creates a cookie with the given name and value, rejecting a null/empty name and any control
	 *  characters in name or value (the response-splitting defence). Attributes default to a root
	 *  Path and no Domain/Max-Age/Secure/HttpOnly/SameSite. */
	private static boolean isBlank(String s) {
		int len = s.length();
		if (len == 0) return true;
		for (int i = 0; i < len; i++) {
			if (s.charAt(i) > ' ') {
				return false;
			}
		}
		return true;
	}

	public Cookie(String name, String value) {
		if (name == null || isBlank(name)) {
			throw new IllegalArgumentException("Cookie name cannot be null or empty");
		}
		rejectControlChars("Cookie name", name);
		rejectControlChars("Cookie value", value);
		this.name = name;
		this.value = value;
	}

	/**
	 * Rejects characters that would let an attacker break out of the Set-Cookie header and
	 * inject additional attributes/headers/response data. Blocks control characters (CR/LF/NUL),
	 * semicolons (attribute separator), commas, and other HTTP-illegal characters.
	 */
	private static void rejectControlChars(String field, String s) {
		if (s == null) return;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < 0x20 || c == 0x7f || c == ';' || c == ',') {
				throw new IllegalArgumentException(field + " contains an illegal character");
			}
		}
	}

	/** The cookie name (the part before {@code =}). */
	public String getName() {
		return name;
	}

	/** The cookie value (the part after {@code =}). */
	public String getValue() {
		return value;
	}

	/** The {@code Domain} attribute, or null if unset. */
	public String getDomain() {
		return domain;
	}

	/** Sets the {@code Domain} attribute (rejecting control characters that could break the header). */
	public void setDomain(String domain) {
		rejectControlChars("Cookie domain", domain);
		this.domain = domain;
	}

	/** The {@code Path} attribute (defaults to {@code /}). */
	public String getPath() {
		return path;
	}

	/** Sets the {@code Path} attribute (rejecting control characters that could break the header). */
	public void setPath(String path) {
		rejectControlChars("Cookie path", path);
		this.path = path;
	}

	/** The {@code Max-Age} attribute in seconds, or null if unset. */
	public Long getMaxAge() {
		return maxAge;
	}

	/** Sets the {@code Max-Age} attribute (seconds; a non-positive value asks the client to delete it). */
	public void setMaxAge(Long maxAge) {
		this.maxAge = maxAge;
	}

	/** Whether the {@code Secure} attribute is set (cookie only sent over HTTPS). */
	public boolean isSecure() {
		return secure;
	}

	/** Sets the {@code Secure} attribute. */
	public void setSecure(boolean secure) {
		this.secure = secure;
	}

	/** Whether the {@code HttpOnly} attribute is set (cookie hidden from client-side scripts). */
	public boolean isHttpOnly() {
		return httpOnly;
	}

	/** Sets the {@code HttpOnly} attribute. */
	public void setHttpOnly(boolean httpOnly) {
		this.httpOnly = httpOnly;
	}

	/** The {@code SameSite} attribute, or null if unset. */
	public SameSite getSameSite() {
		return sameSite;
	}

	/** Sets the {@code SameSite} attribute (Lax / Strict / None). */
	public void setSameSite(SameSite sameSite) {
		this.sameSite = sameSite;
	}

	/** Serializes the cookie to its {@code Set-Cookie} header value form ({@code name=value} plus
	 *  any configured attributes). */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(name).append("=").append(value != null ? value : "");
		
		if (path != null && !path.isEmpty()) {
			sb.append("; Path=").append(path);
		}
		if (domain != null && !domain.isEmpty()) {
			sb.append("; Domain=").append(domain);
		}
		if (maxAge != null) {
			sb.append("; Max-Age=").append(maxAge);
		}
		// RFC 6265bis §5.4.7: a SameSite=None cookie MUST also be Secure or browsers reject (drop) it.
		// Emit Secure when explicitly set OR implied by SameSite=None, so the cookie isn't silently
		// discarded by the client.
		if (secure || sameSite == SameSite.NONE) {
			sb.append("; Secure");
		}
		if (httpOnly) {
			sb.append("; HttpOnly");
		}
		if (sameSite != null) {
			sb.append("; SameSite=").append(sameSite.getValue());
		}
		return sb.toString();
	}
}
