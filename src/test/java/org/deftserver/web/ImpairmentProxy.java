package org.deftserver.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * A tiny, self-contained TCP "impairment proxy" for network-condition robustness testing — the
 * pragmatic alternative to a full network simulator (ns-3) when the goal is simply to verify the
 * server stays robust and correct under adverse client behaviour rather than to model real network
 * topology/congestion.
 * <p>
 * It listens on an ephemeral port and forwards every connection to {@code targetPort}, applying a
 * configurable {@link Impairment} independently to each direction:
 * <ul>
 *   <li><b>chunkSize</b> — split each forwarded block into pieces of at most this many bytes
 *       (byte-level <i>dribbling</i> — exercises the server's incremental header/body parser and,
 *       on the response side, makes the client a slow reader so the server's send buffer fills and
 *       it must defer to OP_WRITE);</li>
 *   <li><b>perChunkDelayMs</b> — sleep before forwarding each piece (latency / bandwidth throttle);</li>
 *   <li><b>bytesBeforeDrop</b> — after forwarding this many bytes in the direction, abruptly close
 *       the connection (mid-stream drop / truncation — exercises the server's cleanup paths).</li>
 * </ul>
 * Plain blocking I/O on daemon threads: this is a test utility, so clarity beats throughput.
 */
public class ImpairmentProxy implements AutoCloseable {

	/** Per-direction impairment knobs. Defaults are "pass-through, no impairment". */
	public static final class Impairment {
		/** Max bytes forwarded per write (smaller = more fragmentation). */
		public volatile int chunkSize = Integer.MAX_VALUE;
		/** Fixed delay (ms) applied before each forwarded chunk (latency / bandwidth throttle). */
		public volatile long perChunkDelayMs = 0;
		/** Upper bound (ms) of an additional <i>random</i> per-chunk delay (network jitter); the actual
		 *  delay added is uniform in {@code [0, jitterMaxMs]}. 0 disables jitter. */
		public volatile long jitterMaxMs = 0;
		/** Close the connection once this many bytes have been forwarded in this direction. */
		public volatile long bytesBeforeDrop = Long.MAX_VALUE;

		/** Total delay (fixed + random jitter) to apply before the next chunk, in ms. */
		long nextDelayMs() {
			long d = perChunkDelayMs;
			if (jitterMaxMs > 0) {
				d += java.util.concurrent.ThreadLocalRandom.current().nextLong(jitterMaxMs + 1);
			}
			return d;
		}
	}

	private final int targetPort;
	private final ServerSocket listen;
	private final Thread acceptThread;
	private volatile boolean running = true;

	/** Impairment applied to the client→server direction (the request the server must parse). */
	public final Impairment toServer = new Impairment();
	/** Impairment applied to the server→client direction (the response the client reads slowly). */
	public final Impairment toClient = new Impairment();

	/** Opens the proxy on an ephemeral port forwarding to {@code targetPort} and starts accepting. */
	public ImpairmentProxy(int targetPort) throws IOException {
		this.targetPort = targetPort;
		this.listen = new ServerSocket();
		this.listen.setReuseAddress(true);
		// Bind with a real accept backlog. The JDK default (50) overflows when a chaos storm opens dozens
		// of connections to the proxy at once: overflowed SYNs are dropped, forcing the *client* into
		// multi-second TCP retransmit backoff — which shows up as occasional clean-request failures. Size
		// it like the server's own ACCEPT_BACKLOG so the proxy never becomes the bottleneck.
		this.listen.bind(new InetSocketAddress("127.0.0.1", 0), 1024);
		this.acceptThread = new Thread(this::acceptLoop, "impairment-proxy-accept");
		this.acceptThread.setDaemon(true);
		this.acceptThread.start();
	}

	/** The local port clients should connect to (forwards to the target). */
	public int getPort() {
		return listen.getLocalPort();
	}

	private void acceptLoop() {
		while (running) {
			final Socket client;
			try {
				client = listen.accept();
			} catch (IOException e) {
				return; // listener closed → stop
			}
			// One upstream connection per client connection; two pump threads bridge the two
			// directions. If either side ends, both sockets are closed.
			Thread t = new Thread(() -> handle(client), "impairment-proxy-conn");
			t.setDaemon(true);
			t.start();
		}
	}

	private void handle(Socket client) {
		Socket upstream = null;
		try {
			upstream = new Socket();
			upstream.connect(new InetSocketAddress("127.0.0.1", targetPort), 2000);
			final Socket up = upstream;
			Thread c2s = new Thread(() -> pump(client, up, toServer), "impairment-c2s");
			c2s.setDaemon(true);
			c2s.start();
			// Run server→client on this thread.
			pump(upstream, client, toClient);
			try { c2s.join(2000); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
		} catch (IOException e) {
			// connection setup failed — nothing to do but tear down below
		} finally {
			closeQuietly(client);
			closeQuietly(upstream);
		}
	}

	/** Copies {@code in}→{@code out}, splitting into {@code imp.chunkSize} pieces, delaying each by
	 *  {@code imp.perChunkDelayMs}, and tearing the connection down after {@code imp.bytesBeforeDrop}. */
	private void pump(Socket inSock, Socket outSock, Impairment imp) {
		try {
			InputStream in = inSock.getInputStream();
			OutputStream out = outSock.getOutputStream();
			byte[] buf = new byte[8192];
			long forwarded = 0;
			int n;
			while ((n = in.read(buf)) != -1) {
				int off = 0;
				while (off < n) {
					int len = Math.min(imp.chunkSize, n - off);
					long remainingBudget = imp.bytesBeforeDrop - forwarded;
					if (remainingBudget <= 0) {
						// Drop budget exhausted: abruptly close to simulate a mid-stream truncation.
						closeQuietly(inSock);
						closeQuietly(outSock);
						return;
					}
					if (len > remainingBudget) {
						len = (int) remainingBudget;
					}
					long delay = imp.nextDelayMs();
					if (delay > 0) {
						Thread.sleep(delay);
					}
					out.write(buf, off, len);
					out.flush();
					off += len;
					forwarded += len;
				}
			}
			// Normal EOF: half-close the far side so the peer sees the stream end.
			closeQuietly(outSock);
		} catch (IOException e) {
			closeQuietly(inSock);
			closeQuietly(outSock);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			closeQuietly(inSock);
			closeQuietly(outSock);
		}
	}

	private static void closeQuietly(Socket s) {
		if (s != null) {
			try { s.close(); } catch (IOException ignore) { /* nop */ }
		}
	}

	@Override
	public void close() {
		running = false;
		try { listen.close(); } catch (IOException ignore) { /* nop */ }
	}
}
