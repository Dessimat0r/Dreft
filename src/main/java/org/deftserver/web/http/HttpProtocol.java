package org.deftserver.web.http;

import static org.deftserver.web.http.HttpServerDescriptor.KEEP_ALIVE_TIMEOUT;
import static org.deftserver.web.http.HttpServerDescriptor.READ_BUFFER_SIZE;

import java.io.EOFException;
import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;

import org.deftserver.io.IOHandler;
import org.deftserver.io.IOLoop;
import org.deftserver.io.buffer.DynamicByteBuffer;
import org.deftserver.io.timeout.Timeout;
import org.deftserver.util.Closeables;
import org.deftserver.web.Application;
import org.deftserver.web.handler.RequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

public class HttpProtocol implements IOHandler {
	
	private final static Logger logger = LoggerFactory.getLogger(HttpProtocol.class);

	private final IOLoop ioLoop;
	private final Application application;

	
	// a queue of half-baked (pending/unfinished) HTTP post request
	private final Map<SelectableChannel, HttpRequest> partials = Maps.newHashMap();
 	
	public HttpProtocol(Application app) {
		this(IOLoop.INSTANCE, app);
	}
	
	public HttpProtocol(IOLoop ioLoop, Application app) {
		this.ioLoop = ioLoop;
		application = app;
	}
	
	@Override
	public void handleAccept(SelectionKey key) throws IOException {
		logger.debug("handle accept...");
		SocketChannel clientChannel = ((ServerSocketChannel) key.channel()).accept();
		if (clientChannel != null) {
			// could be null in a multithreaded deft environment because another ioloop was "faster" to accept()
			clientChannel.configureBlocking(false);
			clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
			ioLoop.addHandler(clientChannel, this, SelectionKey.OP_READ, ByteBuffer.allocate(READ_BUFFER_SIZE));
		}
	}
	
	@Override
	public void handleConnect(SelectionKey key) throws IOException {
		logger.error("handle connect in HttpProcotol...");
	}

	@Override
	public void handleRead(SelectionKey key) throws IOException {
		logger.debug("handle read... key: " + key);
		SocketChannel clientChannel = (SocketChannel) key.channel();
		logger.debug("handle read 2...");
		HttpRequest request = getHttpRequest(key, clientChannel);
		if (request == null) {
			System.out.println("null request (no data)");
			return;
		}
		logger.debug("handle read 3..., req class: " + request.getClass().toString() + ", req: " + request);
		
		if (request.isKeepAlive()) {
			ioLoop.addKeepAliveTimeout(
				clientChannel, 
				Timeout.newKeepAliveTimeout(ioLoop, clientChannel, KEEP_ALIVE_TIMEOUT)
			);
		}
		logger.debug("handle read 4...");
		HttpResponse response = new HttpResponse(this, key, request.isKeepAlive());
		logger.debug("handle read 5...");
		RequestHandler rh = application.getHandler(request);
		logger.debug("handle read 6...");
		HttpRequestDispatcher.dispatch(rh, request, response);
		logger.debug("handle read 7...");
		
		//Only close if not async. In that case its up to RH to close it (+ don't close if it's a partial request).
		if (!rh.isMethodAsynchronous(request.getMethod()) && request.isComplete()) {
			response.finish();
		}
	}

	@Override
	public void handleWrite(SelectionKey key) throws IOException {
		logger.debug("handle write...");
		SocketChannel channel = ((SocketChannel) key.channel());

		if (key.attachment() instanceof MappedByteBuffer) {
			System.out.println("mbb write #1");
			writeMappedByteBuffer(key, channel);
			System.out.println("mbb write #2");
		} else if (key.attachment() instanceof DynamicByteBuffer) {
			System.out.println("dbb write #1");
			writeDynamicByteBuffer(key, channel);
			System.out.println("dbb write #2");
		} else if (key.attachment() instanceof ByteBuffer) {
			System.out.println("bb write #1");
			writeByteBuffer(key, channel);
			System.out.println("bb write #2");
		}
		if (ioLoop.hasKeepAliveTimeout(channel)) {
			prolongKeepAliveTimeout(channel);
		}

	}

