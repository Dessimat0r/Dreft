package org.deftserver.io.timeout;

import java.nio.channels.SelectableChannel;

import org.deftserver.io.IOLoop;
import org.deftserver.util.Closeables;
import org.deftserver.web.AsyncCallback;


public class Timeout implements Comparable<Timeout> {

	private static final java.util.concurrent.atomic.AtomicLong seqGenerator = new java.util.concurrent.atomic.AtomicLong(0);

	private final long timeout;
	private final AsyncCallback cb;
	private SelectableChannel channel;
	private final long sequenceNumber = seqGenerator.getAndIncrement();
	private boolean cancelled = false;
	
	/** Creates a timeout that fires {@code cb} once the wall-clock time reaches {@code timeout}
	 *  (absolute epoch milliseconds). */
	public Timeout(long timeout, AsyncCallback cb) {
		this(timeout, cb, null);
	}

	public Timeout(long timeout, AsyncCallback cb, SelectableChannel channel) {
		this.timeout = timeout;
		this.cb = cb;
		this.channel = channel;
	}

	public SelectableChannel getChannel() {
		return channel;
	}

	public void setChannel(SelectableChannel channel) {
		this.channel = channel;
	}

	public long getSequenceNumber() {
		return sequenceNumber;
	}

	/** The absolute deadline (epoch milliseconds) at which this timeout is due. */
	public long getTimeout() {
		return timeout;
	}

	/** Cancels this timeout. O(1): it stays in the queue but its callback becomes a no-op
	 *  (see {@link #getCallback}) and is dropped when its deadline is reached. */
	public void cancel() {
		cancelled = true;
	}

	/** Whether this timeout has been cancelled. */
	public boolean isCancelled() {
		return cancelled;
	}

	/** The callback to run when due — a no-op if the timeout was cancelled, so a cancelled timeout
	 *  that is still in the queue fires harmlessly. */
	public AsyncCallback getCallback() {
		return cancelled ? AsyncCallback.nopCb : cb;
	}

	/** Builds a keep-alive idle timeout that quietly closes {@code clientChannel} when it expires. */
	public static Timeout newKeepAliveTimeout(final IOLoop ioLoop, final SelectableChannel clientChannel, long keepAliveTimeout) {
		return new Timeout(
				System.currentTimeMillis() + keepAliveTimeout,
				new AsyncCallback() { public void onCallback() { Closeables.closeQuietly(ioLoop, clientChannel); } },
				clientChannel
		);
	}

	@Override
	public int compareTo(Timeout that) {
		if (this == that) {
			return 0;
		}
		int diff = Long.compare(this.timeout, that.timeout);
		if (diff != 0) {
			return diff;
		}
		return Long.compare(this.sequenceNumber, that.sequenceNumber);
	}

}
