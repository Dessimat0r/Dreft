package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.ServerSocketChannel;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.deftserver.io.IOLoop;
import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.handler.WebSocketHandler;
import org.deftserver.web.http.WebSocketConnection;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class WebSocketIntegrationTest {

	private static HttpServer server;
	private static int PORT;

	private static class MyWebSocketHandler extends WebSocketHandler {
		@Override
		public void onOpen(WebSocketConnection connection) {
			System.out.println("DEBUG WS: Server onOpen triggered!");
		}

		@Override
		public void onMessage(WebSocketConnection connection, String message) {
			System.out.println("DEBUG WS: Server onMessage triggered! Msg: " + message);
			connection.write("echo: " + message);
		}

		@Override
		public void onClose(WebSocketConnection connection) {
			System.out.println("DEBUG WS: Server onClose triggered!");
		}
	}

	@BeforeClass
	public static void setUp() throws Exception {
		// Find a free ephemeral port
		try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
			serverChannel.bind(new InetSocketAddress(0));
			PORT = serverChannel.socket().getLocalPort();
		}

		Map<String, RequestHandler> reqHandlers = new HashMap<>();
		reqHandlers.put("/ws", new MyWebSocketHandler());
		
		server = new HttpServer(new Application(reqHandlers));
		
		Thread.ofPlatform().start(() -> {
			try {
				server.listen(PORT);
				IOLoop.INSTANCE.start();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		
		// Wait a brief moment for loop to boot
		Thread.sleep(200);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		server.stop();
		IOLoop.INSTANCE.stop();
		// Wait brief moment for cleanup
		Thread.sleep(100);
	}

	@Test
	public void testWebSocketHandshakeAndBidirectionalCommunication() throws Exception {
		CompletableFuture<String> messageFuture = new CompletableFuture<>();
		CountDownLatch closeLatch = new CountDownLatch(1);
		
		WebSocket.Listener listener = new WebSocket.Listener() {
			@Override
			public void onOpen(WebSocket webSocket) {
				System.out.println("DEBUG WS: Client onOpen triggered!");
				webSocket.request(1);
			}

			@Override
			public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
				System.out.println("DEBUG WS: Client onText triggered! Data: " + data);
				messageFuture.complete(data.toString());
				webSocket.request(1);
				return null;
			}

			@Override
			public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
				System.out.println("DEBUG WS: Client onClose triggered!");
				closeLatch.countDown();
				return null;
			}
		};

		HttpClient client = HttpClient.newHttpClient();
		WebSocket webSocket = client.newWebSocketBuilder()
				.buildAsync(URI.create("ws://localhost:" + PORT + "/ws"), listener)
				.get(5, TimeUnit.SECONDS);

		assertNotNull(webSocket);
		
		// Send message from client to server
		webSocket.sendText("Hello Dreft WebSockets!", true);
		
		// Assert that the server responded with the expected echo message
		String serverResponse = messageFuture.get(5, TimeUnit.SECONDS);
		assertEquals("echo: Hello Dreft WebSockets!", serverResponse);
		
		// Close the socket
		webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Bye!").get(5, TimeUnit.SECONDS);
		
		// Assert that client-side close listener was notified
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
	}

	@Test
	public void testLargeMessageExceedingReadBuffer() throws Exception {
		// A message far larger than READ_BUFFER_SIZE (2048) must round-trip — the non-SSL WS
		// read buffer has to grow to hold the whole frame instead of stalling.
		String big = "x".repeat(5000);
		CompletableFuture<String> messageFuture = new CompletableFuture<>();
		StringBuilder received = new StringBuilder();

		WebSocket.Listener listener = new WebSocket.Listener() {
			@Override
			public void onOpen(WebSocket webSocket) { webSocket.request(1); }
			@Override
			public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
				received.append(data);
				if (last) messageFuture.complete(received.toString());
				webSocket.request(1);
				return null;
			}
		};

		HttpClient client = HttpClient.newHttpClient();
		WebSocket webSocket = client.newWebSocketBuilder()
				.buildAsync(URI.create("ws://localhost:" + PORT + "/ws"), listener)
				.get(5, TimeUnit.SECONDS);

		webSocket.sendText(big, true);
		String serverResponse = messageFuture.get(5, TimeUnit.SECONDS);
		assertEquals("echo: " + big, serverResponse);
		webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Bye!").get(5, TimeUnit.SECONDS);
	}

	@Test
	public void testFragmentedMessageIsReassembled() throws Exception {
		CompletableFuture<String> messageFuture = new CompletableFuture<>();

		WebSocket.Listener listener = new WebSocket.Listener() {
			@Override
			public void onOpen(WebSocket webSocket) {
				webSocket.request(1);
			}
			@Override
			public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
				messageFuture.complete(data.toString());
				webSocket.request(1);
				return null;
			}
		};

		HttpClient client = HttpClient.newHttpClient();
		WebSocket webSocket = client.newWebSocketBuilder()
				.buildAsync(URI.create("ws://localhost:" + PORT + "/ws"), listener)
				.get(5, TimeUnit.SECONDS);

		// Send a single logical message in two fragments (last=false then last=true).
		webSocket.sendText("Frag-", false).get(5, TimeUnit.SECONDS);
		webSocket.sendText("mented", true).get(5, TimeUnit.SECONDS);

		// The server must reassemble both fragments into one onMessage and echo it once.
		String serverResponse = messageFuture.get(5, TimeUnit.SECONDS);
		assertEquals("echo: Frag-mented", serverResponse);

		webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Bye!").get(5, TimeUnit.SECONDS);
	}
}
