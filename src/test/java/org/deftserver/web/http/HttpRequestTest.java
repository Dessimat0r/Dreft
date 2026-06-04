package org.deftserver.web.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.deftserver.util.HttpRequestHelper;
import org.deftserver.util.HttpUtil;
import org.deftserver.web.http.HttpRequest;
import org.junit.Assert;
import org.junit.Test;


public class HttpRequestTest {


	@Test 
	public void testDeserializeHttpGetRequest() {
		HttpRequestHelper helper = new HttpRequestHelper();
		helper.addHeader("Host", "127.0.0.1:8080");
		helper.addHeader("User-Agent", "curl/7.19.5 (i386-apple-darwin10.0.0) libcurl/7.19.5 zlib/1.2.3");
		helper.addHeader("Accept", "*/*");
		ByteBuffer bb1 = helper.getRequestAsByteBuffer(); 

		helper = new HttpRequestHelper();
		helper.addHeader("Host", "127.0.0.1:8080");
		helper.addHeader("User-Agent", "Mozilla/5.0 (Macintosh; U; Intel Mac OS X 10.6; sv-SE; rv:1.9.2.2) Gecko/20100316 Firefox/3.6.2");
		helper.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
		helper.addHeader("Accept-Language", "sv-se,sv;q=0.8,en-us;q=0.5,en;q=0.3");
		helper.addHeader("Accept-Encoding", "gzip,deflate");
		helper.addHeader("Accept-Charset", "ISO-8859-1,utf-8;q=0.7,*;q=0.7");
		helper.addHeader("Keep-Alive", "115");
		helper.addHeader("Connection", "keep-alve");
		ByteBuffer bb2 = helper.getRequestAsByteBuffer();

		HttpRequest request1 = HttpRequest.of(bb1);
		HttpRequest request2 = HttpRequest.of(bb2);

		assertEquals("GET / HTTP/1.1", request1.getRequestLine());
		assertEquals("GET / HTTP/1.1", request2.getRequestLine());

		assertEquals(4, request1.getHeaders().size());
		assertEquals(9, request2.getHeaders().size());

		List<String> expectedHeaderNamesInRequest1 = Arrays.asList(new String[]{"User-Agent", "Host", "Accept", "From"});
		for (String expectedHeaderName : expectedHeaderNamesInRequest1) {
			assertTrue(request1.getHeaders().containsKey(expectedHeaderName.toLowerCase()));
		}

		List<String> expectedHeaderNamesInRequest2 = Arrays.asList(new String[]{"Host", "User-Agent", "Accept", "From",
				"Accept-Language", "Accept-Encoding", "Accept-Charset", "Keep-Alive", "Connection"});
		for (String expectedHeaderName : expectedHeaderNamesInRequest2) {
			assertTrue(request2.getHeaders().containsKey(expectedHeaderName.toLowerCase()));
		}

		// TODO RS 100920 verify that the headers exist
	}

	@Test
	public void testSingleGetParameter() {
		HttpRequestHelper helper = new HttpRequestHelper();
		helper.addGetParameter("firstname", "jim");

		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());

