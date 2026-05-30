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
}
