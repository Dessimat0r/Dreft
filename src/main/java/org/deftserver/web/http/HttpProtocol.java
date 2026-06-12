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
import java.util.HashMap;
import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;

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
	private final Map<SelectableChannel, HttpRequest> partials = new ConcurrentHashMap<>();
	private final Map<SocketChannel, ByteBuffer> readBuffers = new ConcurrentHashMap<>();
	// Reusable scratch buffer for reading TLS ciphertext off the socket — avoids allocating ~16 KiB on
	// every HTTPS read. Loop-thread-only (this HttpProtocol's reactor); grown if a session needs more.
	private ByteBuffer sslReadScratch;

	private javax.net.ssl.SSLContext sslContext;
	private final Map<SocketChannel, SSLSessionHandler> sslSessionHandlers = new ConcurrentHashMap<>();
	private final Map<SocketChannel, org.deftserver.web.http.http2.Http2Connection> http2Connections = new ConcurrentHashMap<>();

	private final Map<SocketChannel, WebSocketHandler> websocketHandlers = new ConcurrentHashMap<>();
	private final Map<SocketChannel, WebSocketConnection> websocketConnections = new ConcurrentHashMap<>();
	// Accumulates the payload of a fragmented WebSocket message (data frame with FIN=0 followed
	// by continuation frames) until the final fragment arrives.
	private final Map<SocketChannel, java.io.ByteArrayOutputStream> websocketFragments = new ConcurrentHashMap<>();
	// Whether the in-progress WebSocket data message is text (opcode 0x1) — text messages are
	// validated as UTF-8 at completion (RFC 6455 §8.1).
	private final Map<SocketChannel, Boolean> websocketTextMessage = new ConcurrentHashMap<>();

	private final Map<SocketChannel, Timeout> headerReadTimeouts = new ConcurrentHashMap<>();
	private final Map<SocketChannel, Timeout> bodyReadTimeouts = new ConcurrentHashMap<>();
	// Bounds the time an ASYNC/offloaded handler may take to finish its response (it runs off the
	// loop, so the loop can't otherwise notice it hanging). Armed when dispatch reports async,
	// cancelled when the response finishes. Distinct from the keep-alive (idle) timeout so a long
	// async handler isn't cut off by the idle timer, and a hung async handler on a Connection: close
	// connection (which has no keep-alive timeout) is still bounded rather than leaking forever.
	private final Map<SocketChannel, Timeout> processingTimeouts = new ConcurrentHashMap<>();
	// Idle response-write timeout for a DEFERRED (non-blocking TLS) write: a client that stops reading
	// the response keeps the encrypted pendingWriteBuffer (and the connection) alive indefinitely.
	// Since the write no longer blocks the loop (it's deferred to OP_WRITE), nothing else reaps it —
	// so without this a slow/stalled response-reader is a memory/connection DoS (esp. with no
	// max-connections cap). Reset on each OP_WRITE (= the peer drained some bytes = progress); fires
	// (closes the channel) after RESPONSE_WRITE_TIMEOUT_MS of NO progress. Cancelled when the write
	// completes or the channel closes.
	private final Map<SocketChannel, Timeout> writeTimeouts = new ConcurrentHashMap<>();
	/** How long an async/offloaded handler may take to finish its response before the connection is
	 *  closed (anti-hang). Non-final so it can be tuned (raise it for legitimately long requests). */
	public static volatile long REQUEST_PROCESSING_TIMEOUT_MS = 60_000;
	/** Idle timeout for an established WebSocket connection — longer than the HTTP keep-alive idle
	 *  timeout because WebSockets are long-lived and may legitimately sit quiet between messages.
	 *  Reset on every frame; on expiry the (presumed dead) connection is closed. Tunable. */
	public static volatile long WEBSOCKET_IDLE_TIMEOUT_MS = 300_000; // 5 min
	// Channels whose current response declared Connection: close but whose body write was deferred
	// to OP_WRITE (didn't fit the socket buffer). When that deferred write finally completes, the
	// channel must be closed — NOT re-registered for read — so the server honours its own header.
	private final java.util.Set<SocketChannel> closeAfterWrite = ConcurrentHashMap.newKeySet();

	/** Maximum number of requests served on a single keep-alive connection before the server
	 *  gracefully closes it (forces a fresh connection). Bounds per-connection resource use and
	 *  connection monopolisation (cf. nginx keepalive_requests / Apache MaxKeepAliveRequests).
	 *  Non-final so tests can lower it. */
	public static volatile int MAX_KEEP_ALIVE_REQUESTS = 1000;
	private final Map<SocketChannel, Integer> keepAliveRequestCount = new ConcurrentHashMap<>();
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
	 *  the resources a slow/stalled body upload can hold (Slowloris-on-body). Non-final so tests
	 *  can shorten it. */
	public static volatile long BODY_READ_TIMEOUT_MS = 30_000;

	/** How long a client may take to send its complete request-header section before the
	 *  connection is timed out (Slowloris-on-headers). Non-final so tests can shorten it. */
	public static volatile long HEADER_READ_TIMEOUT_MS = 5000;

	/** Largest the (plaintext) header read buffer may grow to while accumulating a request's
	 *  header block; beyond this the request is answered with 431. Matches the header-section
	 *  cap enforced in {@link HttpRequest}. */
	private static final int MAX_HEADER_BUFFER_SIZE = 64 * 1024;

	/** Per-channel state for serving a file larger than a single mmap window. */
	private final Map<SocketChannel, FileStreamState> fileStreams = new ConcurrentHashMap<>();
	/** Window size for chunked serving of large files. A single {@code FileChannel.map()} is capped
	 *  at {@code Integer.MAX_VALUE}, so files/ranges larger than this are served window-by-window.
	 *  Tunable (and lowerable by tests to exercise the chunked path with a small file). */
	public static volatile int FILE_WINDOW_SIZE = 256 * 1024 * 1024;

	private static final class FileStreamState {
		final java.io.RandomAccessFile raf;
		final java.nio.channels.FileChannel fc;
		long pos;        // next file byte to map
		final long end;  // exclusive end offset
		FileStreamState(java.io.RandomAccessFile raf, java.nio.channels.FileChannel fc, long pos, long end) {
			this.raf = raf; this.fc = fc; this.pos = pos; this.end = end;
		}
	}

	private int maxConnectionsPerIp = -1;

	/** Caps the total simultaneous connections this protocol instance will accept ({@code <= 0} disables). */
	public void setMaxConnections(int max) {
		this.maxConnections = max;
	}

	/** Caps simultaneous connections from a single remote IP (<= 0 disables). Prevents one client
	 *  from exhausting all connection slots, which the global limit alone does not. */
	public void setMaxConnectionsPerIp(int max) {
		this.maxConnectionsPerIp = max;
	}

	/** Current number of open client connections tracked by this protocol instance. */
	public int getActiveConnections() {
		return activeChannels.size();
	}

	/**
	 * Best-effort 408 Request Timeout before abandoning a client that was too slow sending its
	 * request line/headers/body (RFC 9110 §15.5.9 — a server SHOULD send 408 with Connection:
	 * close). Deliberately a SINGLE non-blocking write: this runs on the I/O-loop thread inside a
	 * timeout callback, so a blocking/stalling write would freeze every other connection. If the
	 * socket buffer can't take the ~70-byte response we simply close — the client is already gone.
	 * Skipped for TLS channels, where emitting a valid record (possibly mid-handshake) isn't cheap.
	 */
	private void sendRequestTimeoutAndClose(SocketChannel channel) {
		try {
			if (channel != null && channel.isOpen() && !sslSessionHandlers.containsKey(channel)) {
				ByteBuffer resp = ByteBuffer.wrap(
					("HTTP/1.1 408 Request Timeout\r\n"
						+ "Date: " + org.deftserver.util.DateUtil.getCurrentAsString() + "\r\n"
						+ "Connection: close\r\nContent-Length: 0\r\n\r\n")
						.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
				channel.write(resp);
			}
		} catch (IOException | RuntimeException e) {
			logger.debug("Best-effort 408 write failed for {}: {}", channel, e.toString());
		} finally {
			closeChannel(channel);
		}
	}

	/** The single, idempotent teardown point for a connection: fires the WebSocket {@code onClose}
	 *  exactly once, releases every per-channel resource (SSL handler, partials, WS state, file
	 *  streams, timeouts, close-after-write/keep-alive-count entries), drops it from the
	 *  active-connection set, and closes the socket. All close paths must route through here so
	 *  nothing leaks. */
	public void closeChannel(SocketChannel channel) {
		if (channel != null) {
			sslSessionHandlers.remove(channel);
			http2Connections.remove(channel);
			// Notify a WebSocket app of the close exactly once, here, so onClose fires for EVERY
			// teardown path (read EOF, close frame, write error, idle/limit close, IOLoop generic
			// close) rather than only the few that called it explicitly. remove() returns the
			// handler iff this is the first close for the channel, so there's no double-delivery.
			WebSocketHandler wsHandler = websocketHandlers.remove(channel);
			WebSocketConnection wsConn = websocketConnections.remove(channel);
			if (wsHandler != null) {
				try {
					wsHandler.onClose(wsConn);
				} catch (RuntimeException e) {
					logger.error("Uncaught exception in WebSocket onClose handler", e);
				}
			}
			websocketFragments.remove(channel);
			websocketTextMessage.remove(channel);
			closeAfterWrite.remove(channel);
			keepAliveRequestCount.remove(channel);
			Timeout pt = processingTimeouts.remove(channel);
			if (pt != null) pt.cancel();
			Timeout wt = writeTimeouts.remove(channel);
			if (wt != null) wt.cancel();
			closeFileStream(channel); // release any in-progress large-file handle
			partials.remove(channel);
			readBuffers.remove(channel);
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

	/** IOLoop callback when a channel is torn down outside our own paths (I/O error, cancelled key);
	 *  routes through {@link #closeChannel} so per-channel state is released. */
	@Override
	public void onClose(SelectableChannel channel) {
		// The IOLoop closed this channel outside our own code paths (an I/O exception, a cancelled
		// key, etc.). Release the per-channel state we keep so it doesn't leak. closeChannel is
		// idempotent and the channel is already closing, so it just clears the maps.
		if (channel instanceof SocketChannel) {
			closeChannel((SocketChannel) channel);
		}
	}

	/** Marks a channel as an established WebSocket connection (registers its handler/connection so
	 *  subsequent reads are treated as WS frames). */
	public void upgradeToWebSocket(SocketChannel channel, WebSocketHandler handler, WebSocketConnection connection) {
		websocketHandlers.put(channel, handler);
		websocketConnections.put(channel, connection);
		// Switch from the short HTTP keep-alive idle timeout to the longer WebSocket idle timeout, so a
		// quiet-but-alive WebSocket isn't reaped after ~30 s.
		prolongWebSocketIdleTimeout(channel);
	}

	/** Enables TLS for connections handled by this protocol instance. */
	public void enableSSL(javax.net.ssl.SSLContext sslContext) {
		this.sslContext = sslContext;
	}

	/** True if TLS is enabled for this protocol instance. */
	public boolean isSSLEnabled() {
		return sslContext != null;
	}

	/** Creates a protocol bound to the singleton {@link IOLoop#INSTANCE}. */
	public HttpProtocol(Application app) {
		this(IOLoop.INSTANCE, app);
	}

	/** Creates a protocol bound to a specific I/O loop (multi-loop mode), serving the given application. */
	public HttpProtocol(IOLoop ioLoop, Application app) {
		this.ioLoop = ioLoop;
		application = app;
	}

	/** Accepts a new connection (honouring the global and per-IP connection caps), arms the
	 *  header-read (Slowloris) timeout, registers it for reads, and begins the TLS handshake if HTTPS. */
	@Override
	public void handleAccept(SelectionKey key) throws IOException {
		logger.debug("handle accept... isSSLEnabled={}", isSSLEnabled());
		SocketChannel clientChannel = ((ServerSocketChannel) key.channel()).accept();
		if (clientChannel == null) {
			return; // another loop won the race (multi-reactor), or nothing to accept
		}
		// CRITICAL: isolate ALL per-connection setup. If anything here throws (most importantly the
		// initial TLS handshake, which an immediate-garbage client can make throw an SSLException or
		// RuntimeException), it must NOT propagate out of handleAccept — the IOLoop's accept-error
		// path closes key.channel(), which for an OP_ACCEPT key is the shared *listening*
		// ServerSocketChannel. A single hostile connection would otherwise close the listening socket
		// and stop the whole server from accepting (a trivial remote DoS). Close only this client.
		try {
			boolean isSsl = Boolean.TRUE.equals(key.attachment());
			setUpAcceptedConnection(clientChannel, isSsl);
		} catch (IOException | RuntimeException e) {
			logger.debug("Failed to set up accepted connection {} — closing it (not the listener): {}",
				clientChannel, e.toString());
			closeChannel(clientChannel);
		}
	}

	/** Per-connection setup for a freshly-accepted channel: connection-limit checks, non-blocking
	 *  config, read registration, the Slowloris header timeout, and (for HTTPS) the TLS handshake.
	 *  Any failure here is contained by {@link #handleAccept} so it never closes the listening socket. */
	private void setUpAcceptedConnection(SocketChannel clientChannel, boolean isSslConnection) throws IOException {
		{
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
			if (maxConnectionsPerIp > 0 && !isUnixSocket(clientChannel)) {
				java.net.InetAddress ip = clientChannel.socket().getInetAddress();
				if (ip != null) {
					activeChannels.removeIf(ch -> !ch.isOpen()); // self-heal stale entries first
					int sameIp = 0;
					for (SocketChannel ch : activeChannels) {
						if (!isUnixSocket(ch)) {
							java.net.InetAddress chIp = ch.socket().getInetAddress();
							if (ip.equals(chIp)) {
								sameIp++;
							}
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
			if (clientChannel.supportedOptions().contains(StandardSocketOptions.TCP_NODELAY)) {
				clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
			}
			ioLoop.addHandler(clientChannel, this, SelectionKey.OP_READ, ByteBuffer.allocate(READ_BUFFER_SIZE));
			activeChannels.add(clientChannel);
			
			// Anti-DoS Header Timeout
			Timeout headerTimeout = new Timeout(
				System.currentTimeMillis() + HEADER_READ_TIMEOUT_MS,
				new AsyncCallback() {
					@Override
					public void onCallback() {
						logger.warn("Anti-DoS: Header read timeout for channel {}", clientChannel);
						sendRequestTimeoutAndClose(clientChannel);
					}
				}
			);
			headerReadTimeouts.put(clientChannel, headerTimeout);
			ioLoop.addTimeout(headerTimeout);
			if (isSslConnection) {
				logger.debug("SSL enabled: instantiating SSLSessionHandler");
				SSLSessionHandler handler = new SSLSessionHandler(clientChannel, sslContext, ioLoop);
				sslSessionHandlers.put(clientChannel, handler);
				handler.handshake();
			}
		}
	}
	
	/** Not used server-side (OP_CONNECT is a client-side concern); logged if ever invoked. */
	@Override
	public void handleConnect(SelectionKey key) throws IOException {
		logger.error("handle connect in HttpProtocol...");
	}

	/**
	 * The main read entry point. Routes WebSocket frames to {@link #handleWebSocketRead}, otherwise
	 * reads and parses an HTTP request (returning early while it is incomplete), then decides keep-alive
	 * vs close, builds the {@link HttpResponse}, routes to a handler and dispatches it. Parse failures
	 * become the appropriate 4xx/5xx; nothing is allowed to escape and kill the loop.
	 */
	@Override
	public void handleRead(SelectionKey key) throws IOException {
		logger.debug("handle read... key: {}", key);
		SocketChannel clientChannel = (SocketChannel) key.channel();
		
		org.deftserver.web.http.http2.Http2Connection http2Conn = http2Connections.get(clientChannel);
		if (http2Conn != null) {
			prolongKeepAliveTimeout(clientChannel);
			http2Conn.onReadable();
			return;
		}
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
			} catch (IOException | RuntimeException e) {
				// Failed handshake (garbage, or an engine RuntimeException like "Unexpected handshake
				// status"). Tear the connection down via closeChannel — the single cleanup point that
				// clears sslSessionHandlers, activeChannels, the header/body timeouts AND the IOLoop
				// handler entry — rather than the partial closeQuietly()+remove() that leaked those
				// until the header timeout self-healed.
				logger.debug("SSL handshake failed for {} — closing: {}", clientChannel, e.toString());
				closeChannel(clientChannel);
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
				response.setHeader("X-Content-Type-Options", "nosniff");
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
				response.setHeader("X-Content-Type-Options", "nosniff");
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
							sendRequestTimeoutAndClose(bodyChannel);
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
		key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
		if (logger.isDebugEnabled()) {
			logger.debug("handle read 3..., req class: {}, req: {}", request.getClass(), request);
		}
		
		final boolean keepAlive;

		if (!(request instanceof MalFormedHttpRequest) && request.isKeepAlive()) {
			// Cap the number of requests per keep-alive connection: once the limit is reached, serve
			// this request but signal close (Connection: Close, set by HttpResponse for !keepAlive)
			// so the client reconnects — bounding per-connection resource use / monopolisation.
			int count = keepAliveRequestCount.merge(clientChannel, 1, Integer::sum);
			if (count >= MAX_KEEP_ALIVE_REQUESTS) {
				keepAlive = false;
			} else {
				keepAlive = true;
				ioLoop.addKeepAliveTimeout(clientChannel, newKeepAliveTimeout(clientChannel));
			}
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
			} else if (isAsyncOrOffloaded && request.isComplete() && !(request instanceof MalFormedHttpRequest)
					&& !response.hasFinished()) {
				// The response is still pending — it will be finished later, off the loop. Bound that
				// with a processing timeout so a hung/over-long async handler can't hold the connection
				// forever (the idle timer is extended/replaced meanwhile so a legitimately long async
				// handler isn't cut off; registerForRead restores it on completion). Skipped when the
				// "async" handler already finished synchronously during dispatch — notably a WebSocket
				// upgrade, which sends its 101 inline and then runs under the WebSocket idle timeout.
				armProcessingTimeout(clientChannel);
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
				response.setHeader("X-Content-Type-Options", "nosniff");
				response.write("Internal Server Error");
				response.finish();
			} catch (Exception suppressed) {
				logger.debug("Failed to send 500 after routing/dispatch failure", suppressed);
				closeChannel(clientChannel);
			}
		}
	}

	/** OP_WRITE callback: resumes a deferred write by dispatching on the attachment type (mapped file
	 *  window, dynamic buffer, or plain buffer); also services any pending TLS handshake write that
	 *  was deferred by {@link SSLSessionHandler#onWritable}. A failure closes the channel via
	 *  {@link #closeChannel}. */
	@Override
	public void handleWrite(SelectionKey key) throws IOException {
		SocketChannel channel = ((SocketChannel) key.channel());
		org.deftserver.web.http.http2.Http2Connection http2Conn = http2Connections.get(channel);
		if (http2Conn != null) {
			prolongKeepAliveTimeout(channel);
			http2Conn.onWritable();
			return;
		}
		Object attachment = key.attachment();
		logger.debug("handle write... attachment={}", attachment == null ? "null" : attachment.getClass().getSimpleName());

		SSLSessionHandler sslHandler = sslSessionHandlers.get(channel);
		if (sslHandler != null && sslHandler.hasPendingWrite()) {
			// OP_WRITE fired ⇒ the socket made room ⇒ the peer drained some bytes ⇒ progress: reset
			// the idle response-write timeout so a steadily-reading client isn't reaped (only a
			// no-progress / stalled reader is).
			prolongWriteTimeout(channel);
			try {
				sslHandler.onWritable();
			} catch (IOException e) {
				logger.debug("SSL pending write failed — closing channel: {}", e.getMessage());
				closeChannel(channel);
				return;
			}
			if (!sslHandler.hasPendingWrite()) {
				cancelWriteTimeout(channel); // deferred write drained
				if (attachment == null) {
					key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
				}
			}
			// Fall through to service any concurrently pending response-body write (attachment).
		}

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

	public SSLSessionHandler getSslSessionHandler(SocketChannel channel) {
		return sslSessionHandlers.get(channel);
	}

	/** Writes from {@code src} to the channel, transparently encrypting via the TLS engine if this is
	 *  an HTTPS connection; otherwise a direct non-blocking socket write. Returns the byte count. */
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
		// Absolute deadline: bounds the TOTAL time this synchronous write may hold the I/O-loop thread,
		// independent of progress. The zero-progress stall timer below only catches a fully-stalled
		// peer; a "drip" reader that reads a byte just often enough keeps resetting it and could pin
		// the loop indefinitely (a slow-read DoS that blocks every other connection). This caps it.
		final long absoluteDeadline = System.nanoTime()
			+ HttpServerDescriptor.RESPONSE_WRITE_TIMEOUT_MS * 1_000_000L;
		while (buf.hasRemaining()) {
			int n = channel.write(buf);
			if (n > 0) {
				total += n;
				stallDeadline = 0; // progress: reset the stall timer
				if (System.nanoTime() - absoluteDeadline > 0) {
					throw new IOException("Response write exceeded the write-timeout (slow reader); aborting connection");
				}
			} else {
				long now = System.nanoTime();
				if (now - absoluteDeadline > 0) {
					throw new IOException("Response write exceeded the write-timeout (slow reader); aborting connection");
				}
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

	/** Encrypts {@code src} via the TLS engine and writes the resulting record fully to the socket. */
	private int writeSecurely(SocketChannel channel, ByteBuffer src, SSLSessionHandler sslHandler) throws IOException {
		if (sslHandler.hasPendingWrite()) {
			return 0;
		}
		int startPos = src.position();
		ByteBuffer encrypted = sslHandler.wrap(src);
		int plaintextConsumed = src.position() - startPos;
		sslHandler.tryWrite(encrypted);
		return plaintextConsumed;
	}


	/** Writes the mapped-file window attached to the key; on completion either maps and writes the
	 *  next window of a large-file stream (iteratively) or finishes the response. */
	private long writeMappedByteBuffer(SelectionKey key, SocketChannel channel) throws IOException {
		long bytesWritten = 0;
		// Iterative window loop: write the current mapped window; if the socket fills, defer to
		// OP_WRITE; if it drains and a large-file stream has more windows, map and write the next
		// one (iteratively, never recursively — a multi-GiB file with small windows must not blow
		// the stack). For a normal single-mapping response there is no FileStreamState, so this
		// behaves exactly as before (write window → registerForRead).
		while (true) {
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
			if (mbb.hasRemaining()) {
				key.interestOps(SelectionKey.OP_WRITE); // send buffer full — resume on next OP_WRITE
				return bytesWritten;
			}
			FileStreamState fs = fileStreams.get(channel);
			if (fs != null && fs.pos < fs.end) {
				long windowLen = Math.min((long) FILE_WINDOW_SIZE, fs.end - fs.pos);
				MappedByteBuffer next = fs.fc.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fs.pos, windowLen);
				fs.pos += windowLen;
				key.attach(next);
				// loop to write the next window
			} else {
				SSLSessionHandler sslHandler = sslSessionHandlers.get(channel);
				if (sslHandler != null && sslHandler.hasPendingWrite()) {
					key.interestOps(SelectionKey.OP_WRITE);
					return bytesWritten;
				}
				if (fs != null) {
					closeFileStream(channel); // all windows sent — release the file handle
				}
				handleConnectionIdle(key, channel);
				return bytesWritten;
			}
		}
	}

	/**
	 * Serves a file (or sub-range) of arbitrary size by mapping it in {@link #FILE_WINDOW_SIZE}
	 * windows, avoiding the 2 GiB single-mmap limit. The first window is written here; subsequent
	 * windows are chained by {@link #writeMappedByteBuffer} as each completes.
	 */
	public void streamLargeFile(SelectionKey key, java.io.File file, long start, long length) throws IOException {
		SocketChannel channel = (SocketChannel) key.channel();
		java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
		// If mapping the first window or the initial write fails, the RandomAccessFile must be closed
		// here — otherwise its file descriptor leaks until the channel eventually closes (and on an
		// error path a keep-alive channel may live on). The success path leaves the handle registered
		// in fileStreams for writeMappedByteBuffer to chain the remaining windows and release at the end.
		boolean handedOff = false;
		try {
			FileStreamState state = new FileStreamState(raf, raf.getChannel(), start, start + length);
			fileStreams.put(channel, state);
			long windowLen = Math.min((long) FILE_WINDOW_SIZE, state.end - state.pos);
			MappedByteBuffer mbb = state.fc.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, state.pos, windowLen);
			state.pos += windowLen;
			key.attach(mbb);
			writeMappedByteBuffer(key, channel);
			handedOff = true;
		} finally {
			if (!handedOff) {
				// closeFileStream both drops the (possibly-registered) state and closes the handle;
				// fall back to a direct close if the state never made it into the map.
				if (fileStreams.containsKey(channel)) {
					closeFileStream(channel);
				} else {
					try { raf.close(); } catch (IOException ignore) { }
				}
			}
		}
	}

	/** Releases the open file handle for an in-progress large-file stream on the channel, if any. */
	private void closeFileStream(SocketChannel channel) {
		FileStreamState state = fileStreams.remove(channel);
		if (state != null) {
			try { state.raf.close(); } catch (IOException ignore) { }
		}
	}

	/** Writes the dynamic response buffer attached to the key; on completion honours a deferred
	 *  Connection: close or re-registers for the next request, else defers the remainder to OP_WRITE. */
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
			SSLSessionHandler sslHandler = sslSessionHandlers.get(channel);
			if (sslHandler != null && sslHandler.hasPendingWrite()) {
				if (!toSend.isReadOnly()) toSend.compact();
				key.interestOps(SelectionKey.OP_WRITE);
			} else {
				logger.debug("sent all data in toSend buffer");
				// Honour a deferred Connection: close, otherwise re-register for the next request.
				finishDeferredWrite(key, channel);
			}
		} else {
			if (!toSend.isReadOnly()) toSend.compact(); // make room for more data be "read" in
			key.interestOps(SelectionKey.OP_WRITE);
		}
		return bytesWritten;
	}
	
	/** Writes a plain {@link ByteBuffer} attached to the key (same completion semantics as
	 *  {@link #writeDynamicByteBuffer}). */
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
			SSLSessionHandler sslHandler = sslSessionHandlers.get(channel);
			if (sslHandler != null && sslHandler.hasPendingWrite()) {
				if (!toSend.isReadOnly()) toSend.compact();
				key.interestOps(SelectionKey.OP_WRITE);
			} else {
				logger.debug("sent all data in toSend buffer");
				// Honour a deferred Connection: close, otherwise re-register for the next request.
				finishDeferredWrite(key, channel);
			}
		} else {
			if (!toSend.isReadOnly()) toSend.compact(); // make room for more data be "read" in
			key.interestOps(SelectionKey.OP_WRITE);
		}
		return bytesWritten;
	}

	/** Marks a channel to be closed (rather than re-registered for read) once its currently-pending
	 *  deferred response write completes — used when a Connection: close response didn't fit the
	 *  socket buffer in one flush. */
	public void markCloseAfterWrite(SocketChannel channel) {
		closeAfterWrite.add(channel);
	}

	/**
	 * Bounds the time an async/offloaded handler may take to finish its response (it runs off the
	 * loop, so the loop can't otherwise detect it hanging). For a keep-alive connection the existing
	 * idle timeout is *extended* to the processing window (so the idle timer doesn't fire mid-
	 * processing, yet a hung handler is still bounded, and {@code registerForRead} resets it to the
	 * normal idle value on completion). A {@code Connection: close} connection has no keep-alive
	 * timeout, so a standalone processing timeout is armed (cleaned up by {@link #closeChannel}).
	 */
	private void armProcessingTimeout(final SocketChannel channel) {
		Timeout pt = new Timeout(
			System.currentTimeMillis() + REQUEST_PROCESSING_TIMEOUT_MS,
			new AsyncCallback() {
				@Override
				public void onCallback() {
					logger.warn("Async handler exceeded processing timeout for channel {} — closing", channel);
					sendRequestTimeoutAndClose(channel);
				}
			}
		);
		if (ioLoop.hasKeepAliveTimeout(channel)) {
			// Keep-alive: replace the idle timer with this one, keeping it in the keep-alive index so
			// registerForRead still recognises the connection (and resets it to the normal idle value
			// on completion) — but now with the processing window and 408-on-expiry semantics.
			ioLoop.addKeepAliveTimeout(channel, pt);
		} else {
			// Connection: close — no keep-alive timer; track this one separately (cleaned by closeChannel).
			Timeout existing = processingTimeouts.remove(channel);
			if (existing != null) existing.cancel();
			processingTimeouts.put(channel, pt);
			ioLoop.addTimeout(pt);
		}
	}

	public void handleConnectionIdle(SelectionKey key, SocketChannel channel) throws IOException {
		if (key.isValid()) {
			ByteBuffer buf = readBuffers.get(channel);
			if (buf != null && buf.position() > 0) {
				prolongKeepAliveTimeout(channel);
				key.interestOps(0);
				key.attach(buf);
				ioLoop.addCallback(new AsyncCallback() {
					@Override
					public void onCallback() {
						try {
							if (key.isValid() && channel.isOpen()) {
								handleRead(key);
							}
						} catch (IOException e) {
							logger.debug("IOException during pipelined request execution: {}", e.getMessage());
							closeChannel(channel);
						}
					}
				});
			} else {
				registerForRead(key);
			}
		}
	}

	private void finishDeferredWrite(SelectionKey key, SocketChannel channel) throws IOException {
		cancelWriteTimeout(channel); // the deferred write completed — no longer a slow-reader risk
		if (closeAfterWrite.remove(channel)) {
			closeChannel(channel);
		} else {
			handleConnectionIdle(key, channel);
		}
	}

	/** Arms (once) the idle response-write timeout for a deferred (non-blocking TLS) write. Idempotent. */
	public void armWriteTimeout(SocketChannel channel) {
		if (channel == null || writeTimeouts.containsKey(channel)) {
			return;
		}
		Timeout t = new Timeout(
			System.currentTimeMillis() + HttpServerDescriptor.RESPONSE_WRITE_TIMEOUT_MS,
			new AsyncCallback() {
				@Override public void onCallback() {
					logger.warn("Response-write idle timeout (peer not reading the response) for {} — closing", channel);
					closeChannel(channel);
				}
			});
		writeTimeouts.put(channel, t);
		ioLoop.addTimeout(t);
	}

	/** Resets the idle response-write timeout on write progress (the peer drained some bytes). No-op
	 *  if no write timeout is currently armed. */
	public void prolongWriteTimeout(SocketChannel channel) {
		Timeout old = writeTimeouts.remove(channel);
		if (old == null) {
			return;
		}
		old.cancel();
		armWriteTimeout(channel);
	}

	/** Cancels the response-write timeout (the deferred write completed or the channel is closing). */
	public void cancelWriteTimeout(SocketChannel channel) {
		Timeout t = writeTimeouts.remove(channel);
		if (t != null) {
			t.cancel();
		}
	}

	/** After a response completes, re-registers the channel for OP_READ to await the next request if
	 *  the connection is keep-alive (refreshing the idle timeout), otherwise closes it. */
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
	
	/** Resets the keep-alive idle timeout for a channel (called on each request/activity). */
	public void prolongKeepAliveTimeout(SelectableChannel channel) {
		ioLoop.addKeepAliveTimeout(channel, newKeepAliveTimeout((SocketChannel) channel));
	}

	/** Arms/resets the (longer) WebSocket idle timeout for an upgraded connection. Called at upgrade
	 *  and on each frame so an active socket stays open and a genuinely idle/dead one is reaped. */
	public void prolongWebSocketIdleTimeout(final SocketChannel channel) {
		ioLoop.addKeepAliveTimeout(channel, new Timeout(
			System.currentTimeMillis() + WEBSOCKET_IDLE_TIMEOUT_MS,
			new AsyncCallback() {
				@Override
				public void onCallback() {
					logger.debug("WebSocket idle timeout — closing channel {}", channel);
					closeChannel(channel);
				}
			}
		));
	}

	/**
	 * Builds the keep-alive idle timeout. It tears down via {@link #closeChannel} (NOT a bare
	 * Closeables.closeQuietly) so the per-channel state maps (sslSessionHandlers, partials, the
	 * WebSocket maps, the active-channel set) are all cleaned when an idle connection expires —
	 * otherwise those entries would leak for every timed-out keep-alive connection.
	 */
	private Timeout newKeepAliveTimeout(SocketChannel channel) {
		return new Timeout(
			System.currentTimeMillis() + KEEP_ALIVE_TIMEOUT,
			new AsyncCallback() {
				@Override
				public void onCallback() {
					logger.debug("keep-alive idle timeout — closing channel {}", channel);
					closeChannel(channel);
				}
			}
		);
	}
	
	/** The I/O loop this protocol runs on (the only thread that may touch channel/selector state). */
	public IOLoop getIOLoop() {
		return ioLoop;
	}

	/**
	 * Clears the buffer (prepares for reuse) attached to the given SelectionKey.
	 * @return A cleared (position=0, limit=capacity) ByteBuffer which is ready for new reads
	 */
	private ByteBuffer reuseAttachment(SelectionKey key) {
		SocketChannel channel = (SocketChannel) key.channel();
		ByteBuffer buf = readBuffers.get(channel);
		if (buf != null) {
			buf.clear();
			return buf;
		}
		Object o = key.attachment();
		ByteBuffer attachment = null;
		if (o instanceof MappedByteBuffer) {
			attachment = ByteBuffer.allocate(READ_BUFFER_SIZE);
		} else if (o instanceof DynamicByteBuffer) {
			attachment = ((DynamicByteBuffer) o).getByteBuffer();
		} else {
			attachment = (ByteBuffer) o;
		}
		if (attachment == null || attachment.isReadOnly() || attachment.capacity() < READ_BUFFER_SIZE) {
			attachment = ByteBuffer.allocate(READ_BUFFER_SIZE);
		}
		attachment.clear();
		return attachment;
	}

	//TODO: change this to work with the entire stream rather than individual calls (no guarantee the client sends their data wholly in discrete packets)
	private HttpRequest getHttpRequest(SelectionKey key, SocketChannel clientChannel) throws IOException {
		ByteBuffer buffer = readBuffers.computeIfAbsent(clientChannel, c -> ByteBuffer.allocate(READ_BUFFER_SIZE));
		if (!buffer.hasRemaining()) throw new IllegalStateException("Cleared channel buffer has no remaining space.");
		SSLSessionHandler sslHandler = sslSessionHandlers.get(clientChannel);
		if (sslHandler != null) {
			// Use the actual TLS packet buffer size (up to ~16 KB) — not the tiny READ_BUFFER_SIZE.
			// Reuse a per-protocol scratch buffer rather than allocating ~16 KB on EVERY HTTPS read:
			// the loop is single-threaded and unwrap() copies all ciphertext into the handler's
			// netReadBuf, so the scratch is fully consumed before the next read can touch it. (Each
			// reactor thread has its own HttpProtocol, so there is no cross-thread sharing.)
			int pktSize = sslHandler.getPacketBufferSize();
			if (sslReadScratch == null || sslReadScratch.capacity() < pktSize) {
				sslReadScratch = ByteBuffer.allocate(pktSize);
			}
			ByteBuffer encrypted = sslReadScratch;
			encrypted.clear();
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
			if (!plaintext.hasRemaining() && buffer.position() == 0) {
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
					if (plaintext.hasRemaining()) {
						if (plaintext.remaining() > buffer.remaining()) {
							ByteBuffer grown = ByteBuffer.allocate(plaintext.remaining() + buffer.position());
							buffer.flip();
							grown.put(buffer);
							grown.put(plaintext);
							key.attach(grown);
							readBuffers.put(clientChannel, grown);
							buffer = grown;
						} else {
							buffer.put(plaintext);
						}
					}
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
				readBuffers.put(clientChannel, grown);
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
					readBuffers.put(clientChannel, grown);
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
		if (isHttp2PrefacePrefix(buffer)) {
			if (buffer.position() >= HTTP2_PREFACE.length) {
				buffer.flip();
				byte[] initialBytes = new byte[buffer.remaining()];
				buffer.get(initialBytes);
				buffer.clear();
				org.deftserver.web.http.http2.Http2Connection conn = new org.deftserver.web.http.http2.Http2Connection(this, key, clientChannel, initialBytes);
				http2Connections.put(clientChannel, conn);
				readBuffers.remove(clientChannel);
				Timeout t = headerReadTimeouts.remove(clientChannel);
				if (t != null) t.cancel();
				prolongKeepAliveTimeout(clientChannel);
				conn.onReadable();
				return null;
			} else {
				key.interestOps(SelectionKey.OP_READ);
				return null;
			}
		}
		buffer.flip();
		logger.debug("getHttpRequest buffer remaining: {}", buffer.remaining());
		HttpRequest req = doGetHttpRequest(key, clientChannel, buffer);
		if (req == null) buffer.rewind();
		buffer.compact(); // allow for more data to be read in

		return req;
	}
	
	/** Continues an in-progress (partial) request or parses a fresh one from the buffer: sends an
	 *  early 100-Continue when expected, stores incomplete requests as partials, and turns any parse
	 *  failure into the {@link MalFormedHttpRequest} sentinel (→ 400). Stamps remote/local
	 *  address/port and the secure flag on the returned request. */
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
				// Validate the Expect header for the whole request (RFC 9110 §10.1.1): the only
				// defined expectation is 100-continue; any other value cannot be met, so reject with
				// 417 Expectation Failed rather than silently ignoring it (which would leave a client
				// that withholds its body waiting forever for a 100 that never comes). An HTTP/1.0
				// Expect is ignored entirely (those clients don't understand interim responses).
				String expect = request.getHeader("Expect");
				if (expect != null && !expect.isEmpty()
						&& !expect.equalsIgnoreCase("100-continue")
						&& "HTTP/1.1".equals(request.getVersion())) {
					throw new HttpException(417, "Expectation Failed", "Unsupported expectation: " + expect);
				}
				if (!request.isComplete()) {
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
		if (isUnixSocket(clientChannel)) {
			request.setRemoteHost(java.net.InetAddress.getLoopbackAddress());
			request.setRemotePort(0);
			request.setServerHost(java.net.InetAddress.getLoopbackAddress());
			request.setServerPort(0);
		} else {
			request.setRemoteHost(clientChannel.socket().getInetAddress());
			request.setRemotePort(clientChannel.socket().getPort());
			request.setServerHost(clientChannel.socket().getLocalAddress());
			request.setServerPort(clientChannel.socket().getLocalPort());
		}
		request.setSecure(sslSessionHandlers.containsKey(clientChannel));
		return request;
	}
	
	/** Reads and processes WebSocket frames on an upgraded connection: validates and unmasks frames,
	 *  reassembles fragmented messages (bounded), answers ping/close control frames, validates UTF-8
	 *  text, and dispatches complete messages to the handler. Prolongs the idle timeout on activity. */
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
					// unwrap() already returns the plaintext flipped (read-ready); do NOT flip again —
					// a second flip zeroes remaining() and silently drops every decrypted wss:// frame
					// (the HTTP SSL read path at the top of getHttpRequest consumes unwrap()'s result
					// directly for exactly this reason).
					ByteBuffer appReadBuf = sslHandler.unwrap(netReadBuf);
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
			closeChannel(clientChannel); // onClose is delivered by closeChannel
			return;
		}

		if (bytesRead == 0) {
			return;
		}

		// WebSocket activity: reset the (longer) WebSocket idle timeout so an actively-used connection
		// stays open. Without this, the timeout would close every WebSocket after WEBSOCKET_IDLE_TIMEOUT
		// regardless of traffic.
		if (ioLoop.hasKeepAliveTimeout(clientChannel)) {
			prolongWebSocketIdleTimeout(clientChannel);
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
				closeChannel(clientChannel); // onClose delivered by closeChannel
				return;
			}
			// RFC 6455 §5.1: frames from the client MUST be masked; otherwise fail the connection.
			if (!masked) {
				logger.warn("WebSocket client frame not masked — closing channel per RFC 6455");
				closeChannel(clientChannel); // onClose delivered by closeChannel
				return;
			}

			// RFC 6455 §5.5: control frames (opcode >= 0x8) MUST NOT be fragmented and carry at
			// most 125 bytes of payload. And reserved opcodes (0x3-0x7, 0xB-0xF) must fail the
			// connection.
			boolean isControlFrame = opcode >= 0x8;
			if (isControlFrame && (!fin || actualLen > 125)) {
				logger.warn("Invalid WebSocket control frame (fin={}, len={}) — closing", fin, actualLen);
				closeChannel(clientChannel);
				return;
			}
			if ((opcode >= 0x3 && opcode <= 0x7) || opcode >= 0xB) {
				logger.warn("Reserved WebSocket opcode {} — closing channel", opcode);
				closeChannel(clientChannel);
				return;
			}
			// RFC 6455 §5.2: RSV1/RSV2/RSV3 (the three bits below FIN in the first byte) MUST be 0
			// unless an extension that defines them was negotiated. Dreft negotiates no WebSocket
			// extensions, so any set RSV bit is a protocol error and we fail the connection.
			int rsv = b0 & 0x70;
			if (rsv != 0) {
				logger.warn("WebSocket frame with reserved RSV bits set (0x{}) — closing per RFC 6455 §5.2",
						Integer.toHexString(rsv));
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
				// RFC 6455 §5.5.1 / §7.4: respond with a Close frame to complete the handshake.
				// Echo the peer's status code when it is valid; a 1-byte payload (incomplete code)
				// or an invalid/reserved code is a protocol error → respond with 1002.
				try {
					int code;
					if (payload.length == 0) {
						code = 1000; // no status code → normal closure
					} else if (payload.length == 1 || !isValidWebSocketCloseCode(((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF))) {
						code = 1002; // protocol error
					} else if (payload.length > 2 && !isValidUtf8(payload, 2, payload.length - 2)) {
						// RFC 6455 §8.1 / §7.1.6: the Close reason (after the 2-byte code) MUST be valid
						// UTF-8; otherwise fail with 1007 Invalid frame payload data (Autobahn 7.5.1).
						code = 1007;
					} else {
						code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
					}
					ByteBuffer closeFrame = ByteBuffer.allocate(4);
					closeFrame.put((byte) 0x88);
					closeFrame.put((byte) 2);
					closeFrame.putShort((short) code);
					closeFrame.flip();
					writeBlocking(clientChannel, closeFrame);
				} catch (IOException ignore) {
					// peer already gone; proceed to tear down
				}
				closeChannel(clientChannel); // onClose delivered by closeChannel
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
					websocketTextMessage.put(clientChannel, opcode == 0x1); // text vs binary
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
					boolean isText = Boolean.TRUE.equals(websocketTextMessage.remove(clientChannel));
					byte[] msgBytes = msgBuf.toByteArray();
					websocketFragments.remove(clientChannel);
					String textMsg = null;
					if (isText) {
						// RFC 6455 §8.1: a text message that isn't valid UTF-8 must fail the connection.
						try {
							textMsg = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
								.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
								.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
								.decode(ByteBuffer.wrap(msgBytes)).toString();
						} catch (java.nio.charset.CharacterCodingException e) {
							logger.warn("WebSocket text message is not valid UTF-8 — closing (RFC 6455 §8.1)");
							closeChannel(clientChannel);
							return;
						}
					}
					if (wsConn != null) {
						try {
							if (isText) {
								wsHandler.onMessage(wsConn, textMsg);
							} else {
								wsHandler.onBinaryMessage(wsConn, msgBytes);
							}
						} catch (RuntimeException e) {
							logger.error("Uncaught exception in WebSocket message handler — closing channel", e);
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
	
	/** Valid WebSocket close status codes per RFC 6455 §7.4.1 (plus the 3000-4999 app range). */
	private static boolean isValidWebSocketCloseCode(int code) {
		if (code >= 3000 && code <= 4999) {
			return true;
		}
		switch (code) {
			case 1000: case 1001: case 1002: case 1003:
			case 1007: case 1008: case 1009: case 1010:
			case 1011: case 1012: case 1013: case 1014:
				return true;
			default:
				return false; // includes 1004, 1005, 1006, 1015 and all other reserved/invalid codes
		}
	}

	/** True if {@code [offset, offset+length)} of {@code data} decodes as strict UTF-8 (no malformed
	 *  or unmappable sequences) — used to validate a WebSocket Close-frame reason (RFC 6455 §8.1). */
	private static boolean isValidUtf8(byte[] data, int offset, int length) {
		try {
			java.nio.charset.StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
				.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(data, offset, length));
			return true;
		} catch (java.nio.charset.CharacterCodingException e) {
			return false;
		}
	}

	public Application getApplication() {
		return application;
	}

	private static final byte[] HTTP2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

	private boolean isHttp2PrefacePrefix(ByteBuffer buffer) {
		int pos = buffer.position();
		if (pos == 0) return false;
		int len = Math.min(pos, HTTP2_PREFACE.length);
		for (int i = 0; i < len; i++) {
			if (buffer.get(i) != HTTP2_PREFACE[i]) {
				return false;
			}
		}
		return true;
	}

	public ByteBuffer readPlaintext(SocketChannel clientChannel) throws IOException {
		SSLSessionHandler sslHandler = sslSessionHandlers.get(clientChannel);
		if (sslHandler != null) {
			int pktSize = sslHandler.getPacketBufferSize();
			if (sslReadScratch == null || sslReadScratch.capacity() < pktSize) {
				sslReadScratch = ByteBuffer.allocate(pktSize);
			}
			ByteBuffer encrypted = sslReadScratch;
			encrypted.clear();
			int read = clientChannel.read(encrypted);
			if (read == -1) {
				return null;
			}
			encrypted.flip();
			return sslHandler.unwrap(encrypted);
		} else {
			ByteBuffer buf = ByteBuffer.allocate(65536);
			int read = clientChannel.read(buf);
			if (read == -1) {
				return null;
			}
			buf.flip();
			return buf;
		}
	}

	private static boolean isUnixSocket(SocketChannel channel) {
		try {
			channel.socket();
			return false;
		} catch (UnsupportedOperationException e) {
			return true;
		}
	}

	@Override
	public String toString() { return "HttpProtocol"; }
}