		assertEquals(1, request.getParameters().size());
		assertEquals("jim", request.getParameter("firstname"));
	}

	@Test
	public void testMultipleGetParameter() {
		HttpRequestHelper helper = new HttpRequestHelper();
		helper.addGetParameter("firstname", "jim");
		helper.addGetParameter("lastname", "petersson");
		helper.addGetParameter("city", "stockholm");

		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());
		Map<String, Collection<String>> params = request.getParameters();

		assertEquals(3, getSize(params));
		assertEquals("jim", request.getParameter("firstname"));
		assertEquals("petersson", request.getParameter("lastname"));
		assertEquals("stockholm", request.getParameter("city"));
	}

	private int getSize(Map<String, Collection<String>> mmap) {
		int size = 0;
		for (Collection<String> values : mmap.values()) {
			size += values.size();
		}
		return size;
	}


	@Test
	public void valuelessFormFloodParsesWithoutAmplification() {
		// A form body of many value-less params ("a&a&...") yields no parameters and must not be
		// rejected — and (the point of this regression) must NOT materialise every segment up front
		// (the old regex split did, an OOM amplification the 413 param cap never caught since no
		// param has a value). 200k pairs ≈ 400 KiB.
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < 200_000; i++) {
			if (i > 0) body.append('&');
			body.append('a');
		}
		HttpRequest request = HttpRequest.of(raw(
			"POST /p HTTP/1.1\r\nHost: localhost\r\n" +
			"Content-Type: application/x-www-form-urlencoded\r\n" +
			"Content-Length: " + body.length() + "\r\n\r\n" + body));
		assertTrue(request.isComplete());
		assertEquals(0, request.getPostParameters().size());
	}

	@Test
	public void testSingleParameterWithoutValue() {
		HttpRequestHelper helper = new HttpRequestHelper();
		helper.addGetParameter("firstname", null);

		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());
		Map<String, Collection<String>> params = request.getParameters();
		assertEquals(0, getSize(params));
		assertEquals(null, request.getParameter("firstname"));
	}

	@Test
	public void testMultipleParametersWithoutValue() {
		HttpRequestHelper helper = new HttpRequestHelper();
		helper.addGetParameter("firstname", null);
		helper.addGetParameter("lastName", "");

		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());
		Map<String, Collection<String>> params = request.getParameters();

		assertEquals(0, getSize(params));
		assertEquals(null, request.getParameter("firstname"));
		assertEquals(null, request.getParameter("lastName"));
	}

	@Test
	public void testMultipleParametersWithAndWithoutValue() {
		HttpRequestHelper helper = new HttpRequestHelper();
		helper.addGetParameter("firstname", null);
		helper.addGetParameter("lastName", "petersson");
		helper.addGetParameter("city", "");
		helper.addGetParameter("phoneno", "12345");
		helper.addGetParameter("age", "30");

		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());
		Map<String, Collection<String>> params = request.getParameters();

		assertEquals(3, getSize(params));
		assertEquals(null, request.getParameter("firstname"));
		assertEquals("petersson", request.getParameter("lastName"));
		assertEquals(null, request.getParameter("city"));
		assertEquals("12345", request.getParameter("phoneno"));
		assertEquals("30", request.getParameter("age"));
	}

	@Test
	public void testSingleGetParameterMultipleValues() {
		HttpRequestHelper helper = new HttpRequestHelper();
		helper.addGetParameter("letters", "x");
		helper.addGetParameter("letters", "y");
		helper.addGetParameter("letters", "z");

		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());
		Map<String, Collection<String>> params = request.getParameters();

		assertEquals(3, getSize(params));
		Collection<String> values = params.get("letters");
		assertEquals(3, values.size());
		assertTrue(values.contains("x"));
		assertTrue(values.contains("y"));
		assertTrue(values.contains("z"));
	}

	@Test
	public void testMultipleGetParametersMultipleValues() {
		HttpRequestHelper helper = new HttpRequestHelper();
		helper.addGetParameter("letters", "x");
		helper.addGetParameter("letters", "y");
		helper.addGetParameter("letters", "z");
		helper.addGetParameter("numbers", "23");
		helper.addGetParameter("numbers", "54");
		helper.addGetParameter("country", "swe");

		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());
		Map<String, Collection<String>> params = request.getParameters();

		assertEquals(6, getSize(params));
		Collection<String> letters = params.get("letters");
		Collection<String> numbers = params.get("numbers");
		Collection<String> country = params.get("country");

		assertEquals(3, letters.size());
		assertEquals(2, numbers.size());
		assertEquals(1, country.size());

		assertTrue(letters.contains("x"));
		assertTrue(letters.contains("y"));
		assertTrue(letters.contains("z"));

		assertTrue(numbers.contains("23"));
		assertTrue(numbers.contains("54"));

		assertTrue(country.contains("swe"));
	}

	@Test
	public void testSingleGetParameterMultipleValuesIncludingNull() {
		HttpRequestHelper helper = new HttpRequestHelper();
		helper.addGetParameter("letters", "x");
		helper.addGetParameter("letters", "y");
		helper.addGetParameter("letters", null);
		helper.addGetParameter("letters", "z");

		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());
		Map<String, Collection<String>> params = request.getParameters();

		assertEquals(3, getSize(params));
		Collection<String> values = params.get("letters");
		assertEquals(3, values.size());
		assertTrue(values.contains("x"));
		assertTrue(values.contains("y"));
		assertTrue(values.contains("z"));
	}

	@Test
	public void testEmptyParameters() {
		HttpRequestHelper helper = new HttpRequestHelper();
		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());
		Map<String, Collection<String>> params = request.getParameters();
		assertNotNull(params);
		assertEquals(0, getSize(params));
	}

	@Test(expected=UnsupportedOperationException.class)
	public void testImmutableParameters() {
		HttpRequestHelper helper = new HttpRequestHelper();
		helper.addGetParameter("letter", "x");

		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());
		Map<String, Collection<String>> params = request.getParameters();
		params.put("not", new ArrayList<String>());	
	}

	@Test
	public void testHostVerification_exists_HTTP_1_0() {
		HttpRequestHelper helper = new HttpRequestHelper();
		helper.setVersion("1.0");
		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());
		boolean requestOk = HttpUtil.verifyRequest(request);
		assertTrue(requestOk);
	}

	@Test
	public void testHostVerification_nonExisting_HTTP_1_0() {
		HttpRequestHelper helper = new HttpRequestHelper();
		helper.setVersion("1.0");
		helper.removeHeader("Host");
		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());
		boolean requestOk = HttpUtil.verifyRequest(request);
		assertTrue(requestOk);
	}

	@Test
	public void testHostVerification_exists_HTTP_1_1() {
		HttpRequestHelper helper = new HttpRequestHelper();
		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());
		boolean requestOk = HttpUtil.verifyRequest(request);
		assertTrue(requestOk);
	}

	@Test
	public void testHostVerification_nonExisting_HTTP_1_1() {
		HttpRequestHelper helper = new HttpRequestHelper();
		helper.removeHeader("Host");
		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());
		boolean requestOk = HttpUtil.verifyRequest(request);
		assertFalse(requestOk);
	}

	@Test
	public void testGarbageRequest() {
		HttpRequest.of(ByteBuffer.wrap(
				new byte[] {1, 1, 1, 1}	// garbage
		));
	}
	
	/**
	 * Ensure that header keys are converted to lower case, to facilitate
	 * case-insensitive retrieval through {@link HttpRequest#getHeader(String)}.
	 */
	@Test
	public void testOfConvertsHeaderKeysToLowerCase() {

		HttpRequestHelper helper = new HttpRequestHelper();
		helper.addHeader("TESTKEY", "unimportant");
		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());

		assertFalse(request.getHeaders().containsKey("TESTKEY"));
		assertTrue(request.getHeaders().containsKey("testkey"));
	}

	/**
	 * Ensure that the case of any header values is correctly maintained.
	 */
	@Test
	public void testOfMaintainsHeaderValueCase() {

		String expected = "vAlUe";

		HttpRequestHelper helper = new HttpRequestHelper();
		helper.addHeader("TESTKEY", expected);
		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());

		String actual = request.getHeader("TESTKEY");
		assertEquals(expected, actual);
	}

	/**
	 * Ensure that case for any key passed to the method is unimportant
	 * for its retrieval.
	 */
	@Test
	public void testGetHeader() {

		String expected = "value";

		HttpRequestHelper helper = new HttpRequestHelper();
		helper.addHeader("TESTKEY", expected);
		HttpRequest request = HttpRequest.of(helper.getRequestAsByteBuffer());

		assertEquals(expected, request.getHeader("TESTKEY"));
		assertEquals(expected, request.getHeader("testkey"));
	}
	
	@Test 
	public void testHttpRequestNoQueryString() {
		String requestLine = "GET /foobar HTTP/1.1 ";
		HttpRequest request = new HttpRequest(requestLine, new HashMap<String, String>());
		Assert.assertEquals("/foobar", request.getRequestedPath());
	}
	
	@Test 
	public void testHttpRequestNullQueryString() {
		String requestLine = "GET /foobar? HTTP/1.1 ";
		HttpRequest request = new HttpRequest(requestLine, new HashMap<String, String>());
		Assert.assertEquals("/foobar", request.getRequestedPath());
	}
	
	@Test 
	public void testHttpRequestNullQueryStringTrailingSlash() {
		String requestLine = "GET /foobar/? HTTP/1.1 ";
		HttpRequest request = new HttpRequest(requestLine, new HashMap<String, String>());
		Assert.assertEquals("/foobar/", request.getRequestedPath());
	}

	@Test
	public void parsesHeadersWithOptionalWhitespace() {
		HttpRequest request = HttpRequest.of(raw("""
				GET / HTTP/1.1\r
				Host:localhost\r
				X-Test:   value\r
				\r
				"""));

		assertEquals("localhost", request.getHeader("host"));
		assertEquals("value", request.getHeader("x-test"));
	}

	@Test
	public void rejectsConflictingContentLengthHeaders() {
		HttpRequest request = HttpRequest.of(raw("""
				POST / HTTP/1.1\r
				Host: localhost\r
				Content-Length: 1\r
				Content-Length: 2\r
				\r
				a"""));

		assertTrue(request instanceof MalFormedHttpRequest);
	}

	@Test
	public void parsesChunkedRequestBody() {
		HttpRequest request = HttpRequest.of(raw("""
				POST /echo HTTP/1.1\r
				Host: localhost\r
				Transfer-Encoding: chunked\r
				Content-Type: text/plain\r
				\r
				4\r
				Wiki\r
				5\r
				pedia\r
				0\r
				\r
				"""));

		assertTrue(request.isComplete());
		assertEquals("Wikipedia", request.getBody());
	}

	@Test
	public void rejectsSignedChunkSize() {
		// RFC 9112 §7.1: chunk-size is 1*HEXDIG. A leading '+' (which Integer.parseInt would
		// otherwise accept) must be rejected — a stricter proxy reading "+4" differently than
		// Dreft's 4 is a request-smuggling discrepancy.
		HttpRequest request = HttpRequest.of(raw(
			"POST /echo HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
			+ "+4\r\nWiki\r\n0\r\n\r\n"));
		assertTrue(request instanceof MalFormedHttpRequest);
	}

	@Test
	public void rejectsHexPrefixChunkSize() {
		HttpRequest request = HttpRequest.of(raw(
			"POST /echo HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
			+ "0x4\r\nWiki\r\n0\r\n\r\n"));
		assertTrue(request instanceof MalFormedHttpRequest);
	}

	@Test
	public void chunkLargerThanInitialBufferGrowsAndDecodes() {
		// A single chunk larger than the initial raw-body buffer (64 KiB) must grow the buffer on
		// demand and decode correctly — the raw buffer is no longer pre-sized to the 16 MiB cap.
		int size = 100_000; // > 64 KiB
		StringBuilder data = new StringBuilder(size);
		for (int i = 0; i < size; i++) data.append((char) ('a' + (i % 26)));
		String body = Integer.toHexString(size) + "\r\n" + data + "\r\n0\r\n\r\n";
		HttpRequest request = HttpRequest.of(raw(
			"POST /echo HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n" + body));
		assertTrue("request should be complete", request.isComplete());
		assertEquals(size, request.getBody().length());
		assertEquals(data.toString(), request.getBody());
	}

	@Test
	public void allowsBodiesForPutAndPatch() {
		HttpRequest put = HttpRequest.of(raw("""
				PUT /resource HTTP/1.1\r
				Host: localhost\r
				Content-Type: text/plain\r
				Content-Length: 3\r
				\r
				put"""));
		HttpRequest patch = HttpRequest.of(raw("""
				PATCH /resource HTTP/1.1\r
				Host: localhost\r
				Content-Type: text/plain\r
				Content-Length: 5\r
				\r
				patch"""));

		assertEquals("put", put.getBody());
		assertEquals("patch", patch.getBody());
	}

	@Test
	public void unknownMethodsDoNotCrashParser() {
		HttpRequest request = HttpRequest.of(raw("""
				PROPFIND /resource HTTP/1.1\r
				Host: localhost\r
				\r
				"""));

		assertEquals(org.deftserver.web.HttpVerb.UNKNOWN, request.getMethod());
	}
	@Test
	public void testFindInBB() {
		// Empty search term
		ByteBuffer buf = ByteBuffer.wrap("abc".getBytes(StandardCharsets.ISO_8859_1));
		assertTrue(HttpRequest.findInBB(buf, new byte[0]));
		assertEquals(0, buf.position());

		// Search term exists at start
		buf = ByteBuffer.wrap("abcdef".getBytes(StandardCharsets.ISO_8859_1));
		assertTrue(HttpRequest.findInBB(buf, "abc".getBytes(StandardCharsets.ISO_8859_1)));
		assertEquals(3, buf.position());

		// Search term exists in middle
		buf = ByteBuffer.wrap("abcdef".getBytes(StandardCharsets.ISO_8859_1));
		assertTrue(HttpRequest.findInBB(buf, "cde".getBytes(StandardCharsets.ISO_8859_1)));
		assertEquals(5, buf.position());

		// Search term exists at end
		buf = ByteBuffer.wrap("abcdef".getBytes(StandardCharsets.ISO_8859_1));
		assertTrue(HttpRequest.findInBB(buf, "def".getBytes(StandardCharsets.ISO_8859_1)));
		assertEquals(6, buf.position());

		// Search term does not exist
		buf = ByteBuffer.wrap("abcdef".getBytes(StandardCharsets.ISO_8859_1));
		assertFalse(HttpRequest.findInBB(buf, "xyz".getBytes(StandardCharsets.ISO_8859_1)));
		assertEquals(6, buf.position());

		// Partial/overlapping matches that succeed
		buf = ByteBuffer.wrap("ababcde".getBytes(StandardCharsets.ISO_8859_1));
		assertTrue(HttpRequest.findInBB(buf, "abc".getBytes(StandardCharsets.ISO_8859_1)));
		assertEquals(5, buf.position());
	}
	
	private static ByteBuffer raw(String data) {
		return ByteBuffer.wrap(data.getBytes(StandardCharsets.ISO_8859_1));
	}

	// --- URL path decoding (RFC 3986): '+' is a literal path char, not an encoded space ---

	@Test
	public void testPathDecodePreservesLiteralPlus() {
		assertEquals("/a+b", HttpRequest.normalizeAndDecodePath("/a+b"));
	}

	@Test
	public void testPathDecodePercentSpaceAndPlus() {
		// %20 -> space, %2B -> '+', literal '+' stays '+'
		assertEquals("/a b", HttpRequest.normalizeAndDecodePath("/a%20b"));
		assertEquals("/a+b", HttpRequest.normalizeAndDecodePath("/a%2Bb"));
	}

	@Test
	public void testPathDecodeUtf8() {
		// /caf%C3%A9 -> /café
		assertEquals("/café", HttpRequest.normalizeAndDecodePath("/caf%C3%A9"));
	}

	@Test(expected = HttpException.class)
	public void testPathDecodeRejectsTruncatedEscape() {
		HttpRequest.normalizeAndDecodePath("/a%2");
	}

	@Test(expected = HttpException.class)
	public void testPathDecodeRejectsInvalidEscape() {
		HttpRequest.normalizeAndDecodePath("/a%zzb");
	}

	@Test(expected = HttpException.class)
	public void testPathTraversalStillBlockedAfterDecode() {
		// %2e%2e/ decodes to ../ and must still be rejected
		HttpRequest.normalizeAndDecodePath("/foo/%2e%2e/secret");
	}

	@Test
	public void testMultipartFilenameWithSemicolonPreserved() {
		// A filename containing "; " must not be split apart by the part-header parameter parser.
		String boundary = "BOUNDARY";
		String mpBody = "--" + boundary + "\r\n"
			+ "Content-Disposition: form-data; name=\"file\"; filename=\"a; b.txt\"\r\n"
			+ "Content-Type: text/plain\r\n\r\n"
			+ "hello\r\n"
			+ "--" + boundary + "--\r\n";
		String req = "POST /upload HTTP/1.1\r\n"
			+ "Host: localhost\r\n"
			+ "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n"
			+ "Content-Length: " + mpBody.length() + "\r\n\r\n"
			+ mpBody;
		HttpRequest request = HttpRequest.of(raw(req));
		assertTrue("request should be complete", request.isComplete());
		HttpRequest.Part part = request.getMultiParts().get("file");
		assertNotNull("file part must be parsed", part);
		HttpRequest.HeadKeyVals cd = part.getHeadKeyVal("Content-Disposition");
		assertNotNull(cd);
		assertEquals("a; b.txt", cd.getVals().get("filename"));
		assertEquals("hello", part.getData());
	}

	@Test
	public void testPreferredEncodingRespectsIdentityRejection() {
		// "identity;q=0" → identity must NOT be returned as the default fallback.
		HttpRequest r1 = HttpRequest.of(
			raw("GET / HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: identity;q=0\r\n\r\n"));
		Assert.assertNull(r1.getPreferredEncoding(java.util.List.of("gzip", "identity")));
		// "*;q=0" excludes identity too.
		HttpRequest r2 = HttpRequest.of(
			raw("GET / HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: *;q=0\r\n\r\n"));
		Assert.assertNull(r2.getPreferredEncoding(java.util.List.of("identity")));
		// gzip requested and identity not forbidden → gzip wins.
		HttpRequest r3 = HttpRequest.of(
			raw("GET / HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: gzip\r\n\r\n"));
		assertEquals("gzip", r3.getPreferredEncoding(java.util.List.of("gzip", "identity")));
		// No Accept-Encoding → first supported.
		HttpRequest r4 = HttpRequest.of(raw("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"));
		assertEquals("gzip", r4.getPreferredEncoding(java.util.List.of("gzip", "identity")));
	}

	@Test
	public void testConnectionHeaderMultiTokenClose() {
		// "close" appearing as one of several Connection tokens must still close the connection.
		HttpRequest closed = HttpRequest.of(
			raw("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive, close\r\n\r\n"));
		assertFalse(closed.isKeepAlive());
		// Plain HTTP/1.1 with no Connection header defaults to keep-alive.
		HttpRequest alive = HttpRequest.of(raw("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"));
		assertTrue(alive.isKeepAlive());
		// HTTP/1.0 with multi-token keep-alive must stay alive.
		HttpRequest alive10 = HttpRequest.of(
			raw("GET / HTTP/1.0\r\nHost: localhost\r\nConnection: keep-alive, foo\r\n\r\n"));
		assertTrue(alive10.isKeepAlive());
	}

	@Test
	public void testQuotedAndReorderedMultipartBoundary() {
		// Boundary may be quoted and need not be the first Content-Type parameter.
		String boundary = "BOUNDARY";
		String mpBody = "--" + boundary + "\r\n"
			+ "Content-Disposition: form-data; name=\"f\"\r\n\r\n"
			+ "v\r\n"
			+ "--" + boundary + "--\r\n";
		String req = "POST /u HTTP/1.1\r\n"
			+ "Host: localhost\r\n"
			+ "Content-Type: multipart/form-data; charset=utf-8; boundary=\"" + boundary + "\"\r\n"
			+ "Content-Length: " + mpBody.length() + "\r\n\r\n"
			+ mpBody;
		HttpRequest request = HttpRequest.of(raw(req));
		assertTrue(request.isComplete());
		assertTrue(request.isMultipart());
		assertNotNull(request.getMultiParts().get("f"));
		assertEquals("v", request.getMultiParts().get("f").getData());
	}

	@Test
	public void testMultipartDuplicateFieldNamesPreserved() {
		String b = "BND";
		String mpBody = "--" + b + "\r\n"
			+ "Content-Disposition: form-data; name=\"f\"\r\n\r\nA\r\n"
			+ "--" + b + "\r\n"
			+ "Content-Disposition: form-data; name=\"f\"\r\n\r\nB\r\n"
			+ "--" + b + "--\r\n";
		String req = "POST /u HTTP/1.1\r\n"
			+ "Host: localhost\r\n"
			+ "Content-Type: multipart/form-data; boundary=" + b + "\r\n"
			+ "Content-Length: " + mpBody.length() + "\r\n\r\n"
			+ mpBody;
		HttpRequest request = HttpRequest.of(raw(req));
		assertTrue(request.isComplete());
		java.util.List<HttpRequest.Part> parts = request.getParts("f");
		assertEquals(2, parts.size());
		assertEquals("A", parts.get(0).getData());
		assertEquals("B", parts.get(1).getData());
		// The name-keyed map remains last-wins for backwards compatibility.
		assertEquals("B", request.getMultiParts().get("f").getData());
		assertEquals(2, request.getAllParts().size());
	}

	@Test
	public void testMultipartWithPreambleBeforeFirstBoundary() {
		// RFC 2046 §5.1.1 permits an ignored preamble (and some clients prepend a leading CRLF)
		// before the first boundary. Such bodies must parse rather than be rejected as malformed.
		String b = "BND";
		String mpBody = "This is an ignored preamble.\r\n"
			+ "--" + b + "\r\n"
			+ "Content-Disposition: form-data; name=\"f\"\r\n\r\nA\r\n"
			+ "--" + b + "--\r\n";
		String req = "POST /u HTTP/1.1\r\n"
			+ "Host: localhost\r\n"
			+ "Content-Type: multipart/form-data; boundary=" + b + "\r\n"
			+ "Content-Length: " + mpBody.length() + "\r\n\r\n"
			+ mpBody;
		HttpRequest request = HttpRequest.of(raw(req));
		assertTrue(request.isComplete());
		assertTrue(request.isMultipart());
		assertEquals("A", request.getMultiParts().get("f").getData());
	}

	@Test
	public void testChunkedMultipartIsParsed() {
		// A chunked multipart/form-data upload must be parsed (previously it was accepted but
		// the parts were never decoded, so getMultiParts() came back empty).
		String boundary = "BOUNDARY";
		String mpBody = "--" + boundary + "\r\n"
			+ "Content-Disposition: form-data; name=\"field1\"\r\n\r\n"
			+ "value1\r\n"
			+ "--" + boundary + "--\r\n";
		String chunk = Integer.toHexString(mpBody.length()) + "\r\n" + mpBody + "\r\n0\r\n\r\n";
		String req = "POST /upload HTTP/1.1\r\n"
			+ "Host: localhost\r\n"
			+ "Transfer-Encoding: chunked\r\n"
			+ "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n\r\n"
			+ chunk;
		HttpRequest request = HttpRequest.of(raw(req));
		assertTrue("request should be complete", request.isComplete());
		assertTrue("request should be multipart", request.isMultipart());
		Map<String, HttpRequest.Part> parts = request.getMultiParts();
		assertNotNull("field1 part must be parsed", parts.get("field1"));
		assertEquals("value1", parts.get("field1").getData());
	}

	@Test
	public void testDeepParamNestingIsCappedNoStackOverflow() {
		// A maliciously deep bracket-nested name must not overflow the stack in
		// getParametersTree() (a StackOverflowError would bypass the dispatcher and kill the
		// I/O loop). The nesting depth must be capped.
		StringBuilder name = new StringBuilder("a");
		for (int i = 0; i < 100; i++) name.append("[x]"); // request 100 levels of nesting
		HttpRequest request = HttpRequest.of(
			raw("GET /?" + name + "=1 HTTP/1.1\r\nHost: localhost\r\n\r\n"));
		Map<String, Object> tree = request.getParametersTree();
		assertNotNull(tree);
		assertTrue("nesting depth must be capped, was " + mapDepth(tree), mapDepth(tree) <= 40);
	}

	private static int mapDepth(Object o) {
		if (!(o instanceof Map)) return 0;
		int max = 0;
		for (Object v : ((Map<?, ?>) o).values()) {
			max = Math.max(max, mapDepth(v));
		}
		return max + 1;
	}

	@Test
	public void testQueryStringWithEmbeddedQuestionMarkPreserved() {
		// A '?' inside a query value must not truncate later params (split on FIRST '?' only).
		HttpRequest request = HttpRequest.of(
			raw("GET /search?redirect=http://x?y=1&z=2 HTTP/1.1\r\nHost: localhost\r\n\r\n"));
		assertEquals("http://x?y=1", request.getParameter("redirect"));
		assertEquals("2", request.getParameter("z"));
	}

}
