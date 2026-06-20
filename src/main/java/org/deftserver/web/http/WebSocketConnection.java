package org.deftserver.web.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class WebSocketConnection {

	private final SocketChannel channel;
	private final HttpProtocol protocol;

	/** Wraps an upgraded WebSocket channel together with its owning protocol (used to marshal writes
	 *  back onto the I/O-loop thread). */
	public WebSocketConnection(SocketChannel channel, HttpProtocol protocol) {
		this.channel = channel;
		this.protocol = protocol;
	}

	/**
	 * Sends a text message. May be called from any thread: the actual frame write is marshalled
	 * onto the I/O-loop thread (the only safe place to touch the channel/timeout/handler state),
	 * so it can never interleave a partial frame with the server's own pong/close sends nor race
	 * the loop's per-channel maps.
	 */
	public void write(String message) {
		final byte[] payload = message.getBytes(StandardCharsets.UTF_8);
		if (Thread.currentThread() == protocol.getIOLoop().getThread()) {
			doWrite((byte) 0x81, payload);
		} else {
			protocol.getIOLoop().addCallback(() -> doWrite((byte) 0x81, payload));
		}
	}

	public void write(byte[] data) {
		if (Thread.currentThread() == protocol.getIOLoop().getThread()) {
			doWrite((byte) 0x82, data);
		} else {
			final byte[] payload = data.clone();
			protocol.getIOLoop().addCallback(() -> doWrite((byte) 0x82, payload));
		}
	}

	/** Builds and sends a single unmasked frame with the given first byte (FIN + opcode) for the
	 *  payload (run on the I/O-loop thread). Picks the 7-bit / 16-bit / 64-bit length encoding per
	 *  RFC 6455 §5.2; a failed write tears the connection down. */
	private void doWrite(byte firstByte, byte[] payload) {
		int len = payload.length;
		ByteBuffer frame;
		if (len < 126) {
			frame = ByteBuffer.allocate(2 + len);
			frame.put(firstByte);
			frame.put((byte) len);
		} else if (len <= 65535) {
			frame = ByteBuffer.allocate(4 + len);
			frame.put(firstByte);
			frame.put((byte) 126);
			frame.putShort((short) len);
		} else {
			frame = ByteBuffer.allocate(10 + len);
			frame.put(firstByte);
			frame.put((byte) 127);
			frame.putLong(len);
		}
		frame.put(payload);
		frame.flip();

		try {
			// Non-blocking: any bytes the socket can't take now are deferred to OP_WRITE, so a slow/non-
			// reading peer is reaped by the idle write timeout instead of freezing the I/O-loop thread.
			protocol.writeFrame(channel, frame);
		} catch (IOException e) {
			protocol.closeChannel(channel);
		}
	}

	/** Closes the connection (sends a Close frame then tears down). Safe to call from any thread. */
	public void close() {
		protocol.getIOLoop().addCallback(this::doClose);
	}

	/** Sends a Close frame (status 1000, normal closure) then tears the channel down (run on the
	 *  I/O-loop thread). */
	private void doClose() {
		ByteBuffer frame = ByteBuffer.allocate(4);
		frame.put((byte) 0x88); // Close opcode + FIN
		frame.put((byte) 2);    // Length = 2
		frame.putShort((short) 1000); // Normal closure
		frame.flip();
		try {
			protocol.writeFrame(channel, frame);
			if (protocol.hasPendingFrameWrite(channel)) {
				// Slow reader: close once the Close frame has actually flushed (OP_WRITE drain), so the
				// close handshake never blocks the I/O-loop thread yet the frame still goes out first.
				protocol.markCloseAfterWrite(channel);
				return;
			}
		} catch (IOException ignored) {
		}
		protocol.closeChannel(channel);
	}

	/** The underlying socket channel for this WebSocket connection. */
	public SocketChannel getChannel() {
		return channel;
	}
}
