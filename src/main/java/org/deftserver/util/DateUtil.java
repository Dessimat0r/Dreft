package org.deftserver.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class DateUtil {

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME;
	private static final ZoneId GMT_ZONE = ZoneId.of("GMT");

	private static volatile long lastSeconds = 0;
	private static volatile String cachedDateString = "";

	public static String getCurrentAsString() {
		long nowSeconds = System.currentTimeMillis() / 1000;
		if (nowSeconds != lastSeconds) {
			synchronized (DateUtil.class) {
				if (nowSeconds != lastSeconds) {
					cachedDateString = FORMATTER.format(ZonedDateTime.now(GMT_ZONE));
					lastSeconds = nowSeconds;
				}
			}
		}
		return cachedDateString;
	}

	public static String formatToRFC1123(long epochMillis) {
		return FORMATTER.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), GMT_ZONE));
	}

	public static long parseRFC1123ToMillis(String rfc1123Str) {
		if (rfc1123Str == null) return -1;
		try {
			return ZonedDateTime.parse(rfc1123Str, FORMATTER).toInstant().toEpochMilli();
		} catch (Exception e) {
			return -1;
		}
	}
}