	private long writeMappedByteBuffer(SelectionKey key, SocketChannel channel) {
		long bytesWritten = 0;
		MappedByteBuffer mbb = (MappedByteBuffer) key.attachment();
		if (mbb.hasRemaining()) {
			try {
				int written = 0;
				do {
					written = channel.write(mbb);
					if (written > 0) {
						bytesWritten += written;
					}
				} while (channel.isConnected() && written > 0 && mbb.hasRemaining());
			} catch (IOException e) {
				logger.error("Failed to send data to client: {}", e.getMessage());
				Closeables.closeQuietly(channel);
			}
		}
		if (!mbb.hasRemaining()) {
			closeOrRegisterForRead(key);
		}
		return bytesWritten;
	}
	
	private long writeDynamicByteBuffer(SelectionKey key, SocketChannel channel) {
		DynamicByteBuffer dbb = (DynamicByteBuffer) key.attachment();
		logger.debug("pending data about to be written");
		ByteBuffer toSend = dbb.getByteBuffer();
		//toSend.flip(); // prepare for write
		long bytesWritten = 0;
		if (toSend.hasRemaining()) {
			try {
				int written = 0;
				do {
					written = channel.write(toSend);
					if (written > 0) {
						bytesWritten += written;
					}
				} while (channel.isConnected() && written > 0 && toSend.hasRemaining());
			} catch (IOException e) {
				logger.error("Failed to send data to client: {}", e.getMessage());
				Closeables.closeQuietly(channel);
			}
			logger.debug("sent {} bytes to wire", bytesWritten);
		}
		if (!toSend.hasRemaining()) {
			logger.debug("sent all data in toSend buffer");
			closeOrRegisterForRead(key); // should probably only be done if the HttpResponse is finished
		} else {
			toSend.compact(); // make room for more data be "read" in
		}
		return bytesWritten;
	}
	
	private long writeByteBuffer(SelectionKey key, SocketChannel channel) {
		ByteBuffer toSend = (ByteBuffer) key.attachment();
		logger.debug("pending data about to be written");
		//toSend.flip(); // prepare for write
		long bytesWritten = 0;
		if (toSend.hasRemaining()) {
			try {
				int written = 0;
				do {
					written = channel.write(toSend);
					if (written > 0) {
						bytesWritten += written;
					}
				} while (channel.isConnected() && written > 0 && toSend.hasRemaining());
			} catch (IOException e) {
				logger.error("Failed to send data to client: {}", e.getMessage());
				Closeables.closeQuietly(channel);
			}
			logger.debug("sent {} bytes to wire", bytesWritten);
		}
		if (!toSend.hasRemaining()) {
			logger.debug("sent all data in toSend buffer");
			closeOrRegisterForRead(key); // should probably only be done if the HttpResponse is finished
		} else {
			toSend.compact(); // make room for more data be "read" in
		}
		return bytesWritten;
	}

