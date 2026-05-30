package org.deftserver.web.http;

import static org.deftserver.web.http.HttpServerDescriptor.KEEP_ALIVE_TIMEOUT;
import static org.deftserver.web.http.HttpServerDescriptor.READ_BUFFER_SIZE;

import java.io.EOFException;
import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;

import java.util.HashMap;

import org.deftserver.io.IOHandler;
import org.deftserver.io.IOLoop;
import org.deftserver.io.buffer.DynamicByteBuffer;
import org.deftserver.io.timeout.Timeout;
import org.deftserver.web.Application;
import org.deftserver.web.AsyncCallback;
import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.handler.WebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpProtocol implements IOHandler {
	
	private final static Logger logger = LoggerFactory.getLogger(HttpProtocol.class);

	private final IOLoop ioLoop;
	private final Application application;

	
	// a queue of half-baked (pending/unfinished) HTTP post request
	private final Map<SelectableChannel, HttpRequest> partials = new HashMap<>();
	
	private javax.net.ssl.SSLContext sslContext;
	private final Map<SocketChannel, SSLSessionHandler> sslSessionHandlers = new HashMap<>();

	private final Map<SocketChannel, WebSocketHandler> websocketHandlers = new HashMap<>();
	private final Map<SocketChannel, WebSocketConnection> websocketConnections = new HashMap<>();
	// Accumulates the payload of a fragmented WebSocket message (data frame with FIN=0 followed
	// by continuation frames) until the final fragment arrives.
	private final Map<SocketChannel, java.io.ByteArrayOutputStream> websocketFragments = new HashMap<>();

	private final Map<SocketChannel, Timeout> headerReadTimeouts = new HashMap<>();
	private final Map<SocketChannel, Timeout> bodyReadTimeouts = new HashMap<>();
	// Set by getHttpRequest (same call stack as handleRead) when a complete request is followed by
	// buffered bytes of a pipelined request we won't process — causes the connection to close.
	private boolean pipelinedDataPending = false;
	private int maxConnections = -1;
	// Set of live client channels (not an int counter): channels can be closed via several paths
	// that don't all funnel through closeChannel (IOLoop exception handlers, generic Closeables,
	// flush/finish I/O errors). A bare counter would leak upward on those paths and eventually
	// throttle the server forever. A set lets us self-heal by pruning closed channels before the
	// limit check. Accessed only from this protocol's I/O-loop thread.
	private final java.util.Set<SocketChannel> activeChannels = new java.util.HashSet<>();

	/** Upper bound on a single inbound WebSocket frame payload (16 MiB), mirroring the HTTP
	 *  body cap. Guards against OOM / NegativeArraySizeException from a hostile length field. */
	private static final long MAX_WS_FRAME_PAYLOAD = 16L * 1024 * 1024;

	/** Absolute cap on how long a request body may take to arrive after its headers. Bounds
	 *  the resources a slow/stalled body upload can hold (Slowloris-on-body). */
	private static final long BODY_READ_TIMEOUT_MS = 30_000;

	/** Largest the (plaintext) header read buffer may grow to while accumulating a request's
	 *  header block; beyond this the request is answered with 431. Matches the header-section
	 *  cap enforced in {@link HttpRequest}. */
	private static final int MAX_HEADER_BUFFER_SIZE = 64 * 1024;

	private int maxConnectionsPerIp = -1;

	public void setMaxConnections(int max) {
		this.maxConnections = max;
	}

	/** Caps simultaneous connections from a single remote IP (<= 0 disables). Prevents one client
	 *  from exhausting all connection slots, which the global limit alone does not. */
	public void setMaxConnectionsPerIp(int max) {
		this.maxConnectionsPerIp = max;
	}

	public int getActiveConnections() {
		return activeChannels.size();
	}

	public void closeChannel(SocketChannel channel) {
		if (channel != null) {
			sslSessionHandlers.remove(channel);
			websocketHandlers.remove(channel);
			websocketConnections.remove(channel);
			websocketFragments.remove(channel);
			partials.remove(channel);
			Timeout t = headerReadTimeouts.remove(channel);
			if (t != null) {
				t.cancel();
			}
			Timeout bt = bodyReadTimeouts.remove(channel);
			if (bt != null) {
				bt.cancel();
			}
			activeChannels.remove(channel);
			if (channel.isOpen()) {
				org.deftserver.util.Closeables.closeQuietly(ioLoop, channel);
			}
		}
	}

	public void upgradeToWebSocket(SocketChannel channel, WebSocketHandler handler, WebSocketConnection connection) {
		websocketHandlers.put(channel, handler);
		websocketConnections.put(channel, connection);
	}

	public void enableSSL(javax.net.ssl.SSLContext sslContext) {
		this.sslContext = sslContext;
	}

	public boolean isSSLEnabled() {
		return sslContext != null;
	}
 	
	public HttpProtocol(Application app) {
		this(IOLoop.INSTANCE, app);
	}
	
	public HttpProtocol(IOLoop ioLoop, Application app) {
		this.ioLoop = ioLoop;
		application = app;
	}
	@Override
	public void handleAccept(SelectionKey key) throws IOException {
		logger.debug("handle accept... isSSLEnabled={}", isSSLEnabled());
		SocketChannel clientChannel = ((ServerSocketChannel) key.channel()).accept();
		if (clientChannel != null) {
			if (maxConnections > 0 && activeChannels.size() >= maxConnections) {
				// Self-heal: prune any channels that were closed via a path that didn't run
				// through closeChannel before deciding we're really at the limit.
				activeChannels.removeIf(ch -> !ch.isOpen());
				if (activeChannels.size() >= maxConnections) {
					logger.warn("Anti-DoS: Connection count limit reached ({}), rejecting new connection", maxConnections);
					clientChannel.close();
					return;
				}
			}
			if (maxConnectionsPerIp > 0) {
				java.net.InetAddress ip = clientChannel.socket().getInetAddress();
				if (ip != null) {
					activeChannels.removeIf(ch -> !ch.isOpen()); // self-heal stale entries first
					int sameIp = 0;
					for (SocketChannel ch : activeChannels) {
						java.net.InetAddress chIp = ch.socket().getInetAddress();
						if (ip.equals(chIp)) {
							sameIp++;
						}
					}
					if (sameIp >= maxConnectionsPerIp) {
						logger.warn("Anti-DoS: per-IP connection limit ({}) reached for {}, rejecting", maxConnectionsPerIp, ip);
						clientChannel.close();
						return;
					}
				}
			}
			// could be null in a multithreaded deft environment because another ioloop was "faster" to accept()
			clientChannel.configureBlocking(false);
			clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
			ioLoop.addHandler(clientChannel, this, SelectionKey.OP_READ, ByteBuffer.allocate(READ_BUFFER_SIZE));
			activeChannels.add(clientChannel);
			
			// Anti-DoS Header Timeout
			Timeout headerTimeout = new Timeout(
				System.currentTimeMillis() + 5000,
				new AsyncCallback() {
					@Override
					public void onCallback() {
						logger.warn("Anti-DoS: Header read timeout for channel {}", clientChannel);
						closeChannel(clientChannel);
					}
				}
			);
			headerReadTimeouts.put(clientChannel, headerTimeout);
			ioLoop.addTimeout(headerTimeout);
			if (isSSLEnabled()) {
				logger.debug("SSL enabled: instantiating SSLSessionHandler");
				SSLSessionHandler handler = new SSLSessionHandler(clientChannel, sslContext, ioLoop);
				sslSessionHandlers.put(clientChannel, handler);
				handler.handshake();
			}
		}
	}
	
	@Override
	public void handleConnect(SelectionKey key) throws IOException {
		logger.error("handle connect in HttpProcotol...");
	}

	@Override
	public void handleRead(SelectionKey key) throws IOException {
		logger.debug("handle read... key: {}", key);
		SocketChannel clientChannel = (SocketChannel) key.channel();
		
		WebSocketHandler wsHandler = websocketHandlers.get(clientChannel);
		if (wsHandler != null) {
			handleWebSocketRead(key, clientChannel, wsHandler);
			return;
		}

		logger.debug("handle read 2...");
		SSLSessionHandler sslHandler = sslSessionHandlers.get(clientChannel);
		logger.debug("handle read: sslHandler={}, handshakeComplete={}", sslHandler, sslHandler == null ? "N/A" : sslHandler.isHandshakeComplete());
		if (sslHandler != null && !sslHandler.isHandshakeComplete()) {
			try {
				sslHandler.handshake();
			} catch (IOException e) {
				logger.warn("SSL Handshake failed: {}", e.getMessage());
				sslHandler.closeQuietly();
				sslSessionHandlers.remove(clientChannel);
				return;
			}
			if (!sslHandler.isHandshakeComplete()) {
				return;
			}
		}
		HttpRequest request;
		try {
			request = getHttpRequest(key, clientChannel);
		} catch (HttpException he) {
			logger.warn("HTTP exception during request parsing: {}", he.getMessage());
			Timeout t = headerReadTimeouts.remove(clientChannel);
			if (t != null) t.cancel();
			try {
				HttpResponse response = new HttpResponse(this, key, false);
				response.setStatusCode(he.getStatusCode());
				response.setHeader("Connection", "close");
				response.setHeader("Content-Type", "text/plain; charset=utf-8");
				response.write(he.getLongHTMLMessage());
				response.finish();
			} catch (Exception ex) {
				logger.debug("Failed to send HTTP exception response (client likely disconnected): {}", ex.getMessage());
				closeChannel(clientChannel);
			}
			return;
		} catch (SSLConnectionClosedException e) {
			// Peer sent TLS close_notify — graceful shutdown, not an error
			logger.debug("TLS close_notify during request parsing, closing channel");
			Timeout t = headerReadTimeouts.remove(clientChannel);
			if (t != null) t.cancel();
			SSLSessionHandler closedSslHandler = sslSessionHandlers.remove(clientChannel);
			if (closedSslHandler != null) closedSslHandler.closeQuietly();
			closeChannel(clientChannel);
			return;
		} catch (Exception e) {
			logger.warn("Unexpected exception during request parsing", e);
			Timeout t = headerReadTimeouts.remove(clientChannel);
			if (t != null) t.cancel();
			try {
				HttpResponse response = new HttpResponse(this, key, false);
				response.setStatusCode(400);
				response.setHeader("Connection", "close");
				response.setHeader("Content-Type", "text/plain; charset=utf-8");
				response.write("Bad Request");
				response.finish();
			} catch (Exception ex) {
				logger.debug("Failed to send Bad Request response (client likely disconnected): {}", ex.getMessage());
				closeChannel(clientChannel);
			}
			return;
		}

		if (request == null) {
			logger.debug("null request (no data)");
			return;
		}
		
		// Headers are parsed; the header-read timeout no longer applies.
		Timeout t = headerReadTimeouts.remove(clientChannel);
		if (t != null) {
			t.cancel();
		}
		if (!request.isComplete()) {
			// Body still arriving. Arm a single body-read timeout (absolute cap) so a client
			// that stalls or dribbles its body cannot hold the connection open indefinitely
			// (Slowloris on the body). Armed once per request, not refreshed.
			if (!bodyReadTimeouts.containsKey(clientChannel)) {
				final SocketChannel bodyChannel = clientChannel;
				Timeout bodyTimeout = new Timeout(
					System.currentTimeMillis() + BODY_READ_TIMEOUT_MS,
					new AsyncCallback() {
						@Override
						public void onCallback() {
							logger.warn("Anti-DoS: body read timeout for channel {}", bodyChannel);
							closeChannel(bodyChannel);
						}
					}
				);
				bodyReadTimeouts.put(clientChannel, bodyTimeout);
				ioLoop.addTimeout(bodyTimeout);
			}
			logger.debug("HttpRequest is incomplete. Waiting for more data.");
			return;
		}
		// Request fully received — clear any body-read timeout.
		Timeout bt = bodyReadTimeouts.remove(clientChannel);
		if (bt != null) {
			bt.cancel();
		}
		logger.debug("handle read 3..., req class: {}, req: {}", request.getClass(), request);
		
		final boolean keepAlive;

		// Don't keep the connection alive if a pipelined request is already buffered: we don't
		// process pipelined requests, so closing makes the client retry the follow-up on a fresh
		// connection (RFC 9112 §9.3.2) instead of hanging for a response we'll never send.
		if (!(request instanceof MalFormedHttpRequest) && request.isKeepAlive() && !pipelinedDataPending) {
			keepAlive = true;
			ioLoop.addKeepAliveTimeout(
				clientChannel,
				Timeout.newKeepAliveTimeout(ioLoop, clientChannel, KEEP_ALIVE_TIMEOUT)
			);
		} else keepAlive = false;
		
		HttpResponse response = new HttpResponse(this, key, keepAlive, request.getMethod() == org.deftserver.web.HttpVerb.HEAD);
		response.setRequest(request);
		logger.debug("handle read 5...");
		try {
			RequestHandler rh = application.getHandler(request);
			logger.debug("handle read 6...");
			boolean isAsyncOrOffloaded = HttpRequestDispatcher.dispatch(rh, request, response);
			logger.debug("handle read 7...");

			//Only close if not async or offloaded. In that case its up to RH or Virtual Thread to close it (+ don't close if it's a partial request).
			if (request instanceof MalFormedHttpRequest || (!isAsyncOrOffloaded && request.isComplete())) {
				response.finish();
			}
		} catch (RuntimeException e) {
			// Routing (getHandler) or dispatcher pre-handler logic threw unexpectedly. The
			// handler-level catch lives in the dispatcher; this guards everything around it so
			// the client still gets a 500 and the channel is cleaned up rather than silently
			// dropped by the I/O loop. Reuse the (not-yet-flushed) response to avoid any
			// possibility of double-sending headers.
			logger.error("Unexpected exception while routing/dispatching request — sending 500", e);
			try {
				response.setStatusCode(500);
				response.setHeader("Connection", "close");
				response.setHeader("Content-Type", "text/plain; charset=utf-8");
				response.write("Internal Server Error");
				response.finish();
			} catch (Exception suppressed) {
				logger.debug("Failed to send 500 after routing/dispatch failure", suppressed);
				closeChannel(clientChannel);
			}
		}
	}

	@Override
	public void handleWrite(SelectionKey key) throws IOException {
		Object attachment = key.attachment();
		// Null-safe: evaluating attachment.getClass() directly would NPE (and that NPE would
		// escape into the I/O loop) if OP_WRITE ever fires with no attachment.
		logger.debug("handle write... attachment={}", attachment == null ? "null" : attachment.getClass().getSimpleName());
		SocketChannel channel = ((SocketChannel) key.channel());
		try {
			if (key.attachment() instanceof MappedByteBuffer) {
				logger.debug("mbb write #1");
				writeMappedByteBuffer(key, channel);
				logger.debug("mbb write #2");
			} else if (key.attachment() instanceof DynamicByteBuffer) {
				logger.debug("dbb write #1");
				writeDynamicByteBuffer(key, channel);
				logger.debug("dbb write #2");
			} else if (key.attachment() instanceof ByteBuffer) {
				logger.debug("bb write #1");
				writeByteBuffer(key, channel);
				logger.debug("bb write #2");
			}
		} catch (RuntimeException | IOException e) {
			// Failure in a write helper (broken pipe, or unexpected runtime error) — close via
			// closeChannel so all channel-state maps (partials, timeouts, WS handlers) AND the
			// active-connection accounting are cleaned up, rather than letting the IOLoop close
			// it generically (which would leak the connection count).
			logger.debug("Exception in handleWrite — closing channel: {}", e.getMessage());
			closeChannel(channel);
			return;
		}
		if (ioLoop.hasKeepAliveTimeout(channel)) {
			prolongKeepAliveTimeout(channel);
		}
	}

	public int write(SocketChannel channel, ByteBuffer src) throws IOException {
		SSLSessionHandler sslHandler = sslSessionHandlers.get(channel);
		if (sslHandler != null) {
			return writeSecurely(channel, src, sslHandler);
		} else {
			return channel.write(src);
		}
	}

	/**
	 * Maximum time we will keep trying to push bytes onto a non-blocking socket whose
	 * send buffer is full before declaring the peer dead and aborting the connection.
	 * Any forward progress resets the clock, so a slow-but-live client is never dropped;
	 * this only bounds the otherwise-infinite busy loop against a peer that has stopped
	 * reading entirely (which would peg the I/O-loop thread at 100% CPU forever).
	 */
	private static final long WRITE_STALL_TIMEOUT_NS = 10L * 1_000_000_000L;

	/**
	 * Fully writes {@code buf} to a non-blocking channel, tolerating short send-buffer-full
	 * stalls but aborting with an {@link IOException} if the socket makes zero progress for
	 * {@link #WRITE_STALL_TIMEOUT_NS}. Used by the SSL record writer and the small control
	 * writes (100-Continue, WebSocket Pong/close) that must be emitted in their entirety at
	 * the call site and therefore cannot defer to OP_WRITE.
	 */
	private static long writeFully(SocketChannel channel, ByteBuffer buf) throws IOException {
		long total = 0;
		long stallDeadline = 0;
		while (buf.hasRemaining()) {
			int n = channel.write(buf);
			if (n > 0) {
				total += n;
				stallDeadline = 0; // progress: reset the stall timer
			} else {
				long now = System.nanoTime();
				if (stallDeadline == 0) {
					stallDeadline = now + WRITE_STALL_TIMEOUT_NS;
				} else if (now - stallDeadline > 0) {
					throw new IOException("Socket write stalled (peer not reading); aborting connection");
				}
				// Park briefly rather than busy-spin: the loop is already committed to this
				// synchronous write, so this only avoids burning a core while the send buffer
				// is full (forward progress resets stallDeadline above).
				java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
			}
		}
		return total;
	}

	/**
	 * SSL-aware, fully-blocking write for control messages that must be flushed immediately
	 * (100-Continue, WebSocket frames). Routes through the TLS layer when enabled.
	 */
	public void writeBlocking(SocketChannel channel, ByteBuffer src) throws IOException {
		SSLSessionHandler sslHandler = sslSessionHandlers.get(channel);
		if (sslHandler != null) {
			writeFully(channel, sslHandler.wrap(src));
		} else {
			writeFully(channel, src);
		}
	}

	private int writeSecurely(SocketChannel channel, ByteBuffer src, SSLSessionHandler sslHandler) throws IOException {
		ByteBuffer encrypted = sslHandler.wrap(src);
		long written = writeFully(channel, encrypted);
		logger.debug("writeSecurely: wrote {} bytes to socket", written);
		return (int) written;
	}

	private long writeMappedByteBuffer(SelectionKey key, SocketChannel channel) throws IOException {
		long bytesWritten = 0;
		MappedByteBuffer mbb = (MappedByteBuffer) key.attachment();
		if (mbb.hasRemaining()) {
			int written = 0;
			do {
				written = write(channel, mbb);
				if (written > 0) {
					bytesWritten += written;
				}
			} while (written > 0 && mbb.hasRemaining());
		}
		logger.debug("sent {} bytes to wire", bytesWritten);
		if (!mbb.hasRemaining()) {
			registerForRead(key);
		} else {
			key.interestOps(SelectionKey.OP_WRITE);
		}
		return bytesWritten;
	}
	
	private long writeDynamicByteBuffer(SelectionKey key, SocketChannel channel) throws IOException {
		DynamicByteBuffer dbb = (DynamicByteBuffer) key.attachment();
		logger.debug("pending data about to be written");
		ByteBuffer toSend = dbb.getByteBuffer();
		if (!toSend.isReadOnly()) toSend.flip(); // prepare for write
		long bytesWritten = 0;
		if (toSend.hasRemaining()) {
			int written = 0;
			do {
				written = write(channel, toSend);
				if (written > 0) {
					bytesWritten += written;
				}
			} while (written > 0 && toSend.hasRemaining());
		}
		logger.debug("sent {} bytes to wire", bytesWritten);
		if (!toSend.hasRemaining()) {
			logger.debug("sent all data in toSend buffer");
			registerForRead(key); // should probably only be done if the HttpResponse is finished
		} else {
			if (!toSend.isReadOnly()) toSend.compact(); // make room for more data be "read" in
			key.interestOps(SelectionKey.OP_WRITE);
		}
		return bytesWritten;
	}
	
	private long writeByteBuffer(SelectionKey key, SocketChannel channel) throws IOException {
		ByteBuffer toSend = (ByteBuffer) key.attachment();
		logger.debug("pending data about to be written");
		if (!toSend.isReadOnly()) toSend.flip(); // prepare for write
		long bytesWritten = 0;
		if (toSend.hasRemaining()) {
			int written = 0;
			do {
				written = write(channel, toSend);
				if (written > 0) {
					bytesWritten += written;
				}
			} while (written > 0 && toSend.hasRemaining());
			logger.debug("sent {} bytes to wire", bytesWritten);
		}
		if (!toSend.hasRemaining()) {
			logger.debug("sent all data in toSend buffer");
			registerForRead(key); // should probably only be done if the HttpResponse is finished
		} else {
			if (!toSend.isReadOnly()) toSend.compact(); // make room for more data be "read" in
			key.interestOps(SelectionKey.OP_WRITE);
		}
		return bytesWritten;
	}

	public void registerForRead(SelectionKey key) throws IOException {
		if (key.isValid()) {
			if (ioLoop.hasKeepAliveTimeout(key.channel())) {
				key.channel().register(key.selector(), SelectionKey.OP_READ, reuseAttachment(key));
				prolongKeepAliveTimeout(key.channel());
				logger.debug("keep-alive connection. registrating for read.");
			} else {
				logger.debug("non-keep-alive connection. closing channel.");
				closeChannel((SocketChannel) key.channel());
			}
		}
	}
	
	public void prolongKeepAliveTimeout(SelectableChannel channel) {
		ioLoop.addKeepAliveTimeout(
			channel, 
			Timeout.newKeepAliveTimeout(ioLoop, channel, KEEP_ALIVE_TIMEOUT)
		);
	}
	
	public IOLoop getIOLoop() {
		return ioLoop;
	}
	
	/**
	 * Clears the buffer (prepares for reuse) attached to the given SelectionKey.
	 * @return A cleared (position=0, limit=capacity) ByteBuffer which is ready for new reads
	 */
	private ByteBuffer reuseAttachment(SelectionKey key) {
		Object o = key.attachment();
		ByteBuffer attachment = null;
		if (o instanceof MappedByteBuffer) {
			attachment = ByteBuffer.allocate(READ_BUFFER_SIZE);
		} else if (o instanceof DynamicByteBuffer) {
			attachment = ((DynamicByteBuffer) o).getByteBuffer();
		} else {
			attachment = (ByteBuffer) o;
		}
		// a read-only buffer is only meant for reads
		if (attachment.isReadOnly() || attachment.capacity() < READ_BUFFER_SIZE) {
			attachment = ByteBuffer.allocate(READ_BUFFER_SIZE);
		}
		attachment.clear(); // prepare for reuse
		return attachment;
	}

	//TODO: change this to work with the entire stream rather than individual calls (no guarantee the client sends their data wholly in discrete packets)
	private HttpRequest getHttpRequest(SelectionKey key, SocketChannel clientChannel) throws IOException {
		// Default for this call; only the plaintext completion path below sets it true. This must
		// be reset up front so the value from a previous call (possibly another channel) can't leak
		// through the SSL early-return paths into handleRead's keep-alive decision.
		pipelinedDataPending = false;
		ByteBuffer buffer = (ByteBuffer) key.attachment();
		if (!buffer.hasRemaining()) throw new IllegalStateException("Cleared channel buffer has no remaining space.");
		SSLSessionHandler sslHandler = sslSessionHandlers.get(clientChannel);
		if (sslHandler != null) {
			// Use the actual TLS packet buffer size (up to ~16 KB) — not the tiny READ_BUFFER_SIZE
			int pktSize = sslHandler.getPacketBufferSize();
			ByteBuffer encrypted = ByteBuffer.allocate(pktSize);
			int read;
			try {
				read = clientChannel.read(encrypted);
				if (read == -1) {
					closeChannel(clientChannel);
					return null;
				}
			} catch (IOException e) {
				logger.debug("IOException during SSL channel read (likely client disconnect): {}", e.getMessage());
				closeChannel(clientChannel);
				return null;
			}
			encrypted.flip();
			ByteBuffer plaintext;
			try {
				plaintext = sslHandler.unwrap(encrypted);
			} catch (SSLConnectionClosedException e) {
				logger.debug("TLS close_notify from {}: closing channel", clientChannel.getRemoteAddress());
				sslHandler.closeQuietly();
				sslSessionHandlers.remove(clientChannel);
				closeChannel(clientChannel);
				return null;
			}
			if (!plaintext.hasRemaining()) {
				// No application data yet (could be a renegotiation record, etc.)
				return null;
			}
			// If we're in the middle of receiving a body, feed directly to the partial request
			// rather than going through the small key-attachment buffer.
			HttpRequest partial = partials.get(clientChannel);
			if (partial != null) {
				logger.debug("SSL partial body: feeding {} plaintext bytes directly to partial request", plaintext.remaining());
				if (partial.putContentData(true, plaintext)) {
					partials.remove(clientChannel);
					logger.debug("SSL partial body complete");
				} else {
					key.interestOps(SelectionKey.OP_READ);
				}
				return partial;
			}
			// Initial read (headers + possibly the start of the body).
			// Accumulate into the key attachment buffer.
			if (plaintext.remaining() > buffer.remaining()) {
				// Plaintext larger than the attachment buffer — grow it
				ByteBuffer grown = ByteBuffer.allocate(plaintext.remaining() + buffer.position());
				buffer.flip();
				grown.put(buffer);
				grown.put(plaintext);
				// Swap the grown buffer into the key as the new attachment
				key.attach(grown);
				buffer = grown;
			} else {
				buffer.put(plaintext);
			}
		} else {
			try {
				int read;
				do {
					read = clientChannel.read(buffer);
					if (read == -1) {
						closeChannel(clientChannel);
						return null;
					}
				} while (read > 0 && buffer.hasRemaining());
			} catch (IOException e) {
				logger.debug("IOException during channel read (likely client disconnect): {}", e.getMessage());
				closeChannel(clientChannel);
				return null;
			}
			// The default read buffer is small (READ_BUFFER_SIZE). If it filled before a full
			// header block arrived, grow it (up to MAX_HEADER_BUFFER_SIZE) so large-but-legal
			// requests — long URLs, big Cookie/Authorization headers — aren't truncated into an
			// IllegalStateException. Exceeding the cap is answered with 431. Header phase only.
			if (!buffer.hasRemaining() && !partials.containsKey(clientChannel)) {
				ByteBuffer scan = buffer.duplicate();
				scan.flip();
				if (!HttpRequest.findInBB(scan, HttpRequest.HTTP_HEAD_TERM_BYTES)) {
					if (buffer.capacity() >= MAX_HEADER_BUFFER_SIZE) {
						throw new HttpException(431, "Request Header Fields Too Large",
							"The request header section exceeds the maximum permitted size");
					}
					int newCap = Math.min(buffer.capacity() * 2, MAX_HEADER_BUFFER_SIZE);
					ByteBuffer grown = ByteBuffer.allocate(newCap);
					buffer.flip();
					grown.put(buffer);
					key.attach(grown);
					key.interestOps(SelectionKey.OP_READ);
					return null; // resume reading the header block into the larger buffer
				}
			}
		}
		// Unified header-size guard. The plaintext path grows/caps its buffer above; the TLS
		// path grows its buffer to fit each decrypted record without an intrinsic bound, so a
		// client that streams headers and never sends the CRLFCRLF terminator could grow it
		// without limit (OOM). Once we're past MAX_HEADER_BUFFER_SIZE with still no terminator
		// and no body in progress, reject with 431. (For the plaintext path this is unreachable
		// — it already 431s when the capped buffer fills.)
		if (buffer.position() > MAX_HEADER_BUFFER_SIZE && !partials.containsKey(clientChannel)) {
			ByteBuffer scan = buffer.duplicate();
			scan.flip();
			if (!HttpRequest.findInBB(scan, HttpRequest.HTTP_HEAD_TERM_BYTES)) {
				throw new HttpException(431, "Request Header Fields Too Large",
					"The request header section exceeds the maximum permitted size");
			}
		}
		buffer.flip();
		logger.debug("getHttpRequest buffer remaining: {}", buffer.remaining());
		HttpRequest req = doGetHttpRequest(key, clientChannel, buffer);
		if (req == null) buffer.rewind();
		buffer.compact(); // allow for more data to be read in
		// Detect a pipelined follow-up request (bytes left in the buffer after a complete request).
		// We don't pipeline, so flag it: the response will close the connection rather than reset
		// the buffer (discarding the bytes) and leaving the client hung waiting for that response.
		pipelinedDataPending = req != null && req.isComplete() && buffer.position() > 0;
		return req;
	}
	
	private HttpRequest doGetHttpRequest(SelectionKey key, SocketChannel clientChannel, ByteBuffer buffer) throws IOException {
		if (logger.isTraceEnabled()) {
			byte[] arr = new byte[buffer.remaining()];
			buffer.duplicate().get(arr);
			logger.trace("Buffer received: [{}]", new String(arr, java.nio.charset.StandardCharsets.ISO_8859_1));
		}
		//do we have any unfinished http post requests for this channel?
		HttpRequest request = partials.get(clientChannel);
		if (request != null) {
			logger.debug("continuing to parse partial http request - http req #{}, remaining: {}", request.getRequestNum(), request.getRemaining());
			if (request.putContentData(true, buffer)) {	// if received the entire payload/body
				logger.debug("entire partial http request received, removing - http req #{}, remaining: {}, flipremain: {}", request.getRequestNum(), request.getRemaining(), request.getFlipRemain());
				partials.remove(clientChannel);
			} else {
				key.interestOps(SelectionKey.OP_READ);
			}
		} else {
			try {
				if (!HttpRequest.findInBB(buffer.duplicate(), HttpRequest.HTTP_HEAD_TERM_BYTES)) {
					key.interestOps(SelectionKey.OP_READ);
					return null;
				}
				request = HttpRequest.of(application, buffer);
				if (!request.isComplete()) {
					String expect = request.getHeader("Expect");
					// Only HTTP/1.1 clients understand a 100 Continue interim response (RFC 9110
					// §10.1.1 — a server MUST NOT send it to an HTTP/1.0 client).
					if (expect != null && expect.equalsIgnoreCase("100-continue") && "HTTP/1.1".equals(request.getVersion())) {
						int clen = request.getContentLength();
						if (clen > HttpRequest.MAX_BODY_SIZE) {
							throw new HttpException(413, "Payload Too Large", "Payload size exceeds maximum allowed limit");
						}
						ByteBuffer continueResponse = ByteBuffer.wrap("HTTP/1.1 100 Continue\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
						writeBlocking(clientChannel, continueResponse);
						logger.debug("Sent HTTP/1.1 100 Continue early response");
					}
					logger.debug("adding partial http request - http req #{}, remaining: {}", request.getRequestNum(), request.getRemaining());
					partials.put(key.channel(), request);
					key.interestOps(SelectionKey.OP_READ);
				} else {
					logger.debug("normal HttpRequest");
				}
			} catch (IOException | IllegalArgumentException e) {
				request = MalFormedHttpRequest.instance;
				logger.debug("malformed HttpRequest", e);
			}
		}
		if (request == null) return null;
		//set extra request info
		request.setRemoteHost(clientChannel.socket().getInetAddress());
		request.setRemotePort(clientChannel.socket().getPort());
		request.setServerHost(clientChannel.socket().getLocalAddress());
		request.setServerPort(clientChannel.socket().getLocalPort());
		return request;
	}
	
	private void handleWebSocketRead(SelectionKey key, SocketChannel clientChannel, WebSocketHandler wsHandler) throws IOException {
		WebSocketConnection wsConn = websocketConnections.get(clientChannel);
		ByteBuffer buffer = (ByteBuffer) key.attachment();
		if (buffer == null) {
			buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
			key.attach(buffer);
		}
		int bytesRead;
		try {
			// Decrypt if SSL active
			SSLSessionHandler sslHandler = sslSessionHandlers.get(clientChannel);
			if (sslHandler != null) {
				// Read into netReadBuf and unwrap
				ByteBuffer netReadBuf = ByteBuffer.allocate(READ_BUFFER_SIZE);
				int read = clientChannel.read(netReadBuf);
				if (read == -1) {
					bytesRead = -1;
				} else if (read == 0) {
					bytesRead = 0;
				} else {
					netReadBuf.flip();
					ByteBuffer appReadBuf = sslHandler.unwrap(netReadBuf);
					appReadBuf.flip();
					if (appReadBuf.remaining() > buffer.remaining()) {
						buffer.flip();
						ByteBuffer temp = ByteBuffer.allocate(appReadBuf.remaining() + buffer.capacity());
						temp.put(buffer);
						buffer = temp;
						key.attach(buffer);
					}
					bytesRead = appReadBuf.remaining();
					buffer.put(appReadBuf);
				}
			} else {
				bytesRead = clientChannel.read(buffer);
			}
		} catch (IOException e) {
			bytesRead = -1;
		}
		
		if (bytesRead == -1) {
			if (wsConn != null) {
				wsHandler.onClose(wsConn);
			}
			closeChannel(clientChannel);
			return;
		}
		
		if (bytesRead == 0) {
			return;
		}
		
		buffer.flip();
		
		while (buffer.remaining() >= 2) {
			buffer.mark();
			int b0 = buffer.get() & 0xFF;
			int b1 = buffer.get() & 0xFF;
			
			boolean fin = (b0 & 0x80) != 0;
			int opcode = b0 & 0x0F;
			boolean masked = (b1 & 0x80) != 0;
			long payloadLen = b1 & 0x7F;
			
			int needed = 0;
			if (payloadLen == 126) needed += 2;
			else if (payloadLen == 127) needed += 8;
			if (masked) needed += 4;
			
			if (buffer.remaining() < needed) {
				buffer.reset();
				break; // not enough bytes for headers
			}
			
			long actualLen = payloadLen;
			if (payloadLen == 126) {
				actualLen = buffer.getShort() & 0xFFFF;
			} else if (payloadLen == 127) {
				actualLen = buffer.getLong();
			}

			// RFC 6455: a 64-bit length must have its high bit clear, and we cap frame size
			// to avoid OOM / NegativeArraySizeException from a malicious or corrupt length.
			if (actualLen < 0 || actualLen > MAX_WS_FRAME_PAYLOAD) {
				logger.warn("WebSocket frame payload length {} invalid/too large — closing channel", actualLen);
				if (wsConn != null) {
					try { wsHandler.onClose(wsConn); } catch (RuntimeException ignore) {}
				}
				closeChannel(clientChannel);
				return;
			}
			// RFC 6455 §5.1: frames from the client MUST be masked; otherwise fail the connection.
			if (!masked) {
				logger.warn("WebSocket client frame not masked — closing channel per RFC 6455");
				if (wsConn != null) {
					try { wsHandler.onClose(wsConn); } catch (RuntimeException ignore) {}
				}
				closeChannel(clientChannel);
				return;
			}

			byte[] maskingKey = null;
			if (masked) {
				maskingKey = new byte[4];
				buffer.get(maskingKey);
			}

			if (buffer.remaining() < actualLen) {
				buffer.reset();
				// The whole frame must fit in the buffer before we can decode it. The default
				// read buffer is small (READ_BUFFER_SIZE); a frame larger than the current
				// capacity would otherwise never fit and the connection would stall. Grow the
				// buffer (in read mode) to hold the full frame — bounded by MAX_WS_FRAME_PAYLOAD.
				long requiredCapacity = actualLen + 14; // payload + max frame header
				if (buffer.capacity() < requiredCapacity) {
					ByteBuffer bigger = ByteBuffer.allocate((int) requiredCapacity);
					bigger.put(buffer);   // copy the unparsed [mark, limit) bytes
					bigger.flip();        // back to read mode for the trailing compact()
					buffer = bigger;
					key.attach(buffer);
				}
				break; // entire payload not yet received
			}

			byte[] payload = new byte[(int) actualLen];
			buffer.get(payload);
			
			if (masked && maskingKey != null) {
				for (int i = 0; i < payload.length; i++) {
					payload[i] = (byte) (payload[i] ^ maskingKey[i % 4]);
				}
			}
			
			if (opcode == 0x8) { // Close frame
				// RFC 6455 §5.5.1: respond with a Close frame (echoing the status code if the
				// peer sent one) to complete the closing handshake, then tear down.
				try {
					int code = (payload.length >= 2) ? (((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)) : 1000;
					ByteBuffer closeFrame = ByteBuffer.allocate(4);
					closeFrame.put((byte) 0x88);
					closeFrame.put((byte) 2);
					closeFrame.putShort((short) code);
					closeFrame.flip();
					writeBlocking(clientChannel, closeFrame);
				} catch (IOException ignore) {
					// peer already gone; proceed to tear down
				}
				if (wsConn != null) {
					try {
						wsHandler.onClose(wsConn);
					} catch (RuntimeException e) {
						logger.error("Uncaught exception in WebSocket onClose handler — closing channel", e);
					}
				}
				closeChannel(clientChannel);
				return;
			} else if (opcode == 0x9) { // Ping
				// Send Pong frame (control frames carry at most 125 bytes of payload)
				int pongLen = Math.min(payload.length, 125);
				ByteBuffer pong = ByteBuffer.allocate(2 + pongLen);
				pong.put((byte) 0x8A); // Pong opcode
				pong.put((byte) pongLen);
				pong.put(payload, 0, pongLen);
				pong.flip();
				writeBlocking(clientChannel, pong);
			} else if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) { // Text, Binary or Continuation
				// Reassemble fragmented messages: a data frame with FIN=0 begins a message that
				// is continued by opcode-0x0 frames until one arrives with FIN=1. Control frames
				// may be interleaved (handled above) and do not affect this state.
				java.io.ByteArrayOutputStream msgBuf = websocketFragments.get(clientChannel);
				if (opcode != 0x0) {
					// Start of a new data message. A new data frame while one is still in
					// progress is a protocol violation.
					if (msgBuf != null) {
						logger.warn("WebSocket: new data frame while a fragmented message is in progress — closing");
						closeChannel(clientChannel);
						return;
					}
					msgBuf = new java.io.ByteArrayOutputStream();
				} else if (msgBuf == null) {
					// Continuation frame with no message in progress — protocol violation.
					logger.warn("WebSocket: continuation frame without a started message — closing");
					closeChannel(clientChannel);
					return;
				}
				if ((long) msgBuf.size() + payload.length > MAX_WS_FRAME_PAYLOAD) {
					logger.warn("WebSocket: reassembled message exceeds limit — closing");
					closeChannel(clientChannel);
					return;
				}
				msgBuf.write(payload, 0, payload.length);
				if (fin) {
					String msg = new String(msgBuf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
					websocketFragments.remove(clientChannel);
					if (wsConn != null) {
						try {
							wsHandler.onMessage(wsConn, msg);
						} catch (RuntimeException e) {
							logger.error("Uncaught exception in WebSocket onMessage handler — closing channel", e);
							closeChannel(clientChannel);
							return;
						}
					}
				} else {
					// More fragments to come; stash the in-progress buffer.
					websocketFragments.put(clientChannel, msgBuf);
				}
			}
		}
		buffer.compact();
	}
	
	@Override
	public String toString() { return "HttpProtocol"; }
}
