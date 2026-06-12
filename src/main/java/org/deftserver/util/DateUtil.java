package org.deftserver.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

public class DateUtil {

	// Output: strict IMF-fixdate (RFC 9110 §5.6.7). Crucially the day-of-month is ALWAYS two
	// digits — Java's built-in RFC_1123_DATE_TIME emits a single digit for days 1-9
	// ("Fri, 5 Jun 2026"), which is not a valid HTTP-date. Locale.US fixes the English
	// day/month names regardless of the platform default locale.
	private static final DateTimeFormatter OUTPUT_FORMATTER =
		DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
	// Parsing accepts all THREE formats a recipient MUST handle (RFC 9110 §5.6.7):
	//   1. IMF-fixdate, e.g. "Sun, 06 Nov 1994 08:49:37 GMT"  (lenient 1-2 digit day via RFC_1123)
	//   2. obsolete RFC 850, e.g. "Sunday, 06-Nov-94 08:49:37 GMT"  (2-digit year, base 1970)
	//   3. asctime, e.g. "Sun Nov  6 08:49:37 1994"  (space-padded day, no zone → assume GMT)
	private static final DateTimeFormatter IMF_FIXDATE = DateTimeFormatter.RFC_1123_DATE_TIME;
	private static final DateTimeFormatter RFC850 = new DateTimeFormatterBuilder()
		.parseCaseInsensitive()
		.appendPattern("EEEE, dd-MMM-")
		.appendValueReduced(ChronoField.YEAR, 2, 2, 1970)
		.appendPattern(" HH:mm:ss zzz")
		.toFormatter(Locale.US);
	private static final DateTimeFormatter ASCTIME = new DateTimeFormatterBuilder()
		.parseCaseInsensitive()
		.appendPattern("EEE MMM ")
		.padNext(2)
		.appendValue(ChronoField.DAY_OF_MONTH)
		.appendPattern(" HH:mm:ss yyyy")
		.toFormatter(Locale.US);
	private static final ZoneId GMT_ZONE = ZoneId.of("GMT");

	private static volatile long lastSeconds = 0;
	private static volatile String cachedDateString = "";

	/** The current time as an IMF-fixdate string for the response {@code Date} header, cached to
	 *  one-second granularity (double-checked) so the formatter runs at most once per second. */
	public static String getCurrentAsString() {
		long nowSeconds = System.currentTimeMillis() / 1000;
		if (nowSeconds != lastSeconds) {
			synchronized (DateUtil.class) {
				if (nowSeconds != lastSeconds) {
					cachedDateString = OUTPUT_FORMATTER.format(ZonedDateTime.ofInstant(Instant.ofEpochSecond(nowSeconds), GMT_ZONE));
					lastSeconds = nowSeconds;
				}
			}
		}
		return cachedDateString;
	}

	/** Formats an epoch-milliseconds instant as a strict IMF-fixdate (e.g. for {@code Last-Modified}). */
	public static String formatToRFC1123(long epochMillis) {
		return OUTPUT_FORMATTER.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), GMT_ZONE));
	}

	/** Parses an HTTP-date in any of the three RFC 9110 formats (IMF-fixdate, obsolete RFC 850,
	 *  asctime) to epoch milliseconds, or returns -1 if it is null or unparseable. */
	public static long parseRFC1123ToMillis(String httpDate) {
		if (httpDate == null) return -1;
		httpDate = httpDate.trim();
		// IMF-fixdate (the common case) first.
		try {
			return ZonedDateTime.parse(httpDate, IMF_FIXDATE).toInstant().toEpochMilli();
		} catch (Exception ignore) { /* try next format */ }
		// Obsolete RFC 850.
		try {
			return ZonedDateTime.parse(httpDate, RFC850).toInstant().toEpochMilli();
		} catch (Exception ignore) { /* try next format */ }
		// asctime (no zone in the string → interpret as GMT).
		try {
			return java.time.LocalDateTime.parse(httpDate, ASCTIME).atZone(GMT_ZONE).toInstant().toEpochMilli();
		} catch (Exception ignore) {
			return -1;
		}
	}
}