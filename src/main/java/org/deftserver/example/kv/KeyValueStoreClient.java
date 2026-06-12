package org.deftserver.example.kv;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

import org.deftserver.io.AsynchronousSocket;
import org.deftserver.web.AsyncCallback;
import org.deftserver.web.AsyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Example client for the {@link KeyValueStore} demo. Demonstration code, not part of the framework. */
public class KeyValueStoreClient {
	
	private final static Logger logger = LoggerFactory.getLogger(KeyValueStoreClient.class);
	
	private AsynchronousSocket socket;
	private SocketChannel channel;
	private final String host;
	private final int port;
	
	/** Creates a client targeting the given host and port. */
	public KeyValueStoreClient(String host, int port) {
		this.host = host;
		this.port = port;
	}

	/** Opens a non-blocking connection to the store. */
	public void connect() {
		try {
			channel = SocketChannel.open(new InetSocketAddress(host, port));
			channel.configureBlocking(false);
			socket = new AsynchronousSocket(channel);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/** Sends a GET command and delivers the value to {@code cb}. */
	public void get(String value, AsyncResult<String> cb) {
		socket.write("GET deft\r\n", new WriteCallback(cb));
	}

	public java.util.concurrent.CompletableFuture<String> get(String value) {
		java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
		get(value, new AsyncResult<String>() {
			@Override
			public void onSuccess(String result) {
				future.complete(result);
			}
			@Override
			public void onFailure(Throwable caught) {
				future.completeExceptionally(caught);
			}
		});
		return future;
	}

	/** Callback that, once the GET command is written, reads the store's reply line. */
	private class WriteCallback implements AsyncCallback {

		private final AsyncResult<String> cb;

		/** Wraps the user's result callback to be invoked once the reply has been read. */
		public WriteCallback(AsyncResult<String> cb) {
			this.cb = cb;
		}
		
		@Override
		public void onCallback() {
			// write is finished. read response from server
			logger.debug("readUntil: \r\n");
			socket.readUntil("\r\n", cb);
		}
		
	}
	
}
