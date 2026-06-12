package org.deftserver.io;


import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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


public class IOLoop implements IOLoopMXBean {
	
	/* IOLoop singleton to use for convenience (otherwise you would have to pass around the 
	 * IOLoop instance explicitly, now you can simply use IOLoop.INSTANCE) */
	public static final IOLoop INSTANCE;
	static {
		try {
			INSTANCE = new IOLoop();
		} catch (IOException e) {
			throw new IllegalStateException("Couldn't create IOLoop", e);
		}
	}
	
	private volatile boolean running = false;

	// The thread currently authorised to drive this loop. Set atomically with `running` in start().
	// A stop() immediately followed by another start() (a server restart, or test classes that share
	// IOLoop.INSTANCE and stop/start it per class) could otherwise leave the *previous* loop thread —
	// still mid-iteration inside select() — resuming once the new start() flips `running` back to
	// true, putting TWO threads on one Selector concurrently (data races, misframed reads, stalls).
	// The loop condition checks identity against this token so only the most-recent starter keeps
	// running; any superseded thread exits on its next iteration.
	private volatile Thread loopThread = null;

	private int consecutiveSelectFailures = 0;

	private final Logger logger = LoggerFactory.getLogger(IOLoop.class);

	private Selector selector;
	
	private final Map<SelectableChannel, IOHandler> handlers = new java.util.concurrent.ConcurrentHashMap<>();
	
	private final TimeoutManager tm = new JMXDebuggableTimeoutManager();
	private final CallbackManager cm = new JMXDebuggableCallbackManager();
	
	private static final AtomicInteger sequence = new AtomicInteger(0);
	
