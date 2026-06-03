package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.Cookie;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class CookieApiTest {

	private static HttpServer server;
	private static int PORT;

	private static class CookieTestHandler extends RequestHandler {
		@Override
		public void get(org.deftserver.web.http.HttpRequest request, org.deftserver.web.http.HttpResponse response) throws IOException {
			// Read client cookie
			String userCookie = request.getCookie("user");
			
			// Set new typed cookies
			Cookie c1 = new Cookie("session", "abc123_session");
			c1.setHttpOnly(true);
			c1.setSecure(true);
			c1.setSameSite(Cookie.SameSite.LAX);
			c1.setPath("/custom");
			c1.setMaxAge(3600L);
			
			Cookie c2 = new Cookie("theme", "dark");
			c2.setDomain("localhost");
			
			response.setCookie(c1);
			response.setCookie(c2);
			
			response.write("user:" + userCookie);
		}
	}

	@BeforeClass
	public static void setUp() throws Exception {
		try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
			serverChannel.bind(new InetSocketAddress(0));
			PORT = serverChannel.socket().getLocalPort();
		}

		Map<String, RequestHandler> reqHandlers = new HashMap<>();
		reqHandlers.put("/cookie", new CookieTestHandler());
		
		server = new HttpServer(new Application(reqHandlers));

		server.bind(PORT);
		server.start(1); // dedicated IOLoop, isolated from the shared IOLoop.INSTANCE
		
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		server.stop();
		Thread.sleep(100);
	}

	@Test
	public void testCookieParsingAndCookieAPI() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + PORT + "/cookie"))
				.header("Cookie", "user=RogerSchild; other=123")
				.GET()
				.build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		
		assertEquals(200, response.statusCode());
		assertEquals("user:RogerSchild", response.body());
		
		// Assert Set-Cookie headers are returned correctly
		List<String> setCookies = response.headers().allValues("Set-Cookie");
		assertNotNull(setCookies);
		assertEquals(2, setCookies.size());
		
		boolean foundSession = false;
		boolean foundTheme = false;
		for (String rawCookie : setCookies) {
			if (rawCookie.startsWith("session=abc123_session")) {
				assertTrue(rawCookie.contains("Path=/custom"));
				assertTrue(rawCookie.contains("Max-Age=3600"));
				assertTrue(rawCookie.contains("Secure"));
				assertTrue(rawCookie.contains("HttpOnly"));
				assertTrue(rawCookie.contains("SameSite=Lax"));
				foundSession = true;
			} else if (rawCookie.startsWith("theme=dark")) {
				assertTrue(rawCookie.contains("Domain=localhost"));
				foundTheme = true;
			}
		}
		
		assertTrue(foundSession);
		assertTrue(foundTheme);
	}

	// --- Response-splitting / header-injection defence (CR/LF/control chars rejected) ---

	@Test(expected = IllegalArgumentException.class)
	public void testCookieValueRejectsCRLF() {
		new Cookie("x", "a\r\nSet-Cookie: evil=1");
	}

	@Test(expected = IllegalArgumentException.class)
	public void testCookieNameRejectsControlChar() {
		new Cookie("a\nb", "v");
	}

	@Test(expected = IllegalArgumentException.class)
	public void testCookiePathRejectsCRLF() {
		Cookie c = new Cookie("x", "y");
		c.setPath("/ok\r\nInjected: 1");
	}

	@Test
	public void testCookieAllowsNullValue() {
		// Null value is legal (renders empty); must not throw.
		Cookie c = new Cookie("x", null);
		assertTrue(c.toString().startsWith("x="));
	}
}
