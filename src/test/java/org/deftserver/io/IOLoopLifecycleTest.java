package org.deftserver.io;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Regression tests for the {@link IOLoop} start/stop lifecycle (progress.md V2-4).
 *
 * The historical defect: {@code stop()} only flipped a {@code running} flag, so a {@code start()}
 * issued promptly afterwards could resume while the previous loop thread was still mid-{@code
 * select()} — putting two threads on one {@link java.nio.channels.Selector} concurrently (data
 * races / misframed reads / stalls). The fix gives the loop a thread-ownership token and makes
 * {@code stop()} wake + join the loop thread when called off-loop. These tests exercise rapid
 * stop/restart cycles and assert the loop thread is genuinely gone after {@code stop()} returns.
 */
public class IOLoopLifecycleTest {

	/** After {@code stop()} (called off-loop) returns, the loop thread must have terminated, and a
	 *  fresh {@code start()} on a new thread must work — repeated many times without a lingering thread. */
	@Test
	public void stopJoinsLoopThreadAndRestartIsClean() throws Exception {
		IOLoop loop = new IOLoop();
		try {
			for (int cycle = 0; cycle < 25; cycle++) {
				Thread t = new Thread(loop::start, "test-ioloop-" + cycle);
				t.start();

				// Prove the loop is live this cycle: a scheduled callback must execute.
				final CountDownLatch ran = new CountDownLatch(1);
				loop.addCallback(ran::countDown);
				assertTrue("callback should run on cycle " + cycle, ran.await(2, TimeUnit.SECONDS));

				// stop() is called from this (non-loop) thread, so it must wake + join the loop thread.
				loop.stop();
				t.join(2000);
				assertFalse("loop thread must be dead after stop() on cycle " + cycle, t.isAlive());
			}
		} finally {
			loop.dispose();
		}
	}

	/** A superseded loop thread must not keep running: if a second {@code start()} claims ownership,
	 *  the first thread exits. We approximate this by ensuring that across many quick restarts only
	 *  one callback fires per scheduled callback (no double-execution from two concurrent loops). */
	@Test
	public void noDoubleExecutionAcrossRapidRestarts() throws Exception {
		IOLoop loop = new IOLoop();
		final AtomicInteger executions = new AtomicInteger(0);
		try {
			for (int cycle = 0; cycle < 15; cycle++) {
				Thread t = new Thread(loop::start, "test-ioloop-rapid-" + cycle);
				t.start();
				final CountDownLatch ran = new CountDownLatch(1);
				// Each scheduled callback increments exactly once; two concurrent loop threads would
				// risk processing the callback queue twice.
				loop.addCallback(() -> { executions.incrementAndGet(); ran.countDown(); });
				assertTrue("callback should run on cycle " + cycle, ran.await(2, TimeUnit.SECONDS));
				loop.stop();
				t.join(2000);
				assertFalse("no lingering loop thread on cycle " + cycle, t.isAlive());
			}
			// Exactly one execution per cycle — never more (which would indicate overlapping loops).
			assertTrue("expected 15 callback executions, got " + executions.get(), executions.get() == 15);
		} finally {
			loop.dispose();
		}
	}

	/**
	 * Regression (progress.md V3-20): an exception escaping a handler's {@code handleAccept} must NOT
	 * close the listening {@link java.nio.channels.ServerSocketChannel} — otherwise one hostile
	 * connection would stop the whole server from accepting (a remote DoS). The loop must keep the
	 * listener open and continue accepting.
	 */
	@Test
	public void acceptHandlerExceptionDoesNotCloseListeningSocket() throws Exception {
		IOLoop loop = new IOLoop();
		Thread t = new Thread(loop::start, "test-accept-ioloop");
		t.setDaemon(true);
		t.start();

		java.nio.channels.ServerSocketChannel server = java.nio.channels.ServerSocketChannel.open();
		server.configureBlocking(false);
		server.socket().bind(new java.net.InetSocketAddress("127.0.0.1", 0));
		final int port = server.socket().getLocalPort();

		final AtomicInteger acceptCalls = new AtomicInteger();
		org.deftserver.io.IOHandler throwingHandler = new org.deftserver.io.IOHandler() {
			@Override public void handleAccept(java.nio.channels.SelectionKey key) throws java.io.IOException {
				acceptCalls.incrementAndGet();
				((java.nio.channels.ServerSocketChannel) key.channel()).accept(); // drain the backlog
				throw new RuntimeException("simulated per-connection setup failure escaping handleAccept");
			}
			@Override public void handleConnect(java.nio.channels.SelectionKey key) { }
			@Override public void handleRead(java.nio.channels.SelectionKey key) { }
			@Override public void handleWrite(java.nio.channels.SelectionKey key) { }
		};

		try {
			// Register the listener on the loop thread (addHandler is not thread-safe off-loop).
			final CountDownLatch registered = new CountDownLatch(1);
			loop.addCallback(() -> {
				try { loop.addHandler(server, throwingHandler, java.nio.channels.SelectionKey.OP_ACCEPT, null); }
				catch (java.io.IOException e) { throw new RuntimeException(e); }
				registered.countDown();
			});
			assertTrue("listener registered", registered.await(2, TimeUnit.SECONDS));

			// First connection → handleAccept throws. The listener must survive.
			try (java.net.Socket s1 = new java.net.Socket("127.0.0.1", port)) { /* trigger accept */ }
			Thread.sleep(200);
			assertTrue("listening socket must stay open after handleAccept threw", server.isOpen());

			// Second connection must still trigger an accept — proving the server keeps accepting.
			try (java.net.Socket s2 = new java.net.Socket("127.0.0.1", port)) { /* trigger accept */ }
			Thread.sleep(200);
			assertTrue("server must keep accepting after a handleAccept exception (calls=" + acceptCalls.get() + ")",
				acceptCalls.get() >= 2);
			assertTrue("listening socket must still be open", server.isOpen());
		} finally {
			loop.stop();
			t.join(2000);
			try { server.close(); } catch (java.io.IOException ignore) { }
			loop.dispose();
		}
	}

	@Test
	public void submitCallableAndRunnableOnIOLoop() throws Exception {
		IOLoop loop = new IOLoop();
		Thread t = new Thread(loop::start, "test-submit-ioloop");
		t.start();
		try {
			final String currentThreadName = Thread.currentThread().getName();
			java.util.concurrent.CompletableFuture<String> callableFuture = loop.submit(() -> {
				assertFalse(Thread.currentThread().getName().equals(currentThreadName));
				assertTrue(Thread.currentThread().getName().startsWith("I/O-LOOP"));
				return "hello from loop thread";
			});
			org.junit.Assert.assertEquals("hello from loop thread", callableFuture.get(2, TimeUnit.SECONDS));

			final AtomicInteger runVal = new AtomicInteger();
			java.util.concurrent.CompletableFuture<Void> runnableFuture = loop.submit(() -> {
				assertFalse(Thread.currentThread().getName().equals(currentThreadName));
				assertTrue(Thread.currentThread().getName().startsWith("I/O-LOOP"));
				runVal.set(42);
			});
			runnableFuture.get(2, TimeUnit.SECONDS);
			org.junit.Assert.assertEquals(42, runVal.get());
		} finally {
			loop.stop();
			t.join(2000);
			loop.dispose();
		}
	}
}
