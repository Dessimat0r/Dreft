package org.deftserver.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Shared test helpers for the socket-based integration tests.
 * <p>
 * The integration test classes start a real server on a background thread and then connect to it.
 * Historically each {@code @BeforeClass} used a fixed {@code Thread.sleep(200)} to "wait" for the
 * server to come up — which is a flakiness source: under load the background thread may not have
 * reached {@code server.listen()} (and therefore not bound the port) within 200&nbsp;ms, so the
 * first test's connection is refused. {@link #awaitListening(int)} polls for actual connectability
 * instead, so startup waits exactly as long as needed and no longer.
 */
public final class TestServerSupport {

	private TestServerSupport() { }

	/** How long to wait for the server socket to start accepting before giving up. */
	private static final long DEFAULT_TIMEOUT_MS = 5_000;

	/**
	 * Blocks until a TCP connection to {@code localhost:port} succeeds (the server is bound and
	 * accepting), or throws once {@link #DEFAULT_TIMEOUT_MS} elapses. Replaces fixed startup sleeps
	 * with a deterministic readiness check.
	 */
	public static void awaitListening(int port) {
		awaitListening(port, DEFAULT_TIMEOUT_MS);
	}

	/** As {@link #awaitListening(int)} with an explicit timeout (milliseconds). */
	public static void awaitListening(int port, long timeoutMs) {
		long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
		while (System.nanoTime() < deadline) {
			// A successful connect proves the listening socket is bound and the backlog is accepting.
			try (Socket probe = new Socket()) {
				probe.connect(new InetSocketAddress("127.0.0.1", port), 200);
				return;
			} catch (IOException notYet) {
				// Server not up yet — back off briefly and retry until the deadline.
				try {
					Thread.sleep(10);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("Interrupted while waiting for server on port " + port, interrupted);
				}
			}
		}
		throw new IllegalStateException("Server did not start accepting on port " + port + " within " + timeoutMs + " ms");
	}
}
