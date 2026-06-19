package org.deftserver.web.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Field;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;

import org.deftserver.io.IOLoop;
import org.deftserver.web.Application;
import org.deftserver.web.HttpVerb;
import org.junit.Test;

public class HttpProtocolTest {

	@SuppressWarnings("unchecked")
	@Test
	public void testPartialsLeakClearedOnClose() throws Exception {
		Application app = new Application(new HashMap<>());
		HttpProtocol protocol = new HttpProtocol(IOLoop.INSTANCE, app);

		// Get private connectionStates map via reflection
		Field statesField = HttpProtocol.class.getDeclaredField("connectionStates");
		statesField.setAccessible(true);
		Map<SocketChannel, ?> connectionStates = (Map<SocketChannel, ?>) statesField.get(protocol);

		// Open a real unconnected SocketChannel
		try (SocketChannel channel = SocketChannel.open()) {
			// Populate partials map to simulate a half-received request.
			// Use a dummy HttpRequest (not null) because ConcurrentHashMap rejects null values.
			java.util.Map<String, String> emptyHeaders = java.util.Collections.emptyMap();
			HttpRequest dummy = new HttpRequest("GET / HTTP/1.1", emptyHeaders);
			java.lang.reflect.Method putPartialMethod = HttpProtocol.class.getDeclaredMethod("putPartial", java.nio.channels.SelectableChannel.class, HttpRequest.class);
			putPartialMethod.setAccessible(true);
			putPartialMethod.invoke(protocol, channel, dummy);
			assertEquals(1, connectionStates.size());

			// Trigger closeChannel
			protocol.closeChannel(channel);

			// Verify it was successfully removed from connectionStates, preventing the leak!
			assertEquals(0, connectionStates.size());
			assertFalse(connectionStates.containsKey(channel));
		}
	}

	@Test
	public void testIsUnixSocketCorrectness() throws Exception {
		java.lang.reflect.Method method = HttpProtocol.class.getDeclaredMethod("isUnixSocket", SocketChannel.class);
		method.setAccessible(true);
		
		try (SocketChannel channel = SocketChannel.open()) {
			boolean result = (boolean) method.invoke(null, channel);
			assertFalse(result); // A standard TCP SocketChannel is not a Unix domain socket
		}
	}

	@Test
	public void testContinueBytesField() throws Exception {
		Field field = HttpProtocol.class.getDeclaredField("CONTINUE_BYTES");
		field.setAccessible(true);
		byte[] continueBytes = (byte[]) field.get(null);
		org.junit.Assert.assertArrayEquals("HTTP/1.1 100 Continue\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII), continueBytes);
	}

}
