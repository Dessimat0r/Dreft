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

import com.google.common.base.Charsets;

public class HttpResponse {
	
	private final static Logger logger = LoggerFactory.getLogger(HttpResponse.class);
	
	private final HttpProtocol protocol;
	private final SelectionKey key;
	
	private int statusCode = 200;	// default response status code
	
	private final Map<String, String> headers = new HashMap<String, String>();
	private boolean headersCreated = false;
	private final DynamicByteBuffer responseData = DynamicByteBuffer.allocate(WRITE_BUFFER_SIZE);
	private boolean hasContent = false;
	
	public HttpResponse(HttpProtocol protocol, SelectionKey key, boolean keepAlive) {
		this.protocol = protocol;
		this.key = key;
		headers.put("Server", "Deft/0.4.0-SNAPSHOT");
		headers.put("Date", DateUtil.getCurrentAsString());
		headers.put("Connection", keepAlive ? "Keep-Alive" : "Close");
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
		byte[] bytes = data.getBytes(Charsets.UTF_8);
		responseData.put(bytes);
		return this;
	}
	
	public HttpResponse write(byte[] data) {
		responseData.put(data);
		return this;
	}	

	/**
	 * Explicit flush. 
	 * 
	 * @return the number of bytes that were actually written as the result of this flush.
	 */
	public long flush() throws IOException {
		long bytesWritten = 0;
		try {
			if (!headersCreated) {
				String initial = createInitalLineAndHeaders();			
				bytesWritten += responseData.prepend(initial);
				headersCreated = true;
			}
			responseData.flip(); // prepare for write
	
			SocketChannel channel = (SocketChannel) key.channel();
			if (responseData.hasRemaining()) {
				int written = 0;
				do {
					written = channel.write(responseData.getByteBuffer());
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
		} finally {
			if (!responseData.hasRemaining()) {
				responseData.clear();
			}
		}
		return bytesWritten;
	}
	
	/**
	 * Should only be invoked by third party asynchronous request handlers 
	 * (or by the Deft framework for synchronous request handlers). 
	 * If no previous (explicit) flush is invoked, the "Content-Length" and "Etag" header will be calculated and 
	 * inserted to the HTTP response.
	 * 
	 */
	public long finish() throws IOException {
		long bytesWritten = 0;
		SocketChannel clientChannel = (SocketChannel) key.channel();

		if (key.attachment() instanceof MappedByteBuffer) {
			MappedByteBuffer mbb = (MappedByteBuffer) key.attachment();
			if (mbb.hasRemaining()) {
				int written = 0;
				do {
					written = clientChannel.write(mbb);
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
				bytesWritten = flush();
			}
			// close (or register for read) if
			// (a) DBB is attached but all data is sent to wire (hasRemaining ==
			// false)
			// (b) no DBB is attached (never had to register for write)
			if (key.attachment() instanceof DynamicByteBuffer) {
				DynamicByteBuffer dbb = (DynamicByteBuffer) key.attachment();
				if (!dbb.hasRemaining()) {
					protocol.registerForRead(key);
				}
			} else if (key.attachment() instanceof ByteBuffer) {
				 ByteBuffer bb = (ByteBuffer)key.attachment();
				 if (!bb.hasRemaining()) {
					 protocol.registerForRead(key);
				 }
			} else {
				protocol.registerForRead(key);
			}
		}
		return bytesWritten;
	}	
	private void setEtagAndContentLength() {
		if (responseData.position() > 0) {
			setHeader("Etag", HttpUtil.getEtag(responseData.array()));
		}
		System.out.println("cl-httpresp1");
		setHeader("Content-Length", String.valueOf(responseData.position()));
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
		
		sb.append("\r\n");
		return sb.toString();
	}
	
	public long write(ByteBuffer data) throws IOException {
		SocketChannel sc = ((SocketChannel) key.channel());
		long size = data.remaining();
		System.out.println("cl-httpresp4");
		setHeader("Content-Length", String.valueOf(size));
		long bytesWritten = 0;
		flush(); // write initial line + headers
		
		if (data.hasRemaining()) {
			int written = 0;
			do {
				written = sc.write(data);
				bytesWritten += written;
			} while (written > 0 && data.hasRemaining());
		}
		logger.debug("sent bytebuffer data, bytes sent: {}, remaining: {}", bytesWritten, data.remaining());
		if (data.hasRemaining()) {
			logger.debug("unable to send complete bytebuffer, attaching to key for later send");
			key.channel().register(key.selector(), SelectionKey.OP_WRITE, data);
		}
		return bytesWritten;
	}
	
	/**
	 * Experimental support.
	 * Before use, read https://github.com/rschildmeijer/deft/issues/75
	 */
	public long write(File file) throws IOException {
		//setHeader("Etag", HttpUtil.getEtag(file));
		System.out.println("cl-httpresp3");
		setHeader("Content-Length", String.valueOf(file.length()));
		long bytesWritten = 0;
		flush(); // write initial line + headers
		
		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			FileChannel fc = raf.getChannel();
			MappedByteBuffer mbb = raf.getChannel().map(MapMode.READ_ONLY, 0L, fc.size());
			if (mbb.hasRemaining()) {
				int written = 0;
				do {
					written = ((SocketChannel) key.channel()).write(mbb);
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
	}
	
}
