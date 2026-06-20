package org.deftserver.web.http.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for the async-client {@link Response} — primarily the case-insensitive header handling
 * (HTTP field names are case-insensitive, RFC 9110 §5.1) that {@code getHeader}/{@code setHeader} provide.
 */
public class ResponseTest {

	private static Response newResponse() {
		return new Response(System.currentTimeMillis());
	}

	@Test
	public void getHeaderIsCaseInsensitive() {
		Response r = newResponse();
		r.setHeader("Content-Type", "text/html");
		assertEquals("text/html", r.getHeader("Content-Type"));
		assertEquals("text/html", r.getHeader("content-type"));
		assertEquals("text/html", r.getHeader("CONTENT-TYPE"));
		assertEquals("text/html", r.getHeader("CoNtEnT-tYpE"));
	}

	@Test
	public void setHeaderIsCaseInsensitiveOnStore() {
		Response r = newResponse();
		// Stored under a differently-cased name, looked up under another casing.
		r.setHeader("transfer-encoding", "chunked");
		assertEquals("chunked", r.getHeader("Transfer-Encoding"));
	}

	@Test
	public void headerValueCasingIsPreserved() {
		Response r = newResponse();
		r.setHeader("ETag", "\"AbCdEf\"");
		// Only the NAME is case-folded; the value keeps its original casing.
		assertEquals("\"AbCdEf\"", r.getHeader("etag"));
	}

	@Test
	public void lastWriteWinsAcrossNameCasings() {
		Response r = newResponse();
		r.setHeader("Cache-Control", "no-cache");
		r.setHeader("cache-control", "no-store"); // same field, different name casing → overwrites
		assertEquals("no-store", r.getHeader("CACHE-CONTROL"));
	}

	@Test
	public void absentHeaderReturnsNull() {
		Response r = newResponse();
		assertNull(r.getHeader("X-Does-Not-Exist"));
	}

	@Test
	public void nullKeyIsSafe() {
		Response r = newResponse();
		r.setHeader(null, "ignored"); // must not throw
		assertNull(r.getHeader(null)); // must not throw, returns null
	}
}
