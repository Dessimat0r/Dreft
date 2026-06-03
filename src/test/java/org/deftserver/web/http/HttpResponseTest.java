package org.deftserver.web.http;

import org.junit.Test;

public class HttpResponseTest {

	// HttpResponse can be constructed with null protocol/key for header-only assertions; setHeader
	// does not touch the channel.
	private static HttpResponse newResponse() {
		return new HttpResponse(null, null, false);
	}

	@Test(expected = IllegalArgumentException.class)
	public void setHeaderRejectsCRLFInValue() {
		// Response-splitting defence: a CR/LF in a header value must be rejected.
		newResponse().setHeader("Location", "/ok\r\nSet-Cookie: evil=1");
	}

	@Test(expected = IllegalArgumentException.class)
	public void setHeaderRejectsBareLFInValue() {
		newResponse().setHeader("X-Test", "a\nb");
	}

	@Test(expected = IllegalArgumentException.class)
	public void setHeaderRejectsControlCharInName() {
		newResponse().setHeader("X-Te\r\nst", "v");
	}

	@Test(expected = IllegalArgumentException.class)
	public void setHeaderRejectsColonInName() {
		newResponse().setHeader("X:Y", "v");
	}

	@Test(expected = IllegalArgumentException.class)
	public void setHeaderRejectsNullValue() {
		// A null value must fail fast here rather than surfacing as an NPE during serialization.
		newResponse().setHeader("X-Test", null);
	}

	@Test
	public void setHeaderAcceptsNormalValuesIncludingTabAndPunctuation() {
		HttpResponse r = newResponse();
		r.setHeader("Content-Type", "text/html; charset=utf-8");
		r.setHeader("Date", "Sun, 06 Nov 1994 08:49:37 GMT");
		r.setHeader("X-Tabbed", "a\tb"); // HT is the one permitted control char in a value
		r.setHeader("Allow", "GET, HEAD, POST");
	}
}
