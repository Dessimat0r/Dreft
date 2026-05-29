package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class HTTPSDeftSystemTest {

	private static int PORT;
	private static HttpServer server;

	public static class HelloWorldRequestHandler extends RequestHandler {
		@Override
		public void get(HttpRequest request, org.deftserver.web.http.HttpResponse response) {
			response.write("Hello Secure World!");
		}
	}

	@BeforeClass
	public static void setup() throws Exception {
		Map<String, RequestHandler> reqHandlers = new HashMap<>();
		reqHandlers.put("/", new HelloWorldRequestHandler());
		Application application = new Application(reqHandlers);

		// Initialize Server SSLContext
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
		server.bind(0); // Bind to dynamic OS-allocated free port
		PORT = server.getPort(); // Retrieve the actual port allocated by the OS

		Thread.ofPlatform()
			.name("Secure-I/O-Loop")
			.start(() -> {
				try {
					server.start(1);
				} catch (IOException e) {
					e.printStackTrace();
				}
			});

		// Bounded wait for server to start up
		Thread.sleep(1000);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		if (server != null) {
			server.stop();
		}
		Thread.sleep(500);
	}

	private HttpClient createSecureHttpClient() throws Exception {
		SSLContext sslContext = SSLContext.getInstance("TLS");
		TrustManager[] trustAll = new TrustManager[] {
			new X509TrustManager() {
				public X509Certificate[] getAcceptedIssuers() { return null; }
				public void checkClientTrusted(X509Certificate[] certs, String authType) {}
				public void checkServerTrusted(X509Certificate[] certs, String authType) {}
			}
		};
		sslContext.init(null, trustAll, null);
		org.apache.http.conn.ssl.SSLConnectionSocketFactory sslsf = new org.apache.http.conn.ssl.SSLConnectionSocketFactory(
			sslContext,
			org.apache.http.conn.ssl.NoopHostnameVerifier.INSTANCE
		);
		return org.apache.http.impl.client.HttpClients.custom()
			.setSSLSocketFactory(sslsf)
			.build();
	}

	@Test
	public void testHTTPSGetRequest() throws Exception {
		HttpClient httpclient = createSecureHttpClient();
		HttpGet httpget = new HttpGet("https://localhost:" + PORT + "/");
		
		org.apache.http.HttpResponse response = httpclient.execute(httpget);
		assertEquals(200, response.getStatusLine().getStatusCode());
		
		String body = EntityUtils.toString(response.getEntity());
		assertEquals("Hello Secure World!", body);
	}
}
