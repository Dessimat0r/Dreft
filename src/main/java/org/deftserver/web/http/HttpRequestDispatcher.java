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

	/** Handlers slower than this (ns) are flagged for virtual-thread offload. Non-final so tests can
	 *  lower it to force the offload decision deterministically. */
	static long HEAVY_THRESHOLD_NS = Long.getLong("deft.heavy_threshold_ns", 5_000_000L); // Default: 5ms

	/** Test hook: whether the given handler/verb is currently flagged for offload. */
	static boolean isFlaggedHeavy(Class<?> handlerClass, HttpVerb method) {
		return heavyHandlers.getOrDefault(new HandlerKey(handlerClass, method), false);
	}

	/** Test hook: clears the adaptive-offload flags (for test isolation). */
	static void clearHeavyHandlers() {
		heavyHandlers.clear();
	}

	/**
	 * Routes a request to the right verb method of the handler, applying CORS headers/preflight,
	 * adaptive offload of slow ("heavy") handlers onto a virtual thread, and crash isolation
	 * (handler failures become a 500 rather than escaping into the I/O loop).
	 *
	 * @return true if the response will be finished asynchronously (by an async handler or the
	 *         offload thread); false if the caller should finish it.
	 */
	public static boolean dispatch(RequestHandler rh, HttpRequest request, HttpResponse response) throws IOException {
		HttpVerb method = request.getMethod();
		logger.debug("method: {}", method);
		
		boolean isAsync = rh.isMethodAsynchronous(method);

		CorsConfig cors = rh.getCorsConfig();
		if (cors != null) {
			String origin = request.getHeader("Origin");
			if (origin != null && !origin.isEmpty() && cors.isOriginAllowed(origin)) {
				response.setHeader("Access-Control-Allow-Origin", origin);
				response.addVary("Origin");
				if (cors.isAllowCredentials()) {
					response.setHeader("Access-Control-Allow-Credentials", "true");
				}
				
				if (method == HttpVerb.OPTIONS) {
					// Only intercept if this is a true CORS preflight (has Access-Control-Request-Method).
					// A non-preflight OPTIONS should reach the handler so it can describe its own capabilities.
					String acrm = request.getHeader("Access-Control-Request-Method");
					if (acrm != null && !acrm.trim().isEmpty()) {
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
		}

		HandlerKey key = new HandlerKey(rh.getClass(), method);
		boolean isHeavy = heavyHandlers.getOrDefault(key, false);

		if (isHeavy && !isAsync) {
			logger.debug("Adaptive Dispatcher: executing slow method {} of {} on Virtual Thread", method, rh.getClass().getSimpleName());
			final org.deftserver.io.IOLoop ioLoop = response.getProtocol().getIOLoop();
			Thread.startVirtualThread(() -> {
				try {
					doDispatch(rh, method, request, response);
					ioLoop.addCallback(() -> response.finish());
				} catch (Throwable e) {
					logger.error("Unhandled throwable in virtual-thread handler dispatch — sending 500", e);
					ioLoop.addCallback(() -> {
						try {
							response.setStatusCode(500);
							response.setHeader("Content-Type", "text/plain; charset=utf-8");
							response.setHeader("X-Content-Type-Options", "nosniff");
							response.setHeader("Connection", "close");
							response.write("Internal Server Error");
							response.finish();
						} catch (Exception suppressed) {
							logger.debug("Failed to send 500 response after virtual-thread handler crash", suppressed);
							try { response.getProtocol().closeChannel(response.getChannel()); } catch (Exception ignore) {}
						}
					});
				}
			});
			return true;
		} else {
			long start = System.nanoTime();
			try {
				doDispatch(rh, method, request, response);
			} catch (RuntimeException | StackOverflowError | LinkageError e) {
				logger.error("Unhandled error in synchronous handler dispatch — sending 500", e);
				try {
					response.setStatusCode(500);
					response.setHeader("Content-Type", "text/plain; charset=utf-8");
					response.setHeader("X-Content-Type-Options", "nosniff");
					response.setHeader("Connection", "close");
					response.write("Internal Server Error");
					response.finish();
				} catch (Exception suppressed) {
					logger.debug("Failed to send 500 response after handler crash", suppressed);
					try { response.getProtocol().closeChannel(response.getChannel()); } catch (Exception ignore) {}
				}
				return true;
			} finally {
				long duration = System.nanoTime() - start;
				if (!isAsync && duration > HEAVY_THRESHOLD_NS && !response.hasFlushedHeaders()) {
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
			if (method != HttpVerb.UNKNOWN && !rh.isVerbImplemented(method)) {
				response.setHeader("Allow", rh.getAllowHeader());
				if (method == HttpVerb.OPTIONS) {
					response.setStatusCode(204);
				} else {
					response.setStatusCode(405);
				}
				response.write("");
				if (rh.isMethodAsynchronous(method)) {
					response.finish();
				}
				return;
			}
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
			response.setHeader("X-Content-Type-Options", "nosniff");
			response.write(he.getLongHTMLMessage());
			if (rh.isMethodAsynchronous(request.getMethod())) {
				response.finish();
			}
		}
	}
}
