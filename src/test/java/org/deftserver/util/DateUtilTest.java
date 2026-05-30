package org.deftserver.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DateUtilTest {

	@Test
	public void formatsImfFixdateWithTwoDigitDay() {
		// Epoch 0 = Thu, 01 Jan 1970 00:00:00 GMT — the day MUST be zero-padded ("01"),
		// which Java's RFC_1123_DATE_TIME would render as "1".
		assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", DateUtil.formatToRFC1123(0L));
	}

	@Test
	public void parsesStrictAndLenientDayForms() {
		// Strict 2-digit day round-trips.
		assertEquals(0L, DateUtil.parseRFC1123ToMillis("Thu, 01 Jan 1970 00:00:00 GMT"));
		// Lenient single-digit day (as some clients send) still parses.
		assertEquals(0L, DateUtil.parseRFC1123ToMillis("Thu, 1 Jan 1970 00:00:00 GMT"));
	}

	@Test
	public void invalidDateReturnsMinusOne() {
		assertEquals(-1L, DateUtil.parseRFC1123ToMillis("not a date"));
		assertEquals(-1L, DateUtil.parseRFC1123ToMillis(null));
	}

	@Test
	public void parsesAllThreeHttpDateFormats() {
		// RFC 9110 §5.6.7: recipients MUST accept all three formats. All three below denote the
		// same instant (the canonical RFC example).
		long imf = DateUtil.parseRFC1123ToMillis("Sun, 06 Nov 1994 08:49:37 GMT");
		long rfc850 = DateUtil.parseRFC1123ToMillis("Sunday, 06-Nov-94 08:49:37 GMT");
		long asctime = DateUtil.parseRFC1123ToMillis("Sun Nov  6 08:49:37 1994");
		org.junit.Assert.assertTrue("IMF-fixdate should parse", imf > 0);
		assertEquals("RFC 850 must match IMF-fixdate", imf, rfc850);
		assertEquals("asctime must match IMF-fixdate", imf, asctime);
	}
}
