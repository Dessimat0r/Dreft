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
			try { ((SocketChannel) key.channel()).close(); } catch (IOException ignore) {}
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
				boolean isWriting = key.isValid() && (key.interestOps() & SelectionKey.OP_WRITE) != 0;
				if (isWriting) {
					if (key.attachment() instanceof DynamicByteBuffer) {
						DynamicByteBuffer dbb = (DynamicByteBuffer) key.attachment();
						if (!dbb.hasRemaining()) {
							protocol.registerForRead(key);
						}
					} else if (key.attachment() instanceof ByteBuffer) {
						ByteBuffer bb = (ByteBuffer) key.attachment();
						if (!bb.hasRemaining()) {
							protocol.registerForRead(key);
						}
					}
				} else {
					protocol.registerForRead(key);
				}
			}
			return bytesWritten;
		} catch (IOException e) {
			logger.debug("Client disconnected during finish (broken pipe): {}", e.getMessage());
			try { ((SocketChannel) key.channel()).close(); } catch (IOException ignore) {}
			return 0;
		}
	}	

	private void setEtagAndContentLength() {
		if (request != null && !isBodySuppressed()) {
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
				}
			}
		}

		if (statusCode / 100 == 1 || statusCode == 204 || statusCode == 304) {
			setHeader("Content-Length", "0");
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
			etag = HttpUtil.getEtag(responseData.array(), 0, responseData.position());
			setHeader("Etag", etag);
		}

		if (request != null) {
			String inm = request.getHeader("If-None-Match");
			if (inm != null && etag != null && (inm.equals(etag) || inm.equals("*"))) {
				setStatusCode(304);
				responseData.clear();
				setHeader("Content-Length", "0");
				return;
			}
			String im = request.getHeader("If-Match");
			if (im != null && etag != null && !im.equals(etag) && !im.equals("*")) {
				setStatusCode(412);
				responseData.clear();
				setHeader("Content-Length", "0");
				return;
			}
			String ims = request.getHeader("If-Modified-Since");
			String lm = headers.get("Last-Modified");
			if (ims != null && lm != null) {
				long imsTime = DateUtil.parseRFC1123ToMillis(ims);
				long lmTime = DateUtil.parseRFC1123ToMillis(lm);
				if (imsTime != -1 && lmTime != -1 && lmTime <= imsTime) {
					setStatusCode(304);
					responseData.clear();
					setHeader("Content-Length", "0");
					return;
				}
			}
			String ius = request.getHeader("If-Unmodified-Since");
			if (ius != null && lm != null) {
				long iusTime = DateUtil.parseRFC1123ToMillis(ius);
				long lmTime = DateUtil.parseRFC1123ToMillis(lm);
				if (iusTime != -1 && lmTime != -1 && lmTime > iusTime) {
					setStatusCode(412);
					responseData.clear();
					setHeader("Content-Length", "0");
					return;
				}
			}
		}

		if (useChunked) {
			headers.remove("Content-Length");
			setHeader("Transfer-Encoding", "chunked");
		} else {
			setHeader("Content-Length", String.valueOf(responseData.position()));
		}
	}
	
	private String createInitalLineAndHeaders() {
		StringBuilder sb = new StringBuilder(HttpUtil.createInitialLine(statusCode));
		for (Map.Entry<String, String> header : headers.entrySet()) {
			sb.append(header.getKey());
			if (!header.getValue().isEmpty()) {
				sb.append(": ");
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
		try {
			SocketChannel sc = ((SocketChannel) key.channel());
			long size = data.remaining();
			long bytesWritten = 0;
			
			if (!headersCreated) {
				if (useChunked) {
					setHeader("Transfer-Encoding", "chunked");
					headers.remove("Content-Length");
				} else {
					setHeader("Content-Length", String.valueOf(size));
				}
			}
			
			flush(); // write initial line + headers
			if (isBodySuppressed()) {
				return bytesWritten;
			}
			
			if (data.hasRemaining()) {
				if (useChunked) {
					String hex = Integer.toHexString(data.remaining());
					protocol.write(sc, ByteBuffer.wrap((hex + "\r\n").getBytes(StandardCharsets.ISO_8859_1)));
				}
				int written = 0;
				do {
					written = protocol.write(sc, data);
					bytesWritten += written;
				} while (written > 0 && data.hasRemaining());
				if (useChunked) {
					protocol.write(sc, ByteBuffer.wrap("\r\n".getBytes(StandardCharsets.ISO_8859_1)));
				}
			}
			return bytesWritten;
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
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

	private static int findHeaderEnd(ByteBuffer buffer) {
		for (int i = buffer.position(); i <= buffer.limit() - 4; i++) {
			if (buffer.get(i) == '\r' && buffer.get(i + 1) == '\n'
					&& buffer.get(i + 2) == '\r' && buffer.get(i + 3) == '\n') {
				return i + 4;
			}
		}
		return -1;
	}
}
