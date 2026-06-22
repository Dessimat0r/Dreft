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
 * <p>Usage: {@code java -cp <cp> org.deftserver.web.H2SpecHarness [port]} (default port 18080), then
 * {@code h2spec -p <port> -h 127.0.0.1}. The {@code run-h2spec.sh} script at the repo root wires this
 * all together (build → launch → run h2spec → tear down). See {@code HTTP2_COMPLIANCE.md} for the
 * expected result and the one documented exception.
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

	public static void main(String[] args) throws Exception {
		int port = args.length > 0 ? Integer.parseInt(args[0]) : 18080;
		Map<String, RequestHandler> handlers = new HashMap<>();
		handlers.put("/", new OkHandler());
		HttpServer server = new HttpServer(new Application(handlers));
		// Enable the h2c Upgrade so load testers that default to it (e.g. h2load) can connect over
		// cleartext. h2spec uses prior-knowledge (sends the preface directly), so it is unaffected.
		server.setHttp2CleartextUpgradeEnabled(true);
		server.bind(port);
		server.start(1);
		System.out.println("H2SpecHarness listening on " + port + " (Ctrl-C to stop)");
		Thread.currentThread().join();
	}
}
