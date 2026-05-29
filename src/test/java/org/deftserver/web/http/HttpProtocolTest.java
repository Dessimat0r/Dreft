package org.deftserver.web.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Field;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;

import org.deftserver.io.IOLoop;
import org.deftserver.web.Application;
import org.junit.Test;

public class HttpProtocolTest {

	@SuppressWarnings("unchecked")
	@Test
	public void testPartialsLeakClearedOnClose() throws Exception {
		Application app = new Application(new HashMap<>());
		HttpProtocol protocol = new HttpProtocol(IOLoop.INSTANCE, app);

		// Get private partials map via reflection
		Field partialsField = HttpProtocol.class.getDeclaredField("partials");
		partialsField.setAccessible(true);
		Map<SocketChannel, HttpRequest> partials = (Map<SocketChannel, HttpRequest>) partialsField.get(protocol);

		// Open a real unconnected SocketChannel
		try (SocketChannel channel = SocketChannel.open()) {
			// Populate partials map to simulate a half-received request
			partials.put(channel, null);
			assertEquals(1, partials.size());

			// Trigger closeChannel
			protocol.closeChannel(channel);

			// Verify it was successfully removed from partials, preventing the leak!
			assertEquals(0, partials.size());
			assertFalse(partials.containsKey(channel));
		}
	}

}
