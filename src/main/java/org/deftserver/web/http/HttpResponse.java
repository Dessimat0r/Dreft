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

import org.deftserver.io.buffer.DynamicByteBuffer;
import org.deftserver.util.DateUtil;
import org.deftserver.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class HttpResponse {
	
	private final static Logger logger = LoggerFactory.getLogger(HttpResponse.class);
	
	private final HttpProtocol protocol;
	private final SelectionKey key;
	
	private int statusCode = 200;	// default response status code
	
	private final Map<String, String> headers = new HashMap<String, String>();
	private boolean headersCreated = false;
	private final DynamicByteBuffer responseData = DynamicByteBuffer.allocate(WRITE_BUFFER_SIZE);
	private boolean hasContent = false;
	private final boolean suppressBody;
	
	private HttpRequest request;
	private boolean useChunked = false;
	private boolean useGzip = false;
	private boolean finished = false;
	private final java.util.List<Cookie> cookies = new java.util.ArrayList<>();

	public void setCookie(Cookie cookie) {
		cookies.add(cookie);
	}
	
	public void setRequest(HttpRequest request) {
		this.request = request;
	}
	
	public HttpResponse(HttpProtocol protocol, SelectionKey key, boolean keepAlive) {
		this(protocol, key, keepAlive, false);
	}

	public HttpResponse(HttpProtocol protocol, SelectionKey key, boolean keepAlive, boolean suppressBody) {
		this.protocol = protocol;
		this.key = key;
		this.suppressBody = suppressBody;
		headers.put("Server", "Dreft/0.4.0-SNAPSHOT");
		headers.put("Date", DateUtil.getCurrentAsString());
		headers.put("Connection", keepAlive ? "Keep-Alive" : "Close");
	}
	
	public SocketChannel getChannel() {
		return (SocketChannel) key.channel();
	}
	
	public HttpProtocol getProtocol() {
		return protocol;
	}
	public void setStatusCode(int sc) {
		statusCode = sc;
	}
	
	public void setHeader(String header, String value) {
		headers.put(header, value);
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
			if (!headersCreated) {
				// Streaming case: a handler that flushes before finish() with neither a
				// Content-Length nor chunked framing produces a response whose body can only be
				// delimited by closing the connection. Force Connection: close so a keep-alive
				// client isn't left mis-framed (finish() then tears the connection down). The
				// normal write()+finish() path sets Content-Length before flushing, so it is
				// unaffected and stays keep-alive.
				if (!useChunked && !headers.containsKey("Content-Length") && !suppressBody) {
					setHeader("Connection", "Close");
				}
				if (useChunked && responseData.position() > 0) {
					int len = responseData.position();
					String hex = Integer.toHexString(len);
					byte[] prefix = (hex + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
					responseData.prepend(prefix);
					responseData.put("\r\n".getBytes(StandardCharsets.ISO_8859_1));
				}

				String initial = createInitalLineAndHeaders();
				if (suppressBody) {
					responseData.clear(); // discard any body bytes
				}
				bytesWritten += responseData.prepend(initial);
				headersCreated = true;
			}
			responseData.flip(); // prepare for write
	
			SocketChannel channel = (SocketChannel) key.channel();
			if (responseData.hasRemaining()) {
				int written = 0;
				do {
					written = protocol.write(channel, responseData.getByteBuffer());
					bytesWritten += written;
				} while (channel.isConnected() && written > 0 && responseData.hasRemaining());
			}
			if (protocol.getIOLoop().hasKeepAliveTimeout(channel)) {
				protocol.prolongKeepAliveTimeout(channel);
			}
			if (responseData.hasRemaining()) { 
				responseData.compact();	// make room for more data be 'read' in
				key.channel().register(key.selector(), SelectionKey.OP_WRITE, responseData);
			}
		} catch (IOException e) {
			logger.debug("Client disconnected during flush (broken pipe): {}", e.getMessage());
			// Route through closeChannel so the protocol's channel set / per-channel maps are
			// cleaned (a bare channel.close() would leak the active-connection accounting).
			protocol.closeChannel((SocketChannel) key.channel());
			return 0;
		} finally {
			if (!responseData.hasRemaining()) {
				responseData.clear();
			}
		}
		return bytesWritten;
	}
	
	public long finish() {
		if (finished) {
			return 0;
		}
		finished = true;
		try {
			long bytesWritten = 0;
			SocketChannel clientChannel = (SocketChannel) key.channel();
	
			if (key.attachment() instanceof MappedByteBuffer) {
				MappedByteBuffer mbb = (MappedByteBuffer) key.attachment();
				if (mbb.hasRemaining()) {
					int written = 0;
					do {
						written = protocol.write(clientChannel, mbb);
						bytesWritten += written;
					} while (written > 0 && mbb.hasRemaining() && clientChannel.isOpen());
				}
				if (!mbb.hasRemaining()) {
					protocol.registerForRead(key);
				}
			} else {
				if (clientChannel.isOpen()) {
					if (!headersCreated) {
						setEtagAndContentLength();
					}
					if (useChunked) {
						if (responseData.position() > 0) {
							int len = responseData.position();
							String hex = Integer.toHexString(len);
							byte[] prefix = (hex + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
							responseData.prepend(prefix);
							responseData.put("\r\n".getBytes(StandardCharsets.ISO_8859_1));
						}
						responseData.put("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
						if (!headersCreated) {
							String initial = createInitalLineAndHeaders();
							if (suppressBody) {
								responseData.clear();
							}
							responseData.prepend(initial);
							headersCreated = true;
						}
						bytesWritten = flush();
					} else {
						bytesWritten = flush();
					}
				}
				// close (or register for read) if all data has been written.
				// If OP_WRITE is interested, a write is pending, so we must wait until the buffer is fully sent.
				// If the response declared Connection: close (e.g. a 404/400/403 or error page on an
				// otherwise keep-alive connection), close once the body is fully written instead of
				// keeping the socket open — otherwise the server contradicts its own header and holds
				// the connection until the keep-alive timeout.
				boolean closeConnection = "close".equalsIgnoreCase(headers.get("Connection"));
				boolean isWriting = key.isValid() && (key.interestOps() & SelectionKey.OP_WRITE) != 0;
				if (isWriting) {
					if (key.attachment() instanceof DynamicByteBuffer) {
						DynamicByteBuffer dbb = (DynamicByteBuffer) key.attachment();
						if (!dbb.hasRemaining()) {
							finishConnection(closeConnection);
						}
					} else if (key.attachment() instanceof ByteBuffer) {
						ByteBuffer bb = (ByteBuffer) key.attachment();
						if (!bb.hasRemaining()) {
							finishConnection(closeConnection);
						}
					}
				} else {
					finishConnection(closeConnection);
				}
			}
			return bytesWritten;
		} catch (IOException e) {
			logger.debug("Client disconnected during finish (broken pipe): {}", e.getMessage());
			protocol.closeChannel((SocketChannel) key.channel());
			return 0;
		}
	}	

	private void setEtagAndContentLength() {
		if (request != null && !isBodySuppressed() && responseData.position() > 0) {
			String acceptEncoding = request.getHeader("Accept-Encoding");
			if (org.deftserver.util.HttpUtil.isGzipAcceptable(acceptEncoding)) {
				String contentType = headers.get("Content-Type");
				if (contentType != null && (
					contentType.contains("text") || 
					contentType.contains("json") || 
					contentType.contains("xml") || 
					contentType.contains("javascript"))) {
					useGzip = true;
					useChunked = true;
					setHeader("Content-Encoding", "gzip");
					setHeader("Transfer-Encoding", "chunked");
					// The representation now depends on Accept-Encoding; tell caches so they
					// don't serve gzipped bytes to a client that didn't ask for them.
					String existingVary = headers.get("Vary");
					if (existingVary == null || existingVary.isEmpty()) {
						setHeader("Vary", "Accept-Encoding");
					} else if (!existingVary.toLowerCase(java.util.Locale.ROOT).contains("accept-encoding")) {
						setHeader("Vary", existingVary + ", Accept-Encoding");
					}
				}
			}
		}

		if (statusCode / 100 == 1 || statusCode == 204 || statusCode == 304) {
			markBodiless();
			return;
		}

		if (useGzip && responseData.position() > 0) {
			java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
			try (java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(baos)) {
				gzos.write(responseData.array(), 0, responseData.position());
			} catch (IOException e) {
				logger.error("Error compressing response: {}", e.getMessage());
			}
			byte[] compressed = baos.toByteArray();
			responseData.clear();
			responseData.put(compressed);
		}

		String etag = null;
		if (responseData.position() > 0) {
			// RFC 9110 §8.8.3: an ETag is a quoted-string. Quoting is also required for
			// If-None-Match / If-Match to match, since clients echo the quoted value back.
			etag = "\"" + HttpUtil.getEtag(responseData.array(), 0, responseData.position()) + "\"";
			setHeader("Etag", etag);
		}

		if (request != null) {
			String inm = request.getHeader("If-None-Match");
			if (inm != null && etag != null && ifMatchHeaderMatches(inm, etag)) {
				setStatusCode(304);
				markBodiless();
				return;
			}
			String im = request.getHeader("If-Match");
			if (im != null && etag != null && !ifMatchHeaderMatches(im, etag)) {
				setStatusCode(412);
				markBodiless();
				return;
			}
			String ims = request.getHeader("If-Modified-Since");
			String lm = headers.get("Last-Modified");
			// RFC 9110 §13.1.3: ignore If-Modified-Since when If-None-Match is present.
			if (ims != null && lm != null && inm == null) {
				long imsTime = DateUtil.parseRFC1123ToMillis(ims);
				long lmTime = DateUtil.parseRFC1123ToMillis(lm);
				if (imsTime != -1 && lmTime != -1 && lmTime <= imsTime) {
					setStatusCode(304);
					markBodiless();
					return;
				}
			}
			String ius = request.getHeader("If-Unmodified-Since");
			if (ius != null && lm != null) {
				long iusTime = DateUtil.parseRFC1123ToMillis(ius);
				long lmTime = DateUtil.parseRFC1123ToMillis(lm);
				if (iusTime != -1 && lmTime != -1 && lmTime > iusTime) {
					setStatusCode(412);
					markBodiless();
					return;
				}
			}
		}

		if (useChunked) {
			headers.remove("Content-Length");
			setHeader("Transfer-Encoding", "chunked");
		} else if (isBodySuppressed() && headers.containsKey("Content-Length")) {
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
			protocol.registerForRead(key);
		}
	}

	private void markBodiless() {
		responseData.clear();
		useChunked = false;
		useGzip = false;
		headers.remove("Transfer-Encoding");
		headers.remove("Content-Encoding");
		setHeader("Content-Length", "0");
	}

	private String createInitalLineAndHeaders() {
		StringBuilder sb = new StringBuilder(HttpUtil.createInitialLine(statusCode));
		for (Map.Entry<String, String> header : headers.entrySet()) {
			// A header field is always "name ':' OWS value"; emitting the name alone (when the
			// value is empty) produces a malformed, colon-less line.
			sb.append(header.getKey()).append(": ");
			if (!header.getValue().isEmpty()) {
				sb.append(header.getValue());
			}
			sb.append("\r\n");
		}
		for (Cookie cookie : cookies) {
			sb.append("Set-Cookie: ").append(cookie.toString()).append("\r\n");
		}
		sb.append("\r\n");
		return sb.toString();
	}
	
	public long write(ByteBuffer data) {
		int size = data.remaining();

		if (!headersCreated) {
			if (useChunked) {
				setHeader("Transfer-Encoding", "chunked");
				headers.remove("Content-Length");
			} else {
				setHeader("Content-Length", String.valueOf(size));
			}
		}

		// Emit the initial line + headers (and any previously-buffered body). flush() routes
		// through the partial-write/OP_WRITE machinery, so nothing is lost on a full socket.
		long bytesWritten = flush();
		if (isBodySuppressed()) {
			return bytesWritten;
		}

		// Buffer the body (with chunk framing if chunked) and flush. Previously the body and the
		// chunk-size/CRLF markers were written straight to the socket in a loop that stopped on a
		// 0-byte (buffer-full) write, silently dropping the remaining payload AND desynchronising
		// the chunked stream. Buffering via responseData lets flush() defer the remainder to
		// OP_WRITE instead.
		if (data.hasRemaining()) {
			if (useChunked) {
				responseData.put((Integer.toHexString(data.remaining()) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
				responseData.put(data);
				responseData.put("\r\n".getBytes(StandardCharsets.ISO_8859_1));
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
			//setHeader("Etag", HttpUtil.getEtag(file));
			logger.debug("cl-httpresp3");
			setHeader("Content-Length", String.valueOf(file.length()));
			long bytesWritten = 0;
			flush(); // write initial line + headers
			if (isBodySuppressed()) {
				return bytesWritten;
			}
			
			try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
				FileChannel fc = raf.getChannel();
				MappedByteBuffer mbb = raf.getChannel().map(MapMode.READ_ONLY, 0L, fc.size());
				if (mbb.hasRemaining()) {
					int written = 0;
					do {
						written = protocol.write(((SocketChannel) key.channel()), mbb);
						bytesWritten += written;
					} while (written > 0 && mbb.hasRemaining());
					logger.debug("sent file data, bytes sent: {}, remaining: {}", bytesWritten, mbb.remaining());
				}
				if (mbb.hasRemaining()) {
					logger.debug("unable to send complete file, attaching to key for later send");
					key.channel().register(key.selector(), SelectionKey.OP_WRITE, mbb);
				}
			}
			return bytesWritten;
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	public long write(File file, long start, long length) {
		try {
			long bytesWritten = 0;
			flush(); // write initial line + headers
			if (isBodySuppressed()) {
				return bytesWritten;
			}
			
			try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
				FileChannel fc = raf.getChannel();
				MappedByteBuffer mbb = fc.map(MapMode.READ_ONLY, start, length);
				if (mbb.hasRemaining()) {
					int written = 0;
					do {
						written = protocol.write(((SocketChannel) key.channel()), mbb);
						bytesWritten += written;
					} while (written > 0 && mbb.hasRemaining());
					logger.debug("sent partial file data, bytes sent: {}, remaining: {}", bytesWritten, mbb.remaining());
				}
				if (mbb.hasRemaining()) {
					logger.debug("unable to send complete partial file, attaching to key for later send");
					key.channel().register(key.selector(), SelectionKey.OP_WRITE, mbb);
				}
			}
			return bytesWritten;
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	private boolean isBodySuppressed() {
		return suppressBody || statusCode / 100 == 1 || statusCode == 204 || statusCode == 304;
	}

	/**
	 * Returns true if an If-None-Match / If-Match header value (a comma-separated list of
	 * entity-tags, or "*") matches the given ETag. Weak indicators are ignored (weak
	 * comparison), which is correct for If-None-Match and a safe superset for If-Match here.
	 */
	private static boolean ifMatchHeaderMatches(String headerValue, String etag) {
		if (headerValue == null || etag == null) return false;
		headerValue = headerValue.trim();
		if (headerValue.equals("*")) return true;
		String target = stripWeak(etag);
		for (String candidate : headerValue.split(",")) {
			candidate = candidate.trim();
			if (!candidate.isEmpty() && stripWeak(candidate).equals(target)) {
				return true;
			}
		}
		return false;
	}

	private static String stripWeak(String etag) {
		return etag.startsWith("W/") ? etag.substring(2) : etag;
	}
}
