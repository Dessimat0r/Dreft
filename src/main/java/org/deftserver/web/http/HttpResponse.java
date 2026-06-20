package org.deftserver.web.http;

import static org.deftserver.web.http.HttpServerDescriptor.WRITE_BUFFER_SIZE;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;

import org.deftserver.io.IOLoop;
import org.deftserver.io.buffer.DynamicByteBuffer;
import org.deftserver.util.DateUtil;
import org.deftserver.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class HttpResponse {
	
	private static final byte[] CRLF = { '\r', '\n' };
	private static final byte[] ZERO_CRLF_CRLF = { '0', '\r', '\n', '\r', '\n' };
	
	private final static Logger logger = LoggerFactory.getLogger(HttpResponse.class);
	
	private final HttpProtocol protocol;
	private final SelectionKey key;
	
	protected int statusCode = 200;	// default response status code
	
	protected final Map<String, String> headers = new HashMap<String, String>();
	protected boolean headersCreated = false;
	// True once the CPU-heavy response finalization (gzip + ETag over the whole body, both O(body size))
	// has run, so it happens exactly once whether done off-loop by the dispatcher's virtual thread
	// (finalizeFramingOffLoop) or on the loop by finish(). See setEtagAndContentLength.
	private boolean framingFinalized = false;
	private final DynamicByteBuffer responseData = DynamicByteBuffer.allocate(WRITE_BUFFER_SIZE);
	private final boolean suppressBody;
	
	private HttpRequest request;
	private boolean useChunked = false;
	private String compressionEncoding = null;
	protected boolean finished = false;
	private boolean suppressContentLength = false;
	protected long contentLength = -1;
	protected final java.util.List<Cookie> cookies = new java.util.ArrayList<>();

	/** Queues a cookie to be emitted as its own {@code Set-Cookie} response header line. */
	public void setCookie(Cookie cookie) {
		cookies.add(cookie);
	}

	/** Associates the originating request, used for conditional-request, keep-alive and
	 *  protocol-version decisions taken while framing the response. */
	public void setRequest(HttpRequest request) {
		this.request = request;
	}

	/** Convenience constructor: builds a response that sends its body (not a HEAD). */
	public HttpResponse(HttpProtocol protocol, SelectionKey key, boolean keepAlive) {
		this(protocol, key, keepAlive, false);
	}

	/**
	 * Builds the response for a single request. Seeds the mandatory {@code Server}/{@code Date}
	 * headers and the {@code Connection} disposition (Keep-Alive vs Close, per {@code keepAlive}).
	 * {@code suppressBody} is true for HEAD requests — headers/Content-Length are still computed
	 * but the body bytes are never written.
	 */
	public HttpResponse(HttpProtocol protocol, SelectionKey key, boolean keepAlive, boolean suppressBody) {
		this.protocol = protocol;
		this.key = key;
		this.suppressBody = suppressBody;
		headers.put("Server", "Dreft/0.4.0-SNAPSHOT");
		headers.put("Date", DateUtil.getCurrentAsString());
		headers.put("Connection", keepAlive ? "Keep-Alive" : "Close");
	}

	/** The client socket channel this response is being written to. */
	public SocketChannel getChannel() {
		return (SocketChannel) key.channel();
	}

	/** The owning protocol (used for SSL-aware writes, channel teardown and I/O-loop hand-off). */
	public HttpProtocol getProtocol() {
		return protocol;
	}

	/** True once the status line + headers have been emitted to the socket — i.e. the handler has
	 *  already performed network I/O (via {@code flush()}/{@code write(File)}/{@code write(ByteBuffer)}).
	 *  The dispatcher uses this to refuse to adaptively offload such a handler onto a virtual thread,
	 *  since its NIO (selector registration / buffered writes) must stay on the I/O-loop thread. */
	public boolean hasFlushedHeaders() {
		return headersCreated;
	}

	/** True once {@link #finish()} has run. Used to tell a still-pending async response (which needs a
	 *  processing timeout) from one that already completed synchronously during dispatch (e.g. a
	 *  WebSocket upgrade, which is marked async but sends its 101 inline and manages its own timeouts). */
	public boolean hasFinished() {
		return finished;
	}

	/** Sets the HTTP status code for this response (default 200). */
	public void setStatusCode(int sc) {
		statusCode = sc;
	}

	public void setHeader(String header, String value) {
		validateHeaderField(header, value);
		if (header.equalsIgnoreCase("Content-Length")) {
			try {
				contentLength = Long.parseLong(value);
			} catch (NumberFormatException ignored) {}
			return;
		}
		removeHeader(header);
		headers.put(header, value);
	}

	private boolean hasHeader(String name) {
		if (name.equalsIgnoreCase("Content-Length")) {
			return contentLength != -1;
		}
		for (String key : headers.keySet()) {
			if (key.equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	private String getHeaderValue(String name) {
		if (name.equalsIgnoreCase("Content-Length")) {
			return contentLength == -1 ? null : String.valueOf(contentLength);
		}
		for (Map.Entry<String, String> entry : headers.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name)) {
				return entry.getValue();
			}
		}
		return null;
	}

	private void removeHeader(String name) {
		if (name.equalsIgnoreCase("Content-Length")) {
			contentLength = -1;
		}
		for (String key : headers.keySet()) {
			if (key.equalsIgnoreCase(name)) {
				headers.remove(key);
				break;
			}
		}
	}

	/**
	 * Adds a field name to the {@code Vary} header without clobbering any value already present
	 * (case-insensitive de-dup). {@code Vary} is a SET of header names, so it must be merged rather
	 * than replaced — this lets the CORS layer ({@code Origin}), the gzip path ({@code Accept-Encoding})
	 * and a handler's own {@code Vary} coexist regardless of the order in which they run, so a cache
	 * never serves the wrong compressed/CORS/negotiated representation to another client.
	 */
	public void addVary(String fieldName) {
		String existing = getHeaderValue("Vary");
		if (existing == null || existing.isEmpty()) {
			setHeader("Vary", fieldName);
			return;
		}
		if ("*".equals(existing.trim())) {
			return; // already varies on everything
		}
		int len = existing.length();
		int fieldLen = fieldName.length();
		int i = 0;
		while (i < len) {
			while (i < len && existing.charAt(i) == ' ') i++;
			int start = i;
			while (i < len && existing.charAt(i) != ',') i++;
			int end = i;
			while (end > start && existing.charAt(end - 1) == ' ') end--;
			if (end - start == fieldLen && existing.regionMatches(true, start, fieldName, 0, fieldLen)) {
				return; // already listed
			}
			i++;
		}
		setHeader("Vary", existing + ", " + fieldName);
	}

	/**
	 * Rejects header names/values that could break out of the header into the status/header
	 * stream (CR, LF, NUL and other control characters) — the central defence against HTTP
	 * response-splitting/header-injection for any value a handler reflects from user input
	 * (e.g. a redirect Location or a reflected CORS Origin). HT is the only control char
	 * permitted in a field value (RFC 9110 §5.5); names must be a non-empty token.
	 */
	private static void validateHeaderField(String name, String value) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("Header name must not be null or empty");
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c <= 0x20 || c == 0x7f || c == ':') {
				throw new IllegalArgumentException("Illegal character in header name: " + name);
			}
		}
		// Reject a null value fail-fast (with a clear message) rather than letting it surface as a
		// confusing NullPointerException later during header serialization.
		if (value == null) {
			throw new IllegalArgumentException("Header value must not be null for header: " + name);
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if ((c < 0x20 && c != '\t') || c == 0x7f) {
				throw new IllegalArgumentException("Illegal control character in value for header: " + name);
			}
		}
	}

	/**
	 * The given data data will be sent as the HTTP response upon next flush or when the response is finished.
	 *
	 * @return this for chaining purposes.
	 */
	public HttpResponse write(String data) {
		byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
		responseData.put(bytes);
		return this;
	}

	/**
	 * Explicit flush. 
	 * 
	 * @return the number of bytes that were actually written as the result of this flush.
	 */
	public long flush() {
		long bytesWritten = 0;
		try {
			SocketChannel channel = (SocketChannel) key.channel();
			if (!headersCreated) {
				if (!useChunked && !hasHeader("Content-Length") && !suppressBody) {
					setHeader("Connection", "Close");
				}
				if (useChunked && responseData.position() > 0) {
					int len = responseData.position();
					byte[] prefix = getChunkHeaderBytes(len);
					responseData.prepend(prefix);
					responseData.put(CRLF);
				}
				if (suppressBody) {
					responseData.clear();
				}
				int headerLen = calculateHeadersLength();
				ByteBuffer headerBuf = ByteBuffer.allocate(headerLen);
				writeHeadersToBuffer(headerBuf.array(), 0);
				headerBuf.limit(headerLen);
				headersCreated = true;

				responseData.flip();
				ByteBuffer bodyBuf = responseData.getByteBuffer();
				ByteBuffer[] srcs = { headerBuf, bodyBuf };

				long written = 0;
				do {
					long n = protocol.write(channel, srcs);
					if (n <= 0) break;
					written += n;
				} while (channel.isConnected() && (headerBuf.hasRemaining() || bodyBuf.hasRemaining()));

				bytesWritten += written;

				if (headerBuf.hasRemaining()) {
					int remHeaders = headerBuf.remaining();
					responseData.compact();
					byte[] arr = responseData.prependSpace(remHeaders);
					headerBuf.get(arr, 0, remHeaders);
				} else {
					responseData.compact();
				}
			} else {
				responseData.flip();
				if (responseData.hasRemaining()) {
					int written = 0;
					do {
						written = protocol.write(channel, responseData.getByteBuffer());
						bytesWritten += written;
					} while (channel.isConnected() && written > 0 && responseData.hasRemaining());
				}
				if (responseData.hasRemaining()) {
					responseData.compact();
				}
			}
			if (protocol.getIOLoop().hasKeepAliveTimeout(channel)) {
				protocol.prolongKeepAliveTimeout(channel);
			}
			SSLSessionHandler sslHandler = protocol.getSslSessionHandler(channel);
			boolean sslPending = (sslHandler != null && sslHandler.hasPendingWrite());
			if (responseData.hasRemaining() || sslPending) {
				key.channel().register(key.selector(), SelectionKey.OP_WRITE, responseData);
			}
			if (sslPending) {
				protocol.armWriteTimeout(channel);
			}
		} catch (IOException e) {
			logger.debug("Client disconnected during flush (broken pipe): {}", e.getMessage());
			protocol.closeChannel((SocketChannel) key.channel());
			return 0;
		} finally {
			if (!responseData.hasRemaining()) {
				responseData.clear();
			}
		}
		return bytesWritten;
	}
	
	/**
	 * Completes the response: emits any remaining body (including the chunked terminator / mapped-file
	 * window), decides the conditional-request outcome and final framing, then either closes the
	 * connection (Connection: close) or re-arms it for the next keep-alive request. Idempotent — a
	 * second call (e.g. the dispatcher finishing an already-finished async response) is a no-op.
	 *
	 * @return the number of bytes written by this call.
	 */
	public long finish() {
		if (finished) {
			return 0;
		}
		finished = true;
		try {
			long bytesWritten = 0;
			SocketChannel clientChannel = (SocketChannel) key.channel();
	
			Object rawAtt = IOLoop.getAttachment(key);
			if (rawAtt instanceof MappedByteBuffer) {
				MappedByteBuffer mbb = (MappedByteBuffer) rawAtt;
				if (mbb.hasRemaining()) {
					int written = 0;
					do {
						written = protocol.write(clientChannel, mbb);
						bytesWritten += written;
					} while (written > 0 && mbb.hasRemaining() && clientChannel.isOpen());
				}
				boolean closeConnection = "close".equalsIgnoreCase(getHeaderValue("Connection"));
				SSLSessionHandler sslHandler = protocol.getSslSessionHandler(clientChannel);
				if (sslHandler != null && sslHandler.hasPendingWrite()) {
					if (closeConnection) {
						protocol.markCloseAfterWrite(clientChannel);
					}
					// Deferred write still draining — reap the peer if it stalls reading the response.
					protocol.armWriteTimeout(clientChannel);
				} else {
					if (!mbb.hasRemaining()) {
						finishConnection(closeConnection);
					} else {
						if (closeConnection) {
							protocol.markCloseAfterWrite(clientChannel);
						}
					}
				}
			} else {
				if (clientChannel.isOpen()) {
					if (!headersCreated) {
						setEtagAndContentLength();
					}
					if (useChunked) {
						if (responseData.position() > 0) {
							int len = responseData.position();
							byte[] prefix = getChunkHeaderBytes(len);
							responseData.prepend(prefix);
							responseData.put(CRLF);
						}
						responseData.put(ZERO_CRLF_CRLF);
						if (!headersCreated) {
							if (suppressBody) {
								responseData.clear();
							}
							int headerLen = calculateHeadersLength();
							byte[] arr = responseData.prependSpace(headerLen);
							writeHeadersToBuffer(arr, 0);
							headersCreated = true;
						}
						bytesWritten = flush();
					} else {
						bytesWritten = flush();
					}
				}
				boolean closeConnection = "close".equalsIgnoreCase(getHeaderValue("Connection"));
				SSLSessionHandler sslHandler = protocol.getSslSessionHandler(clientChannel);
				if (sslHandler != null && sslHandler.hasPendingWrite()) {
					if (closeConnection) {
						protocol.markCloseAfterWrite(clientChannel);
					}
					// Deferred write still draining — reap the peer if it stalls reading the response.
					protocol.armWriteTimeout(clientChannel);
				} else {
					boolean isWriting = key.isValid() && (key.interestOps() & SelectionKey.OP_WRITE) != 0;
					if (isWriting) {
						rawAtt = IOLoop.getAttachment(key);
						if (rawAtt instanceof DynamicByteBuffer) {
							DynamicByteBuffer dbb = (DynamicByteBuffer) rawAtt;
							if (!dbb.hasRemaining()) {
								finishConnection(closeConnection);
							} else if (closeConnection) {
								protocol.markCloseAfterWrite(clientChannel);
							}
						} else if (rawAtt instanceof ByteBuffer) {
							ByteBuffer bb = (ByteBuffer) rawAtt;
							if (!bb.hasRemaining()) {
								finishConnection(closeConnection);
							} else if (closeConnection) {
								protocol.markCloseAfterWrite(clientChannel);
							}
						}
					} else {
						finishConnection(closeConnection);
					}
				}
			}
			return bytesWritten;
		} catch (IOException e) {
			logger.debug("Client disconnected during finish (broken pipe): {}", e.getMessage());
			protocol.closeChannel((SocketChannel) key.channel());
			return 0;
		}
	}	

	/**
	 * Package-private hook for the {@link HttpRequestDispatcher} virtual-thread offload: runs the
	 * CPU-heavy response finalization (gzip compression + ETag over the whole body, both O(body size))
	 * OFF the I/O-loop thread, so that {@link #finish()} — which the dispatcher marshals back to the loop
	 * — only has to do the (non-blocking) socket write. Without this a multi-MB dynamic response would
	 * gzip+hash on the loop, stalling every other connection for the duration. Idempotent (guarded by
	 * {@code framingFinalized}) and a no-op once headers have already been flushed (a streaming handler),
	 * so it never re-frames an in-flight response. Touches only the request headers and this response's
	 * own buffer, so it's safe to run off-loop.
	 */
	void finalizeFramingOffLoop() {
		if (!headersCreated) {
			setEtagAndContentLength();
		}
	}

	/** Finalises body framing before sending: applies gzip where appropriate, computes the ETag,
	 *  evaluates conditional-request preconditions (304/412), and sets Content-Length or chunked
	 *  Transfer-Encoding (clearing the body for bodiless statuses). Idempotent: runs its work at most
	 *  once (it may be invoked off-loop via {@link #finalizeFramingOffLoop} and then again by finish()). */
	private void setEtagAndContentLength() {
		if (framingFinalized) {
			return; // already finalized (e.g. off-loop by the dispatcher) — don't gzip/hash/evaluate twice
		}
		framingFinalized = true;
		// gzip here is framed with Transfer-Encoding: chunked, which HTTP/1.0 clients cannot parse
		// — only enable it for HTTP/1.1 requests so a 1.0 client gets an identity, Content-Length
		// response instead of an unreadable chunked one. Also never gzip a 206 Partial Content
		// response: re-encoding the body would invalidate the byte range / Content-Range. And never
		// gzip when the handler already set a Content-Encoding (e.g. it pre-compressed the body) —
		// that would double-encode it into garbage.
		if (request != null && !isBodySuppressed() && responseData.position() > 0
				&& statusCode != 206
				&& !hasHeader("Content-Encoding")
				&& "HTTP/1.1".equals(request.getVersion())) {
			String acceptEncoding = request.getHeader("Accept-Encoding");
			String preferred = org.deftserver.util.HttpUtil.getPreferredCompression(acceptEncoding);
			if (preferred != null) {
				String contentType = getHeaderValue("Content-Type");
				// RFC 9110 §8.3.1: media types are case-insensitive. Fold to lower (Locale.ROOT,
				// per the project convention) so "TEXT/HTML" or "Application/JSON" still compress.
				String ctLower = contentType == null ? null
					: contentType.toLowerCase(java.util.Locale.ROOT);
				if (ctLower != null && (
					ctLower.contains("text") ||
					ctLower.contains("json") ||
					ctLower.contains("xml") ||
					ctLower.contains("javascript"))) {
					compressionEncoding = preferred;
					useChunked = true;
					setHeader("Content-Encoding", preferred);
					setHeader("Transfer-Encoding", "chunked");
					// The representation now depends on Accept-Encoding; tell caches so they
					// don't serve gzipped bytes to a client that didn't ask for them. Merge (don't
					// clobber any Origin/handler Vary already set).
					addVary("Accept-Encoding");
				}
			}
		}

		// A CORS response varies by Origin. Re-ensure it here, at finish time (after the handler has
		// run), so a handler that set its own Vary can't drop the Origin token the CORS layer added
		// before dispatch — which would let a cache serve one origin's response to another.
		if (hasHeader("Access-Control-Allow-Origin")
				&& !"*".equals(getHeaderValue("Access-Control-Allow-Origin"))) {
			addVary("Origin");
		}

		if (statusCode / 100 == 1 || statusCode == 204 || statusCode == 304) {
			markBodiless();
			return;
		}

		if (compressionEncoding != null && responseData.position() > 0) {
			try {
				byte[] compressed = HttpUtil.compress(
					responseData.array(), 0, responseData.position(), compressionEncoding
				);
				responseData.clear();
				responseData.put(compressed);
			} catch (IOException e) {
				logger.error("Error compressing response: {}", e.getMessage());
			}
		}

		String etag = null;
		if (responseData.position() > 0) {
			// RFC 9110 §8.8.3: an ETag is a quoted-string. Quoting is also required for
			// If-None-Match / If-Match to match, since clients echo the quoted value back.
			etag = "\"" + HttpUtil.getEtag(protocol.getIOLoop().getMd5(), responseData.array(), 0, responseData.position()) + "\"";
			// Use the RFC 9110 §8.8.3 canonical spelling "ETag" (matches the static handler and what
			// case-sensitive clients/caches expect), even though header names are case-insensitive.
			setHeader("ETag", etag);
		}

		if (request != null) {
			// RFC 9110 §13.2.2 fixes the precedence order so combinations are deterministic:
			//   1. If-Match            2. If-Unmodified-Since (only if If-Match absent)
			//   3. If-None-Match       4. If-Modified-Since   (only if If-None-Match absent)
			// Evaluating these out of order (e.g. If-None-Match before If-Match) produces the wrong
			// status for contradictory combinations — e.g. If-Match:"v2" + If-None-Match:"v1" against
			// current "v1" must be 412 (If-Match fails first), not 304.
			String im = request.getHeader("If-Match");
			String inm = request.getHeader("If-None-Match");
			String ius = request.getHeader("If-Unmodified-Since");
			String ims = request.getHeader("If-Modified-Since");
			String lm = getHeaderValue("Last-Modified");

			// 1. If-Match: precondition fails (→412) if the current ETag matches none of the listed tags.
			if (im != null && etag != null && !ifMatchHeaderMatches(im, etag)) {
				setStatusCode(412);
				markBodiless();
				return;
			}
			// 2. If-Unmodified-Since: only when If-Match is absent (§13.1.4). 412 if modified since.
			if (im == null && ius != null && lm != null) {
				long iusTime = DateUtil.parseRFC1123ToMillis(ius);
				long lmTime = DateUtil.parseRFC1123ToMillis(lm);
				if (iusTime != -1 && lmTime != -1 && lmTime > iusTime) {
					setStatusCode(412);
					markBodiless();
					return;
				}
			}
			// RFC 9110 §13.1.2/§13.1.3: If-None-Match and If-Modified-Since are only "not modified"
			// (304) semantics for GET/HEAD. For any other method a *matching* If-None-Match is a
			// precondition failure (412), and If-Modified-Since MUST be ignored entirely.
			org.deftserver.web.HttpVerb method = request.getMethod();
			boolean isGetOrHead = method == org.deftserver.web.HttpVerb.GET
					|| method == org.deftserver.web.HttpVerb.HEAD;
			// 3. If-None-Match: matches → 304 for GET/HEAD, 412 for any other method.
			if (inm != null && etag != null && ifMatchHeaderMatches(inm, etag)) {
				setStatusCode(isGetOrHead ? 304 : 412);
				markBodiless();
				return;
			}
			// 4. If-Modified-Since: only when If-None-Match is absent (§13.1.3) and only for GET/HEAD.
			if (isGetOrHead && inm == null && ims != null && lm != null) {
				long imsTime = DateUtil.parseRFC1123ToMillis(ims);
				long lmTime = DateUtil.parseRFC1123ToMillis(lm);
				if (imsTime != -1 && lmTime != -1 && lmTime <= imsTime) {
					setStatusCode(304);
					markBodiless();
					return;
				}
			}
		}

		if (useChunked) {
			removeHeader("Content-Length");
			setHeader("Transfer-Encoding", "chunked");
		} else if (isBodySuppressed() && hasHeader("Content-Length")) {
			// HEAD: keep the Content-Length the handler set (the size the GET body would be).
			// The actual body bytes are absent, so don't overwrite it with the empty-buffer size.
		} else {
			setHeader("Content-Length", String.valueOf(responseData.position()));
		}
	}
	
	/**
	 * Turns the response into a valid bodiless response (304/412/204/1xx): drops the buffered
	 * body and clears any body-framing/encoding state that gzip may have enabled, so we never
	 * emit a contradictory 304 carrying Transfer-Encoding: chunked + Content-Encoding: gzip +
	 * Content-Length together (which would also make finish() write a stray chunk terminator).
	 */
	/** Either closes the connection (Connection: close) or re-arms it for the next keep-alive
	 *  request, once the response body has been fully written. */
	private void finishConnection(boolean closeConnection) throws IOException {
		if (closeConnection) {
			protocol.closeChannel((SocketChannel) key.channel());
		} else {
			protocol.handleConnectionIdle(key, (SocketChannel) key.channel());
		}
	}

	/** Strips any body and body-encoding framing for a bodiless response (1xx/204/304), leaving
	 *  Content-Length: 0. */
	private void markBodiless() {
		responseData.clear();
		useChunked = false;
		compressionEncoding = null;
		removeHeader("Transfer-Encoding");
		removeHeader("Content-Encoding");
		// Content-Length: 0 is the unambiguous framing for a bodiless response. Note: although a 1xx
		// (e.g. 101 Switching Protocols) strictly carries no Content-Length, the JDK java.net.http
		// WebSocket client rejects a 101 handshake that lacks it — so we keep it for interop
		// (robustness/interop over a pedantic reading; a 1xx body is forbidden regardless, RFC 9110
		// §6.2, so the header is harmless).
		setHeader("Content-Length", "0");
		// RFC 9110 §8.6: a server MUST NOT send Content-Length on 1xx (except 101), 204, or 304.
		// createInitalLineAndHeaders() checks the flag and skips it for non-101 statuses.
		suppressContentLength = true;
	}

	private static int stringSize(long x) {
		if (x == 0) return 1;
		int len = 0;
		long temp = x;
		if (temp < 0) {
			len++;
			temp = -temp;
		}
		while (temp > 0) {
			len++;
			temp /= 10;
		}
		return len;
	}

	private static int writeLongToBuffer(byte[] arr, int cur, long value) {
		if (value == 0) {
			arr[cur++] = '0';
			return cur;
		}
		int size = stringSize(value);
		int nextPos = cur + size;
		long temp = value;
		while (temp > 0) {
			long digit = temp % 10;
			arr[--nextPos] = (byte) ('0' + digit);
			temp /= 10;
		}
		return cur + size;
	}

	private int calculateHeadersLength() {
		int len = 0;
		String initialLine = HttpUtil.createInitialLine(statusCode);
		len += initialLine.length();
		for (Map.Entry<String, String> header : headers.entrySet()) {
			len += header.getKey().length();
			len += 2; // ": "
			len += header.getValue().length();
			len += 2; // "\r\n"
		}
		boolean writeCl = (contentLength != -1) && (!suppressContentLength || statusCode == 101);
		if (writeCl) {
			len += 16; // "Content-Length: "
			len += stringSize(contentLength);
			len += 2; // "\r\n"
		}
		for (Cookie cookie : cookies) {
			len += 12; // "Set-Cookie: "
			len += cookie.toString().length();
			len += 2; // "\r\n"
		}
		len += 2; // "\r\n"
		return len;
	}

	private int writeHeadersToBuffer(byte[] arr, int offset) {
		int cur = offset;
		String initialLine = HttpUtil.createInitialLine(statusCode);
		int initialLen = initialLine.length();
		for (int i = 0; i < initialLen; i++) {
			arr[cur++] = (byte) initialLine.charAt(i);
		}
		for (Map.Entry<String, String> header : headers.entrySet()) {
			String key = header.getKey();
			int keyLen = key.length();
			for (int i = 0; i < keyLen; i++) {
				arr[cur++] = (byte) key.charAt(i);
			}
			arr[cur++] = ':';
			arr[cur++] = ' ';
			String val = header.getValue();
			int valLen = val.length();
			for (int i = 0; i < valLen; i++) {
				arr[cur++] = (byte) val.charAt(i);
			}
			arr[cur++] = '\r';
			arr[cur++] = '\n';
		}
		boolean writeCl = (contentLength != -1) && (!suppressContentLength || statusCode == 101);
		if (writeCl) {
			String prefix = "Content-Length: ";
			for (int i = 0; i < prefix.length(); i++) {
				arr[cur++] = (byte) prefix.charAt(i);
			}
			cur = writeLongToBuffer(arr, cur, contentLength);
			arr[cur++] = '\r';
			arr[cur++] = '\n';
		}
		for (Cookie cookie : cookies) {
			String cookieStr = cookie.toString();
			String prefix = "Set-Cookie: ";
			for (int i = 0; i < prefix.length(); i++) {
				arr[cur++] = (byte) prefix.charAt(i);
			}
			int cookieLen = cookieStr.length();
			for (int i = 0; i < cookieLen; i++) {
				arr[cur++] = (byte) cookieStr.charAt(i);
			}
			arr[cur++] = '\r';
			arr[cur++] = '\n';
		}
		arr[cur++] = '\r';
		arr[cur++] = '\n';
		return cur - offset;
	}
	
	/**
	 * Streaming write of raw bytes: emits the status line + headers on the first call (setting
	 * Content-Length to this buffer's size, or chunk-framing if {@link #useChunked}) then the body,
	 * flushing immediately. Intended for a single full-body write or for repeated *chunked* writes;
	 * the per-call flush routes through OP_WRITE so a full socket never silently drops payload.
	 *
	 * @return the number of bytes written by this call.
	 */
	public long write(ByteBuffer data) {
		int size = data.remaining();

		if (!headersCreated) {
			if (useChunked) {
				setHeader("Transfer-Encoding", "chunked");
				removeHeader("Content-Length");
			} else {
				setHeader("Content-Length", String.valueOf(size));
			}
		} else if (!useChunked) {
			throw new IllegalStateException("Non-chunked write(ByteBuffer) only supports a single call; use chunked mode or write(String) for multiple writes");
		}

		long bytesWritten = flush();
		if (isBodySuppressed()) {
			return bytesWritten;
		}

		if (data.hasRemaining()) {
			if (useChunked) {
				responseData.put(getChunkHeaderBytes(data.remaining()));
				responseData.put(data);
				responseData.put(CRLF);
			} else {
				responseData.put(data);
			}
			bytesWritten += flush();
		}
		return bytesWritten;
	}
	
	/**
	 * Experimental support.
	 * Before use, read https://github.com/rschildmeijer/deft/issues/75
	 */
	public long write(File file) {
		try {
			logger.debug("cl-httpresp3");
			setHeader("Content-Length", String.valueOf(file.length()));
			long bytesWritten = 0;

			if (isBodySuppressed()) {
				flush();
				return 0;
			}

			// Files larger than a single mmap window are served in chunks (avoids the 2 GiB
			// FileChannel.map limit). The protocol layer owns the windowing + cleanup.
			if (file.length() > HttpProtocol.FILE_WINDOW_SIZE) {
				flush(); // write initial line + headers only
				protocol.streamLargeFile(key, file, 0L, file.length());
				return bytesWritten;
			}

			java.nio.ByteBuffer cachedMbb = org.deftserver.web.handler.StaticContentHandler.getMappedBuffer(file);
			if (cachedMbb != null) {
				if (!headersCreated) {
					int headerLen = calculateHeadersLength();
					ByteBuffer headerBuf = ByteBuffer.allocate(headerLen);
					writeHeadersToBuffer(headerBuf.array(), 0);
					headerBuf.limit(headerLen);
					headersCreated = true;
					ByteBuffer[] srcs = { headerBuf, cachedMbb };
					long written = 0;
					do {
						long n = protocol.write(((SocketChannel) key.channel()), srcs);
						if (n <= 0) break;
						written += n;
					} while (headerBuf.hasRemaining() || cachedMbb.hasRemaining());
					bytesWritten += written;
					if (headerBuf.hasRemaining() || cachedMbb.hasRemaining()) {
						DynamicByteBuffer pending = DynamicByteBuffer.allocate(
							headerBuf.remaining() + cachedMbb.remaining());
						pending.put(headerBuf);
						pending.put(cachedMbb);
						pending.flip();
						org.deftserver.io.IOLoop.setAttachment(key, pending.getByteBuffer());
						key.interestOps(SelectionKey.OP_WRITE);
					}
				} else {
					if (cachedMbb.hasRemaining()) {
						int written = 0;
						do {
							written = protocol.write(((SocketChannel) key.channel()), cachedMbb);
							bytesWritten += written;
						} while (written > 0 && cachedMbb.hasRemaining());
					}
					if (cachedMbb.hasRemaining()) {
						org.deftserver.io.IOLoop.setAttachment(key, cachedMbb);
						key.interestOps(SelectionKey.OP_WRITE);
					}
				}
				logger.debug("sent file data, bytes sent: {}, remaining: {}", bytesWritten, cachedMbb.remaining());
				return bytesWritten;
			}

			try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
				FileChannel fc = raf.getChannel();
				MappedByteBuffer mbb = fc.map(MapMode.READ_ONLY, 0L, fc.size());
				if (!headersCreated) {
					int headerLen = calculateHeadersLength();
					ByteBuffer headerBuf = ByteBuffer.allocate(headerLen);
					writeHeadersToBuffer(headerBuf.array(), 0);
					headerBuf.limit(headerLen);
					headersCreated = true;
					ByteBuffer[] srcs = { headerBuf, mbb };
					long written = 0;
					do {
						long n = protocol.write(((SocketChannel) key.channel()), srcs);
						if (n <= 0) break;
						written += n;
					} while (headerBuf.hasRemaining() || mbb.hasRemaining());
					bytesWritten += written;
					if (headerBuf.hasRemaining() || mbb.hasRemaining()) {
						DynamicByteBuffer pending = DynamicByteBuffer.allocate(
							headerBuf.remaining() + mbb.remaining());
						pending.put(headerBuf);
						pending.put(mbb);
						pending.flip();
						org.deftserver.io.IOLoop.setAttachment(key, pending.getByteBuffer());
						key.interestOps(SelectionKey.OP_WRITE);
					}
				} else {
					if (mbb.hasRemaining()) {
						int written = 0;
						do {
							written = protocol.write(((SocketChannel) key.channel()), mbb);
							bytesWritten += written;
						} while (written > 0 && mbb.hasRemaining());
					}
					if (mbb.hasRemaining()) {
						org.deftserver.io.IOLoop.setAttachment(key, mbb);
						key.interestOps(SelectionKey.OP_WRITE);
					}
				}
				logger.debug("sent file data, bytes sent: {}, remaining: {}", bytesWritten, mbb.remaining());
			}
			return bytesWritten;
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/**
	 * Writes a byte range {@code [start, start+length)} of a file as the response body (the 206
	 * Partial Content path). Ranges larger than one mmap window are streamed by the protocol layer;
	 * smaller ones are mapped and written directly, deferring any unsent tail to OP_WRITE.
	 *
	 * @return the number of body bytes written by this call.
	 */
	public long write(File file, long start, long length) {
		try {
			long bytesWritten = 0;

			if (isBodySuppressed()) {
				flush();
				return 0;
			}

			// A range larger than a single mmap window is served in chunks too.
			if (length > HttpProtocol.FILE_WINDOW_SIZE) {
				flush(); // write initial line + headers only
				protocol.streamLargeFile(key, file, start, length);
				return bytesWritten;
			}

			java.nio.ByteBuffer cachedMbb = org.deftserver.web.handler.StaticContentHandler.getMappedBuffer(file);
			if (cachedMbb != null) {
				java.nio.ByteBuffer sliced = cachedMbb.duplicate();
				sliced.position((int) start);
				sliced.limit((int) (start + length));
				if (!headersCreated) {
					int headerLen = calculateHeadersLength();
					ByteBuffer headerBuf = ByteBuffer.allocate(headerLen);
					writeHeadersToBuffer(headerBuf.array(), 0);
					headerBuf.limit(headerLen);
					headersCreated = true;
					ByteBuffer[] srcs = { headerBuf, sliced };
					long written = 0;
					do {
						long n = protocol.write(((SocketChannel) key.channel()), srcs);
						if (n <= 0) break;
						written += n;
					} while (headerBuf.hasRemaining() || sliced.hasRemaining());
					bytesWritten += written;
					if (headerBuf.hasRemaining() || sliced.hasRemaining()) {
						DynamicByteBuffer pending = DynamicByteBuffer.allocate(
							headerBuf.remaining() + sliced.remaining());
						pending.put(headerBuf);
						pending.put(sliced);
						pending.flip();
						org.deftserver.io.IOLoop.setAttachment(key, pending.getByteBuffer());
						key.interestOps(SelectionKey.OP_WRITE);
					}
				} else {
					if (sliced.hasRemaining()) {
						int written = 0;
						do {
							written = protocol.write(((SocketChannel) key.channel()), sliced);
							bytesWritten += written;
						} while (written > 0 && sliced.hasRemaining());
					}
					if (sliced.hasRemaining()) {
						org.deftserver.io.IOLoop.setAttachment(key, sliced);
						key.interestOps(SelectionKey.OP_WRITE);
					}
				}
				logger.debug("sent partial file data, bytes sent: {}, remaining: {}", bytesWritten, sliced.remaining());
				return bytesWritten;
			}

			try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
				FileChannel fc = raf.getChannel();
				MappedByteBuffer mbb = fc.map(MapMode.READ_ONLY, start, length);
				if (!headersCreated) {
					int headerLen = calculateHeadersLength();
					ByteBuffer headerBuf = ByteBuffer.allocate(headerLen);
					writeHeadersToBuffer(headerBuf.array(), 0);
					headerBuf.limit(headerLen);
					headersCreated = true;
					ByteBuffer[] srcs = { headerBuf, mbb };
					long written = 0;
					do {
						long n = protocol.write(((SocketChannel) key.channel()), srcs);
						if (n <= 0) break;
						written += n;
					} while (headerBuf.hasRemaining() || mbb.hasRemaining());
					bytesWritten += written;
					if (headerBuf.hasRemaining() || mbb.hasRemaining()) {
						DynamicByteBuffer pending = DynamicByteBuffer.allocate(
							headerBuf.remaining() + mbb.remaining());
						pending.put(headerBuf);
						pending.put(mbb);
						pending.flip();
						org.deftserver.io.IOLoop.setAttachment(key, pending.getByteBuffer());
						key.interestOps(SelectionKey.OP_WRITE);
					}
				} else {
					if (mbb.hasRemaining()) {
						int written = 0;
						do {
							written = protocol.write(((SocketChannel) key.channel()), mbb);
							bytesWritten += written;
						} while (written > 0 && mbb.hasRemaining());
					}
					if (mbb.hasRemaining()) {
						org.deftserver.io.IOLoop.setAttachment(key, mbb);
						key.interestOps(SelectionKey.OP_WRITE);
					}
				}
				logger.debug("sent partial file data, bytes sent: {}, remaining: {}", bytesWritten, mbb.remaining());
			}
			return bytesWritten;
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/** True when this response must carry no body: a HEAD request ({@code suppressBody}), or a
	 *  status that is defined to be bodiless (1xx, 204 No Content, 304 Not Modified). */
	protected boolean isBodySuppressed() {
		return suppressBody || statusCode / 100 == 1 || statusCode == 204 || statusCode == 304;
	}

	/**
	 * Returns true if an If-None-Match / If-Match header value (a comma-separated list of
	 * entity-tags, or "*") matches the given ETag. Weak indicators are ignored (weak
	 * comparison), which is correct for If-None-Match and a safe superset for If-Match here.
	 */
	private static boolean ifMatchHeaderMatches(String headerValue, String etag) {
		if (headerValue == null || etag == null) return false;
		String trimmedHeader = headerValue.trim();
		if (trimmedHeader.equals("*")) return true;
		String target = stripWeak(etag);
		int len = trimmedHeader.length();
		int i = 0;
		while (i < len) {
			while (i < len && trimmedHeader.charAt(i) == ' ') i++;
			int start = i;
			while (i < len && trimmedHeader.charAt(i) != ',') i++;
			int end = i;
			while (end > start && trimmedHeader.charAt(end - 1) == ' ') end--;
			int candStart = start;
			if (end - start >= 2 && trimmedHeader.charAt(start) == 'W' && trimmedHeader.charAt(start + 1) == '/') {
				candStart += 2;
			}
			int candLen = end - candStart;
			if (candLen == target.length() && trimmedHeader.regionMatches(candStart, target, 0, candLen)) {
				return true;
			}
			i++;
		}
		return false;
	}

	/** Strips the weak-validator prefix ({@code W/}) from an ETag so weak and strong forms of the
	 *  same tag compare equal (the weak comparison used for If-None-Match). */
	private static String stripWeak(String etag) {
		return etag.startsWith("W/") ? etag.substring(2) : etag;
	}

	private static byte[] getChunkHeaderBytes(int len) {
		String hex = Integer.toHexString(len);
		int hexLen = hex.length();
		byte[] bytes = new byte[hexLen + 2];
		for (int i = 0; i < hexLen; i++) {
			bytes[i] = (byte) hex.charAt(i);
		}
		bytes[hexLen] = '\r';
		bytes[hexLen + 1] = '\n';
		return bytes;
	}
}
