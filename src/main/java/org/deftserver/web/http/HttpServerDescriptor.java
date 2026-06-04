package org.deftserver.web.http;

/**
 * This class provides a possiblity to change the tunables used by Deft for the http server configuration.
 * Do not change the values unless you know what you are doing.
 */
public class HttpServerDescriptor {

	/** The number of seconds Deft will wait for subsequent socket activity before closing the connection */
	public static volatile int KEEP_ALIVE_TIMEOUT = 30 * 1000;	// 30s
	
	/**
	 * Size of the read (receive) buffer.
	 * "Ideally, an HTTP request should not go beyond 1 packet. 
	 * The most widely used networks limit packets to approximately 1500 bytes, so if you can constrain each request 
	 * to fewer than 1500 bytes, you can reduce the overhead of the request stream." (from: http://bit.ly/bkksUu)
	 */
	public static volatile int READ_BUFFER_SIZE = 2048;	// 2048 bytes
	
	/**
	 * Size of the write (send) buffer.
	 */
	public static volatile int WRITE_BUFFER_SIZE = 2048;	// 2048 bytes

	/**
	 * Absolute cap (ms) on how long a single blocking write may hold the I/O-loop thread (the TLS
	 * application-write path writes synchronously). The existing zero-progress stall timer aborts a
	 * fully-stalled peer, but a "drip" reader that reads a byte just often enough keeps resetting it,
	 * holding the loop indefinitely — a slow-read DoS that blocks all other connections. This absolute
	 * deadline bounds that (a §36 "response write timeout"): a peer that hasn't let the server finish
	 * writing within this window is dropped. Generous by default so genuinely slow-but-legitimate
	 * readers complete; lower it for stricter anti-DoS (at the cost of dropping very slow readers).
	 * NOTE: the real elimination is non-blocking TLS writes (OP_WRITE deferral, as plaintext already
	 * does) — see progress.md V3-24; this is the low-risk bound until then.
	 */
	public static volatile long RESPONSE_WRITE_TIMEOUT_MS = 30_000; // 30s

	/**
	 * The requested listen() backlog for the accept queue — how many fully-established connections the
	 * OS may queue awaiting accept() before it refuses (RST) new ones. The JDK default (50) is small:
	 * under a connection burst (especially TLS, whose handshakes make the single accept loop slower to
	 * drain the queue) it overflows and legitimate clients get "connection refused". A larger backlog
	 * lets the server absorb bursts. The OS silently caps this at SOMAXCONN.
	 */
	public static volatile int ACCEPT_BACKLOG = 1024;

}
