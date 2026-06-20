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

	
	private static final class ChannelState {
		HttpRequest partial;
		ByteBuffer readBuffer;
		SSLSessionHandler sslSessionHandler;
		org.deftserver.web.http.http2.Http2Connection http2Connection;
		WebSocketHandler websocketHandler;
		WebSocketConnection websocketConnection;
		java.io.ByteArrayOutputStream websocketFragment;
		Boolean websocketTextMessage;
		Timeout headerReadTimeout;
		Timeout bodyReadTimeout;
		Timeout processingTimeout;
		Timeout writeTimeout;
		boolean closeAfterWrite;
		int keepAliveRequestCount;
		FileStreamState fileStream;
		java.net.InetAddress ip;
	}

	private final Map<SocketChannel, ChannelState> connectionStates = new ConcurrentHashMap<>();

	// Per-channel FIFO of small "must-flush-now" control buffers (WebSocket frames, the 100-Continue
	// interim response) whose socket write did not fully drain. The remainder is flushed on OP_WRITE and a
	// peer that never drains is reaped by the idle write timeout — so a slow/non-reading peer can NEVER
	// freeze the I/O-loop thread (which the bounded-but-blocking writeBlocking did, parking the loop for up
	// to RESPONSE_WRITE_TIMEOUT_MS). Buffers are stored already SSL-wrapped (and copied, since the SSL wrap
	// buffer is reused). Touched only on the I/O-loop thread.
	private final Map<SocketChannel, java.util.ArrayDeque<ByteBuffer>> pendingFrameWrites = new ConcurrentHashMap<>();

	private ChannelState getState(SelectableChannel channel) {
		if (channel == null) return null;
		return connectionStates.get(channel);
	}

	private ChannelState getOrNewState(SelectableChannel channel) {
		return connectionStates.computeIfAbsent((SocketChannel) channel, c -> new ChannelState());
	}

	private void cleanIfEmpty(SelectableChannel channel, ChannelState state) {
		if (state.partial == null &&
			state.readBuffer == null &&
			state.sslSessionHandler == null &&
			state.http2Connection == null &&
			state.websocketHandler == null &&
			state.websocketConnection == null &&
			state.websocketFragment == null &&
			state.websocketTextMessage == null &&
			state.headerReadTimeout == null &&
			state.bodyReadTimeout == null &&
			state.processingTimeout == null &&
			state.writeTimeout == null &&
			!state.closeAfterWrite &&
			state.keepAliveRequestCount == 0 &&
			state.fileStream == null &&
			state.ip == null) {
			connectionStates.remove(channel);
		}
	}

	private HttpRequest getPartial(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? null : state.partial;
	}

	private void putPartial(SelectableChannel channel, HttpRequest req) {
		getOrNewState(channel).partial = req;
	}

	private HttpRequest removePartial(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			HttpRequest old = state.partial;
			state.partial = null;
			cleanIfEmpty(channel, state);
			return old;
		}
		return null;
	}

	private boolean hasPartial(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state != null && state.partial != null;
	}

	private ByteBuffer getReadBuffer(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? null : state.readBuffer;
	}

	private void putReadBuffer(SelectableChannel channel, ByteBuffer buf) {
		getOrNewState(channel).readBuffer = buf;
	}

	private ByteBuffer removeReadBuffer(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			ByteBuffer old = state.readBuffer;
			state.readBuffer = null;
			cleanIfEmpty(channel, state);
			return old;
		}
		return null;
	}

	public SSLSessionHandler getSslSessionHandler(SocketChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? null : state.sslSessionHandler;
	}

	private void putSslSessionHandler(SocketChannel channel, SSLSessionHandler h) {
		getOrNewState(channel).sslSessionHandler = h;
	}

	private SSLSessionHandler removeSslSessionHandler(SocketChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			SSLSessionHandler old = state.sslSessionHandler;
			state.sslSessionHandler = null;
			cleanIfEmpty(channel, state);
			return old;
		}
		return null;
	}

	private org.deftserver.web.http.http2.Http2Connection getHttp2Connection(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? null : state.http2Connection;
	}

	private void putHttp2Connection(SelectableChannel channel, org.deftserver.web.http.http2.Http2Connection conn) {
		getOrNewState(channel).http2Connection = conn;
	}

	private org.deftserver.web.http.http2.Http2Connection removeHttp2Connection(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			org.deftserver.web.http.http2.Http2Connection old = state.http2Connection;
			state.http2Connection = null;
			cleanIfEmpty(channel, state);
			return old;
		}
		return null;
	}

	private WebSocketHandler getWebsocketHandler(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? null : state.websocketHandler;
	}

	private void putWebsocketHandler(SelectableChannel channel, WebSocketHandler h) {
		getOrNewState(channel).websocketHandler = h;
	}

	private WebSocketHandler removeWebsocketHandler(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			WebSocketHandler old = state.websocketHandler;
			state.websocketHandler = null;
			cleanIfEmpty(channel, state);
			return old;
		}
		return null;
	}

	private boolean hasWebsocketHandler(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state != null && state.websocketHandler != null;
	}

	private WebSocketConnection getWebsocketConnection(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? null : state.websocketConnection;
	}

	private void putWebsocketConnection(SelectableChannel channel, WebSocketConnection conn) {
		getOrNewState(channel).websocketConnection = conn;
	}

	private WebSocketConnection removeWebsocketConnection(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			WebSocketConnection old = state.websocketConnection;
			state.websocketConnection = null;
			cleanIfEmpty(channel, state);
			return old;
		}
		return null;
	}

	private java.io.ByteArrayOutputStream getWebsocketFragment(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? null : state.websocketFragment;
	}

	private void putWebsocketFragment(SelectableChannel channel, java.io.ByteArrayOutputStream os) {
		getOrNewState(channel).websocketFragment = os;
	}

	private java.io.ByteArrayOutputStream removeWebsocketFragment(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			java.io.ByteArrayOutputStream old = state.websocketFragment;
			state.websocketFragment = null;
			cleanIfEmpty(channel, state);
			return old;
		}
		return null;
	}

	private Boolean getWebsocketTextMessage(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? null : state.websocketTextMessage;
	}

	private void putWebsocketTextMessage(SelectableChannel channel, Boolean b) {
		getOrNewState(channel).websocketTextMessage = b;
	}

	private Boolean removeWebsocketTextMessage(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			Boolean old = state.websocketTextMessage;
			state.websocketTextMessage = null;
			cleanIfEmpty(channel, state);
			return old;
		}
		return null;
	}

	private Timeout getHeaderReadTimeout(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? null : state.headerReadTimeout;
	}

	private void putHeaderReadTimeout(SelectableChannel channel, Timeout t) {
		getOrNewState(channel).headerReadTimeout = t;
	}

	private Timeout removeHeaderReadTimeout(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			Timeout old = state.headerReadTimeout;
			state.headerReadTimeout = null;
			cleanIfEmpty(channel, state);
			return old;
		}
		return null;
	}

	private Timeout getBodyReadTimeout(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? null : state.bodyReadTimeout;
	}

	private void putBodyReadTimeout(SelectableChannel channel, Timeout t) {
		getOrNewState(channel).bodyReadTimeout = t;
	}

	private Timeout removeBodyReadTimeout(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			Timeout old = state.bodyReadTimeout;
			state.bodyReadTimeout = null;
			cleanIfEmpty(channel, state);
			return old;
		}
		return null;
	}

	private boolean hasBodyReadTimeout(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state != null && state.bodyReadTimeout != null;
	}

	private Timeout getProcessingTimeout(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? null : state.processingTimeout;
	}

	private void putProcessingTimeout(SelectableChannel channel, Timeout t) {
		getOrNewState(channel).processingTimeout = t;
	}

	private Timeout removeProcessingTimeout(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			Timeout old = state.processingTimeout;
			state.processingTimeout = null;
			cleanIfEmpty(channel, state);
			return old;
		}
		return null;
	}

	private Timeout getWriteTimeout(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? null : state.writeTimeout;
	}

	private void putWriteTimeout(SelectableChannel channel, Timeout t) {
		getOrNewState(channel).writeTimeout = t;
	}

	private Timeout removeWriteTimeout(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			Timeout old = state.writeTimeout;
			state.writeTimeout = null;
			cleanIfEmpty(channel, state);
			return old;
		}
		return null;
	}

	private boolean hasWriteTimeout(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state != null && state.writeTimeout != null;
	}

	private boolean isCloseAfterWrite(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state != null && state.closeAfterWrite;
	}

	private void addCloseAfterWrite(SelectableChannel channel) {
		getOrNewState(channel).closeAfterWrite = true;
	}

	private boolean removeCloseAfterWrite(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			boolean old = state.closeAfterWrite;
			state.closeAfterWrite = false;
			cleanIfEmpty(channel, state);
			return old;
		}
		return false;
	}

	private int getKeepAliveRequestCount(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? 0 : state.keepAliveRequestCount;
	}

	private void putKeepAliveRequestCount(SelectableChannel channel, int count) {
		getOrNewState(channel).keepAliveRequestCount = count;
	}

	private int incrementKeepAliveRequestCount(SelectableChannel channel) {
		ChannelState state = getOrNewState(channel);
		state.keepAliveRequestCount++;
		return state.keepAliveRequestCount;
	}

	private void removeKeepAliveRequestCount(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			state.keepAliveRequestCount = 0;
			cleanIfEmpty(channel, state);
		}
	}

	private FileStreamState getFileStream(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? null : state.fileStream;
	}

	private void putFileStream(SelectableChannel channel, FileStreamState fs) {
		getOrNewState(channel).fileStream = fs;
	}

	private FileStreamState removeFileStream(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			FileStreamState old = state.fileStream;
			state.fileStream = null;
			cleanIfEmpty(channel, state);
			return old;
		}
		return null;
	}

	private java.net.InetAddress getChannelIp(SelectableChannel channel) {
		ChannelState state = getState(channel);
		return state == null ? null : state.ip;
	}

	private void putChannelIp(SelectableChannel channel, java.net.InetAddress ip) {
		getOrNewState(channel).ip = ip;
	}

	private java.net.InetAddress removeChannelIp(SelectableChannel channel) {
		ChannelState state = getState(channel);
		if (state != null) {
			java.net.InetAddress old = state.ip;
			state.ip = null;
			cleanIfEmpty(channel, state);
			return old;
		}
		return null;
	}

	private ByteBuffer sslReadScratch;

	private javax.net.ssl.SSLContext sslContext;

	public static volatile long REQUEST_PROCESSING_TIMEOUT_MS = 60_000;
	public static volatile long WEBSOCKET_IDLE_TIMEOUT_MS = 300_000; // 5 min
	public static volatile int MAX_KEEP_ALIVE_REQUESTS = 1000;
	// Set of live client channels (not an int counter): channels can be closed via several paths
	// that don't all funnel through closeChannel (IOLoop exception handlers, generic Closeables,
	// flush/finish I/O errors). A bare counter would leak upward on those paths and eventually
	// throttle the server forever. A set lets us self-heal by pruning closed channels before the
	// limit check. Accessed only from this protocol's I/O-loop thread.
	private final java.util.Set<SocketChannel> activeChannels = new java.util.HashSet<>();
	private final java.util.Map<java.net.InetAddress, java.lang.Integer> ipConnectionCounts = new java.util.HashMap<>();

	/** Upper bound on a single inbound WebSocket frame payload (16 MiB), mirroring the HTTP
	 *  body cap. Guards against OOM / NegativeArraySizeException from a hostile length field. */
	private static final long MAX_WS_FRAME_PAYLOAD = 16L * 1024 * 1024;
	private static final byte[] CONTINUE_BYTES = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

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

	private int maxConnections = -1;
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
			if (channel != null && channel.isOpen() && getSslSessionHandler(channel) == null) {
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
			pendingFrameWrites.remove(channel); // drop any undrained control-write buffers
			ChannelState state = connectionStates.remove(channel);
			if (state != null) {
				if (state.websocketHandler != null) {
					try {
						state.websocketHandler.onClose(state.websocketConnection);
					} catch (RuntimeException e) {
						logger.error("Uncaught exception in WebSocket onClose handler", e);
					}
				}
				if (state.processingTimeout != null) state.processingTimeout.cancel();
				if (state.writeTimeout != null) state.writeTimeout.cancel();
				if (state.headerReadTimeout != null) state.headerReadTimeout.cancel();
				if (state.bodyReadTimeout != null) state.bodyReadTimeout.cancel();
				if (state.fileStream != null) {
					try { state.fileStream.raf.close(); } catch (IOException ignore) { }
				}
				if (activeChannels.remove(channel)) {
					if (maxConnectionsPerIp > 0 && state.ip != null) {
						int count = ipConnectionCounts.getOrDefault(state.ip, 0);
						if (count <= 1) {
							ipConnectionCounts.remove(state.ip);
						} else {
							ipConnectionCounts.put(state.ip, count - 1);
						}
					}
				}
			}
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
		putWebsocketHandler(channel, handler);
		putWebsocketConnection(channel, connection);
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
		final ServerSocketChannel listener = (ServerSocketChannel) key.channel();
		final boolean isSsl = Boolean.TRUE.equals(IOLoop.getAttachment(key));
		// Drain a bounded BATCH of pending connections per readiness event rather than one. The selector
		// is level-triggered, so a single accept() per event empties the OS backlog only one connection
		// per event-loop iteration — under a burst that needlessly delays accepts (and, once the backlog
		// fills, pushes clients into multi-second SYN-retransmit backoff). The cap keeps the loop fair to
		// read/write/timeout work and bounds cross-reactor imbalance in multi-loop mode; the next
		// iteration's OP_ACCEPT resumes draining any remainder.
		final int maxAccepts = Math.max(1, HttpServerDescriptor.MAX_ACCEPTS_PER_EVENT);
		for (int i = 0; i < maxAccepts; i++) {
			SocketChannel clientChannel = listener.accept();
			if (clientChannel == null) {
				break; // backlog drained (or another reactor won the race in multi-reactor mode)
			}
			// CRITICAL: isolate ALL per-connection setup. If anything here throws (most importantly the
			// initial TLS handshake, which an immediate-garbage client can make throw an SSLException or
			// RuntimeException), it must NOT propagate out of handleAccept — the IOLoop's accept-error
			// path closes key.channel(), which for an OP_ACCEPT key is the shared *listening*
			// ServerSocketChannel. A single hostile connection would otherwise close the listening socket
			// and stop the whole server from accepting (a trivial remote DoS). Close only this client.
			try {
				setUpAcceptedConnection(clientChannel, isSsl);
			} catch (IOException | RuntimeException e) {
				logger.debug("Failed to set up accepted connection {} — closing it (not the listener): {}",
					clientChannel, e.toString());
				closeChannel(clientChannel);
			}
		}
	}

	/** Per-connection setup for a freshly-accepted channel: connection-limit checks, non-blocking
	 *  config, read registration, the Slowloris header timeout, and (for HTTPS) the TLS handshake.
	 *  Any failure here is contained by {@link #handleAccept} so it never closes the listening socket. */
	private void setUpAcceptedConnection(SocketChannel clientChannel, boolean isSslConnection) throws IOException {
		{
			if (maxConnections > 0 && activeChannels.size() >= maxConnections) {
				activeChannels.removeIf(ch -> !ch.isOpen());
				rebuildIpConnectionCounts();
				if (activeChannels.size() >= maxConnections) {
					logger.warn("Anti-DoS: Connection count limit reached ({}), rejecting new connection", maxConnections);
					clientChannel.close();
					return;
				}
			}
			if (maxConnectionsPerIp > 0 && !isUnixSocket(clientChannel)) {
				java.net.InetAddress ip = clientChannel.socket().getInetAddress();
				if (ip != null) {
					int sameIp = ipConnectionCounts.getOrDefault(ip, 0);
					if (sameIp >= maxConnectionsPerIp) {
						activeChannels.removeIf(ch -> !ch.isOpen());
						rebuildIpConnectionCounts();
						sameIp = ipConnectionCounts.getOrDefault(ip, 0);
					}
					if (sameIp >= maxConnectionsPerIp) {
						logger.warn("Anti-DoS: per-IP connection limit ({}) reached for {}, rejecting", maxConnectionsPerIp, ip);
						clientChannel.close();
						return;
					}
				}
			}
			clientChannel.configureBlocking(false);
			if (clientChannel.supportedOptions().contains(StandardSocketOptions.TCP_NODELAY)) {
				clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
			}
			ioLoop.addHandler(clientChannel, this, SelectionKey.OP_READ, ByteBuffer.allocate(READ_BUFFER_SIZE));
			activeChannels.add(clientChannel);
			if (maxConnectionsPerIp > 0 && !isUnixSocket(clientChannel)) {
				java.net.InetAddress ip = clientChannel.socket().getInetAddress();
				if (ip != null) {
					putChannelIp(clientChannel, ip);
					ipConnectionCounts.put(ip, ipConnectionCounts.getOrDefault(ip, 0) + 1);
				}
			}
			
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
			putHeaderReadTimeout(clientChannel, headerTimeout);
			ioLoop.addTimeout(headerTimeout);
			if (isSslConnection) {
				logger.debug("SSL enabled: instantiating SSLSessionHandler");
				SSLSessionHandler handler = new SSLSessionHandler(clientChannel, sslContext, ioLoop);
				putSslSessionHandler(clientChannel, handler);
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
		if (logger.isDebugEnabled()) {
			logger.debug("handle read... key: {}", key);
		}
		SocketChannel clientChannel = (SocketChannel) key.channel();
		
		org.deftserver.web.http.http2.Http2Connection http2Conn = getHttp2Connection(clientChannel);
		if (http2Conn != null) {
			prolongKeepAliveTimeout(clientChannel);
			http2Conn.onReadable();
			return;
		}
		WebSocketHandler wsHandler = getWebsocketHandler(clientChannel);
		if (wsHandler != null) {
			handleWebSocketRead(key, clientChannel, wsHandler);
			return;
		}

		if (logger.isDebugEnabled()) {
			logger.debug("handle read 2...");
		}
		SSLSessionHandler sslHandler = getSslSessionHandler(clientChannel);
		if (logger.isDebugEnabled()) {
			logger.debug("handle read: sslHandler={}, handshakeComplete={}", sslHandler, sslHandler == null ? "N/A" : sslHandler.isHandshakeComplete());
		}
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
			Timeout t = removeHeaderReadTimeout(clientChannel);
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
			Timeout t = removeHeaderReadTimeout(clientChannel);
			if (t != null) t.cancel();
			SSLSessionHandler closedSslHandler = removeSslSessionHandler(clientChannel);
			if (closedSslHandler != null) closedSslHandler.closeQuietly();
			closeChannel(clientChannel);
			return;
		} catch (Exception e) {
			logger.warn("Unexpected exception during request parsing", e);
			Timeout t = removeHeaderReadTimeout(clientChannel);
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
		Timeout t = removeHeaderReadTimeout(clientChannel);
		if (t != null) {
			t.cancel();
		}
		if (!request.isComplete()) {
			// Body still arriving. Arm a single body-read timeout (absolute cap) so a client
			// that stalls or dribbles its body cannot hold the connection open indefinitely
			// (Slowloris on the body). Armed once per request, not refreshed.
			if (!hasBodyReadTimeout(clientChannel)) {
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
				putBodyReadTimeout(clientChannel, bodyTimeout);
				ioLoop.addTimeout(bodyTimeout);
			}
			logger.debug("HttpRequest is incomplete. Waiting for more data.");
			return;
		}
		// Request fully received — clear any body-read timeout.
		Timeout bt = removeBodyReadTimeout(clientChannel);
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
			int count = incrementKeepAliveRequestCount(clientChannel);
			if (count >= MAX_KEEP_ALIVE_REQUESTS) {
				keepAlive = false;
			} else {
				keepAlive = true;
				ioLoop.addKeepAliveTimeout(clientChannel, newKeepAliveTimeout(clientChannel));
			}
		} else keepAlive = false;
		
		HttpResponse response = new HttpResponse(this, key, keepAlive, request.getMethod() == org.deftserver.web.HttpVerb.HEAD);
		response.setRequest(request);
		if (logger.isDebugEnabled()) {
			logger.debug("handle read 5...");
		}
		try {
			RequestHandler rh = application.getHandler(request);
			if (logger.isDebugEnabled()) {
				logger.debug("handle read 6...");
			}
			boolean isAsyncOrOffloaded = HttpRequestDispatcher.dispatch(rh, request, response);
			if (logger.isDebugEnabled()) {
				logger.debug("handle read 7...");
			}

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
		org.deftserver.web.http.http2.Http2Connection http2Conn = getHttp2Connection(channel);
		if (http2Conn != null) {
			prolongKeepAliveTimeout(channel);
			http2Conn.onWritable();
			return;
		}
		// Deferred control-write (WebSocket frame / 100-Continue) draining? Such a channel has no response
		// attachment to service — it's a frame-write channel — so handle it here and return.
		if (hasPendingFrameWrite(channel)) {
			prolongWriteTimeout(channel); // OP_WRITE fired ⇒ the peer made room ⇒ progress
			try {
				if (drainPendingFrameWrites(channel)) {
					cancelWriteTimeout(channel);
					if (isCloseAfterWrite(channel)) {
						closeChannel(channel); // the Close frame finished flushing — now tear down
					} else {
						ioLoop.updateHandler(channel, SelectionKey.OP_READ); // resume reading frames / the body
					}
				}
			} catch (IOException e) {
				logger.debug("Frame write failed during drain — closing channel: {}", e.getMessage());
				closeChannel(channel);
			}
			return;
		}
		Object attachment = IOLoop.getAttachment(key);
		if (logger.isDebugEnabled()) {
			logger.debug("handle write... attachment={}", attachment == null ? "null" : attachment.getClass().getSimpleName());
		}

		SSLSessionHandler sslHandler = getSslSessionHandler(channel);
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
			Object rawAtt = IOLoop.getAttachment(key);
			if (rawAtt instanceof MappedByteBuffer) {
				if (logger.isDebugEnabled()) logger.debug("mbb write #1");
				writeMappedByteBuffer(key, channel);
				if (logger.isDebugEnabled()) logger.debug("mbb write #2");
			} else if (rawAtt instanceof DynamicByteBuffer) {
				if (logger.isDebugEnabled()) logger.debug("dbb write #1");
				writeDynamicByteBuffer(key, channel);
				if (logger.isDebugEnabled()) logger.debug("dbb write #2");
			} else if (rawAtt instanceof ByteBuffer) {
				if (logger.isDebugEnabled()) logger.debug("bb write #1");
				writeByteBuffer(key, channel);
				if (logger.isDebugEnabled()) logger.debug("bb write #2");
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
		if (hasWebsocketHandler(channel)) {
			prolongWebSocketIdleTimeout(channel);
		} else if (ioLoop.hasKeepAliveTimeout(channel)) {
			prolongKeepAliveTimeout(channel);
		}
	}

	/** Writes from {@code src} to the channel, transparently encrypting via the TLS engine if this is
	 *  an HTTPS connection; otherwise a direct non-blocking socket write. Returns the byte count. */
	public int write(SocketChannel channel, ByteBuffer src) throws IOException {
		SSLSessionHandler sslHandler = getSslSessionHandler(channel);
		if (sslHandler != null) {
			return writeSecurely(channel, src, sslHandler);
		} else {
			return channel.write(src);
		}
	}

	public long write(SocketChannel channel, ByteBuffer[] srcs) throws IOException {
		SSLSessionHandler sslHandler = getSslSessionHandler(channel);
		if (sslHandler != null) {
			long written = 0;
			for (ByteBuffer src : srcs) {
				if (src.hasRemaining()) {
					written += writeSecurely(channel, src, sslHandler);
					if (src.hasRemaining()) {
						break;
					}
				}
			}
			return written;
		} else {
			return channel.write(srcs);
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

	private static long writeFully(SocketChannel channel, ByteBuffer[] srcs) throws IOException {
		long total = 0;
		long stallDeadline = 0;
		final long absoluteDeadline = System.nanoTime()
			+ HttpServerDescriptor.RESPONSE_WRITE_TIMEOUT_MS * 1_000_000L;
		boolean hasRemaining = true;
		while (hasRemaining) {
			hasRemaining = false;
			for (ByteBuffer buf : srcs) {
				if (buf.hasRemaining()) {
					hasRemaining = true;
					break;
				}
			}
			if (!hasRemaining) {
				break;
			}
			long n = channel.write(srcs);
			if (n > 0) {
				total += n;
				stallDeadline = 0;
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
				java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
			}
		}
		return total;
	}

	public void writeBlocking(SocketChannel channel, ByteBuffer src) throws IOException {
		SSLSessionHandler sslHandler = getSslSessionHandler(channel);
		if (sslHandler != null) {
			writeFully(channel, sslHandler.wrap(src));
		} else {
			writeFully(channel, src);
		}
	}

	public void writeBlocking(SocketChannel channel, ByteBuffer[] srcs) throws IOException {
		SSLSessionHandler sslHandler = getSslSessionHandler(channel);
		if (sslHandler != null) {
			for (ByteBuffer src : srcs) {
				if (src.hasRemaining()) {
					writeFully(channel, sslHandler.wrap(src));
				}
			}
		} else {
			writeFully(channel, srcs);
		}
	}

	/** True if a previously deferred control-write (WebSocket frame / 100-Continue) is still draining. */
	public boolean hasPendingFrameWrite(SocketChannel channel) {
		java.util.ArrayDeque<ByteBuffer> q = pendingFrameWrites.get(channel);
		return q != null && !q.isEmpty();
	}

	/**
	 * Non-blocking write of a small must-flush-now control buffer (a WebSocket frame, or a 100-Continue
	 * interim response). Sends what the socket accepts immediately; any unsent bytes are queued and flushed
	 * on OP_WRITE, and a peer that never drains is reaped by the idle write timeout — so a slow/non-reading
	 * peer can NEVER freeze the I/O-loop thread (unlike {@link #writeBlocking}, which parks the loop for up
	 * to the multi-second write-stall window, stalling every other connection on the loop). Ordering is
	 * preserved (FIFO): a buffer that arrives while a previous one is still draining is queued behind it.
	 * Must run on the I/O-loop thread (WebSocket sends are marshalled there via {@code addCallback}; the
	 * 100-Continue / inbound-frame paths are already on it).
	 */
	public void writeFrame(SocketChannel channel, ByteBuffer frame) throws IOException {
		SSLSessionHandler sslHandler = getSslSessionHandler(channel);
		final ByteBuffer toSend;
		if (sslHandler != null) {
			// wrap() returns a REUSED internal buffer — copy the ciphertext out before it can be deferred,
			// or the next wrap would overwrite the bytes still queued for this channel.
			toSend = copyRemaining(sslHandler.wrap(frame));
		} else {
			toSend = frame;
		}
		java.util.ArrayDeque<ByteBuffer> q = pendingFrameWrites.get(channel);
		if (q != null && !q.isEmpty()) {
			// A previous frame is still draining — queue behind it (the wire is a byte stream, so order
			// must be preserved). Copy so the caller may reuse its buffer.
			q.addLast(copyRemaining(toSend));
			armWriteTimeout(channel);
			return;
		}
		channel.write(toSend);
		if (toSend.hasRemaining()) {
			if (q == null) {
				q = new java.util.ArrayDeque<>();
				pendingFrameWrites.put(channel, q);
			}
			q.addLast(copyRemaining(toSend));
			ioLoop.updateHandler(channel, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
			armWriteTimeout(channel);
		}
	}

	/** A fresh ByteBuffer holding a copy of {@code src}'s remaining bytes (src is consumed). */
	private static ByteBuffer copyRemaining(ByteBuffer src) {
		ByteBuffer copy = ByteBuffer.allocate(src.remaining());
		copy.put(src);
		copy.flip();
		return copy;
	}

	/** Drains queued control-write buffers on OP_WRITE; returns true once the queue is empty. The caller
	 *  resumes OP_READ / closes the channel as appropriate. */
	private boolean drainPendingFrameWrites(SocketChannel channel) throws IOException {
		java.util.ArrayDeque<ByteBuffer> q = pendingFrameWrites.get(channel);
		if (q == null) {
			return true;
		}
		while (!q.isEmpty()) {
			ByteBuffer head = q.peekFirst();
			channel.write(head);
			if (head.hasRemaining()) {
				return false; // socket buffer full again — resume on the next OP_WRITE
			}
			q.removeFirst();
		}
		pendingFrameWrites.remove(channel);
		return true;
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
			MappedByteBuffer mbb = (MappedByteBuffer) IOLoop.getAttachment(key);
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
			FileStreamState fs = getFileStream(channel);
			if (fs != null && fs.pos < fs.end) {
				long windowLen = Math.min((long) FILE_WINDOW_SIZE, fs.end - fs.pos);
				MappedByteBuffer next = fs.fc.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fs.pos, windowLen);
				fs.pos += windowLen;
				IOLoop.setAttachment(key, next);
				// loop to write the next window
			} else {
				SSLSessionHandler sslHandler = getSslSessionHandler(channel);
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
			putFileStream(channel, state);
			long windowLen = Math.min((long) FILE_WINDOW_SIZE, state.end - state.pos);
			MappedByteBuffer mbb = state.fc.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, state.pos, windowLen);
			state.pos += windowLen;
			IOLoop.setAttachment(key, mbb);
			writeMappedByteBuffer(key, channel);
			handedOff = true;
		} finally {
			if (!handedOff) {
				// closeFileStream both drops the (possibly-registered) state and closes the handle;
				// fall back to a direct close if the state never made it into the map.
				if (getFileStream(channel) != null) {
					closeFileStream(channel);
				} else {
					try { raf.close(); } catch (IOException ignore) { }
				}
			}
		}
	}

	/** Releases the open file handle for an in-progress large-file stream on the channel, if any. */
	private void closeFileStream(SocketChannel channel) {
		FileStreamState state = removeFileStream(channel);
		if (state != null) {
			try { state.raf.close(); } catch (IOException ignore) { }
		}
	}

	/** Writes the dynamic response buffer attached to the key; on completion honours a deferred
	 *  Connection: close or re-registers for the next request, else defers the remainder to OP_WRITE. */
	private long writeDynamicByteBuffer(SelectionKey key, SocketChannel channel) throws IOException {
		DynamicByteBuffer dbb = (DynamicByteBuffer) IOLoop.getAttachment(key);
		if (logger.isDebugEnabled()) {
			logger.debug("pending data about to be written");
		}
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
		if (logger.isDebugEnabled()) {
			logger.debug("sent {} bytes to wire", bytesWritten);
		}
		if (!toSend.hasRemaining()) {
			SSLSessionHandler sslHandler = getSslSessionHandler(channel);
			if (sslHandler != null && sslHandler.hasPendingWrite()) {
				if (!toSend.isReadOnly()) toSend.compact();
				key.interestOps(SelectionKey.OP_WRITE);
			} else {
				if (logger.isDebugEnabled()) {
					logger.debug("sent all data in toSend buffer");
				}
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
		ByteBuffer toSend = (ByteBuffer) IOLoop.getAttachment(key);
		if (logger.isDebugEnabled()) {
			logger.debug("pending data about to be written");
		}
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
			if (logger.isDebugEnabled()) {
				logger.debug("sent {} bytes to wire", bytesWritten);
			}
		}
		if (!toSend.hasRemaining()) {
			SSLSessionHandler sslHandler = getSslSessionHandler(channel);
			if (sslHandler != null && sslHandler.hasPendingWrite()) {
				if (!toSend.isReadOnly()) toSend.compact();
				key.interestOps(SelectionKey.OP_WRITE);
			} else {
				if (logger.isDebugEnabled()) {
					logger.debug("sent all data in toSend buffer");
				}
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
		addCloseAfterWrite(channel);
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
			Timeout existing = removeProcessingTimeout(channel);
			if (existing != null) existing.cancel();
			putProcessingTimeout(channel, pt);
			ioLoop.addTimeout(pt);
		}
	}

	public void handleConnectionIdle(SelectionKey key, SocketChannel channel) throws IOException {
		if (key.isValid()) {
			ByteBuffer buf = getReadBuffer(channel);
			if (buf != null && buf.position() > 0) {
				prolongKeepAliveTimeout(channel);
				key.interestOps(0);
				IOLoop.setAttachment(key, buf);
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
		if (removeCloseAfterWrite(channel)) {
			closeChannel(channel);
		} else {
			handleConnectionIdle(key, channel);
		}
	}

	/** Arms (once) the idle response-write timeout for a deferred (non-blocking TLS) write. Idempotent. */
	public void armWriteTimeout(SocketChannel channel) {
		if (channel == null || getWriteTimeout(channel) != null) {
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
		putWriteTimeout(channel, t);
		ioLoop.addTimeout(t);
	}

	/** Resets the idle response-write timeout on write progress (the peer drained some bytes). No-op
	 *  if no write timeout is currently armed. */
	public void prolongWriteTimeout(SocketChannel channel) {
		Timeout old = removeWriteTimeout(channel);
		if (old == null) {
			return;
		}
		old.cancel();
		armWriteTimeout(channel);
	}

	/** Cancels the response-write timeout (the deferred write completed or the channel is closing). */
	public void cancelWriteTimeout(SocketChannel channel) {
		Timeout t = removeWriteTimeout(channel);
		if (t != null) {
			t.cancel();
		}
	}

	/** After a response completes, re-registers the channel for OP_READ to await the next request if
	 *  the connection is keep-alive (refreshing the idle timeout), otherwise closes it. */
	public void registerForRead(SelectionKey key) throws IOException {
		if (key.isValid()) {
			if (hasWebsocketHandler(key.channel())) {
				key.channel().register(key.selector(), SelectionKey.OP_READ, reuseAttachment(key));
				logger.debug("websocket connection. registering for read.");
			} else if (ioLoop.hasKeepAliveTimeout(key.channel())) {
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
		ByteBuffer buf = getReadBuffer(channel);
		if (buf != null) {
			buf.clear();
			return buf;
		}
		Object o = IOLoop.getAttachment(key);
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
		ByteBuffer buffer = getReadBuffer(clientChannel);
		if (buffer == null) {
			buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
			putReadBuffer(clientChannel, buffer);
		}
		if (!buffer.hasRemaining()) throw new IllegalStateException("Cleared channel buffer has no remaining space.");
		SSLSessionHandler sslHandler = getSslSessionHandler(clientChannel);
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
				removeSslSessionHandler(clientChannel);
				closeChannel(clientChannel);
				return null;
			}
			if (!plaintext.hasRemaining() && buffer.position() == 0) {
				return null;
			}
			// If we're in the middle of receiving a body, feed directly to the partial request
			// rather than going through the small key-attachment buffer.
			HttpRequest partial = getPartial(clientChannel);
			if (partial != null) {
				if (logger.isDebugEnabled()) {
					logger.debug("SSL partial body: feeding {} plaintext bytes directly to partial request", plaintext.remaining());
				}
				if (partial.putContentData(true, plaintext)) {
					removePartial(clientChannel);
					if (logger.isDebugEnabled()) {
						logger.debug("SSL partial body complete");
					}
					if (plaintext.hasRemaining()) {
						if (plaintext.remaining() > buffer.remaining()) {
							ByteBuffer grown = ByteBuffer.allocate(plaintext.remaining() + buffer.position());
							buffer.flip();
							grown.put(buffer);
							grown.put(plaintext);
							IOLoop.setAttachment(key, grown);
							putReadBuffer(clientChannel, grown);
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
				IOLoop.setAttachment(key, grown);
				putReadBuffer(clientChannel, grown);
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
			if (!buffer.hasRemaining() && !hasPartial(clientChannel)) {
				if (!containsInBB(buffer, 0, buffer.position(), HttpRequest.HTTP_HEAD_TERM_BYTES)) {
					if (buffer.capacity() >= MAX_HEADER_BUFFER_SIZE) {
						throw new HttpException(431, "Request Header Fields Too Large",
							"The request header section exceeds the maximum permitted size");
					}
					int newCap = Math.min(buffer.capacity() * 2, MAX_HEADER_BUFFER_SIZE);
					ByteBuffer grown = ByteBuffer.allocate(newCap);
					buffer.flip();
					grown.put(buffer);
					IOLoop.setAttachment(key, grown);
					putReadBuffer(clientChannel, grown);
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
		if (buffer.position() > MAX_HEADER_BUFFER_SIZE && !hasPartial(clientChannel)) {
			if (!containsInBB(buffer, 0, buffer.position(), HttpRequest.HTTP_HEAD_TERM_BYTES)) {
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
				putHttp2Connection(clientChannel, conn);
				removeReadBuffer(clientChannel);
				Timeout t = removeHeaderReadTimeout(clientChannel);
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
		if (logger.isDebugEnabled()) {
			logger.debug("getHttpRequest buffer remaining: {}", buffer.remaining());
		}
		HttpRequest req = doGetHttpRequest(key, clientChannel, buffer);
		if (req == null) buffer.rewind();
		buffer.compact(); // allow for more data to be read in

		return req;
	}

	private static boolean containsInBB(ByteBuffer buffer, int start, int limit, byte[] bytes) {
		int len = bytes.length;
		if (len == 0) return true;
		if (buffer.hasArray()) {
			byte[] arr = buffer.array();
			int offset = buffer.arrayOffset();
			for (int i = start; i <= limit - len; i++) {
				boolean match = true;
				for (int j = 0; j < len; j++) {
					if (arr[offset + i + j] != bytes[j]) {
						match = false;
						break;
					}
				}
				if (match) return true;
			}
		} else {
			for (int i = start; i <= limit - len; i++) {
				boolean match = true;
				for (int j = 0; j < len; j++) {
					if (buffer.get(i + j) != bytes[j]) {
						match = false;
						break;
					}
				}
				if (match) return true;
			}
		}
		return false;
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
		HttpRequest request = getPartial(clientChannel);
		if (request != null) {
			if (logger.isDebugEnabled()) {
				logger.debug("continuing to parse partial http request - http req #{}, remaining: {}", request.getRequestNum(), request.getRemaining());
			}
			if (request.putContentData(true, buffer)) {	// if received the entire payload/body
				if (logger.isDebugEnabled()) {
					logger.debug("entire partial http request received, removing - http req #{}, remaining: {}, flipremain: {}", request.getRequestNum(), request.getRemaining(), request.getFlipRemain());
				}
				removePartial(clientChannel);
			} else {
				key.interestOps(SelectionKey.OP_READ);
			}
		} else {
			try {
				if (!containsInBB(buffer, buffer.position(), buffer.limit(), HttpRequest.HTTP_HEAD_TERM_BYTES)) {
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
						ByteBuffer continueResponse = ByteBuffer.wrap(CONTINUE_BYTES);
						// Non-blocking, like the WS frame path: a client that asked for 100-continue but then
						// stalls reading our interim response must not pin the I/O-loop thread. If the 25-byte
						// response can't flush now it defers to OP_WRITE; the drain restores OP_READ so the body
						// read resumes once the client has actually received the 100.
						writeFrame(clientChannel, continueResponse);
						if (logger.isDebugEnabled()) {
							logger.debug("Sent HTTP/1.1 100 Continue early response");
						}
					}
					if (logger.isDebugEnabled()) {
						logger.debug("adding partial http request - http req #{}, remaining: {}", request.getRequestNum(), request.getRemaining());
					}
					putPartial(key.channel(), request);
					// Don't clobber the OP_READ|OP_WRITE that a deferred 100-Continue set — its OP_WRITE drain
					// restores OP_READ. Only force read-only when nothing is pending.
					if (!hasPendingFrameWrite(clientChannel)) {
						key.interestOps(SelectionKey.OP_READ);
					}
				} else {
					if (logger.isDebugEnabled()) {
						logger.debug("normal HttpRequest");
					}
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
		request.setSecure(getSslSessionHandler(clientChannel) != null);
		return request;
	}
	
	/** Reads and processes WebSocket frames on an upgraded connection: validates and unmasks frames,
	 *  reassembles fragmented messages (bounded), answers ping/close control frames, validates UTF-8
	 *  text, and dispatches complete messages to the handler. Prolongs the idle timeout on activity. */
	private void handleWebSocketRead(SelectionKey key, SocketChannel clientChannel, WebSocketHandler wsHandler) throws IOException {
		WebSocketConnection wsConn = getWebsocketConnection(clientChannel);
		ByteBuffer buffer = (ByteBuffer) IOLoop.getAttachment(key);
		if (buffer == null) {
			buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
			IOLoop.setAttachment(key, buffer);
		}
		int bytesRead;
		try {
			// Decrypt if SSL active
			SSLSessionHandler sslHandler = getSslSessionHandler(clientChannel);
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
						IOLoop.setAttachment(key, buffer);
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
					IOLoop.setAttachment(key, buffer);
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
					// Non-blocking: if the Close frame can't flush now (slow reader), defer it and tear the
					// channel down only once it has actually gone out — so the close handshake never parks
					// the I/O-loop thread.
					writeFrame(clientChannel, closeFrame);
					if (hasPendingFrameWrite(clientChannel)) {
						markCloseAfterWrite(clientChannel);
						return; // closeChannel happens when the OP_WRITE drain finishes the frame
					}
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
				writeFrame(clientChannel, pong); // non-blocking; defers to OP_WRITE if the socket is full
			} else if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) { // Text, Binary or Continuation
				// Reassemble fragmented messages: a data frame with FIN=0 begins a message that
				// is continued by opcode-0x0 frames until one arrives with FIN=1. Control frames
				// may be interleaved (handled above) and do not affect this state.
				java.io.ByteArrayOutputStream msgBuf = getWebsocketFragment(clientChannel);
				if (opcode != 0x0) {
					// Start of a new data message. A new data frame while one is still in
					// progress is a protocol violation.
					if (msgBuf != null) {
						logger.warn("WebSocket: new data frame while a fragmented message is in progress — closing");
						closeChannel(clientChannel);
						return;
					}
					msgBuf = new java.io.ByteArrayOutputStream();
					putWebsocketTextMessage(clientChannel, opcode == 0x1); // text vs binary
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
					boolean isText = Boolean.TRUE.equals(removeWebsocketTextMessage(clientChannel));
					byte[] msgBytes = msgBuf.toByteArray();
					removeWebsocketFragment(clientChannel);
					String textMsg = null;
					if (isText) {
						try {
							java.nio.charset.CharsetDecoder decoder = utf8Decoder.get();
							decoder.reset();
							textMsg = decoder.decode(ByteBuffer.wrap(msgBytes)).toString();
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
					putWebsocketFragment(clientChannel, msgBuf);
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

	private static final ThreadLocal<java.nio.charset.CharsetDecoder> utf8Decoder = ThreadLocal.withInitial(() -> 
		java.nio.charset.StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
			.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
	);

	private static boolean isValidUtf8(byte[] data, int offset, int length) {
		try {
			java.nio.charset.CharsetDecoder decoder = utf8Decoder.get();
			decoder.reset();
			decoder.decode(ByteBuffer.wrap(data, offset, length));
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
		SSLSessionHandler sslHandler = getSslSessionHandler(clientChannel);
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
		String className = channel.getClass().getName();
		if (className.contains("UnixDomainSocketChannel")) {
			return true;
		}
		try {
			java.net.SocketAddress local = channel.getLocalAddress();
			if (local != null && local.getClass().getName().contains("UnixDomainSocketAddress")) {
				return true;
			}
			java.net.SocketAddress remote = channel.getRemoteAddress();
			if (remote != null && remote.getClass().getName().contains("UnixDomainSocketAddress")) {
				return true;
			}
		} catch (Exception e) {}
		return false;
	}

	@Override
	public String toString() { return "HttpProtocol"; }

	private void rebuildIpConnectionCounts() {
		for (ChannelState state : connectionStates.values()) {
			state.ip = null;
		}
		ipConnectionCounts.clear();
		for (SocketChannel ch : activeChannels) {
			if (!isUnixSocket(ch)) {
				try {
					java.net.InetAddress ip = ch.socket().getInetAddress();
					if (ip != null) {
						putChannelIp(ch, ip);
						ipConnectionCounts.put(ip, ipConnectionCounts.getOrDefault(ip, 0) + 1);
					}
				} catch (Exception ignore) {}
			}
		}
	}
}
