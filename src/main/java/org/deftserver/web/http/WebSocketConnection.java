package org.deftserver.web.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class WebSocketConnection {

	private final SocketChannel channel;
	private final HttpProtocol protocol;

	public WebSocketConnection(SocketChannel channel, HttpProtocol protocol) {
		this.channel = channel;
		this.protocol = protocol;
	}

	public void write(String message) {
		byte[] payload = message.getBytes(StandardCharsets.UTF_8);
		int len = payload.length;
		ByteBuffer frame;
		if (len < 126) {
			frame = ByteBuffer.allocate(2 + len);
			frame.put((byte) 0x81);
			frame.put((byte) len);
		} else if (len <= 65535) {
			frame = ByteBuffer.allocate(4 + len);
			frame.put((byte) 0x81);
			frame.put((byte) 126);
			frame.putShort((short) len);
		} else {
			frame = ByteBuffer.allocate(10 + len);
			frame.put((byte) 0x81);
			frame.put((byte) 127);
			frame.putLong(len);
		}
		frame.put(payload);
		frame.flip();
		
		try {
			// Write frame synchronously to keep it simple, or queue it
			while (frame.hasRemaining()) {
				protocol.write(channel, frame);
			}
		} catch (IOException e) {
			protocol.closeChannel(channel);
		}
	}

	public void close() {
		ByteBuffer frame = ByteBuffer.allocate(4);
		frame.put((byte) 0x88); // Close opcode + FIN
		frame.put((byte) 2);    // Length = 2
		frame.putShort((short) 1000); // Normal closure
		frame.flip();
		try {
			while (frame.hasRemaining()) {
				protocol.write(channel, frame);
			}
		} catch (IOException ignored) {
		} finally {
			protocol.closeChannel(channel);
		}
	}

	public SocketChannel getChannel() {
		return channel;
	}
}
