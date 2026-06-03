package org.deftserver.io;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;

import org.deftserver.util.Closeables;
import org.deftserver.util.NopAsyncResult;
import org.deftserver.web.AsyncCallback;
import org.deftserver.web.AsyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class AsynchronousSocket implements IOHandler {
	
	private static final Logger logger = LoggerFactory.getLogger(AsynchronousSocket.class);
	
	private final IOLoop ioLoop;
	
	private final static int DEFAULT_BYTEBUFFER_SIZE = 1024;
	
	private final AsyncResult<String> nopAsyncStringResult = NopAsyncResult.of(String.class).nopAsyncResult;
	private final AsyncResult<Boolean> nopAsyncBooleanResult = NopAsyncResult.of(Boolean.class).nopAsyncResult;

	public interface DnsResolver {
		java.net.InetSocketAddress resolve(String host, int port) throws Exception;
	}

	private static DnsResolver dnsResolver = java.net.InetSocketAddress::new;

	/** Overrides the DNS resolver used for connects (e.g. to inject a stub in tests); defaults to the
	 *  blocking {@link java.net.InetSocketAddress} resolution run on a virtual thread. */
	public static void setDnsResolver(DnsResolver resolver) {
		dnsResolver = resolver;
	}
	
	private final SelectableChannel channel;
	private int interestOps;
	
	private String readDelimiter = "";
	private int readBytes = Integer.MAX_VALUE;
	
	private AsyncResult<Boolean> connectCallback = nopAsyncBooleanResult;
	private AsyncCallback closeCallback = AsyncCallback.nopCb;
	private AsyncResult<String> readCallback = nopAsyncStringResult;
	private AsyncCallback writeCallback = AsyncCallback.nopCb;
	
	private final StringBuilder readBuffer = new StringBuilder();
	private final org.deftserver.io.buffer.DynamicByteBuffer writeBuffer = org.deftserver.io.buffer.DynamicByteBuffer.allocate(DEFAULT_BYTEBUFFER_SIZE);
	
	private boolean reachedEOF = false;
	
	/**
	 * Creates a new {@code AsynchronousSocket} that will delegate its io operations to the given 
	 * {@link SelectableChannel}. 
	 * <p>
	 * Support for three non-blocking asynchronous methods that take callbacks:
	 * <p> 
	 * {@link #readUntil(String, AsyncResult)}
	 * <p>  
	 * {@link #readBytes(int, AsyncResult)} and
	 * <p>
	 * {@link #write(String, AsyncCallback)} 
	 * <p>
	 * The {@link SelectableChannel} should be the result of either {@link SocketChannel#open()} (client operations, 
	 * connected or  unconnected) or {@link ServerSocketChannel#accept()} (server operations).
	 * <p> 
	 * The given {@code SelectableChannel} will be configured to be in non-blocking mode, even if it is non-blocking
	 * already. 
	 * 
	 * <p>Below is an example of how a simple server could be implemented.
	 * <pre>
     *   final ServerSocketChannel server = ServerSocketChannel.open();
     *   server.socket().bind(new InetSocketAddress(9090));
     * 	
     *   AcceptUtil.accept(server, new AsyncCallback() { public void onCallback() { onAccept(server);} });
     *   IOLoop.INSTANCE.start();
	 * 
     *   private static void onAccept(ServerSocketChannel channel) {
     *       SocketChannel client = channel.accept();
     *       AsynchronousSocket socket = new AsynchronousSocket(client);
     *       // use socket
     *   }
	 * </pre>
	 */
	public AsynchronousSocket(SelectableChannel channel) throws IOException {
		this(IOLoop.INSTANCE, channel);
	}
	
	/** Wraps a channel for non-blocking async I/O on the given loop, registering it (with OP_READ if
	 *  already connected). */
	public AsynchronousSocket(IOLoop ioLoop, SelectableChannel channel) throws IOException {
		this.ioLoop = ioLoop;
		this.channel = channel;
		channel.configureBlocking(false);
		if (channel instanceof SocketChannel && (((SocketChannel) channel).isConnected())) {
			interestOps = SelectionKey.OP_READ;
		} else {
			interestOps = SelectionKey.OP_CONNECT;
		}
		ioLoop.addHandler(channel, this, interestOps, null);
	}
	
	/**
	 * Connects to the given host port tuple and invokes the given callback when a successful connection is established.
	 * <p>
	 * You can both read and write on the {@code AsynchronousSocket} before it is connected
	 * (in which case the data will be written/read as soon as the connection is ready).
	 */
	public void connect(String host, int port, AsyncResult<Boolean> ccb) {
		connectCallback = ccb;
		ioLoop.addCallback(() -> {
			interestOps |= SelectionKey.OP_CONNECT;
			ioLoop.updateHandler(channel, interestOps);
		});
		if (channel instanceof SocketChannel) {
			Thread.startVirtualThread(() -> {
				try {
					final InetSocketAddress address = dnsResolver.resolve(host, port);
					ioLoop.addCallback(() -> {
						try {
							((SocketChannel) channel).connect(address);
						} catch (IOException e) {
							logger.error("Failed to connect to: {}, message: {} ", host, e.getMessage());
							invokeConnectFailureCallback(e);
						} catch (UnresolvedAddressException e) {
							logger.warn("Unresolvable host: {}", host);
							invokeConnectFailureCallback(e);
						}
					});
				} catch (Exception e) {
					ioLoop.addCallback(() -> invokeConnectFailureCallback(e));
				}
			});
		}
	}
	
	/**
	 * Close the socket.
	 */
	public void close() {
		Closeables.closeQuietly(ioLoop, channel);
		invokeCloseCallback();
	}
	
	/**
	 * The given callback will invoked when the underlaying {@code SelectableChannel} is closed. 
	 */
	public void setCloseCallback(AsyncCallback ccb) {
		closeCallback = ccb;
	}
	
	/**
	 * Should only be invoked by the IOLoop
	 */
	@Override
	public void handleAccept(SelectionKey key) throws IOException {
		logger.debug("handle accept...");
	}

	/**
	 * Invoked by the IOLoop when it tears this socket down on an I/O error — make sure the close
	 * callback fires (invokeCloseCallback is one-shot, so an explicit close() won't double-notify).
	 */
	@Override
	public void onClose(java.nio.channels.SelectableChannel channel) {
		invokeCloseCallback();
	}

	/**
	 * Should only be invoked by the IOLoop
	 */
	@Override
	public void handleConnect(SelectionKey key) throws IOException {
		logger.debug("handle connect...");
		SocketChannel sc = (SocketChannel) channel;
		if (sc.isConnectionPending()) {
			try {
				sc.finishConnect();
				invokeConnectSuccessfulCallback();
				interestOps &= ~SelectionKey.OP_CONNECT;
				ioLoop.updateHandler(channel, interestOps |= SelectionKey.OP_READ);
			} catch (ConnectException e) {
				logger.warn("Connect failed: {}", e.getMessage());
				invokeConnectFailureCallback(e);
			}
		}
	}
	
	private final ByteBuffer READ_BUFFER = ByteBuffer.allocate(DEFAULT_BYTEBUFFER_SIZE);
	private static final ThreadLocal<java.nio.charset.CharsetDecoder> DECODER =
		ThreadLocal.withInitial(() -> StandardCharsets.ISO_8859_1.newDecoder());
	
	/**
	 * Should only be invoked by the IOLoop
	 */
	@Override
	public void handleRead(SelectionKey key) throws IOException {
		logger.debug("handle read...");
		SocketChannel socketChannel = ((SocketChannel) key.channel());
		long bytesRead = 0;
		int read = 0;
		READ_BUFFER.clear();
		if (READ_BUFFER.hasRemaining()) {
			do {
				read = socketChannel.read(READ_BUFFER);
				if (read > 0) {
					bytesRead += read;
				}
			} while (read > 0 && READ_BUFFER.hasRemaining());
		}
		READ_BUFFER.flip();
		// Decode directly into a char[] via a reusable CharsetDecoder, avoiding an intermediate String allocation.
		java.nio.CharBuffer decoded = DECODER.get().decode(READ_BUFFER);
		readBuffer.append(decoded);
		if (read == -1) {	// EOF
			reachedEOF = true;
			ioLoop.updateHandler(channel, interestOps &= ~SelectionKey.OP_READ);
			if (readBuffer.length() > 0) {
				checkReadState();
			}
			return;
		}
		logger.debug("readBuffer size: {}", readBuffer.length());
		checkReadState();
	}

	/**
	 * Should only be invoked by the IOLoop
	 */
	@Override
	public void handleWrite(SelectionKey key) {
		logger.debug("handle write...");
		doWrite();
	}

	/**
	 * Reads from the underlaying SelectableChannel until delimiter is reached. When it its, the given
	 * AsyncResult will be invoked.
	 */
	public void readUntil(String delimiter, AsyncResult<String> rcb) {
		logger.debug("readUntil delimiter: {}", delimiter);
		readDelimiter = delimiter;
		readCallback = rcb;
		checkReadState();
	}

	/**
	 * Reads from the underlaying SelectableChannel until n bytes are read. When it its, the given
	 * AsyncResult will be invoked.
	 */
	public void readBytes(int n, AsyncResult<String> rcb) {
		logger.debug("readBytes #bytes: {}", n);
		readBytes = n;
		readCallback = rcb;
		checkReadState();
	}
	
	/**
	 *  If readBuffer contains readDelimiter, client read is finished => invoke readCallback (onSuccess)
	 *  Or if readBytes bytes are read, client read is finished => invoke readCallback (onSuccess)
	 *  Of if end-of-stream is reached => invoke readCallback (onFailure)
	 */
	private void checkReadState() {
		int index = readBuffer.indexOf(readDelimiter);
		if (index != -1 && !readDelimiter.isEmpty()) {
			String result = readBuffer.substring(0, index /*+ readDelimiter.length()*/);
			readBuffer.delete(0, index + readDelimiter.length());
			logger.debug("readBuffer size: {}", readBuffer.length());
			readDelimiter = "";
			invokeReadSuccessfulCallback(result);
		} else if (readBuffer.length() >= readBytes) {
			String result = readBuffer.substring(0, readBytes);
			readBuffer.delete(0, readBytes);
			logger.debug("readBuffer size: {}", readBuffer.length());
			readBytes = Integer.MAX_VALUE;
			invokeReadSuccessfulCallback(result);
		} else if (reachedEOF) {
			invokeReadFailureCallback(new EOFException("Reached end-of-stream"));
		}
	}

	// The invoke*Callback helpers below each fire (and then clear, where one-shot) the corresponding
	// stored user callback. They are the single dispatch points so a callback can't be invoked twice.

	private void invokeReadSuccessfulCallback(String result) {
		AsyncResult<String> cb = readCallback;
		readCallback = nopAsyncStringResult;
		cb.onSuccess(result);
	}
	
	private void invokeReadFailureCallback(Exception e) {
		AsyncResult<String> cb = readCallback;
		readCallback = nopAsyncStringResult;
		cb.onFailure(e);
	}
	
	private void invokeWriteCallback() {
		AsyncCallback cb = writeCallback;
		writeCallback = AsyncCallback.nopCb;
		cb.onCallback();
	}
	
	private void invokeCloseCallback() {
		AsyncCallback cb = closeCallback;
		closeCallback = AsyncCallback.nopCb;
		cb.onCallback();
	}
	
	private void invokeConnectSuccessfulCallback() {
		AsyncResult<Boolean> cb = connectCallback;
		connectCallback = nopAsyncBooleanResult;
		cb.onSuccess(true);
	}
	
	private void invokeConnectFailureCallback(Exception e) {
		AsyncResult<Boolean> cb = connectCallback;
		connectCallback = nopAsyncBooleanResult;
		cb.onFailure(e);
	}

	/**
	 * Writes the given data to the underlaying SelectableChannel. When all data is successfully transmitted, the given 
	 * AsyncCallback will be invoked 
	 */
	public void write(String data, AsyncCallback wcb) {
		logger.debug("write data: {}", data);
		byte[] bytes = data.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		writeBuffer.put(bytes);
		logger.debug("writeBuffer size: {}", writeBuffer.position());
		writeCallback = wcb;
		doWrite();
	}
	
	/**
	 * If we succeed to write everything in writeBuffer, client write is finished => invoke writeCallback
	 */
	private void doWrite() {
		SocketChannel sc = (SocketChannel)channel;
		int bytesWritten = 0;
		if (sc.isConnected() && writeBuffer.position() > 0) {
			try {
				ByteBuffer writeBuf = writeBuffer.getByteBuffer();
				writeBuf.flip();
				int written = 0;
				do {
					written = sc.write(writeBuf);
					bytesWritten += written;
				} while (written > 0 && sc.isConnected() && writeBuf.hasRemaining());
				writeBuf.compact();
			} catch (IOException e) {
				logger.error("IOException during write: {}", e.getMessage());
				invokeCloseCallback();
				Closeables.closeQuietly(ioLoop, channel);
				return;
			}
		}
		logger.debug("wrote: {} bytes", bytesWritten);
		logger.debug("writeBuffer size: {}", writeBuffer.position());
		if (writeBuffer.position() > 0) {
			ioLoop.updateHandler(channel, interestOps |= SelectionKey.OP_WRITE);
		} else {
			ioLoop.updateHandler(channel, interestOps &= ~SelectionKey.OP_WRITE);
			invokeWriteCallback();
		}
	}

}
