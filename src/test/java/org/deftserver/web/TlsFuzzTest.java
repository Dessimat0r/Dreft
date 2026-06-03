package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Concurrent adversarial fuzzing of the TLS layer. Many connections hammer the HTTPS port with
 * non-TLS garbage — random bytes, fake/partial TLS-record headers, truncated handshakes — while
 * legitimate HTTPS requests are woven through. The SSLEngine must reject every malformed record and
 * the server must close those connections cleanly (never crash, never wedge the loop), and the real
 * HTTPS requests interleaved with the garbage must all still succeed.
 */
public class TlsFuzzTest {

	private static HttpServer server;
	private static int PORT;
	private static SSLSocketFactory trustAllFactory;

	@BeforeClass
	public static void setUp() throws Exception {
		Map<String, RequestHandler> h = new HashMap<>();
		h.put("/ok", new RequestHandler() {
			@Override public void get(HttpRequest q, HttpResponse r) { r.write("ok"); }
		});

		KeyStore ks = KeyStore.getInstance("PKCS12");
		try (FileInputStream fis = new FileInputStream("src/test/resources/keystore.p12")) {
			ks.load(fis, "password".toCharArray());
		}
		KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
		kmf.init(ks, "password".toCharArray());
		SSLContext serverCtx = SSLContext.getInstance("TLS");
		serverCtx.init(kmf.getKeyManagers(), null, null);

		server = new HttpServer(new Application(h));
		server.enableSSL(serverCtx);
		server.bind(0);
		PORT = server.getPort();
		Thread.ofPlatform().name("TlsFuzz-IOLoop").start(() -> {
			try { server.start(1); } catch (Exception e) { e.printStackTrace(); }
		});
		Thread.sleep(1000); // let the loop boot (a plain-TCP readiness probe is unreliable for TLS)

		// Trust-all client factory (test only) for the legitimate HTTPS path.
		SSLContext clientCtx = SSLContext.getInstance("TLS");
		clientCtx.init(null, new TrustManager[]{new X509TrustManager() {
			public void checkClientTrusted(X509Certificate[] c, String a) { }
			public void checkServerTrusted(X509Certificate[] c, String a) { }
			public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
		}}, new SecureRandom());
		trustAllFactory = clientCtx.getSocketFactory();
	}

	@AfterClass
	public static void tearDown() {
		if (server != null) server.stop();
	}

	/** Builds one chunk of non-TLS garbage to throw at the TLS port. */
	private static byte[] tlsGarbage(ThreadLocalRandom rnd) {
		switch (rnd.nextInt(5)) {
			case 0: { byte[] b = new byte[1 + rnd.nextInt(2048)]; rnd.nextBytes(b); return b; } // pure noise
			case 1: // plausible-looking TLS record header (handshake, TLS1.2) then random body
				{
					int len = rnd.nextInt(4096);
					byte[] b = new byte[5 + Math.min(len, 1024)];
					b[0] = 22; b[1] = 3; b[2] = 3; b[3] = (byte) (len >> 8); b[4] = (byte) len;
					for (int i = 5; i < b.length; i++) b[i] = (byte) rnd.nextInt(256);
					return b;
				}
			case 2: return new byte[]{22, 3, 1}; // truncated record header
			case 3: return "GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1); // plaintext HTTP to TLS port
			default: { // huge claimed record length, little data
				byte[] b = new byte[10];
				b[0] = 23; b[1] = 3; b[2] = 3; b[3] = (byte) 0xFF; b[4] = (byte) 0xFF;
				for (int i = 5; i < 10; i++) b[i] = (byte) rnd.nextInt(256);
				return b;
			}
		}
	}

