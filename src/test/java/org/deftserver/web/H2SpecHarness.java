package org.deftserver.web;

import java.util.HashMap;
import java.util.Map;

import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;

/**
 * Manual harness (NOT a Surefire test — the class name doesn't end in {@code Test}) that launches a
 * cleartext Dreft server exposing a single {@code /} handler, for running the external
 * <a href="https://github.com/summerwind/h2spec">h2spec</a> HTTP/2 conformance suite against it.
 *
 * <p>Usage: {@code java -cp <cp> org.deftserver.web.H2SpecHarness [port] [tls]} (default port 18080),
 * then {@code h2spec -p <port> -h 127.0.0.1} (add {@code -t -k} for the {@code tls} mode, which serves
 * HTTP/2 over TLS via ALPN using the test keystore). The {@code run-h2spec.sh} script at the repo root
 * wires the cleartext path together (build → launch → run h2spec → tear down). See {@code
 * HTTP2_COMPLIANCE.md} for the expected result and the one documented exception.
 */
public final class H2SpecHarness {

	private H2SpecHarness() {}

	/** Handler that answers every method with a tiny 200 — enough for h2spec's request exchanges. */
	private static final class OkHandler extends RequestHandler {
		@Override public void get(HttpRequest req, HttpResponse resp)    { resp.write("ok"); }
		@Override public void post(HttpRequest req, HttpResponse resp)   { resp.write("ok"); }
		@Override public void put(HttpRequest req, HttpResponse resp)    { resp.write("ok"); }
		@Override public void delete(HttpRequest req, HttpResponse resp) { resp.write("ok"); }
	}

	/** Returns a fixed-size body (for throughput benchmarking with real payloads). */
	private static final class FixedSizeHandler extends RequestHandler {
		private final String body;
		FixedSizeHandler(int size) { this.body = "x".repeat(size); }
		@Override public void get(HttpRequest req, HttpResponse resp) { resp.write(body); }
	}

	/** Echoes the request body back (for benchmarking inbound DATA / request-body handling). */
	private static final class EchoHandler extends RequestHandler {
		@Override public void post(HttpRequest req, HttpResponse resp) { resp.write(req.getBody()); }
		@Override public void put(HttpRequest req, HttpResponse resp)  { resp.write(req.getBody()); }
	}

	public static void main(String[] args) throws Exception {
		int port = args.length > 0 ? Integer.parseInt(args[0]) : 18080;
		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/", new OkHandler());
		// Sized payloads for throughput benchmarks (exercise DATA chunking + flow control + the write path).
		handlers.put("/1k", new FixedSizeHandler(1024));
		handlers.put("/16k", new FixedSizeHandler(16 * 1024));
		handlers.put("/64k", new FixedSizeHandler(64 * 1024));
		handlers.put("/256k", new FixedSizeHandler(256 * 1024));
		handlers.put("/1m", new FixedSizeHandler(1024 * 1024));
		handlers.put("/echo", new EchoHandler());
		HttpServer server = new HttpServer(new Application(handlers));
		boolean tls = args.length > 1 && args[1].equalsIgnoreCase("tls");
		if (tls) {
			// HTTP/2 over TLS via ALPN, using the test keystore.
			java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
			try (java.io.FileInputStream fis = new java.io.FileInputStream("src/test/resources/keystore.p12")) {
				ks.load(fis, "password".toCharArray());
			}
			javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance("SunX509");
			kmf.init(ks, "password".toCharArray());
			javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
			sslContext.init(kmf.getKeyManagers(), null, null);
			server.enableSSL(sslContext);
		} else {
			// Enable the h2c Upgrade so load testers that default to it (e.g. h2load) can connect over
			// cleartext. h2spec uses prior-knowledge (sends the preface directly), so it is unaffected.
			server.setHttp2CleartextUpgradeEnabled(true);
		}
		// Reactor (I/O-loop) count is controllable for multi-reactor benchmarking via -Dh2.reactors=N
		// (default 1). h2spec uses a single connection so it is unaffected.
		int reactors = Integer.getInteger("h2.reactors", 1);
		server.bind(port);
		server.start(reactors);
		System.out.println("H2SpecHarness listening on " + port + (tls ? " (TLS/ALPN)" : " (cleartext)")
			+ ", reactors=" + reactors + " (Ctrl-C to stop)");
		Thread.currentThread().join();
	}
}
