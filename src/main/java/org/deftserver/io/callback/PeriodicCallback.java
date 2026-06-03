package org.deftserver.io.callback;

import org.deftserver.io.IOLoop;
import org.deftserver.io.timeout.Timeout;
import org.deftserver.web.AsyncCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PeriodicCallback {

	private static final Logger logger = LoggerFactory.getLogger(PeriodicCallback.class);

	private final IOLoop ioLoop;
	private final AsyncCallback cb;
	private final long period;
	private volatile boolean active = false;
	// Bumped on every start()/cancel() so a stale scheduled run (queued by a previous start) can be
	// recognised and dropped. Without this, start()→cancel()→start() would leave the first start's
	// pending timeout alive; when it fires it would see active==true again and spawn a SECOND
	// reschedule chain → the callback fires twice (or more) per period.
	private volatile long generation = 0;

	/** 
	 * A periodic callback that will execute its callback once every period.
	 * @param cb 
	 * @param period The period in ms (must be > 0)
	 */
	public PeriodicCallback(AsyncCallback cb, long period) {
		this(IOLoop.INSTANCE, cb, period);
	}

	public PeriodicCallback(IOLoop ioLoop, AsyncCallback cb, long period) {
		if (cb == null) throw new IllegalArgumentException("callback must not be null");
		if (period <= 0) throw new IllegalArgumentException("period must be positive, got " + period);
		this.ioLoop = ioLoop;
		this.cb = cb;
		this.period = period;
	}

	public void start() {
		if (active) return;
		active = true;
		scheduleNext(++generation); // claim a fresh generation; supersedes any stale pending run
	}

	private void scheduleNext(final long gen) {
		ioLoop.addTimeout(
			new Timeout(
				System.currentTimeMillis() + period,
				new AsyncCallback() { @Override public void onCallback() { run(gen); }}
			)
		);
	}

	private void run(long gen) {
		// Only the most-recent generation may run — a timeout queued by a superseded start() (after a
		// cancel()+start()) carries an older gen and is dropped here, so there's never more than one
		// live reschedule chain.
		if (active && gen == generation) {
			try {
				cb.onCallback();
			} catch (RuntimeException e) {
				logger.error("PeriodicCallback callback threw — rescheduling anyway", e);
			}
			scheduleNext(gen);
		}
	}

	public void cancel() {
		this.active = false;
		generation++; // invalidate any pending scheduled run so a later start() can't resurrect it
	}

}
