package org.deftserver.io.callback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.deftserver.io.IOLoop;
import org.junit.Test;

public class PeriodicCallbackTest {

	@Test
	public void startCancelStartDoesNotDoubleFire() throws InterruptedException {
		// Regression: start()→cancel()→start() must leave exactly ONE reschedule chain. The old code
		// left the first start's pending timeout alive, which on firing saw active==true again and
		// spawned a second chain → the callback fired twice per period.
		//
		// Load-independent signal: a single chain reschedules `period` ms AFTER each fire, so two of
		// its fires can never be closer than ~period apart (even on a starved machine they're just
		// later, never closer). The double-fire bug fires both chains back-to-back in the same loop
		// iteration — microseconds apart. So the MINIMUM inter-fire gap distinguishes them regardless
		// of absolute rate.
		final IOLoop ioLoop;
		try {
			ioLoop = new IOLoop();
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		Thread loopThread = Thread.ofPlatform().daemon(true).start(ioLoop::start);

		final java.util.concurrent.ConcurrentLinkedQueue<Long> fireTimes = new java.util.concurrent.ConcurrentLinkedQueue<>();
		final long period = 20; // ms
		ioLoop.addCallback(() -> {
			PeriodicCallback pc = new PeriodicCallback(ioLoop, () -> fireTimes.add(System.nanoTime()), period);
			pc.start();
			pc.cancel();
			pc.start();
		});

		Thread.sleep(500);
		ioLoop.stop();
		loopThread.join(1000);

		Long[] ts = fireTimes.toArray(new Long[0]); // appended in fire order by the single loop thread
		assertTrue("callback should have fired at least twice in 500 ms, got " + ts.length, ts.length >= 2);
		long minGapMs = Long.MAX_VALUE;
		for (int i = 1; i < ts.length; i++) {
			minGapMs = Math.min(minGapMs, (ts[i] - ts[i - 1]) / 1_000_000L);
		}
		// A single chain never fires closer than ~period (20 ms); the double-fire bug fires two chains
		// in the same iteration (≈0 ms apart). 10 ms cleanly separates them.
		assertTrue("two reschedule chains fired together (min gap " + minGapMs
			+ " ms < period/2) — start/cancel/start double-fire regression", minGapMs >= period / 2);
	}
	
	@Test
	public void testPeriodicCallback() throws InterruptedException {
		final IOLoop ioLoop;
		try {
			ioLoop = new IOLoop();
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		// start the IOLoop from a new thread so we dont block this test.
		Thread.ofPlatform().start(() -> ioLoop.start());
		
		final CountDownLatch latch = new CountDownLatch(10);
		long period = 10; // 10ms (=> ~100times / s)
		ioLoop.addCallback(() -> new PeriodicCallback(ioLoop, latch::countDown, period).start());
		
		latch.await(1, TimeUnit.SECONDS);
		ioLoop.stop();
		// TODO wait?
		assertEquals(0, latch.getCount());
	}
	
}
