package org.deftserver.io.callback;

import java.util.AbstractCollection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

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
	
	@Override
	public int getNumberOfCallbacks() {
		return callbacks.size();
	}

	@Override
	public void addCallback(AsyncCallback callback) {
		callbacks.add(callback);
		logger.debug("Callback added");
	}

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
			cb.onCallback();
			if (logger.isDebugEnabled()) {
				logger.debug("Callback executed");
			}
		}
		return !callbacks.isEmpty();
	}
	

}
