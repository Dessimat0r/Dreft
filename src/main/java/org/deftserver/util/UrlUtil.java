package org.deftserver.util;

import java.net.MalformedURLException;
import java.net.URL;

public class UrlUtil {

	/** Example: 
	 * <pre>
	 * {@code 
	 * url: http://tt.se/                 Location: /start              =>  http://tt.se/start
	 * url: http://localhost/moved_perm   Location: /                   =>  http://localhost/
	 * url: http://github.com/            Location: http://github.com/  =>  https://github.com/
	 * }
	 * 
	 * (If the new url throws a MalformedURLException the url String representation will be returned.)
	 */
	public static String urlJoin(URL url, String locationHeader) {
		try {
			return url.toURI().resolve(locationHeader).toURL().toString();
		} catch (Exception e) {
			return url.toString();
		}
	}

}
