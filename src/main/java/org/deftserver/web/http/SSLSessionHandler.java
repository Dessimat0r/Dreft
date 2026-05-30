package org.deftserver.web.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import org.deftserver.io.IOLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SSLSessionHandler {
	private static final Logger logger = LoggerFactory.getLogger(SSLSessionHandler.class);

	private final SocketChannel socketChannel;
	private final SSLEngine engine;
	private final IOLoop ioLoop;

	private final ByteBuffer appReadBuf;
	private final ByteBuffer netWriteBuf;
	private final ByteBuffer appWriteBuf;

	// netReadBuf is mutable: BUFFER_UNDERFLOW leaves residual bytes in it, so it must
	// be able to grow when a new src record doesn't fit alongside the residual data.
	private ByteBuffer netReadBuf;

	private boolean handshakeComplete = false;

	public SSLSessionHandler(SocketChannel socketChannel, SSLContext sslContext, IOLoop ioLoop) throws SSLException {
		this.socketChannel = socketChannel;
		this.ioLoop = ioLoop;
		this.engine = sslContext.createSSLEngine();
		this.engine.setUseClientMode(false);

		SSLSession session = engine.getSession();
		int packetBufferSize = session.getPacketBufferSize();
		int appBufferSize = session.getApplicationBufferSize();

		this.netReadBuf = ByteBuffer.allocate(packetBufferSize);
		this.appReadBuf = ByteBuffer.allocate(appBufferSize);
		this.netWriteBuf = ByteBuffer.allocate(packetBufferSize);
		this.appWriteBuf = ByteBuffer.allocate(appBufferSize);

		this.engine.beginHandshake();
	}

	public boolean isHandshakeComplete() {
		return handshakeComplete;
	}

	/** Maximum TLS record size on the wire (used to size read buffers correctly). */
	public int getPacketBufferSize() {
		return engine.getSession().getPacketBufferSize();
	}

	public synchronized void handshake() throws IOException {
		if (handshakeComplete) return;

		SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
		logger.debug("TLS Handshake status: {}", hs);

		while (!handshakeComplete) {
			switch (hs) {
				case NEED_UNWRAP:
					int read = readEncrypted();
					if (read < 0) {
						throw new IOException("Connection closed during handshake NEED_UNWRAP");
					}
					netReadBuf.flip();
					SSLEngineResult res = engine.unwrap(netReadBuf, appReadBuf);
					netReadBuf.compact();
					hs = res.getHandshakeStatus();
					logger.debug("Handshake NEED_UNWRAP result: {}, next status: {}", res.getStatus(), hs);

					if (res.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
						return; // Wait for more data from socket
					}
					break;

				case NEED_WRAP:
					netWriteBuf.clear();
					appWriteBuf.flip(); // empty
					res = engine.wrap(appWriteBuf, netWriteBuf);
					appWriteBuf.compact();
					hs = res.getHandshakeStatus();
					logger.debug("Handshake NEED_WRAP result: {}, next status: {}", res.getStatus(), hs);

					netWriteBuf.flip();
					writeEncrypted();
					break;

				case NEED_TASK:
					Runnable task;
					while ((task = engine.getDelegatedTask()) != null) {
						final Runnable t = task;
						Thread.startVirtualThread(() -> {
							t.run();
							ioLoop.addCallback(() -> {
								try {
									handshake();
								} catch (IOException e) {
									logger.error("Error during async handshake task execution: {}", e.getMessage());
									closeQuietly();
								}
							});
						});
					}
					return; // Wait for delegated task to run and call handshake

				case FINISHED:
				case NOT_HANDSHAKING:
					handshakeComplete = true;
					logger.debug("TLS Handshake finished successfully!");
					break;

				default:
					throw new IllegalStateException("Unexpected handshake status: " + hs);
			}
		}
	}

	/**
	 * Bound on how long we keep retrying a full-buffer non-blocking write before treating
	 * the peer as dead. Forward progress resets the clock, so only a genuinely stalled
	 * socket (which would otherwise spin the I/O-loop thread at 100% CPU forever) is dropped.
	 */
	private static final long WRITE_STALL_TIMEOUT_NS = 10L * 1_000_000_000L;

	private int readEncrypted() throws IOException {
		return socketChannel.read(netReadBuf);
	}

	/** Fully writes {@code buf} to the (non-blocking) channel, aborting if the socket makes
	 *  zero progress for {@link #WRITE_STALL_TIMEOUT_NS}. */
	private void writeFully(ByteBuffer buf) throws IOException {
		long stallDeadline = 0;
		while (buf.hasRemaining()) {
			int n = socketChannel.write(buf);
			if (n > 0) {
				stallDeadline = 0;
			} else {
				long now = System.nanoTime();
				if (stallDeadline == 0) {
					stallDeadline = now + WRITE_STALL_TIMEOUT_NS;
				} else if (now - stallDeadline > 0) {
					throw new IOException("TLS socket write stalled (peer not reading); aborting");
				}
				// Park briefly instead of busy-spinning while the send buffer is full.
				java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
			}
		}
	}

	private void writeEncrypted() throws IOException {
		writeFully(netWriteBuf);
	}

	public synchronized ByteBuffer unwrap(ByteBuffer src) throws IOException {
		// Allocate destination large enough for multiple records being unwrapped in one call
		ByteBuffer dst = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize() * 4);

		appReadBuf.flip();
		if (appReadBuf.hasRemaining()) {
			dst.put(appReadBuf);
		}
		appReadBuf.clear();

		if (src.hasRemaining()) {
			// Grow netReadBuf if residual data from a previous BUFFER_UNDERFLOW leaves
			// insufficient space for the new src bytes.
			if (src.remaining() > netReadBuf.remaining()) {
				ByteBuffer grown = ByteBuffer.allocate(netReadBuf.position() + src.remaining());
				netReadBuf.flip();
				grown.put(netReadBuf);
				netReadBuf = grown;
			}
			netReadBuf.put(src);
		}
		netReadBuf.flip();

		while (netReadBuf.hasRemaining()) {
			SSLEngineResult res = engine.unwrap(netReadBuf, dst);
			if (res.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
				break;
			} else if (res.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
				ByteBuffer newDst = ByteBuffer.allocate(dst.capacity() * 2);
				dst.flip();
				newDst.put(dst);
				dst = newDst;
			} else if (res.getStatus() == SSLEngineResult.Status.CLOSED) {
				throw new SSLConnectionClosedException();
			}
		}
		netReadBuf.compact();
		dst.flip();
		return dst;
	}

	public synchronized ByteBuffer wrap(ByteBuffer src) throws IOException {
		ByteBuffer dst = ByteBuffer.allocate(engine.getSession().getPacketBufferSize() * 2);
		while (src.hasRemaining()) {
			SSLEngineResult res = engine.wrap(src, dst);
			if (res.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
				ByteBuffer newDst = ByteBuffer.allocate(dst.capacity() * 2);
				dst.flip();
				newDst.put(dst);
				dst = newDst;
			} else if (res.getStatus() == SSLEngineResult.Status.CLOSED) {
				throw new SSLConnectionClosedException();
			}
		}
		dst.flip();
		return dst;
	}

	public void closeQuietly() {
		try {
			engine.closeOutbound();
			// Attempt to send the TLS close_notify alert so the peer gets a clean
			// shutdown rather than a TCP RST. Best-effort: if the socket is already
			// gone, the IOException is silently ignored below.
			ByteBuffer src = ByteBuffer.allocate(0);
			ByteBuffer dst = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
			SSLEngineResult result = engine.wrap(src, dst);
			if (result.bytesProduced() > 0) {
				dst.flip();
				writeFully(dst);
			}
		} catch (Exception e) {
			// Ignore — channel may already be closed or broken
		}
		try {
			socketChannel.close();
		} catch (IOException e) {
			// Ignore
		}
	}
}
