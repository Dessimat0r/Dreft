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
		public void onBinaryMessage(WebSocketConnection connection, byte[] data) {
			// Echo binary frames back as binary (prefixed) to exercise WebSocketConnection.write(byte[]).
			byte[] reply = new byte[data.length + 1];
			reply[0] = (byte) 0xBB;
			System.arraycopy(data, 0, reply, 1, data.length);
			connection.write(reply);
		}

		@Override
		public void onClose(WebSocketConnection connection) {
			System.out.println("DEBUG WS: Server onClose triggered!");
		}
	}

	private static class ThrowingWebSocketHandler extends WebSocketHandler {
		@Override
		public void onOpen(WebSocketConnection connection) { /* nop */ }
		@Override
		public void onMessage(WebSocketConnection connection, String message) {
			throw new RuntimeException("Intentional WS handler exception");
		}
		@Override
		public void onClose(WebSocketConnection connection) { /* nop */ }
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
		reqHandlers.put("/throw-ws", new ThrowingWebSocketHandler());
		
		server = new HttpServer(new Application(reqHandlers));
		
		server.bind(PORT);
		server.start(1); // dedicated IOLoop, isolated from the shared IOLoop.INSTANCE
		
		// Wait a brief moment for loop to boot
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		server.stop();
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
	public void idleWebSocketIsClosedByWebSocketIdleTimeout() throws Exception {
		long original = org.deftserver.web.http.HttpProtocol.WEBSOCKET_IDLE_TIMEOUT_MS;
		org.deftserver.web.http.HttpProtocol.WEBSOCKET_IDLE_TIMEOUT_MS = 600;
		try {
			CountDownLatch closed = new CountDownLatch(1);
			WebSocket.Listener listener = new WebSocket.Listener() {
				@Override public void onOpen(WebSocket ws) { ws.request(1); }
				@Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
					closed.countDown();
					return null;
				}
				@Override public void onError(WebSocket ws, Throwable error) {
					closed.countDown(); // an abrupt server close may surface as an error
				}
			};
			HttpClient client = HttpClient.newHttpClient();
			WebSocket ws = client.newWebSocketBuilder()
					.buildAsync(URI.create("ws://localhost:" + PORT + "/ws"), listener)
					.get(5, TimeUnit.SECONDS);
			assertNotNull(ws);
			// Send nothing: the idle WebSocket must be reaped by the (lowered) idle timeout, not held
			// open indefinitely.
			assertTrue("idle WebSocket should have been closed by the idle timeout",
				closed.await(4, TimeUnit.SECONDS));
		} finally {
			org.deftserver.web.http.HttpProtocol.WEBSOCKET_IDLE_TIMEOUT_MS = original;
		}
	}

	@Test
	public void testBinaryMessageRoundTrip() throws Exception {
		// A client binary message must be delivered to onBinaryMessage and echoed back as a binary
		// frame via WebSocketConnection.write(byte[]).
		CompletableFuture<byte[]> binaryFuture = new CompletableFuture<>();
		java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();

		WebSocket.Listener listener = new WebSocket.Listener() {
			@Override
			public void onOpen(WebSocket webSocket) { webSocket.request(1); }

			@Override
			public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
				while (data.hasRemaining()) acc.write(data.get());
				if (last) binaryFuture.complete(acc.toByteArray());
				webSocket.request(1);
				return null;
			}
		};

		HttpClient client = HttpClient.newHttpClient();
		WebSocket webSocket = client.newWebSocketBuilder()
				.buildAsync(URI.create("ws://localhost:" + PORT + "/ws"), listener)
				.get(5, TimeUnit.SECONDS);

		byte[] payload = new byte[] {1, 2, 3, 4, 5};
		webSocket.sendBinary(java.nio.ByteBuffer.wrap(payload), true).get(5, TimeUnit.SECONDS);

		byte[] echoed = binaryFuture.get(5, TimeUnit.SECONDS);
		// Server prefixes 0xBB then echoes the payload.
		assertEquals(payload.length + 1, echoed.length);
		assertEquals((byte) 0xBB, echoed[0]);
		for (int i = 0; i < payload.length; i++) {
			assertEquals(payload[i], echoed[i + 1]);
		}
		webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Bye!").get(5, TimeUnit.SECONDS);
	}

	@Test
	public void testUnsupportedWebSocketVersionGets426() throws Exception {
		// A WebSocket upgrade requesting a version other than 13 must get 426 Upgrade Required.
		try (java.net.Socket s = new java.net.Socket("localhost", PORT)) {
			s.setSoTimeout(3000);
			String req = "GET /ws HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Upgrade: websocket\r\n" +
				"Connection: Upgrade\r\n" +
				"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
				"Sec-WebSocket-Version: 8\r\n\r\n";
			s.getOutputStream().write(req.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			s.getOutputStream().flush();
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[2048];
			int n;
			while ((n = s.getInputStream().read(buf)) != -1) out.write(buf, 0, n);
			String resp = out.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
			assertTrue("expected 426, got: " + resp.substring(0, Math.min(40, resp.length())),
				resp.startsWith("HTTP/1.1 426"));
			assertTrue("must advertise supported version 13:\n" + resp,
				resp.contains("Sec-WebSocket-Version: 13"));
		}
	}

	@Test
	public void testRsvBitSetFailsTheConnection() throws Exception {
		// RFC 6455 §5.2: with no extension negotiated, any data frame whose RSV1/2/3 bits are set
		// is a protocol error and the server MUST fail (close) the connection. We send an otherwise
		// well-formed masked text frame but with RSV1 set in the first byte (0xC1 = FIN|RSV1|text).
		try (java.net.Socket s = new java.net.Socket("localhost", PORT)) {
			s.setSoTimeout(3000);
			String req = "GET /ws HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Upgrade: websocket\r\n" +
				"Connection: Upgrade\r\n" +
				"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
				"Sec-WebSocket-Version: 13\r\n\r\n";
			java.io.OutputStream os = s.getOutputStream();
			os.write(req.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			os.flush();
			// Consume the 101 handshake response headers.
			java.io.InputStream in = s.getInputStream();
			java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
			int c;
			while (!acc.toString(java.nio.charset.StandardCharsets.ISO_8859_1).contains("\r\n\r\n")
					&& (c = in.read()) != -1) {
				acc.write(c);
			}
			assertTrue("handshake must succeed first:\n" + acc,
				acc.toString(java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 101"));
			// Masked text frame with RSV1 set: 0xC1, MASK|len, key, masked payload "hi".
			byte[] payload = "hi".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			byte[] key = {0x11, 0x22, 0x33, 0x44};
			java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
			f.write(0xC1);                 // FIN + RSV1 + opcode text
			f.write(0x80 | payload.length); // MASK + len
			f.write(key, 0, 4);
			for (int i = 0; i < payload.length; i++) f.write(payload[i] ^ key[i % 4]);
			os.write(f.toByteArray());
			os.flush();
			// The server must close the connection. Reading should return either a Close frame
			// (0x88 first byte) or EOF — never an echo. We assert the stream ends or yields a close.
			int first = in.read();
			boolean closedOrCloseFrame = (first == -1) || (first == 0x88);
			assertTrue("RSV-bit frame must fail the connection (got first byte 0x"
				+ Integer.toHexString(first & 0xFF) + ")", closedOrCloseFrame);
		}
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

	@Test
	public void testWebSocketHandlerRuntimeExceptionClosesConnection() throws Exception {
		// A RuntimeException thrown from onMessage must close the connection (the catch in
		// HttpProtocol.handleRead closes the channel) but must NOT take down the I/O loop.
		try (java.net.Socket s = new java.net.Socket("localhost", PORT)) {
			s.setSoTimeout(3000);
			String upgrade = "GET /throw-ws HTTP/1.1\r\n" +
				"Host: localhost\r\n" +
				"Upgrade: websocket\r\n" +
				"Connection: Upgrade\r\n" +
				"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
				"Sec-WebSocket-Version: 13\r\n\r\n";
			s.getOutputStream().write(upgrade.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			s.getOutputStream().flush();

			java.io.InputStream in = s.getInputStream();
			java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
			int c;
			while (!acc.toString(java.nio.charset.StandardCharsets.ISO_8859_1).contains("\r\n\r\n")
					&& (c = in.read()) != -1) {
				acc.write(c);
			}
			assertTrue("handshake must succeed",
				acc.toString(java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 101"));

			// Send a valid masked text frame — the handler will throw and the server must close.
			byte[] payload = "hi".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			byte[] key = {0x11, 0x22, 0x33, 0x44};
			java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
			f.write(0x81); // FIN + text opcode
			f.write(0x80 | payload.length); // MASK + len
			f.write(key, 0, 4);
			for (int i = 0; i < payload.length; i++) f.write(payload[i] ^ key[i % 4]);
			s.getOutputStream().write(f.toByteArray());
			s.getOutputStream().flush();

			// The server must close the connection (either a close frame 0x88 or EOF).
			int first = in.read();
			boolean closedOrCloseFrame = (first == -1) || (first == 0x88);
			assertTrue("RuntimeException in onMessage must close the connection (got first byte 0x"
				+ Integer.toHexString(first & 0xFF) + ")", closedOrCloseFrame);
		}

		// Verify the I/O loop is still alive: a second (valid) WebSocket connection works.
		CompletableFuture<String> messageFuture = new CompletableFuture<>();
		CountDownLatch closeLatch = new CountDownLatch(1);
		WebSocket.Listener listener = new WebSocket.Listener() {
			@Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
			@Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
				messageFuture.complete(data.toString());
				webSocket.request(1);
				return null;
			}
			@Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
				closeLatch.countDown();
				return null;
			}
			@Override public void onError(WebSocket ws, Throwable error) { closeLatch.countDown(); }
		};
		HttpClient client = HttpClient.newHttpClient();
		WebSocket ws = client.newWebSocketBuilder()
				.buildAsync(URI.create("ws://localhost:" + PORT + "/ws"), listener)
				.get(5, TimeUnit.SECONDS);
		assertNotNull(ws);
		ws.sendText("ping", true);
		assertEquals("echo: ping", messageFuture.get(5, TimeUnit.SECONDS));
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "Bye!").get(5, TimeUnit.SECONDS);
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
	}
}
