package org.deftserver.web.http;

import java.io.IOException;

import org.deftserver.web.HttpVerb;
import org.deftserver.web.handler.RequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpRequestDispatcher {

	private static final Logger logger = LoggerFactory.getLogger(HttpRequestDispatcher.class);

	private record HandlerKey(Class<?> handlerClass, HttpVerb method) {}

	private static final java.util.concurrent.ConcurrentHashMap<HandlerKey, Boolean> heavyHandlers = 
		new java.util.concurrent.ConcurrentHashMap<>();

	private static final long HEAVY_THRESHOLD_NS = Long.getLong("deft.heavy_threshold_ns", 5_000_000L); // Default: 5ms

	public static boolean dispatch(RequestHandler rh, HttpRequest request, HttpResponse response) throws IOException {
		HttpVerb method = request.getMethod();
		logger.debug("method: {}", method);
		
		boolean isAsync = rh.isMethodAsynchronous(method);

		CorsConfig cors = rh.getCorsConfig();
		if (cors != null) {
			String origin = request.getHeader("Origin");
			if (cors.isOriginAllowed(origin)) {
				response.setHeader("Access-Control-Allow-Origin", origin);
				// The response is origin-specific; signal caches so they don't reuse one
				// origin's CORS response for another. (The gzip path merges Accept-Encoding in.)
				response.setHeader("Vary", "Origin");
				if (cors.isAllowCredentials()) {
					response.setHeader("Access-Control-Allow-Credentials", "true");
				}
				
				if (method == HttpVerb.OPTIONS) {
					java.util.List<String> allowedMethods = cors.getAllowedMethods();
					if (!allowedMethods.isEmpty()) {
						response.setHeader("Access-Control-Allow-Methods", String.join(", ", allowedMethods));
					}
					java.util.List<String> allowedHeaders = cors.getAllowedHeaders();
					if (!allowedHeaders.isEmpty()) {
						response.setHeader("Access-Control-Allow-Headers", String.join(", ", allowedHeaders));
					}
					if (cors.getMaxAge() != null) {
						response.setHeader("Access-Control-Max-Age", String.valueOf(cors.getMaxAge()));
					}
					response.setStatusCode(204);
					response.write("");
					if (isAsync) {
						response.finish();
					}
					return isAsync;
				}
			}
		}

		HandlerKey key = new HandlerKey(rh.getClass(), method);
		boolean isHeavy = heavyHandlers.getOrDefault(key, false);

		if (isHeavy && !isAsync) {
			logger.debug("Adaptive Dispatcher: executing slow method {} of {} on Virtual Thread", method, rh.getClass().getSimpleName());
			final org.deftserver.io.IOLoop ioLoop = response.getProtocol().getIOLoop();
			Thread.startVirtualThread(() -> {
				try {
					doDispatch(rh, method, request, response);
					// finish() performs NIO selector/channel registration, which must run on the
					// I/O-loop thread (registering from another thread races with select()).
					// addCallback is the documented thread-safe hand-off back to the loop.
					ioLoop.addCallback(() -> response.finish());
				} catch (Exception | StackOverflowError e) {
					logger.error("Unhandled exception in virtual-thread handler dispatch — sending 500", e);
					ioLoop.addCallback(() -> {
						try {
							response.setStatusCode(500);
							response.setHeader("Content-Type", "text/plain; charset=utf-8");
							response.setHeader("Connection", "close");
							response.write("Internal Server Error");
							response.finish();
						} catch (Exception suppressed) {
							logger.debug("Failed to send 500 response after virtual-thread handler crash", suppressed);
							try { response.getChannel().close(); } catch (Exception ignore) {}
						}
					});
				}
			});
			return true;
		} else {
			long start = System.nanoTime();
			try {
				doDispatch(rh, method, request, response);
			} catch (RuntimeException | StackOverflowError e) {
				// A handler crash must never escape into the I/O loop (which would leak the
				// channel and drop the response). Send a 500 and finish here so the channel is
				// flushed/closed cleanly, then report the request as handled.
				logger.error("Unhandled RuntimeException in synchronous handler dispatch — sending 500", e);
				try {
					response.setStatusCode(500);
					response.setHeader("Content-Type", "text/plain; charset=utf-8");
					response.setHeader("Connection", "close");
					response.write("Internal Server Error");
					response.finish();
				} catch (Exception suppressed) {
					logger.debug("Failed to send 500 response after handler crash", suppressed);
					try { response.getChannel().close(); } catch (Exception ignore) {}
				}
				return true; // already finished; caller must not finish again
			} finally {
				long duration = System.nanoTime() - start;
				if (!isAsync && duration > HEAVY_THRESHOLD_NS) {
					logger.info("Adaptive Dispatcher: method {} of {} took {} ms. Flagging as slow/heavy for future requests.",
						method, rh.getClass().getSimpleName(), duration / 1_000_000.0);
					heavyHandlers.put(key, true);
				}
			}
			return isAsync;
		}
	}

	private static void doDispatch(RequestHandler rh, HttpVerb method, HttpRequest request, HttpResponse response) throws IOException {
		try {
			switch (method) {
				case GET -> rh.get(request, response);
				case POST -> rh.post(request, response);
				case HEAD -> rh.head(request, response);
				case PUT -> rh.put(request, response);
				case PATCH -> rh.patch(request, response);
				case DELETE -> rh.delete(request, response);
				case OPTIONS -> rh.options(request, response);
				case TRACE -> rh.trace(request, response);
				case CONNECT -> rh.connect(request, response);
				default -> {
					logger.warn("Unimplemented HTTP method received: {}", method);
					response.setStatusCode(501);
					response.write("");
				}
			}
		} catch (HttpException he) {
			response.setStatusCode(he.getStatusCode());
			response.setHeader("Content-Type", "text/plain; charset=utf-8");
			response.write(he.getLongHTMLMessage());
			if (rh.isMethodAsynchronous(request.getMethod())) {
				response.finish();
			}
		}
	}
}
