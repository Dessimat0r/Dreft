package org.deftserver.web;

import static org.junit.Assert.assertEquals;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.handler.WebSocketHandler;
import org.deftserver.web.http.WebSocketConnection;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Regression for the wss:// (WebSocket over TLS) read path. The plaintext ws:// tests never exercised
 * the SSL branch of handleWebSocketRead, which double-flipped the unwrap() result and silently dropped
 * every decrypted frame. This test upgrades over TLS and round-trips messages of varying sizes.
 */
public class TlsWebSocketTest {

	private static HttpServer server;
	private static int PORT;

	private static class EchoWs extends WebSocketHandler {
		@Override public void onOpen(WebSocketConnection c) { }
		@Override public void onMessage(WebSocketConnection c, String m) { c.write("echo:" + m); }
		@Override public void onClose(WebSocketConnection c) { }
	}

	@BeforeClass
	public static void setUp() throws Exception {
		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/ws", new EchoWs());
		Application application = new Application(handlers);

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

		Thread.ofPlatform().name("Wss-I/O-Loop").start(() -> {
			try { server.start(1); } catch (IOException e) { e.printStackTrace(); }
		});
		Thread.sleep(1000);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		if (server != null) server.stop();
		Thread.sleep(300);
	}

	private static HttpClient trustAllClient() throws Exception {
		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(null, new TrustManager[] { new X509TrustManager() {
			public X509Certificate[] getAcceptedIssuers() { return null; }
			public void checkClientTrusted(X509Certificate[] c, String a) { }
			public void checkServerTrusted(X509Certificate[] c, String a) { }
		} }, null);
		return HttpClient.newBuilder().sslContext(ctx).build();
	}

	@Test
	public void wssEchoRoundTripsMessages() throws Exception {
		HttpClient client = trustAllClient();
		final java.util.List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
		final CompletableFuture<Void> got3 = new CompletableFuture<>();

		WebSocket.Listener listener = new WebSocket.Listener() {
			private final StringBuilder buf = new StringBuilder();
			@Override public void onOpen(WebSocket ws) { ws.request(1); }
			@Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
				buf.append(data);
				if (last) {
					received.add(buf.toString());
					buf.setLength(0);
					if (received.size() >= 3) got3.complete(null);
				}
				ws.request(1);
				return null;
			}
		};

		WebSocket ws = client.newWebSocketBuilder()
				.buildAsync(URI.create("wss://localhost:" + PORT + "/ws"), listener)
				.get(5, TimeUnit.SECONDS);

		ws.sendText("hello", true).get(5, TimeUnit.SECONDS);
		ws.sendText("a second, slightly longer message", true).get(5, TimeUnit.SECONDS);
		// A message larger than the TLS record / read buffer, to exercise multi-record reassembly.
		String big = "x".repeat(40_000);
		ws.sendText(big, true).get(5, TimeUnit.SECONDS);

		got3.get(8, TimeUnit.SECONDS);
		assertEquals("echo:hello", received.get(0));
		assertEquals("echo:a second, slightly longer message", received.get(1));
		assertEquals("echo:" + big, received.get(2));

		ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
	}
}
