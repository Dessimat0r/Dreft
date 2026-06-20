package org.deftserver.web.http;

/**
 * This class provides a possiblity to change the tunables used by Deft for the http server configuration.
 * Do not change the values unless you know what you are doing.
 */
public class HttpServerDescriptor {

	/** How long (ms) the server keeps an idle keep-alive connection open. Default 30,000 (30s). */
	public static volatile int KEEP_ALIVE_TIMEOUT = 30 * 1000;
	
	/** Size (bytes) of the TCP receive buffer. Default 2048 (2 KiB). */
	public static volatile int READ_BUFFER_SIZE = 2048;
	
	/** Size (bytes) of the write (send) buffer. Default 2048 (2 KiB). */
	public static volatile int WRITE_BUFFER_SIZE = 2048;

	/** Absolute cap (ms) on how long a single blocking write may hold the I/O-loop thread. Default 30,000 (30s). */
	public static volatile long RESPONSE_WRITE_TIMEOUT_MS = 30_000;

	/** Length of the TCP accept backlog. Default 1024 (vs JDK default 50). */
	public static volatile int ACCEPT_BACKLOG = 1024;

	/** Max connections drained from the OS accept queue per OP_ACCEPT readiness event. The selector is
	 *  level-triggered, so accepting only one per event drains the backlog one connection per event-loop
	 *  iteration — under a burst that needlessly delays accepts and can let the backlog fill (forcing
	 *  clients into multi-second SYN-retransmit backoff). Draining a bounded batch empties it far faster;
	 *  the cap still yields the loop to read/write/timeout work and bounds cross-reactor imbalance in
	 *  multi-loop mode. Default 64. */
	public static volatile int MAX_ACCEPTS_PER_EVENT = 64;

}
