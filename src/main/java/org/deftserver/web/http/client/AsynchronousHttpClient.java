package org.deftserver.web.http.client;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeoutException;

import org.deftserver.io.AsynchronousSocket;
import org.deftserver.io.IOLoop;
import org.deftserver.io.timeout.Timeout;
import org.deftserver.util.NopAsyncResult;
import org.deftserver.util.UrlUtil;
import org.deftserver.web.AsyncCallback;
import org.deftserver.web.AsyncResult;
import org.deftserver.web.HttpVerb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
* This class implements a simple HTTP 1.1 client on top of Deft's {@code AsynchronousSocket}.
* It does not currently implement all applicable parts of the HTTP
* specification.
* <pre>
* E.g the following is not supported.
*  - POST and PUT
*  
* </pre>
* This class has not been tested extensively in production and
* should be considered experimental as of the release of
* Deft 0.3.
* 
* This http client is inspired by https://github.com/facebook/tornado/blob/master/tornado/simple_httpclient.py
* and part of the documentation is simply copy pasted.
*/
/**
 * A non-blocking HTTP/1.1 client that runs on an {@link IOLoop}: it connects, writes the request,
 * then parses the status line, headers and body (Content-Length or chunked) incrementally, following
 * redirects up to the request's limit, and delivers the {@link Response} to the supplied callback.
 * Internal {@code onX} methods are the steps of that state machine, driven by I/O readiness.
 */
public class AsynchronousHttpClient {

	private static final Logger logger = LoggerFactory.getLogger(AsynchronousHttpClient.class);

	private static final long TIMEOUT = 15 * 1000;	// 15s

	/** Hard cap on a decoded response body (Content-Length or summed chunks). A hostile/buggy server
	 *  must not be able to OOM the client by declaring a giant Content-Length or streaming endless
	 *  chunks; once this is exceeded the request fails cleanly via onFailure. */
	private static final int MAX_RESPONSE_BODY_SIZE = 64 * 1024 * 1024; // 64 MiB

	private static final AsyncResult<Response> nopAsyncResult = NopAsyncResult.of(Response.class).nopAsyncResult;

	private AsynchronousSocket socket;
	
	private Request request;
	private long requestStarted;
	private Response response;
	private AsyncResult<Response> responseCallback;
	
	private Timeout timeout;
	
	private final IOLoop ioLoop;
	
	private static final String HTTP_VERSION = "HTTP/1.1\r\n";
	private static final String USER_AGENT_HEADER = "User-Agent: Deft AsynchronousHttpClient/0.2-SNAPSHOT\r\n";
	private static final String NEWLINE = "\r\n";
	
	/** Creates a client that runs on the singleton {@link IOLoop#INSTANCE}. */
	public AsynchronousHttpClient() {
		this(IOLoop.INSTANCE);
	}

	/** Creates a client that runs on the given I/O loop. */
	public AsynchronousHttpClient(IOLoop ioLoop) {
		this.ioLoop = ioLoop;
	}

	/**
	 * Makes an asynchronous HTTP GET request against the specified url and invokes the given 
	 * callback when the response is fetched.
	 * 
	 * @param url e.g "http://tt.se:80/start/"
	 * @param cb callback that will be executed when the response is received.
	 */
	public void fetch(String url, AsyncResult<Response> cb) {
		request = new Request(url, HttpVerb.GET);
		doFetch(cb, System.currentTimeMillis());
	}
	
	/** Performs the given (pre-built) request and invokes {@code cb} with the response or failure. */
	public void fetch(Request request, AsyncResult<Response> cb) {
		this.request = request;
		doFetch(cb, System.currentTimeMillis());
	}

	public java.util.concurrent.CompletableFuture<Response> fetch(String url) {
		java.util.concurrent.CompletableFuture<Response> future = new java.util.concurrent.CompletableFuture<>();
		fetch(url, new AsyncResult<Response>() {
			@Override
			public void onSuccess(Response result) {
				future.complete(result);
			}
			@Override
			public void onFailure(Throwable caught) {
				future.completeExceptionally(caught);
			}
		});
		return future;
	}

	public java.util.concurrent.CompletableFuture<Response> fetch(Request request) {
		java.util.concurrent.CompletableFuture<Response> future = new java.util.concurrent.CompletableFuture<>();
		fetch(request, new AsyncResult<Response>() {
			@Override
			public void onSuccess(Response result) {
				future.complete(result);
			}
			@Override
			public void onFailure(Throwable caught) {
				future.completeExceptionally(caught);
			}
		});
		return future;
	}