	public void closeOrRegisterForRead(SelectionKey key) {
		if (key.isValid() && ioLoop.hasKeepAliveTimeout(key.channel())) {
			try {
				key.channel().register(key.selector(), SelectionKey.OP_READ, reuseAttachment(key));
				prolongKeepAliveTimeout(key.channel());
				logger.debug("keep-alive connection. registrating for read.");
			} catch (ClosedChannelException e) {
				logger.debug("ClosedChannelException while registrating key for read: {}", e.getMessage());
				Closeables.closeQuietly(ioLoop, key.channel());
			}		
		} else {
			// http request should be finished and no 'keep-alive' => close connection
			logger.debug("Closing finished (non keep-alive) http connection"); 
			Closeables.closeQuietly(ioLoop, key.channel());
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

		if (attachment.capacity() < READ_BUFFER_SIZE) {
			attachment = ByteBuffer.allocate(READ_BUFFER_SIZE);
		}
		attachment.clear(); // prepare for reuse
		return attachment;
	}
	
	/*
	private HttpRequest getHttpRequest(SelectionKey key, SocketChannel clientChannel) {
		DynamicByteBuffer buffer = (DynamicByteBuffer) key.attachment();
		long bytesRead = 0;
		boolean error = false;
		try {
			int read = 0;
			do {
				READ_BUFFER.clear();
				read = clientChannel.read(READ_BUFFER);
				bytesRead += read;
				READ_BUFFER.flip();
				buffer.put(READ_BUFFER);
			} while (read > 0 && buffer.hasRemaining());
		} catch (IOException e) {
			logger.warn("Could not read buffer: {}", e.getMessage());
			Closeables.closeQuietly(ioLoop, clientChannel);
			error = true;
		}
		int oldpos   = buffer.getByteBuffer().position();
		int oldlimit = buffer.getByteBuffer().limit();
		buffer.getByteBuffer().flip();
		int fpos   = buffer.getByteBuffer().position();
		int flimit = buffer.getByteBuffer().limit();
		boolean found = HttpRequest.findInBB(buffer.getByteBuffer(), HttpRequest.HTTP_HEAD_TERM_BYTES);
		if (!found) {
			// found nothing
			buffer.getByteBuffer().limit(oldlimit);
			buffer.getByteBuffer().position(oldpos);
			if (error) {
				System.out.println("buffer cleared");
				buffer.getByteBuffer().clear();
			}
			return null;
		}
		System.out.println("found HTTP_HEAD_TERM_BYTES");
		try {
			int foundpos = buffer.position();
			buffer.getByteBuffer().limit(flimit);
			buffer.getByteBuffer().position(fpos);
			HttpRequest req = doGetHttpRequest(key, clientChannel, buffer.getByteBuffer());
			return req;
		} finally {
			System.out.println("buffer cleared");
			buffer.getByteBuffer().clear();
		}
	}
	*/

	//TODO: change this to work with the entire stream rather than individual calls (no guarantee the client sends their data wholly in discrete packets)
	private HttpRequest getHttpRequest(SelectionKey key, SocketChannel clientChannel) throws IOException {
		ByteBuffer buffer = (ByteBuffer) key.attachment();
		buffer.clear();
		if (!buffer.hasRemaining()) throw new IllegalStateException("Cleared channel buffer has no remaining space.");
		long bytesRead = 0;
		int read = 0;
		do {
			read = clientChannel.read(buffer);
			if (read > 0) {
				bytesRead += read;
			}
		} while (read > 0 && buffer.hasRemaining());
		try {
			buffer.flip();
			logger.debug("getHttpRequest bytesRead: {}, buffer remaining: {}", bytesRead, buffer.remaining());
			//System.out.println("raw: " + new String(buffer.array(), 0, buffer.remaining(), Charsets.ISO_8859_1));
			if (read == -1) logger.debug("EOF in getHttpRequest(..) #2, bytesRead: {}, buffer remaining: {}", bytesRead, buffer.remaining());
			if (read >= 0 || (read == -1 && buffer.hasRemaining())) {
				return doGetHttpRequest(key, clientChannel, buffer);
			}
		} finally {
			if (read == -1) throw new EOFException("EOF in getHttpRequest");
		}
		return null;
	}
	
	private HttpRequest doGetHttpRequest(SelectionKey key, SocketChannel clientChannel, ByteBuffer buffer) {
		//do we have any unfinished http post requests for this channel?
		HttpRequest request = partials.get(clientChannel);
		if (request != null) {
			logger.debug("continuing to parse partial http request - http req #{}, remaining: {}", request.getRequestNum(), request.getRemaining());
			if (request.putContentData(true, buffer)) {	// if received the entire payload/body
				logger.debug("entire partial http request received, removing - http req #{}, remaining: {}, flipremain: {}", request.getRequestNum(), request.getRemaining(), request.getFlipRemain());
				partials.remove(clientChannel);
			}
		} else {
			request = HttpRequest.of(application.nextHttpReqNum(), buffer);
			if (!request.isComplete()) {
				logger.debug("adding partial http request - http req #{}, remaining: {}", request.getRequestNum(), request.getRemaining());
				partials.put(key.channel(), request);
			} else {
				logger.debug("normal HttpRequest");
			}
		}
		//set extra request info
		request.setRemoteHost(clientChannel.socket().getInetAddress());
		request.setRemotePort(clientChannel.socket().getPort());
		request.setServerHost(clientChannel.socket().getLocalAddress());
		request.setServerPort(clientChannel.socket().getLocalPort());
		return request;
	}
	
	@Override
	public String toString() { return "HttpProtocol"; }
	
}
