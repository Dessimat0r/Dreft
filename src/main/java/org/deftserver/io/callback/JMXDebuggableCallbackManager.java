package org.deftserver.io.callback;

import org.deftserver.util.MXBeanUtil;
import org.deftserver.web.AsyncCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class JMXDebuggableCallbackManager implements CallbackManager, CallbackManagerMXBean {

	private final Logger logger = LoggerFactory.getLogger(JMXDebuggableCallbackManager.class);
	
	private final java.util.Queue<AsyncCallback> callbacks = new java.util.concurrent.ConcurrentLinkedQueue<AsyncCallback>();
	
	{ 	// instance initialization block
		MXBeanUtil.registerMXBean(this, "CallbackManager"); 
	}
	
	/** JMX: number of callbacks currently queued. */
	@Override
	public int getNumberOfCallbacks() {
		return callbacks.size();
	}

	/** Enqueues a callback to run on the next loop iteration. Thread-safe (the only safe cross-thread
	 *  entry point into the loop) via the underlying concurrent queue. */
	@Override
	public void addCallback(AsyncCallback callback) {
		callbacks.add(callback);
		logger.debug("Callback added");
	}

	/**
	 * Runs the callbacks queued as of entry (a bounded snapshot, so callbacks added by callbacks run
	 * on the next pass rather than spinning here). Each is crash-isolated so a bad one can't abort the
	 * batch or kill the loop.
	 *
	 * @return true if more callbacks remain queued (the loop should poll again promptly).
	 */
	@Override
	public boolean execute() {
		int size = callbacks.size();
		if (size == 0) {
			return false;
		}
		for (int i = 0; i < size; i++) {
			AsyncCallback cb = callbacks.poll();
			if (cb == null) {
				break;
			}
			try {
				cb.onCallback();
			} catch (RuntimeException | StackOverflowError | LinkageError e) {
				// A bad callback must not abort the rest of this batch nor escape into the I/O
				// loop's selector loop (where an Error would be re-thrown and kill the loop
				// thread). RuntimeException and the per-task-recoverable Errors (deep recursion /
				// class-loading surfacing inside finish(), SSL re-entry, WS writes) are contained
				// here; OutOfMemoryError and other hard JVM errors still propagate. (Cf. P29.)
				logger.error("{} in scheduled callback — skipping and continuing",
					e.getClass().getSimpleName(), e);
			}
			if (logger.isDebugEnabled()) {
				logger.debug("Callback executed");
			}
		}
		return !callbacks.isEmpty();
	}
	

}
