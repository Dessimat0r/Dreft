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

	private final Map<SocketChannel, Timeout> headerReadTimeouts = new HashMap<>();
	private int maxConnections = -1;
	private int activeConnections = 0;

	public void setMaxConnections(int max) {
		this.maxConnections = max;
	}

	public int getActiveConnections() {
		return activeConnections;
	}

	public void closeChannel(SocketChannel channel) {
		if (channel != null) {
			sslSessionHandlers.remove(channel);
			websocketHandlers.remove(channel);
			websocketConnections.remove(channel);
			partials.remove(channel);
			Timeout t = headerReadTimeouts.remove(channel);
			if (t != null) {
				t.cancel();
			}
			if (channel.isOpen()) {
				activeConnections--;
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
			if (maxConnections > 0 && activeConnections >= maxConnections) {
				logger.warn("Anti-DoS: Connection count limit reached ({}), rejecting new connection", maxConnections);
				clientChannel.close();
				return;
			}
			// could be null in a multithreaded deft environment because another ioloop was "faster" to accept()
			clientChannel.configureBlocking(false);
			clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
			ioLoop.addHandler(clientChannel, this, SelectionKey.OP_READ, ByteBuffer.allocate(READ_BUFFER_SIZE));
			activeConnections++;
			
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
		
		Timeout t = headerReadTimeouts.remove(clientChannel);
		if (t != null) {
			t.cancel();
		}
		if (!request.isComplete()) {
			logger.debug("HttpRequest is incomplete. Waiting for more data.");
			return;
		}
		logger.debug("handle read 3..., req class: {}, req: {}", request.getClass(), request);
		
		final boolean keepAlive;
		
		if (!(request instanceof MalFormedHttpRequest) && request.isKeepAlive()) {
			keepAlive = true;
			ioLoop.addKeepAliveTimeout(
				clientChannel, 
				Timeout.newKeepAliveTimeout(ioLoop, clientChannel, KEEP_ALIVE_TIMEOUT)
			);
		} else keepAlive = false;
		
		HttpResponse response = new HttpResponse(this, key, keepAlive, request.getMethod() == org.deftserver.web.HttpVerb.HEAD);
		response.setRequest(request);
		logger.debug("handle read 5...");
		RequestHandler rh = application.getHandler(request);
		logger.debug("handle read 6...");
		boolean isAsyncOrOffloaded = HttpRequestDispatcher.dispatch(rh, request, response);
		logger.debug("handle read 7...");
		
		//Only close if not async or offloaded. In that case its up to RH or Virtual Thread to close it (+ don't close if it's a partial request).
		if (request instanceof MalFormedHttpRequest || (!isAsyncOrOffloaded && request.isComplete())) {
			response.finish();
		}
	}

	@Override
	public void handleWrite(SelectionKey key) throws IOException {
		logger.debug("handle write... attachment={}", key.attachment().getClass().getSimpleName());
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
		} catch (RuntimeException e) {
			// Unexpected failure in a write helper — close channel so all channel-state
			// maps (partials, timeouts, WS handlers) are cleaned up and the channel
			// slot is freed for future connections.
			logger.error("Unexpected RuntimeException in handleWrite — closing channel", e);
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

	private int writeSecurely(SocketChannel channel, ByteBuffer src, SSLSessionHandler sslHandler) throws IOException {
		ByteBuffer encrypted = sslHandler.wrap(src);
		int written = 0;
		while (encrypted.hasRemaining()) {
			written += channel.write(encrypted);
		}
		logger.debug("writeSecurely: wrote {} bytes to socket", written);
		return written;
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
		}
		buffer.flip();
		logger.debug("getHttpRequest buffer remaining: {}", buffer.remaining());
		HttpRequest req = doGetHttpRequest(key, clientChannel, buffer);
		if (req == null) buffer.rewind();
		buffer.compact(); // allow for more data to be read in
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
					if (expect != null && expect.equalsIgnoreCase("100-continue")) {
						int clen = request.getContentLength();
						if (clen > HttpRequest.MAX_BODY_SIZE) {
							throw new HttpException(413, "Payload Too Large", "Payload size exceeds maximum allowed limit");
						}
						ByteBuffer continueResponse = ByteBuffer.wrap("HTTP/1.1 100 Continue\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
						while (continueResponse.hasRemaining()) {
							write(clientChannel, continueResponse);
						}
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
			
			byte[] maskingKey = null;
			if (masked) {
				maskingKey = new byte[4];
				buffer.get(maskingKey);
			}
			
			if (buffer.remaining() < actualLen) {
				buffer.reset();
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
				// Send Pong frame
				ByteBuffer pong = ByteBuffer.allocate(2 + payload.length);
				pong.put((byte) 0x8A); // Pong opcode
				pong.put((byte) payload.length);
				pong.put(payload);
				pong.flip();
				while (pong.hasRemaining()) {
					write(clientChannel, pong);
				}
			} else if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) { // Text, Binary or Continuation
				String msg = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
				if (wsConn != null) {
					try {
						wsHandler.onMessage(wsConn, msg);
					} catch (RuntimeException e) {
						logger.error("Uncaught exception in WebSocket onMessage handler — closing channel", e);
						closeChannel(clientChannel);
						return;
					}
				}
			}
		}
		buffer.compact();
	}
	
	@Override
	public String toString() { return "HttpProtocol"; }
}
