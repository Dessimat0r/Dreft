package org.deftserver.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HttpUtilTest {

	@Test
	public void knownStatusCodeHasCannedReasonPhrase() {
		assertEquals("HTTP/1.1 200 OK\r\n", HttpUtil.createInitialLine(200));
		assertEquals("HTTP/1.1 404 Not Found\r\n", HttpUtil.createInitialLine(404));
	}

	@Test
	public void redirectAndLessCommonStatusLinesAreCorrect() {
		// 302's reason phrase is "Found" (not "Not Found"), and the less-common codes from the
		// features checklist (§45) must carry their proper canned phrases, not a generic fallback.
		assertEquals("HTTP/1.1 302 Found\r\n", HttpUtil.createInitialLine(302));
		assertEquals("HTTP/1.1 303 See Other\r\n", HttpUtil.createInitialLine(303));
		assertEquals("HTTP/1.1 307 Temporary Redirect\r\n", HttpUtil.createInitialLine(307));
		assertEquals("HTTP/1.1 308 Permanent Redirect\r\n", HttpUtil.createInitialLine(308));
		assertEquals("HTTP/1.1 421 Misdirected Request\r\n", HttpUtil.createInitialLine(421));
		assertEquals("HTTP/1.1 425 Too Early\r\n", HttpUtil.createInitialLine(425));
		assertEquals("HTTP/1.1 426 Upgrade Required\r\n", HttpUtil.createInitialLine(426));
	}

	@Test
	public void unknownStatusCodeProducesValidLineInsteadOfThrowing() {
		// 418 / 451 etc. have no canned phrase but must still yield a valid status line
		// rather than aborting response serialization.
		String line = HttpUtil.createInitialLine(418);
		assertTrue(line.startsWith("HTTP/1.1 418 "));
		assertTrue(line.endsWith("\r\n"));

		String redirectLike = HttpUtil.createInitialLine(399);
		assertTrue(redirectLike.startsWith("HTTP/1.1 399 "));
	}

	@Test
	public void outOfRangeStatusCodeCoercedTo500() {
		assertEquals("HTTP/1.1 500 Internal Server Error\r\n", HttpUtil.createInitialLine(999));
		assertEquals("HTTP/1.1 500 Internal Server Error\r\n", HttpUtil.createInitialLine(42));
	}

	@Test
	public void parseAcceptHeaderOrdersByQDescendingAndDropsQZero() {
		java.util.List<String> r = HttpUtil.parseAcceptHeader("text/plain;q=0.5, text/html, application/json;q=0");
		// text/html (implicit q=1) first, then text/plain (0.5); application/json (q=0) dropped.
		assertEquals(java.util.Arrays.asList("text/html", "text/plain"), r);
	}

	@Test
	public void parseAcceptHeaderClampsBogusQAndSkipsEmptyRanges() {
		// A malicious/invalid "q=Infinity" and "q=5" must clamp to 1.0 (not sort ahead of a real
		// q=1.0 by header order), "q=NaN" drops, and empty ranges from stray commas are skipped.
		java.util.List<String> r = HttpUtil.parseAcceptHeader("a/a;q=1.0, b/b;q=Infinity, , c/c;q=NaN, d/d;q=5");
		// a/a, b/b, d/d all end up q=1.0 (header order preserved by the stable sort); c/c dropped;
		// the empty range contributes nothing.
		assertEquals(java.util.Arrays.asList("a/a", "b/b", "d/d"), r);
	}

	@Test
	public void parseAcceptHeaderNegativeQDropped() {
		java.util.List<String> r = HttpUtil.parseAcceptHeader("x/x;q=-1, y/y;q=0.3");
		assertEquals(java.util.Arrays.asList("y/y"), r);
	}

	@Test
	public void getEtagCorrectness() {
		byte[] data = "Hello World".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		assertEquals("b10a8db164e0754105b7a99be72e3fe5", HttpUtil.getEtag(data));
		assertEquals("f5a7924e621e84c9280a9a27e1bcb7f6", HttpUtil.getEtag(data, 6, 5));
	}
}