	/** Opens a socket, arms the request timeout, and connects — wiring the connect result to the
	 *  {@code onConnect}/{@code onConnectFailure} steps of the request state machine. */
	private void doFetch(AsyncResult<Response> cb, long requestStarted) {
		this.requestStarted = requestStarted;
		responseCallback = cb;
		try {
			socket = new AsynchronousSocket(ioLoop, SocketChannel.open());
		} catch (IOException e) {
			logger.error("Error opening SocketChannel: {}", e.getMessage());
			responseCallback = nopAsyncResult;
			cb.onFailure(e);
			return;
		}
		int port = request.getURL().getPort();
		port = port == -1 ? 80 : port;
		startTimeout();
		socket.connect(
				request.getURL().getHost(), 
				port,
				new AsyncResult<Boolean>() {
					public void onFailure(Throwable t) { onConnectFailure(t); }
					public void onSuccess(Boolean result) { onConnect(); }
				}
		);
	}
	
	/**
	 * Close the underlaying {@code AsynchronousSocket}.
	 */
	public void close() {
		logger.debug("Closing http client connection...");
		socket.close(); 
	}
	
	/** Arms the per-operation (connect/read/write) timeout. */
	private void startTimeout() {
		logger.debug("start timeout...");
		timeout = new Timeout(
				System.currentTimeMillis() + TIMEOUT, 
				new AsyncCallback() { public void onCallback() { onTimeout(); } }
		);
		ioLoop.addTimeout(timeout);		
	}
	
	/** Cancels the current operation timeout. */
	private void cancelTimeout() {
		logger.debug("cancel timeout...");
		timeout.cancel();
		timeout = null;
	}
	
	/** Timeout step: fails the response callback and closes the connection. */
	private void onTimeout() {
		logger.debug("Pending operation (connect, read or write) timed out...");
		AsyncResult<Response> cb = responseCallback;
		responseCallback = nopAsyncResult;
		cb.onFailure(new TimeoutException("Connection timed out"));
		close();
	}

	/** Connect step: writes the request line and headers, then awaits the write to complete. */
	private void onConnect() {
		logger.debug("Connected...");
		cancelTimeout();
		startTimeout();
		socket.write(
				makeRequestLineAndHeaders(), 
				new AsyncCallback() { public void onCallback() { onWriteComplete(); }}
		);
	}
	
	/** Connect-failure step: fails the response callback and closes. */
	private void onConnectFailure(Throwable t) {
		logger.debug("Connect failed...");
		cancelTimeout();
		AsyncResult<Response> cb = responseCallback;
		responseCallback = nopAsyncResult;
		cb.onFailure(t);
		close();
	}

	/** Fails the pending response callback (once) and tears the connection down. Used for protocol
	 *  errors encountered mid-response (e.g. a malformed Content-Length) so the caller is always
	 *  notified via onFailure rather than left hanging when the read pipeline aborts. */
	private void failAndClose(Throwable t) {
		cancelTimeout();
		AsyncResult<Response> cb = responseCallback;
		responseCallback = nopAsyncResult;
		cb.onFailure(t);
		close();
	}

	/**
	 * 
	 * @return Eg. 
	 * 				GET /path/to/file/index.html HTTP/1.0
	 * 				From: a@b.com
	 * 				User-Agent: HTTPTool/1.0
	 * 
	 */
	private String makeRequestLineAndHeaders() {
		return request.getVerb() + " " + request.getURL().getPath() + " " + HTTP_VERSION +
				"Host: " + request.getURL().getHost() + "\r\n" +
				USER_AGENT_HEADER +
				NEWLINE;
	}
	
	/** Write-complete step: reads up to the header delimiter, then parses the headers. */
	private void onWriteComplete() {
		logger.debug("onWriteComplete...");
		cancelTimeout();
		startTimeout();
		socket.readUntil(
				"\r\n\r\n", 	/* header delimiter */
				new NaiveAsyncResult() { public void onSuccess(String headers) { onHeaders(headers); }
		});
	}
	
