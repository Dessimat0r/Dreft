package org.deftserver.util;

import java.util.regex.Pattern;

public class ArrayUtil {

	public static String[] dropFromEndWhile(String[] array, String regex) {
		if (array == null) return new String[0];
		Pattern pattern = Pattern.compile(regex);
		for (int i = array.length - 1; i >= 0; i--) {
			String val = array[i];
			if (val != null && !pattern.matcher(val).matches()) {
				String[] trimmedArray = new String[i + 1];
				System.arraycopy(array, 0, trimmedArray, 0, i + 1);
				return trimmedArray;
			}
		}
		return new String[0];
	}

}
