package org.deftserver.web.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

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

	@Test
	public void getChunkHeaderBytesCorrectness() throws Exception {
		Method method = HttpResponse.class.getDeclaredMethod("getChunkHeaderBytes", int.class);
		method.setAccessible(true);

		byte[] b1 = (byte[]) method.invoke(null, 0);
		assertEquals("0\r\n", new String(b1, StandardCharsets.ISO_8859_1));

		byte[] b2 = (byte[]) method.invoke(null, 255);
		assertEquals("ff\r\n", new String(b2, StandardCharsets.ISO_8859_1));

		byte[] b3 = (byte[]) method.invoke(null, 4096);
		assertEquals("1000\r\n", new String(b3, StandardCharsets.ISO_8859_1));
	}

	@Test
	public void protocolConstantsAreCorrect() throws Exception {
		java.lang.reflect.Field crlfField = HttpResponse.class.getDeclaredField("CRLF");
		crlfField.setAccessible(true);
		byte[] crlf = (byte[]) crlfField.get(null);
		assertArrayEquals(new byte[] { '\r', '\n' }, crlf);

		java.lang.reflect.Field zeroField = HttpResponse.class.getDeclaredField("ZERO_CRLF_CRLF");
		zeroField.setAccessible(true);
		byte[] zero = (byte[]) zeroField.get(null);
		assertArrayEquals(new byte[] { '0', '\r', '\n', '\r', '\n' }, zero);
	}
}