	/** Header step: parses the status line and headers, then reads the body by Content-Length or as
	 *  chunked. */
	private void onHeaders(String result) {
		logger.debug("headers: {}", result);
		cancelTimeout();
		response = new Response(requestStarted);
		int len = result.length();
		int start = 0;
		int end = result.indexOf("\r\n");
		if (end == -1) {
			response.setStatuLine(result);
			start = len;
		} else {
			response.setStatuLine(result.substring(start, end));
			start = end + 2;
		}
		while (start < len) {
			end = result.indexOf("\r\n", start);
			if (end == -1) {
				end = len;
			}
			int colonIndex = -1;
			for (int j = start; j < end; j++) {
				if (result.charAt(j) == ':') {
					colonIndex = j;
					break;
				}
			}
			if (colonIndex != -1) {
				int kStart = start;
				while (kStart < colonIndex && result.charAt(kStart) == ' ') kStart++;
				int kEnd = colonIndex;
				while (kEnd > kStart && result.charAt(kEnd - 1) == ' ') kEnd--;
				int vStart = colonIndex + 1;
				while (vStart < end && result.charAt(vStart) == ' ') vStart++;
				int vEnd = end;
				while (vEnd > vStart && result.charAt(vEnd - 1) == ' ') vEnd--;
				String key = result.substring(kStart, kEnd);
				String value = result.substring(vStart, vEnd);
				response.setHeader(key, value);
			}
			start = end + 2;
		}
		logger.debug("cl-ahttpc");
		String contentLength = response.getHeader("Content-Length");
		startTimeout();
		if (contentLength != null) {
			// A malformed or oversized Content-Length must fail the request cleanly (via onFailure)
			// rather than throw an uncaught NumberFormatException up the read-callback chain — which
			// would silently drop the channel and leave the caller's callback never invoked.
			final int bodyLength;
			try {
				bodyLength = Integer.parseInt(contentLength.trim());
				if (bodyLength < 0) {
					throw new NumberFormatException("negative Content-Length");
				}
			} catch (NumberFormatException e) {
				failAndClose(new IOException("Invalid Content-Length in response: " + contentLength));
				return;
			}
			// Bound the declared body: a hostile server must not OOM us with a giant Content-Length.
			if (bodyLength > MAX_RESPONSE_BODY_SIZE) {
				failAndClose(new IOException("Response body exceeds maximum permitted size: " + bodyLength));
				return;
			}
			socket.readBytes(
					bodyLength,
					new NaiveAsyncResult() { public void onSuccess(String body) { onBody(body); } }
			);
		} else {  // Transfer-Encoding: chunked
			socket.readUntil(
					NEWLINE, 	/* chunk delimiter*/
					new NaiveAsyncResult() { public void onSuccess(String octet) { onChunkOctet(octet); } }
			);
		}
	}
				
	/** Body-complete step: either follows a 301/302/303/307/308 redirect (within the limit) or
	 *  finishes and delivers the response. */
	private void onBody(String body) {
		logger.debug("body size: {}", body.length());
		cancelTimeout();
		response.setBody(body);
		if (request.isFollowingRedirects() && request.getMaxRedirects() > 0
				&& isRedirectStatus(response.getStatusLine())) {
			String location = response.getHeader("Location");
			if (location == null) {
				close();
				AsyncResult<Response> cb = responseCallback;
				responseCallback = nopAsyncResult;
				cb.onFailure(new IOException("Redirect response missing Location header"));
				return;
			}
			String newUrl = UrlUtil.urlJoin(request.getURL(), location);
			// Only ever follow a redirect to http/https — a hostile server must not be able to point us
			// at file://, gopher://, jar:// etc. (an SSRF / local-resource vector via a custom handler).
			String scheme = null;
			try {
				scheme = new java.net.URI(newUrl).getScheme();
			} catch (Exception e) {
				// fall through to the null-scheme rejection below
			}
			if (scheme == null
					|| !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
				close();
				AsyncResult<Response> cb = responseCallback;
				responseCallback = nopAsyncResult;
				cb.onFailure(new IOException("Refusing redirect to non-http(s) scheme: " + newUrl));
				return;
			}
			// RFC 9110 §15.4.4: a 303 See Other redirect changes the method to GET regardless of the
			// original; 307/308 preserve it, and historically 301/302 are also followed with GET by most
			// agents for non-GET/HEAD. Switch to GET for 303 specifically (the spec-mandated case).
			HttpVerb redirectVerb = isSeeOther(response.getStatusLine())
				? HttpVerb.GET : HttpVerb.valueOf(request.getVerb());
			request = new Request(newUrl, redirectVerb, true, request.getMaxRedirects() - 1);
			logger.debug("Following redirect, new url: {}, redirects left: {}", newUrl, request.getMaxRedirects());
			if (socket != null) socket.close();
			doFetch(responseCallback, requestStarted);
		} else {
			close();
			invokeResponseCallback();
		}
	}

