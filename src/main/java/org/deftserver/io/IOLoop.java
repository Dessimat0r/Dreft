package org.deftserver.io;

import static com.google.common.collect.Collections2.transform;

import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.deftserver.io.callback.CallbackManager;
import org.deftserver.io.callback.JMXDebuggableCallbackManager;
import org.deftserver.io.timeout.JMXDebuggableTimeoutManager;
import org.deftserver.io.timeout.Timeout;
import org.deftserver.io.timeout.TimeoutManager;
import org.deftserver.util.Closeables;
import org.deftserver.util.MXBeanUtil;
import org.deftserver.web.AsyncCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.Lists;

public class IOLoop implements IOLoopMXBean {
	
	/* IOLoop singleton to use for convenience (otherwise you would have to pass around the 
	 * IOLoop instance explicitly, now you can simply use IOLoop.INSTANCE) */
	public static final IOLoop INSTANCE = new IOLoop();
	
	private boolean running = false;
	
	private final Logger logger = LoggerFactory.getLogger(IOLoop.class);

	private Selector selector;
	
	private final Map<SelectableChannel, IOHandler> handlers = new HashMap<>();
	
	private final TimeoutManager tm = new JMXDebuggableTimeoutManager();
	private final CallbackManager cm = new JMXDebuggableCallbackManager();
	
	private static final AtomicInteger sequence = new AtomicInteger(0);
	
	public IOLoop() {
		try {
			selector = Selector.open();
		} catch (IOException e) {
			logger.error("Could not open selector: {}", e.getMessage());
		}
		MXBeanUtil.registerMXBean(this, "IOLoop");
	}
	/**
	 * Start the io loop. The thread that invokes this method will be blocked (until {@link IOLoop#stop} is invoked) 
	 * and will be the io loop thread.
	 */
	public void start() {
		Thread.currentThread().setName("I/O-LOOP" + sequence.getAndIncrement());
		running = true;
		
		long selectorTimeout = 250; // 250 ms
		while (running) {
			try {
				if (selector.select(selectorTimeout) == 0) {
					long ms = tm.execute();
					selectorTimeout = Math.min(ms, /*selectorTimeout*/ 250);
					if (cm.execute()) {
						selectorTimeout = 1;
					}
					continue;
				}
				Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
				while (keys.hasNext()) {
					SelectionKey key = keys.next();
					keys.remove();
					IOHandler handler = handlers.get(key.channel());
					if (handler == null) {
						logger.debug("No handler found in IOLoop for channel: {}", key.channel());
						Closeables.closeQuietly(this, key.channel());
						continue;
					}
					try {
						if (key.isReadable()) {
							try {
								handler.handleRead(key);
							} catch (EOFException e) {
								logger.debug("EOFException in read", e);
								Closeables.closeQuietly(this, key.channel());
								continue;
							} catch (IOException e) {
								logger.debug("IOException in read", e);
								Closeables.closeQuietly(this, key.channel());
								continue;
							}
						}
						if (key.isWritable()) {
							try {
								handler.handleWrite(key);
							} catch (IOException e) {
								logger.debug("IOException in write", e);
								Closeables.closeQuietly(this, key.channel());
								continue;
							}
						}
						if (key.isConnectable()) {
							try {
								handler.handleConnect(key);
							} catch (IOException e) {
								logger.debug("Unable to connect", e);
								Closeables.closeQuietly(this, key.channel());
								continue;
							}
						}
						if (key.isAcceptable()) {
							try {
								handler.handleAccept(key);
							} catch (IOException e) {
								logger.debug("Unable to accept connection", e);
							}
						}
					} catch (CancelledKeyException e) {
						logger.debug("CancelledKeyException in IOLoop", e);
						Closeables.closeQuietly(this, key.channel());
					}
				}
				long ms = tm.execute();
				selectorTimeout = Math.min(ms, /*selectorTimeout*/ 250);
				if (cm.execute()) { 
					selectorTimeout = 1; 
				}
			} catch (IOException e) {
				logger.error("IOException received in IOLoop", e);			
			}
		}
	}
	
	/**
	 * Stop the io loop and release the thread (io loop thread) that invoked the {@link IOLoop#start} method.
	 */
	public void stop() {
		running = false;
		logger.debug("Stopping IOLoop...");
	}
	
	/**
	 * Registers a new {@code IOHandler} with this {@code IOLoop}.
	 * 
	 * @param channel The {@code SelectableChannel}
	 * @param handler {@code IOHandler that will receive the io callbacks.}
	 * @param interestOps See {@link SelectionKey} for valid values. (Xor for multiple interests).
	 * @param attachment The {@code attachment} that will be accessible from the returning {@code SelectionKey}s 
	 * attachment.
	 * 
	 */
	public SelectionKey addHandler(SelectableChannel channel, IOHandler handler, int interestOps, Object attachment) throws IOException {
		SelectionKey key = registerChannel(channel, interestOps, attachment);
		handlers.put(channel, handler);
		return key;
	}
	
	/**
	 * Unregisters the previously registered {@code IOHandler}.

	 * @param channel The {@code SelectableChannel} that was registered with a user defined {@code IOHandler}
	 */
	public void removeHandler(SelectableChannel channel) {
		handlers.remove(channel);
	}
	
	/**
	 * Update an earlier registered {@code SelectableChannel}
	 * 
	 * @param channel The {@code SelectableChannel}
	 * @param newInterestOps The complete new set of interest operations.
	 */
	public void updateHandler(SelectableChannel channel, int newInterestOps) {
		if (handlers.containsKey(channel)) {
			channel.keyFor(selector).interestOps(newInterestOps);
		} else {
			logger.warn("Tried to update interestOps for an unknown SelectableChannel.");
		}
	}
	
	/**
	 * 
	 * @param channel
	 * @param interestOps
	 * @param attachment
	 * @return
	 */
	private SelectionKey registerChannel(SelectableChannel channel, int interestOps, Object attachment) throws IOException {
		return channel.register(selector, interestOps, attachment);
	}
	
	public void addKeepAliveTimeout(SelectableChannel channel, Timeout keepAliveTimeout) {
		tm.addKeepAliveTimeout(channel, keepAliveTimeout);
	}
	
	public boolean hasKeepAliveTimeout(SelectableChannel channel) {
		return tm.hasKeepAliveTimeout(channel);
	}
	
	public void addTimeout(Timeout timeout) {
		tm.addTimeout(timeout);
	}

	/**
	 * The callback will be invoked in the next iteration in the io loop. This is the only thread safe method that is
	 * exposed by Deft. 
	 * This is a convenient way to return control to the io loop.
	 */
	public void addCallback(AsyncCallback callback) {
		cm.addCallback(callback);
	}
	
// implements IOLoopMXBean
	@Override
	public int getNumberOfRegisteredIOHandlers() {
		return handlers.size();
	}

	@Override
	public List<String> getRegisteredIOHandlers() {
		Map<SelectableChannel, IOHandler> defensive = new HashMap<SelectableChannel, IOHandler>(handlers);
		Collection<String> readables = transform(defensive.values(), new Function<IOHandler, String>() {
			@Override public String apply(IOHandler handler) { return handler.toString(); }
		});
		return Lists.newLinkedList(readables);
	}

}
