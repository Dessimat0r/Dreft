package org.deftserver.io.timeout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;

import org.deftserver.web.AsyncCallback;
import org.junit.Test;

public class JMXDebuggableTimeoutManagerTest {

	private final JMXDebuggableTimeoutManager tm = new JMXDebuggableTimeoutManager();
	
	@Test
	public void timeoutManagerTest() throws InterruptedException {
		final long now = System.currentTimeMillis();
		MockChannel c1 = new MockChannel();
		MockChannel c2 = new MockChannel();
		MockChannel c3 = new MockChannel();

		addNopTimeout(now);
		addNopTimeout(now);
		addNopTimeout(now);
		addNopTimeout(now+1);
		addNopTimeout(now+2);
		addNopTimeout(now+1000);
		addNopTimeout(now+1200);
		addNopTimeout(now+1400);
		
		addNopKeepAliveTimeout(c1, now);
		addNopKeepAliveTimeout(c2, now);
		addNopKeepAliveTimeout(c3, now+1);
		
		assertEquals(11, tm.getNumberOfTimeouts());
		assertEquals(3, tm.getNumberOfKeepAliveTimeouts());

		tm.execute(now + 200);
		assertEquals(3, tm.getNumberOfTimeouts());
		assertEquals(0, tm.getNumberOfKeepAliveTimeouts());
	
		tm.execute(now + 2000);
		assertEquals(0, tm.getNumberOfTimeouts());
		assertEquals(0, tm.getNumberOfKeepAliveTimeouts());
	}
	
	private void addNopTimeout(long timeout) {
		tm.addTimeout(new Timeout(timeout, new AsyncCallback() {
			@Override public void onCallback() { /*nop*/}
		}));	
	}

	private void addNopKeepAliveTimeout(SelectableChannel channel, long timeout) {
		tm.addKeepAliveTimeout(channel, new Timeout(timeout, new AsyncCallback() {
			@Override public void onCallback() { /*nop*/ }
		}));
	}
	
	@Test
	public void addTimeoutDuringTimeoutExecution() throws InterruptedException {
		final long now = System.currentTimeMillis();
		addRecursiveTimeout(now);
		addRecursiveTimeout(now+10);
		addRecursiveTimeout(now+20);
		
		assertEquals(3, tm.getNumberOfTimeouts());
		assertEquals(0, tm.getNumberOfKeepAliveTimeouts());

		Thread.sleep(50);
		long ms = tm.execute();
		assertTrue(ms != Long.MAX_VALUE);
		
		assertEquals(3, tm.getNumberOfTimeouts());
		assertEquals(0, tm.getNumberOfKeepAliveTimeouts());
		
		Thread.sleep(50);
		tm.execute();
		Thread.sleep(50);
		tm.execute();
		Thread.sleep(50);
		tm.execute();
		
		assertEquals(0, tm.getNumberOfTimeouts());
		assertEquals(0, tm.getNumberOfKeepAliveTimeouts());
	}
	
	@Test
	public void keepAliveChurnDoesNotGrowQueueUnbounded() {
		// Repeatedly re-arming the keep-alive timeout for the SAME channel cancels the previous
		// entry each time. Without compaction the dead entries would accumulate to ~2000; the
		// purge keeps the queue bounded to roughly 2x the live count (here: 1).
		final long now = System.currentTimeMillis();
		MockChannel c = new MockChannel();
		for (int i = 0; i < 2000; i++) {
			addNopKeepAliveTimeout(c, now + 100000 + i);
		}
		assertEquals(1, tm.getNumberOfKeepAliveTimeouts());
		assertTrue("queue should be compacted, was " + tm.getNumberOfTimeouts(),
			tm.getNumberOfTimeouts() < 200);
	}

	private void addRecursiveTimeout(final long timeout) {
		final Timeout t = new Timeout(timeout, new AsyncCallback() {
			@Override public void onCallback() { addNopTimeout(System.currentTimeMillis() + 100); }
		});
		tm.addTimeout(t);	
	}
	
	private class MockChannel extends SelectableChannel {

		@Override
		public Object blockingLock() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public SelectableChannel configureBlocking(boolean block)
		throws IOException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public boolean isBlocking() {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean isRegistered() {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public SelectionKey keyFor(Selector sel) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public SelectorProvider provider() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public SelectionKey register(Selector sel, int ops, Object att)
		throws ClosedChannelException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public int validOps() {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		protected void implCloseChannel() throws IOException {
			// TODO Auto-generated method stub

		}
		
	}

}