	private static boolean isRedirectStatus(String statusLine) {
		return redirectCode(statusLine) != -1;
	}

	/** True if the response is a 303 See Other (the redirect that mandates switching to GET). */
	private static boolean isSeeOther(String statusLine) {
		return redirectCode(statusLine) == 303;
	}

	/** Returns the status code if {@code statusLine} is one of the followable redirects (301/302/303/
	 *  307/308), else -1. */
	private static int redirectCode(String statusLine) {
		if (statusLine == null) return -1;
		int sp1 = statusLine.indexOf(' ');
		if (sp1 < 0) return -1;
		int sp2 = statusLine.indexOf(' ', sp1 + 1);
		String codeStr = (sp2 < 0) ? statusLine.substring(sp1 + 1) : statusLine.substring(sp1 + 1, sp2);
		try {
			int code = Integer.parseInt(codeStr.trim());
			boolean followable = code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
			return followable ? code : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}
	
	/** Chunk-data step: appends a decoded chunk and reads the next chunk-size line. */
	private void onChunk(String chunk) {
		logger.debug("chunk size: {}", chunk.length());
		cancelTimeout();
		response.addChunk(chunk.substring(0, chunk.length() - NEWLINE.length()));
		startTimeout();
		socket.readUntil(
				NEWLINE, 	/* chunk delimiter*/
				new NaiveAsyncResult() { public void onSuccess(String octet) { onChunkOctet(octet); } }
		);
	}
	
	/** Chunk-size step: parses the hex chunk size and reads that many body bytes, or finishes on the
	 *  terminating 0-chunk. */
	private void onChunkOctet(String octet) {
		// The chunk-size line may carry chunk-extensions after a ';' (RFC 9112 §7.1.1) — strip them
		// before parsing the hex size. A malformed size must fail the request via onFailure rather
		// than throw an uncaught NumberFormatException up the read-callback chain (silent hang).
		int semi = octet.indexOf(';');
		int limit = (semi == -1) ? octet.length() : semi;
		int start = 0;
		while (start < limit && octet.charAt(start) <= ' ') {
			start++;
		}
		int end = limit;
		while (end > start && octet.charAt(end - 1) <= ' ') {
			end--;
		}
		final int readBytes;
		try {
			if (start == end) {
				throw new NumberFormatException("empty chunk size");
			}
			readBytes = Integer.parseInt(octet, start, end, 16);
			if (readBytes < 0) {
				throw new NumberFormatException("negative chunk size");
			}
		} catch (NumberFormatException e) {
			failAndClose(new IOException("Invalid chunk size in response: " + octet));
			return;
		}
		logger.debug("chunk octet: {} (decimal: {})", octet, readBytes);
		// Bound a single chunk and the running total: this both caps memory and makes the
		// `readBytes + NEWLINE.length()` below safe from int overflow (a near-MAX_VALUE chunk size
		// would otherwise wrap to a negative/small read count and hang the client).
		if (readBytes > MAX_RESPONSE_BODY_SIZE
				|| (long) response.currentBodyLength() + readBytes > MAX_RESPONSE_BODY_SIZE) {
			failAndClose(new IOException("Chunked response body exceeds maximum permitted size"));
			return;
		}
		cancelTimeout();
		startTimeout();
		if (readBytes != 0) {
			socket.readBytes(
					readBytes + NEWLINE.length(),	// chunk delimiter is \r\n
					new NaiveAsyncResult() { public void onSuccess(String chunk) { onChunk(chunk); } }
			);
		} else {
			onBody(response.getBody());
		}
	}
	
	/** Delivers the completed response to the user callback exactly once. */
	private void invokeResponseCallback() {
		AsyncResult<Response> cb = responseCallback;
		responseCallback = nopAsyncResult;
		cb.onSuccess(response);
	}
				
	/**
	 * Naive because all it does when an exception is thrown is log the exception.
	 */
	private abstract class NaiveAsyncResult implements AsyncResult<String> {
		
		@Override
		public void onFailure(Throwable caught) {
			logger.debug("onFailure: {}", caught);
			cancelTimeout();
			AsyncResult<Response> cb = responseCallback;
			responseCallback = nopAsyncResult;
			cb.onFailure(caught);
			close();
		}
		
	}

}
