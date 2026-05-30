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

	public Cookie(String name, String value) {
		if (name == null || name.trim().isEmpty()) {
			throw new IllegalArgumentException("Cookie name cannot be null or empty");
		}
		rejectControlChars("Cookie name", name);
		rejectControlChars("Cookie value", value);
		this.name = name;
		this.value = value;
	}

	/**
	 * Rejects characters that would let an attacker break out of the Set-Cookie header and
	 * inject additional headers/response data (CR, LF, NUL and other control characters).
	 * This is the primary defence against HTTP response-splitting via cookie attributes.
	 */
	private static void rejectControlChars(String field, String s) {
		if (s == null) return;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < 0x20 || c == 0x7f) {
				throw new IllegalArgumentException(field + " contains an illegal control character");
			}
		}
	}

	public String getName() {
		return name;
	}

	public String getValue() {
		return value;
	}

	public String getDomain() {
		return domain;
	}

	public void setDomain(String domain) {
		rejectControlChars("Cookie domain", domain);
		this.domain = domain;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		rejectControlChars("Cookie path", path);
		this.path = path;
	}

	public Long getMaxAge() {
		return maxAge;
	}

	public void setMaxAge(Long maxAge) {
		this.maxAge = maxAge;
	}

	public boolean isSecure() {
		return secure;
	}

	public void setSecure(boolean secure) {
		this.secure = secure;
	}

	public boolean isHttpOnly() {
		return httpOnly;
	}

	public void setHttpOnly(boolean httpOnly) {
		this.httpOnly = httpOnly;
	}

	public SameSite getSameSite() {
		return sameSite;
	}

	public void setSameSite(SameSite sameSite) {
		this.sameSite = sameSite;
	}

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
		if (secure) {
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
