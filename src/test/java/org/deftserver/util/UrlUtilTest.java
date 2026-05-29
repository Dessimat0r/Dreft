package org.deftserver.util;

import static org.junit.Assert.assertEquals;

import java.net.MalformedURLException;
import java.net.URL;

import org.junit.Test;

public class UrlUtilTest {

	@Test
	public void urlJoinTest() throws MalformedURLException {
		assertEquals("http://tt.se/start/", UrlUtil.urlJoin(java.net.URI.create("http://tt.se/").toURL(), "/start/"));
		assertEquals("http://localhost.com/", UrlUtil.urlJoin(java.net.URI.create("http://localhost.com/moved_perm").toURL(), "/"));
		assertEquals("https://github.com/", UrlUtil.urlJoin(java.net.URI.create("http://github.com/").toURL(), "https://github.com/"));
	}
	
}
