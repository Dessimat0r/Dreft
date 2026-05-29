package org.deftserver.io.callback;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.deftserver.io.IOLoop;
import org.junit.Test;

public class PeriodicCallbackTest {
	
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
