package org.deftserver.io.timeout;

import java.nio.channels.SelectableChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.deftserver.util.MXBeanUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JMXDebuggableTimeoutManager implements TimeoutManager, TimeoutManagerMXBean {

	private final Logger logger = LoggerFactory.getLogger(JMXDebuggableTimeoutManager.class);

	private final PriorityQueue<DecoratedTimeout> timeouts = new PriorityQueue<>();
	private final Map<SelectableChannel, DecoratedTimeout> index = new HashMap<>();

	private static final AtomicLong seqGenerator = new AtomicLong(0);

	{ 	// instance initialization block
		MXBeanUtil.registerMXBean(this, "TimeoutManager"); 
	}

	/** Number of cancelled-but-still-queued (dead) entries, used to bound queue memory. */
	private int cancelledCount = 0;

	@Override
	public void addKeepAliveTimeout(SelectableChannel channel, Timeout timeout) {
		logger.debug("added keep-alive timeout: {}", timeout);
		DecoratedTimeout oldTimeout = index.get(channel);
		if (oldTimeout != null) {
			oldTimeout.timeout.cancel(); // O(1) cancel instead of O(N) timeouts.remove(oldTimeout)
			cancelledCount++;
		}
		DecoratedTimeout decorated = new DecoratedTimeout(channel, timeout);
		timeouts.add(decorated);
		index.put(channel, decorated);
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
			java.util.List<DecoratedTimeout> live = new java.util.ArrayList<>(timeouts.size());
			for (DecoratedTimeout d : timeouts) {
				if (!d.timeout.isCancelled()) {
					live.add(d);
				}
			}
			timeouts.clear();
			timeouts.addAll(live);
			cancelledCount = 0;
		}
	}

	@Override
	public void addTimeout(Timeout timeout) {
		logger.debug("added generic timeout: {}", timeout);
		timeouts.add(new DecoratedTimeout(timeout));		
	}

	@Override
	public boolean hasKeepAliveTimeout(SelectableChannel channel) {
		return index.containsKey(channel);
	}

	@Override
	public long execute() {
		return execute(System.currentTimeMillis());
	}

	public long execute(long now) {
		while (!timeouts.isEmpty()) {
			DecoratedTimeout candidate = timeouts.peek();
			if (candidate.timeout.getTimeout() > now) { 
				break; 
			}
			timeouts.poll();
			if (candidate.timeout.isCancelled() && cancelledCount > 0) {
				cancelledCount--;
			}
			try {
				candidate.timeout.getCallback().onCallback();
			} catch (RuntimeException e) {
				// A bad callback must not abort the rest of the timeout queue.
				logger.error("RuntimeException in timeout callback — skipping and continuing", e);
			}
			if (candidate.channel != null) {
				DecoratedTimeout current = index.get(candidate.channel);
				if (current == candidate) {
					index.remove(candidate.channel);
				}
			}
			logger.debug("Timeout triggered: {}", candidate.timeout);
		}
		return timeouts.isEmpty() ? Long.MAX_VALUE : Math.max(1, timeouts.peek().timeout.getTimeout() - now);
	}

	// implements TimeoutMXBean
	@Override
	public int getNumberOfKeepAliveTimeouts() {
		return index.size();
	}

	@Override
	public int getNumberOfTimeouts() {
		return timeouts.size();
	}

	private class DecoratedTimeout implements Comparable<DecoratedTimeout> {

		public final SelectableChannel channel;
		public final Timeout timeout;
		private final long sequenceNumber = seqGenerator.getAndIncrement();

		public DecoratedTimeout(SelectableChannel channel, Timeout timeout) {
			this.channel = channel;
			this.timeout = timeout;
		}

		public DecoratedTimeout(Timeout timeout) {
			this(null, timeout);
		}

		@Override
		public int compareTo(DecoratedTimeout that) {
			if (this == that) {
				return 0;
			}
			int diff = Long.compare(this.timeout.getTimeout(), that.timeout.getTimeout());
			if (diff != 0) {
				return diff;
			}
			return Long.compare(this.sequenceNumber, that.sequenceNumber);
		}
		
	}

}
