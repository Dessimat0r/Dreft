package org.deftserver.web;

import static org.junit.Assert.assertEquals;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * End-to-end test of <b>HTTP/2 over TLS via ALPN</b> (RFC 7301 + RFC 7540 §3.3). The server's
 * {@code SSLEngine} now advertises {@code h2}; the JDK {@link HttpClient} (its real, conformant
 * HTTP/2 stack) negotiates it over the wire and opens with the HTTP/2 connection preface, which the
 * existing preface-detection path routes to {@code Http2Connection}. We assert both that the
 * response is correct <em>and</em> that the negotiated protocol was actually {@code HTTP_2} (not a
 * silent HTTP/1.1 fallback), plus that an {@code http/1.1}-only client still works on the same
 * server (ALPN fallback).
 */
public class Http2AlpnTest {

	private static int PORT;
	private static HttpServer server;

	public static class HelloWorldRequestHandler extends RequestHandler {
		@Override
		public void get(HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("Hello h2 World!");
		}

		@Override
		public void post(HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("echo:" + request.getBody());
		}
	}

	@BeforeClass
	public static void setup() throws Exception {
		Map<String, RequestHandler> reqHandlers = new HashMap<>();
		reqHandlers.put("/", new HelloWorldRequestHandler());
		Application application = new Application(reqHandlers);

		KeyStore ks = KeyStore.getInstance("PKCS12");
		try (FileInputStream fis = new FileInputStream("src/test/resources/keystore.p12")) {
			ks.load(fis, "password".toCharArray());
		}
		KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
		kmf.init(ks, "password".toCharArray());
		SSLContext sslContext = SSLContext.getInstance("TLS");
		sslContext.init(kmf.getKeyManagers(), null, null);

		server = new HttpServer(application);
		server.enableSSL(sslContext);
		server.bind(0);
		PORT = server.getPort();

		Thread.ofPlatform()
			.name("Alpn-I/O-Loop")
			.start(() -> {
				try {
					server.start(1);
				} catch (IOException e) {
					e.printStackTrace();
				}
			});

		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		if (server != null) {
			server.stop();
		}
		Thread.sleep(300);
	}

	private static HttpClient createClient(HttpClient.Version version) throws Exception {
		SSLContext sslContext = SSLContext.getInstance("TLS");
		TrustManager[] trustAll = new TrustManager[] {
			new X509TrustManager() {
				public X509Certificate[] getAcceptedIssuers() { return null; }
				public void checkClientTrusted(X509Certificate[] certs, String authType) {}
				public void checkServerTrusted(X509Certificate[] certs, String authType) {}
			}
		};
		sslContext.init(null, trustAll, null);
		return HttpClient.newBuilder()
			.version(version)
			.sslContext(sslContext)
			.build();
	}

	@Test
	public void getOverAlpnNegotiatesHttp2() throws Exception {
		HttpClient client = createClient(HttpClient.Version.HTTP_2);
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("https://localhost:" + PORT + "/"))
			.GET()
			.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals("ALPN must negotiate HTTP/2, not fall back to HTTP/1.1",
			HttpClient.Version.HTTP_2, response.version());
		assertEquals(200, response.statusCode());
		assertEquals("Hello h2 World!", response.body());
	}

	@Test
	public void postOverAlpnNegotiatesHttp2() throws Exception {
		HttpClient client = createClient(HttpClient.Version.HTTP_2);
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("https://localhost:" + PORT + "/"))
			.POST(java.net.http.HttpRequest.BodyPublishers.ofString("payload-123"))
			.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(HttpClient.Version.HTTP_2, response.version());
		assertEquals(200, response.statusCode());
		assertEquals("echo:payload-123", response.body());
	}

	@Test
	public void http1ClientStillWorksViaAlpnFallback() throws Exception {
		HttpClient client = createClient(HttpClient.Version.HTTP_1_1);
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(URI.create("https://localhost:" + PORT + "/"))
			.GET()
			.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals("an http/1.1-only client must keep working on an ALPN-enabled server",
			HttpClient.Version.HTTP_1_1, response.version());
		assertEquals(200, response.statusCode());
		assertEquals("Hello h2 World!", response.body());
	}
}