	/** Creates an I/O loop with its own NIO {@link Selector} and registers its JMX bean. The loop
	 *  does not run until {@link #start()} is called on the thread that should become the loop thread. */
	public IOLoop() throws IOException {
		selector = Selector.open();
		MXBeanUtil.registerMXBean(this, "IOLoop");
	}
	/**
	 * Start the io loop. The thread that invokes this method will be blocked (until {@link IOLoop#stop} is invoked) 
	 * and will be the io loop thread.
	 */
	public void start() {
		final Thread self = Thread.currentThread();
		final Thread previous;
		synchronized (this) {
			if (running) throw new IllegalStateException("IOLoop already running.");
			// Capture any prior loop thread that hasn't exited yet. stop() flips running=false and
			// joins (bounded) the loop thread, but that join can time out under load — leaving the
			// old thread alive. Claiming ownership here signals it to exit at its next top-of-loop
			// check; below we then wait for it to actually finish before driving the Selector.
			previous = (loopThread != null && loopThread != self && loopThread.isAlive()) ? loopThread : null;
			running = true;
			loopThread = self; // claim ownership atomically with `running`
		}

		// If a superseded thread is still inside an iteration (blocked in select(), or mid-read of a
		// channel), starting to drive the Selector now would put two threads on it concurrently —
		// processing the same keys and reading the same channels in parallel, which corrupts reads.
		// Wake it out of select() and wait for it to exit first. The wait is bounded so a (would-be
		// bug) wedged handler can't hang a restart forever; the ownership token still forces the old
		// thread out as soon as it completes its current iteration.
		if (previous != null) {
			if (selector != null) {
				selector.wakeup();
			}
			try {
				previous.join(2000);
			} catch (InterruptedException e) {
				self.interrupt();
			}
			if (previous.isAlive()) {
				logger.error("Previous IOLoop thread {} did not exit before restart — proceeding may briefly race the Selector", previous.getName());
			}
		}

		self.setName("I/O-LOOP" + sequence.getAndIncrement());

		long selectorTimeout = 250; // 250 ms
		// Exit if either stopped (running=false) or superseded by a newer start() that re-claimed
		// ownership (loopThread != self) — the latter prevents two threads racing one Selector.
		while (running && loopThread == self) {
		try {
			consecutiveSelectFailures = 0;
			if (selector.select(selectorTimeout) == 0) {
					long ms = tm.execute();
					if (cm.execute()) {
						selectorTimeout = 1;
					} else if (ms > 0) {
						selectorTimeout = Math.min(ms, 250);
					} else {
						selectorTimeout = 250;
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
						closeAndNotify(handler, key.channel());
						continue;
					}
					try {
						if (key.isReadable()) {
							try {
								handler.handleRead(key);
			} catch (EOFException e) {
				logger.debug("EOFException in read", e);
				removeHandler(key.channel());
				closeAndNotify(handler, key.channel());
				continue;
			} catch (IOException e) {
				logger.debug("IOException in read", e);
				removeHandler(key.channel());
				closeAndNotify(handler, key.channel());
				continue;
							}
						}
						if (key.isWritable()) {
							try {
								handler.handleWrite(key);
			} catch (IOException e) {
				logger.debug("IOException in write", e);
				removeHandler(key.channel());
				closeAndNotify(handler, key.channel());
				continue;
							}
						}
						if (key.isConnectable()) {
							try {
								handler.handleConnect(key);
			} catch (IOException e) {
				logger.debug("Unable to connect", e);
				removeHandler(key.channel());
				closeAndNotify(handler, key.channel());
				continue;
							}
						}
			if (key.isAcceptable()) {
				try {
					handler.handleAccept(key);
				} catch (IOException e) {
					// Never closeAndNotify(key.channel()) here — that channel is the listening
					// ServerSocketChannel; closing it would halt all accepts. handleAccept is
					// expected to contain per-connection failures itself (see HttpProtocol), so an
					// IOException here is a genuine listener-level issue: log and keep accepting.
					logger.error("Unable to accept connection on listening socket {} (keeping it open)", key.channel(), e);
				}
			}
					} catch (CancelledKeyException e) {
						logger.debug("CancelledKeyException in IOLoop", e);
						dropFaultingChannel(handler, key.channel());
					} catch (RuntimeException e) {
						// A handler bug must not abort processing of the remaining selected keys
						// nor leave a faulting channel registered (which the selector would keep
						// re-reporting as ready, spinning the loop). Drop just this channel — but
						// never the listening socket (dropFaultingChannel guards that).
						logger.error("Unexpected RuntimeException handling channel {} — closing it", key.channel(), e);
						dropFaultingChannel(handler, key.channel());
					} catch (StackOverflowError | LinkageError e) {
						// An Error tied to processing ONE channel must not kill the whole server
						// thread (robustness > everything). A StackOverflowError (deep recursion on a
						// pathological request) and a LinkageError (e.g. ExceptionInInitializerError /
						// NoClassDefFoundError surfacing while parsing) are both per-request and
						// recoverable by dropping just this channel. OutOfMemoryError and other hard
						// JVM errors still propagate to the outer handler.
						logger.error("Contained {} handling channel {} — closing it",
							e.getClass().getSimpleName(), key.channel(), e);
						dropFaultingChannel(handler, key.channel());
					}
				}
				long ms = tm.execute();
				if (cm.execute()) { 
					selectorTimeout = 1; 
				} else if (ms > 0) {
					selectorTimeout = Math.min(ms, 250);
				} else {
					selectorTimeout = 250;
				}
		} catch (IOException e) {
			logger.error("IOException received in IOLoop", e);
			consecutiveSelectFailures++;
			if (consecutiveSelectFailures >= 10) {
				logger.error("10 consecutive select() IOExceptions — shutting down IOLoop", e);
				running = false;
			}
		}
		}
	}
	
	/**
	 * Closes a channel the loop is tearing down, first giving its handler a chance to release any
	 * per-channel state ({@link IOHandler#onClose}) so nothing leaks. Null-safe for the
	 * no-handler-found case.
	 */
	private void closeAndNotify(IOHandler handler, SelectableChannel channel) {
		if (handler != null) {
			try {
				handler.onClose(channel);
		} catch (RuntimeException | StackOverflowError | LinkageError e) {
			logger.debug("IOHandler.onClose hook threw — ignoring", e);
		}
		}
		Closeables.closeQuietly(this, channel);
	}

	/**
	 * Drops a channel that faulted while being serviced (removes its handler and closes it) — UNLESS
	 * it is the listening {@link java.nio.channels.ServerSocketChannel}. A per-event error (e.g. an
	 * exception escaping {@code handleAccept} for a single hostile connection) must never close the
	 * shared listening socket, which would stop the whole server from accepting (a remote DoS — see
	 * progress.md V3-20). For the listener we log and keep accepting; only client channels are dropped.
	 */
	private void dropFaultingChannel(IOHandler handler, SelectableChannel channel) {
		if (channel instanceof java.nio.channels.ServerSocketChannel) {
			logger.error("Error while servicing the listening socket {} — NOT closing it (would halt accepts)", channel);
			return;
		}
		removeHandler(channel);
		closeAndNotify(handler, channel);
	}

