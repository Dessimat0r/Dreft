package org.deftserver.web.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.deftserver.io.IOLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SSLSessionHandler {
	private static final Logger logger = LoggerFactory.getLogger(SSLSessionHandler.class);

	private static final java.util.concurrent.ExecutorService sslExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

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

	// Set once the peer's close_notify has been unwrapped. We don't throw on it immediately if there
	// is still buffered plaintext to deliver (a request can be coalesced with close_notify in one TCP
	// segment); the closed signal is raised only once all decrypted application data has been drained.
	private boolean inboundClosed = false;

	/** Creates a server-side TLS session for a channel: builds the {@link SSLEngine}, sizes the
	 *  net/app wrap/unwrap buffers from the session, and begins the handshake (driven by
	 *  {@link #handshake()} as bytes arrive). */
	public SSLSessionHandler(SocketChannel socketChannel, SSLContext sslContext, IOLoop ioLoop) throws SSLException {
		this.socketChannel = socketChannel;
		this.ioLoop = ioLoop;
		this.engine = sslContext.createSSLEngine();
		this.engine.setUseClientMode(false);

		// ALPN (RFC 7301): advertise HTTP/2 over TLS ("h2") with HTTP/1.1 fallback. A client that
		// offers "h2" gets it negotiated here; per RFC 7540 §3.3 it then opens with the HTTP/2
		// connection preface, which the existing preface-detection path (over the decrypted stream)
		// routes to Http2Connection. A client that doesn't offer "h2" negotiates "http/1.1" (or no
		// protocol) and is handled by the HTTP/1.1 path unchanged. Set before beginHandshake().
		SSLParameters sslParams = engine.getSSLParameters();
		sslParams.setApplicationProtocols(new String[] { "h2", "http/1.1" });
		engine.setSSLParameters(sslParams);

		SSLSession session = engine.getSession();
		int packetBufferSize = session.getPacketBufferSize();
		int appBufferSize = session.getApplicationBufferSize();

		this.netReadBuf = ByteBuffer.allocate(packetBufferSize);
		this.appReadBuf = ByteBuffer.allocate(appBufferSize);
		this.netWriteBuf = ByteBuffer.allocate(packetBufferSize);
		// appWriteBuf is ONLY the empty application-data source handed to engine.wrap() during the
		// handshake (NEED_WRAP) — outbound application data goes through wrap(src) with the caller's own
		// buffer + wrapDst, never this one. So it never holds data and needs no capacity; a zero-length
		// buffer saves ~appBufferSize (≈16 KiB) of heap PER HTTPS CONNECTION (less per-connection memory =
		// harder for one client to exhaust the heap by opening many connections). flip()/compact() on it
		// stay no-ops, and engine.wrap() accepts an empty src during the handshake.
		this.appWriteBuf = ByteBuffer.allocate(0);

		this.engine.beginHandshake();
	}

	/** True once the TLS handshake has finished and application data can flow. */
	public boolean isHandshakeComplete() {
		return handshakeComplete;
	}

	/** The ALPN-negotiated application protocol (e.g. {@code "h2"} or {@code "http/1.1"}), or an
	 *  empty string if the peer offered no ALPN, or {@code null} before the handshake finishes. */
	public String getApplicationProtocol() {
		return engine.getApplicationProtocol();
	}

	/** Maximum TLS record size on the wire (used to size read buffers correctly). */
	public int getPacketBufferSize() {
		return engine.getSession().getPacketBufferSize();
	}

	/** Drives the non-blocking TLS handshake state machine (wrap/unwrap/run-delegated-tasks) until it
	 *  completes or needs more inbound bytes; re-entered as data arrives and after async tasks finish.
	 *  Guarded against a no-progress spin. */
	public synchronized void handshake() throws IOException {
		if (handshakeComplete) return;

		SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
		logger.debug("TLS Handshake status: {}", hs);

		// Defence against a pathological engine state that keeps requesting NEED_WRAP without making
		// progress: that would spin this loop (on the I/O-loop thread) at 100% CPU forever. A real
		// handshake takes only a handful of transitions, so a generous cap can't hurt legitimate use.
		int guard = 0;
		while (!handshakeComplete) {
			if (++guard > 10_000) {
				throw new IOException("TLS handshake made no progress after " + (guard - 1) + " steps; aborting");
			}
			switch (hs) {
				case NEED_UNWRAP:
					int read = readEncrypted();
					if (read < 0) {
						throw new IOException("Connection closed during handshake NEED_UNWRAP");
					}
					netReadBuf.flip();
					SSLEngineResult res = engine.unwrap(netReadBuf, appReadBuf);
					netReadBuf.compact();
					if (appReadBuf.position() > 0) {
						appReadBuf.clear(); // discard plaintext produced during handshake (e.g. TLS 1.3 EncryptedExtensions)
					}
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
					if (!tryWrite(netWriteBuf, () -> {
						try {
							netWriteBuf.compact();
							handshake();
						} catch (IOException e) {
							logger.error("Handshake write resume failed", e);
							closeQuietly();
						}
					})) {
						ioLoop.updateHandler(socketChannel, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
						return;
					}
					break;

			case NEED_TASK:
				Runnable task = engine.getDelegatedTask();
				if (task == null) {
					hs = engine.getHandshakeStatus();
					break;
				}
				while (task != null) {
					final Runnable t = task;
					sslExecutor.submit(() -> {
						try {
							t.run();
						} catch (Throwable taskError) {
							// A failed delegated task must not strand the connection (waiting forever
							// for a handshake resume that never comes — only the header-read timeout
							// would eventually reap it). Tear it down promptly, on the loop thread.
							ioLoop.addCallback(() -> {
								logger.error("TLS delegated handshake task threw — closing connection", taskError);
								closeQuietly();
							});
							return;
						}
						ioLoop.addCallback(() -> {
							try {
								handshake();
							} catch (IOException e) {
								logger.error("Error during async handshake task execution", e);
								closeQuietly();
							}
						});
					});
					task = engine.getDelegatedTask();
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

	/** Reads available ciphertext from the socket into the net-read buffer; returns the byte count
	 *  (or -1 at EOF). */
	private int readEncrypted() throws IOException {
		return socketChannel.read(netReadBuf);
	}

	// --- deferred (non-blocking) write support ---
	// Instead of busy-spinning LockSupport.parkNanos on the I/O-loop thread, partial writes are
	// deferred: the unwritten tail stays in pendingWriteBuffer and writeCompleteAction runs once
	// {@link #onWritable()} drains it. The caller MUST register OP_WRITE on the channel.

	private ByteBuffer pendingWriteBuffer;
	private Runnable writeCompleteAction;

	/** @return true if a previously deferred write is still waiting for socket space. */
	public boolean hasPendingWrite() {
		return pendingWriteBuffer != null && pendingWriteBuffer.hasRemaining();
	}

	/** Non-blocking best-effort write. When the socket buffer cannot accept every byte at once,
	 *  stores {@code buf} as the pending buffer and {@code onComplete} for later resumption.
	 *  @return true if all bytes were written immediately, false if a deferred write was set up. */
	private boolean tryWrite(ByteBuffer buf, Runnable onComplete) throws IOException {
		int n = socketChannel.write(buf);
		if (n < 0) throw new IOException("Connection closed during TLS write");
		if (buf.hasRemaining()) {
			pendingWriteBuffer = buf;
			writeCompleteAction = onComplete;
			return false;
		}
		return true;
	}

	public boolean tryWrite(ByteBuffer buf) throws IOException {
		return tryWrite(buf, null);
	}


	/**
	 * Called by {@code HttpProtocol.handleWrite} when OP_WRITE fires after a deferred write.
	 * Drains the pending buffer; when fully written, executes the continuation.
	 * @return true if the deferred write completed, false if more socket space is needed.
	 */
	public boolean onWritable() throws IOException {
		if (pendingWriteBuffer != null && pendingWriteBuffer.hasRemaining()) {
			socketChannel.write(pendingWriteBuffer);
		}
		if (pendingWriteBuffer != null && !pendingWriteBuffer.hasRemaining()) {
			Runnable action = writeCompleteAction;
			pendingWriteBuffer = null;
			writeCompleteAction = null;
			if (action != null) {
				action.run();
			}
			return true;
		}
		return pendingWriteBuffer == null;
	}

	private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

	/**
	 * Drives any handshaking the engine requests after the main handshake has completed — TLS 1.3
	 * post-handshake messages such as NewSessionTicket (NEED_TASK to process) and KeyUpdate
	 * (NEED_WRAP to answer). Without this the engine's response is never sent and a long-lived
	 * connection that rekeys would eventually fail to decrypt. NEED_UNWRAP just means "more inbound
	 * bytes needed" — handled by the surrounding unwrap loop — so we return on it.
	 *
	 * <p>Calls itself via {@link #onWritable()} continuations so partial writes do not block
	 * the I/O-loop thread; delegated tasks are offloaded to virtual threads. Must only be
	 * called from the I/O-loop thread (or a callback scheduled on it).</p>
	 */
	private void driveHandshake(SSLEngineResult.HandshakeStatus hs) throws IOException {
		int guard = 0;
		while ((hs == SSLEngineResult.HandshakeStatus.NEED_TASK
				|| hs == SSLEngineResult.HandshakeStatus.NEED_WRAP) && guard++ < 100) {
			if (hs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
				Runnable task = engine.getDelegatedTask();
				if (task == null) {
					hs = engine.getHandshakeStatus();
					continue;
				}
				while (task != null) {
					final Runnable t = task;
					sslExecutor.submit(() -> {
						t.run();
						ioLoop.addCallback(() -> {
							try {
								driveHandshake(engine.getHandshakeStatus());
							} catch (IOException e) {
								logger.error("Error during async post-handshake task", e);
								closeQuietly();
							}
						});
					});
					task = engine.getDelegatedTask();
				}
				return; // Wait for delegated tasks to call driveHandshake via callback
			} else { // NEED_WRAP — emit the engine's handshake record (e.g. a KeyUpdate response)
				netWriteBuf.clear();
				SSLEngineResult wr = engine.wrap(EMPTY, netWriteBuf);
				netWriteBuf.flip();
				if (netWriteBuf.hasRemaining()) {
					if (!tryWrite(netWriteBuf, () -> {
						try {
							netWriteBuf.compact();
							driveHandshake(engine.getHandshakeStatus());
						} catch (IOException e) {
							logger.error("Post-handshake write resume failed", e);
							closeQuietly();
						}
					})) {
						ioLoop.updateHandler(socketChannel, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
						return;
					}
				}
				if (wr.getStatus() == SSLEngineResult.Status.CLOSED) {
					throw new SSLConnectionClosedException();
				}
				// Nothing produced and still NEED_WRAP → can't make progress; bail to avoid a spin.
				if (wr.bytesProduced() == 0 && wr.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
					break;
				}
				hs = wr.getHandshakeStatus();
			}
		}
	}

	/** Decrypts inbound ciphertext: buffers {@code src} alongside any residual bytes, unwraps every
	 *  complete TLS record into a (growable) destination, drives any TLS 1.3 post-handshake messages,
	 *  and returns the recovered application bytes (read-ready). */
	// Reusable destination for wrap() — avoids a ~130 KiB allocation on every HTTPS write.
	private ByteBuffer wrapDst;

	// Reusable plaintext destination for unwrap() — avoids a ~64 KiB allocation on every HTTPS read.
	// This handler is per-connection and only touched on its reactor's loop thread, and the buffer
	// returned by unwrap() is fully copied out by the caller before the next read can call unwrap()
	// again, so reuse is safe. Grown (and re-kept) on BUFFER_OVERFLOW.
	private ByteBuffer unwrapDst;

	public synchronized ByteBuffer unwrap(ByteBuffer src) throws IOException {
		// Destination large enough for multiple records unwrapped in one call (reused across reads).
		int wantCap = engine.getSession().getApplicationBufferSize() * 4;
		if (unwrapDst == null || unwrapDst.capacity() < wantCap) {
			unwrapDst = ByteBuffer.allocate(wantCap);
		}
		ByteBuffer dst = unwrapDst;
		dst.clear();

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
			int srcPos = netReadBuf.position();
			SSLEngineResult res = engine.unwrap(netReadBuf, dst);
			if (res.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
				// The engine may have requested a post-handshake write (e.g. KeyUpdate response)
				// before needing more inbound bytes — drive it now so the response isn't dropped.
				driveHandshake(res.getHandshakeStatus());
				// Underflow means the next record is incomplete, so we always stop here and wait for
				// more inbound bytes (whether or not driveHandshake deferred a write — that pending
				// write is drained independently on OP_WRITE).
				break;
			} else if (res.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
				// Restore src position: the engine may have advanced it before detecting overflow.
				netReadBuf.position(srcPos);
				ByteBuffer newDst = ByteBuffer.allocate(dst.capacity() * 2);
				dst.flip();
				newDst.put(dst);
				dst = newDst;
				unwrapDst = newDst; // keep the grown buffer for reuse on subsequent reads
			} else if (res.getStatus() == SSLEngineResult.Status.CLOSED) {
				// Peer sent close_notify. Stop unwrapping, but DON'T discard any application bytes
				// already decoded earlier in this same call — a complete request can arrive coalesced
				// with close_notify in a single TCP segment. Deliver the buffered plaintext first; the
				// closed signal is raised after the loop only once there's nothing left to return.
				inboundClosed = true;
				break;
			} else {
				// OK: a record may have triggered post-handshake handshaking (TLS 1.3
				// NewSessionTicket / KeyUpdate) — drive it so the engine's response is sent.
				driveHandshake(res.getHandshakeStatus());
				if (hasPendingWrite()) {
					break;
				}
			}
		}
		netReadBuf.compact();
		dst.flip();
		// Now that any buffered plaintext has been flipped for return, raise the closed signal only
		// when there is nothing left to deliver. This lets a request coalesced with close_notify be
		// processed; the next unwrap call (with no remaining plaintext) cleanly reports the close.
		if (inboundClosed && !dst.hasRemaining()) {
			throw new SSLConnectionClosedException();
		}
		return dst;
	}

	/** Encrypts application bytes from {@code src} into one or more TLS records, growing the
	 *  destination on overflow and stopping cleanly if the engine can make no progress (e.g. it must
	 *  first read a peer handshake message). Returns the ciphertext ready to write. */
	public synchronized ByteBuffer wrap(ByteBuffer src) throws IOException {
		int wantCap = engine.getSession().getPacketBufferSize() * 2;
		if (wrapDst == null || wrapDst.capacity() < wantCap) {
			wrapDst = ByteBuffer.allocate(wantCap);
		}
		ByteBuffer dst = wrapDst;
		dst.clear();
		while (src.hasRemaining()) {
			int srcPos = src.position();
			SSLEngineResult res = engine.wrap(src, dst);
			if (res.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
				src.position(srcPos); // restore position — engine may have advanced it before overflow
				ByteBuffer newDst = ByteBuffer.allocate(dst.capacity() * 2);
				wrapDst = newDst;
				dst.flip();
				newDst.put(dst);
				dst = newDst;
			} else if (res.getStatus() == SSLEngineResult.Status.CLOSED) {
				throw new SSLConnectionClosedException();
			} else if (res.bytesConsumed() == 0 && res.bytesProduced() == 0) {
				// OK but no progress: the engine can't wrap more application data right now (e.g.
				// it must unwrap a peer handshake message first). Stop rather than spin — the
				// remaining src bytes will be wrapped on a subsequent call after we read inbound.
				break;
			}
		}
		dst.flip();
		return dst;
	}

	/** Best-effort graceful TLS shutdown: sends a {@code close_notify} alert (so the peer sees a clean
	 *  close rather than a TCP reset) then closes the socket, swallowing any errors. Synchronized to
	 *  avoid racing the SSLEngine (which is not thread-safe) with concurrent wrap/unwrap/handshake calls. */
	public synchronized void closeQuietly() {
		try {
			engine.closeOutbound();
			ByteBuffer src = ByteBuffer.allocate(0);
			ByteBuffer dst = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
			SSLEngineResult result = engine.wrap(src, dst);
			if (result.bytesProduced() > 0) {
				dst.flip();
				socketChannel.write(dst); // best-effort non-blocking write
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
