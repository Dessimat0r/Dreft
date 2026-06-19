package org.deftserver.io.timeout;

import java.nio.channels.SelectableChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

import org.deftserver.util.MXBeanUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JMXDebuggableTimeoutManager implements TimeoutManager, TimeoutManagerMXBean {

	private final Logger logger = LoggerFactory.getLogger(JMXDebuggableTimeoutManager.class);

	private java.util.PriorityQueue<Timeout> timeouts = new java.util.PriorityQueue<>();
	private final Map<SelectableChannel, Timeout> index = new HashMap<>();

	{ 	// instance initialization block
		MXBeanUtil.registerMXBean(this, "TimeoutManager"); 
	}

	/** Number of cancelled-but-still-queued (dead) entries, used to bound queue memory. */
	private int cancelledCount = 0;

	@Override
	public void addKeepAliveTimeout(SelectableChannel channel, Timeout timeout) {
		logger.debug("added keep-alive timeout: {}", timeout);
		Timeout oldTimeout = index.get(channel);
		if (oldTimeout != null) {
			oldTimeout.cancel(); // O(1) cancel instead of O(N) timeouts.remove(oldTimeout)
			cancelledCount++;
		}
		timeout.setChannel(channel);
		timeouts.add(timeout);
		index.put(channel, timeout);
		purgeCancelledIfNeeded();
	}

	/**
	 * Compacts the queue when dead (cancelled) entries dominate it. Cancellation stays O(1) (we
	 * just flag the Timeout and leave the node in the heap), but under sustained keep-alive churn
	 * those dead nodes would otherwise accumulate until their original deadlines elapse. Rebuilding
	 * the heap (O(n)) only when more than half the entries are dead keeps the work amortised O(1)
	 * per add while bounding memory to ~2× the live timeout count.
	 */
	private void purgeCancelledIfNeeded() {
		if (cancelledCount > 64 && cancelledCount * 2 > timeouts.size()) {
			java.util.List<Timeout> live = new java.util.ArrayList<>(timeouts.size());
			for (Timeout t : timeouts) {
				if (!t.isCancelled()) {
					live.add(t);
				}
			}
			timeouts = new java.util.PriorityQueue<>(live);
			cancelledCount = 0;
		}
	}

	/** Schedules a generic (non-channel-keyed) timeout, e.g. a header- or body-read deadline. */
	@Override
	public void addTimeout(Timeout timeout) {
		logger.debug("added generic timeout: {}", timeout);
		timeouts.add(timeout);
	}

	/** True if a keep-alive timeout is currently registered for the given channel. */
	@Override
	public boolean hasKeepAliveTimeout(SelectableChannel channel) {
		return index.containsKey(channel);
	}

	/** Fires all timeouts due as of now; see {@link #execute(long)}. */
	@Override
	public long execute() {
		return execute(System.currentTimeMillis());
	}

	/**
	 * Runs the callback of every timeout whose deadline is {@code <= now}, popping them from the
	 * priority queue in deadline order. A cancelled timeout fires a harmless no-op callback. Each
	 * callback is crash-isolated (a bad one can't abort the queue or kill the I/O loop).
	 *
	 * @return milliseconds until the next timeout is due (>= 1), or {@code Long.MAX_VALUE} if none.
	 */
	public long execute(long now) {
		while (!timeouts.isEmpty()) {
			Timeout candidate = timeouts.peek();
			if (candidate.getTimeout() > now) { 
				break; 
			}
			timeouts.poll();
			if (candidate.isCancelled() && cancelledCount > 0) {
				cancelledCount--;
			}
			try {
				candidate.getCallback().onCallback();
			} catch (RuntimeException | StackOverflowError | LinkageError e) {
				// A bad callback must not abort the rest of the timeout queue nor escape into the
				// I/O loop (where an Error would be re-thrown and kill the loop thread). Per-task
				// recoverable Errors are contained here; hard JVM errors still propagate. (Cf. P29.)
				logger.error("{} in timeout callback — skipping and continuing",
					e.getClass().getSimpleName(), e);
			}
			if (candidate.getChannel() != null) {
				Timeout current = index.get(candidate.getChannel());
				if (current == candidate) {
					index.remove(candidate.getChannel());
				}
			}
			logger.debug("Timeout triggered: {}", candidate);
		}
		return timeouts.isEmpty() ? Long.MAX_VALUE : Math.max(1, timeouts.peek().getTimeout() - now);
	}

	// implements TimeoutMXBean

	/** JMX: number of live keep-alive (channel-keyed) timeouts. */
	@Override
	public int getNumberOfKeepAliveTimeouts() {
		return index.size();
	}

	/** JMX: total entries in the timeout queue (including not-yet-purged cancelled ones). */
	@Override
	public int getNumberOfTimeouts() {
		return timeouts.size();
	}

}