	/**
	 * Stop the io loop and release the thread (io loop thread) that invoked the {@link IOLoop#start} method.
	 * <p>
	 * Wakes the selector so the loop thread returns from {@code select()} promptly, and — when called
	 * from a different thread (the normal case: a shutdown/teardown thread) — waits briefly for that
	 * thread to actually exit. This guarantees a subsequent {@link #start()} (a restart, or the shared
	 * {@code IOLoop.INSTANCE} reused across test classes) does not begin while the previous loop thread
	 * is still inside {@code select()}, which would otherwise race two threads on one Selector.
	 */
	public void stop() {
		logger.debug("Stopping IOLoop...");
		running = false;
		final Thread t = loopThread;
		if (selector != null) {
			selector.wakeup(); // unblock the loop thread's select() so it observes running=false now
		}
		if (t != null && t != Thread.currentThread()) {
			try {
				t.join(1000); // bounded: never hang teardown if the loop thread is wedged
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
	
	/**
	 * Registers a new {@code IOHandler} with this {@code IOLoop}.
	 * <p>
	 * <b>Thread-safety:</b> This method calls {@link java.nio.channels.SelectableChannel#register(Selector, int)}
	 * which is not safe to call while the loop's {@code select()} is in progress on another thread.
	 * Call this method <b>only</b> from the IOLoop thread itself or before {@link #start()} is invoked.
	 * For cross-thread registration, use {@link #addCallback(AsyncCallback)} with a callback that registers on loop.
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
			SelectionKey key = channel.keyFor(selector);
			if (key != null && key.isValid()) {
				key.interestOps(newInterestOps);
			} else {
				logger.debug("updateHandler: SelectionKey is null or cancelled for channel {} — skipping", channel);
			}
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
	
	/** Registers (replacing any existing) the keep-alive idle timeout for a channel. */
	public void addKeepAliveTimeout(SelectableChannel channel, Timeout keepAliveTimeout) {
		tm.addKeepAliveTimeout(channel, keepAliveTimeout);
	}

	/** True if a keep-alive timeout is currently registered for the channel. */
	public boolean hasKeepAliveTimeout(SelectableChannel channel) {
		return tm.hasKeepAliveTimeout(channel);
	}

	/** Schedules a generic (non-channel-keyed) timeout, e.g. a header/body read deadline. */
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
		if (selector != null) {
			selector.wakeup();
		}
	}

	public <T> java.util.concurrent.CompletableFuture<T> submit(java.util.concurrent.Callable<T> task) {
		java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
		addCallback(new AsyncCallback() {
			@Override
			public void onCallback() {
				try {
					future.complete(task.call());
				} catch (Throwable t) {
					future.completeExceptionally(t);
				}
			}
		});
		return future;
	}

	public java.util.concurrent.CompletableFuture<Void> submit(Runnable task) {
		java.util.concurrent.CompletableFuture<Void> future = new java.util.concurrent.CompletableFuture<>();
		addCallback(new AsyncCallback() {
			@Override
			public void onCallback() {
				try {
					task.run();
					future.complete(null);
				} catch (Throwable t) {
					future.completeExceptionally(t);
				}
			}
		});
		return future;
	}
	
// implements IOLoopMXBean

	/** JMX: number of channels currently registered with this loop. */
	@Override
	public int getNumberOfRegisteredIOHandlers() {
		return handlers.size();
	}

	/** JMX: string descriptions of the currently-registered handlers (snapshot copy). */
	@Override
	public List<String> getRegisteredIOHandlers() {
		Map<SelectableChannel, IOHandler> defensive = new HashMap<>(handlers);
		return defensive.values().stream()
				.map(IOHandler::toString)
				.collect(java.util.stream.Collectors.toList());
	}
	
	/** Shuts the loop down: closes every registered channel, stops the loop, and unregisters the
	 *  JMX beans (loop/timeout/callback) to avoid classloader/memory leaks. */
	public void dispose() {
		java.util.List<SelectableChannel> channels = new java.util.ArrayList<>(handlers.keySet());
		for (SelectableChannel channel : channels) {
			Closeables.closeQuietly(this, channel);
		}
		stop();
		try { if (selector != null) selector.close(); } catch (java.io.IOException e) { /* ignore */ }
		MXBeanUtil.unregisterMXBean(this, "IOLoop");
		if (tm instanceof JMXDebuggableTimeoutManager) {
			MXBeanUtil.unregisterMXBean(tm, "TimeoutManager");
		}
		if (cm instanceof JMXDebuggableCallbackManager) {
			MXBeanUtil.unregisterMXBean(cm, "CallbackManager");
		}
	}
}