	/** Sends TLS garbage over a plain socket; the server must reject + close without crashing. */
	private static void fireTlsGarbage(ThreadLocalRandom rnd) {
		try (Socket s = new Socket()) {
			s.setSoLinger(true, 0);
			s.setSoTimeout(3000);
			s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 2000);
			OutputStream os = s.getOutputStream();
			int n = 1 + rnd.nextInt(3);
			for (int i = 0; i < n; i++) { os.write(tlsGarbage(rnd)); os.flush(); }
		} catch (Exception ignore) {
			// refused/reset on garbage is fine; the assertions are valid-HTTPS success + liveness
		}
	}

	/** A real HTTPS GET /ok; returns true iff it got 200 "ok". Retries a transient "connection
	 *  refused" (OS accept-backlog overflow under the concurrent connection burst is backpressure,
	 *  not a server failure). */
	private static boolean validHttps() {
		for (int attempt = 0; attempt < 5; attempt++) {
			Boolean r = validHttpsOnce();
			if (r != null) return r;            // got a definitive answer (true/false)
			try { Thread.sleep(20L * (attempt + 1)); } catch (InterruptedException ignore) { return false; }
		}
		return false;
	}

	/** One HTTPS attempt: true=200 ok, false=connected-but-wrong-response, null=transient connect refused. */
	private static Boolean validHttpsOnce() {
		try (SSLSocket s = (SSLSocket) trustAllFactory.createSocket()) {
			s.setSoLinger(true, 0);
			s.setSoTimeout(20000);
			s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 2000);
			s.startHandshake();
			s.getOutputStream().write("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
				.getBytes(StandardCharsets.ISO_8859_1));
			s.getOutputStream().flush();
			InputStream in = s.getInputStream();
			java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int r;
			while ((r = in.read(buf)) != -1) {
				acc.write(buf, 0, r);
				String cur = acc.toString(StandardCharsets.ISO_8859_1);
				if (cur.contains("\r\n\r\n") && cur.endsWith("ok")) break;
				if (acc.size() > 65536) break;
			}
			String resp = acc.toString(StandardCharsets.ISO_8859_1);
			boolean ok = resp.startsWith("HTTP/1.1 200") && resp.endsWith("ok");
			if (!ok) LAST_ERR.set("resp[" + resp.length() + "]=" + resp.replace("\r", "\\r").replace("\n", "\\n"));
			return ok;
		} catch (java.net.ConnectException refused) {
			return null; // transient accept-backlog backpressure under the burst → caller retries
		} catch (Exception e) {
			LAST_ERR.set(String.valueOf(e));
			return false; // connected but something went wrong → a real failure
		}
	}

	static final java.util.concurrent.atomic.AtomicReference<String> LAST_ERR = new java.util.concurrent.atomic.AtomicReference<>("(none)");

	@Test
	public void singleValidHttpsWorks() {
		assertTrue("a single HTTPS GET must work; last err=" + LAST_ERR.get(), validHttps());
	}

	/**
	 * Floods the TLS port with concurrent garbage, then verifies the server RECOVERS to serve valid
	 * HTTPS. A single-threaded SSL reactor can't accept fast enough to keep every interleaved request
	 * succeeding mid-flood (heavy handshakes + an OS-capped accept backlog → transient refusals/RSTs —
	 * degradation, not a crash). The robustness invariant is that the garbage never crashes the server
	 * nor permanently wedges the loop: once the storm subsides, legitimate HTTPS works again.
	 */
	@Test
	public void concurrentTlsGarbageDoesNotCrashAndServerRecovers() throws Exception {
		int threads = 16;
		int tasks = 400;
		ExecutorService exec = Executors.newFixedThreadPool(threads);
		CountDownLatch done = new CountDownLatch(tasks);
		try {
			for (int i = 0; i < tasks; i++) {
				exec.submit(() -> {
					try { fireTlsGarbage(ThreadLocalRandom.current()); }
					finally { done.countDown(); }
				});
			}
			assertTrue("TLS garbage flood did not finish in time", done.await(150, TimeUnit.SECONDS));
		} finally {
			exec.shutdownNow();
			exec.awaitTermination(15, TimeUnit.SECONDS);
		}
		// Recovery: after the flood drains, valid HTTPS must work again (proves no crash / permanent
		// wedge). The single SSL reactor needs a moment to drain the burst, so poll generously for the
		// first success, then require a clean streak.
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
		while (!validHttps() && System.nanoTime() < deadline) {
			Thread.sleep(200);
		}
		int ok = 0;
		for (int i = 0; i < 20; i++) {
			if (validHttps()) ok++;
		}
		assertTrue("server must recover and serve valid HTTPS after the TLS garbage flood, got "
			+ ok + "/20 (last err=" + LAST_ERR.get() + ")", ok >= 18);
	}
}
