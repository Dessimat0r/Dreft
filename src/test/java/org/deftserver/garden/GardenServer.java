package org.deftserver.garden;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;

import org.deftserver.io.IOLoop;
import org.deftserver.web.Application;
import org.deftserver.web.HttpServer;
import org.deftserver.web.handler.NotFoundRequestHandler;
import org.deftserver.web.handler.RequestHandler;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Origin-server adapter for the <a href="https://github.com/narfindustries/http-garden">HTTP Garden</a>
 * differential fuzzer. The Garden discovers HTTP/1.1 parsing discrepancies by sending the same request
 * stream to many servers and comparing how each one <em>parsed</em> it. Each origin server must therefore
 * echo the request <em>as it parsed it</em> in the Garden's standardized JSON shape (every field base64
 * over ISO-8859-1 bytes):
 *
 * <pre>{"version":"&lt;b64&gt;","method":"&lt;b64&gt;","uri":"&lt;b64&gt;","headers":[["&lt;b64name&gt;","&lt;b64val&gt;"],...],"body":"&lt;b64&gt;"}</pre>
 *
 * with a 200 on a request Dreft accepted, and Dreft's own 4xx (400 for a malformed/invalid request) left
 * intact as the differential "rejected" signal. Because Dreft routes by path and has no true catch-all,
 * this wraps {@link Application} so every request Dreft <em>accepts</em> (and would otherwise 404, since no
 * routes are registered) is dispatched to the echo handler, while Dreft's rejections (400/403/…) pass
 * through unchanged.
 *
 * <p>Run by the Garden's Docker image on port 80; the port is overridable via {@code GARDEN_PORT} (or the
 * first CLI arg) so tests can bind an ephemeral port. Not part of the framework — a compliance harness.
 */
public final class GardenServer {

	private static final Logger logger = LoggerFactory.getLogger(GardenServer.class);

	private GardenServer() {}

	/** Builds the catch-all echo {@link Application}: every request Dreft accepts is echoed; Dreft's own
	 *  4xx rejections are preserved. Reused by {@link #main} and by the harness test. */
	public static Application newGardenApplication() {
		final RequestHandler echo = new EchoHandler();
		return new Application(Collections.<String, RequestHandler>emptyMap()) {
			@Override
			public RequestHandler getHandler(HttpRequest request) {
				RequestHandler rh = super.getHandler(request);
				// No routes are registered, so an accepted request resolves to NOT_FOUND — redirect those
				// to the echo handler. Anything else (BAD_REQUEST for malformed/invalid, FORBIDDEN, the
				// server-wide OPTIONS handler, …) is Dreft's genuine verdict and must pass through so the
				// Garden sees how Dreft handled the request.
				if (rh == NotFoundRequestHandler.getInstance()) {
					return echo;
				}
				return rh;
			}
		};
	}

	/** Echoes the parsed request in the Garden's JSON format, for every HTTP method Dreft dispatches. */
	static final class EchoHandler extends RequestHandler {
		@Override public void get(HttpRequest req, HttpResponse resp)     throws IOException { echo(req, resp); }
		@Override public void post(HttpRequest req, HttpResponse resp)    throws IOException { echo(req, resp); }
		@Override public void put(HttpRequest req, HttpResponse resp)     throws IOException { echo(req, resp); }
		@Override public void patch(HttpRequest req, HttpResponse resp)   throws IOException { echo(req, resp); }
		@Override public void delete(HttpRequest req, HttpResponse resp)  throws IOException { echo(req, resp); }
		@Override public void head(HttpRequest req, HttpResponse resp)    throws IOException { echo(req, resp); }
		@Override public void options(HttpRequest req, HttpResponse resp) throws IOException { echo(req, resp); }

		private static void echo(HttpRequest req, HttpResponse resp) {
			// Method and request-target come from the RAW request line (Dreft's getMethod() is an enum that
			// loses the original token, and getRequestedPath() is percent-decoded/normalised — the Garden
			// wants the target as received). Headers reflect Dreft's PARSED view (the point of the echo).
			String requestLine = req.getRequestLine() != null ? req.getRequestLine() : "";
			String method = "";
			String target = "";
			int sp1 = requestLine.indexOf(' ');
			if (sp1 >= 0) {
				method = requestLine.substring(0, sp1);
				int sp2 = requestLine.indexOf(' ', sp1 + 1);
				target = (sp2 >= 0) ? requestLine.substring(sp1 + 1, sp2) : requestLine.substring(sp1 + 1);
			}
			String version = req.getVersion() != null ? req.getVersion() : "";

			StringBuilder sb = new StringBuilder(256);
			sb.append("{\"version\":\"").append(b64(version)).append("\",");
			sb.append("\"method\":\"").append(b64(method)).append("\",");
			sb.append("\"uri\":\"").append(b64(target)).append("\",");
			sb.append("\"headers\":[");
			boolean first = true;
			for (Map.Entry<String, String> h : req.getHeaders().entrySet()) {
				if (!first) {
					sb.append(',');
				}
				sb.append("[\"").append(b64(h.getKey())).append("\",\"").append(b64(h.getValue())).append("\"]");
				first = false;
			}
			sb.append("],");
			String body = req.getBody();
			byte[] bodyBytes = (body == null) ? new byte[0] : body.getBytes(StandardCharsets.ISO_8859_1);
			sb.append("\"body\":\"").append(b64(bodyBytes)).append("\"}");

			resp.setHeader("Content-Type", "application/json");
			resp.write(sb.toString());
		}

		private static String b64(String s) {
			return b64(s.getBytes(StandardCharsets.ISO_8859_1));
		}

		private static String b64(byte[] bytes) {
			return Base64.getEncoder().encodeToString(bytes);
		}
	}

	public static void main(String[] args) {
		int port = 80;
		String env = System.getenv("GARDEN_PORT");
		if (args.length > 0) {
			env = args[0];
		}
		if (env != null && !env.isBlank()) {
			try {
				port = Integer.parseInt(env.trim());
			} catch (NumberFormatException e) {
				logger.warn("Invalid port '{}', defaulting to {}", env, port);
			}
		}
		logger.info("Starting HTTP Garden echo server on port {}", port);
		HttpServer server = new HttpServer(newGardenApplication());
		try {
			server.listen(port);
			IOLoop.INSTANCE.start();
		} catch (IOException e) {
			logger.error("Failed to start Garden echo server", e);
		}
	}
}
