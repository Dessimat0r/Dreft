package org.deftserver.web.handler;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import org.deftserver.web.HttpVerb;
import org.deftserver.web.http.HttpProtocol;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.deftserver.web.http.WebSocketConnection;

/**
 * Base class for WebSocket endpoints: handles the RFC 6455 opening handshake on GET (validating the
 * Upgrade/Connection/Key/Version headers, replying 101 with the computed Sec-WebSocket-Accept, then
 * upgrading the connection) and delivers frames to the {@code onOpen}/{@code onMessage}/
 * {@code onBinaryMessage}/{@code onClose} callbacks. Subclass and implement those callbacks.
 */
public abstract class WebSocketHandler extends RequestHandler {

	private static final String MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
	private static final byte[] MAGIC_GUID_BYTES = MAGIC_GUID.getBytes(StandardCharsets.US_ASCII);
	private static final Base64.Encoder B64_ENCODER = Base64.getEncoder();

	/** Performs the WebSocket opening handshake: a valid Upgrade request gets a 101 + accept key and
	 *  is upgraded (then {@code onOpen} fires); an unsupported version gets 426; anything else 400. */
	@Override
	public void get(HttpRequest request, HttpResponse response) throws IOException {
		String upgrade = request.getHeader("Upgrade");
		String connection = request.getHeader("Connection");
		String key = request.getHeader("Sec-WebSocket-Key");
		String wsVersion = request.getHeader("Sec-WebSocket-Version");

		boolean upgradeRequested = upgrade != null && upgrade.equalsIgnoreCase("websocket") &&
			connection != null && connectionHasToken(connection, "Upgrade") &&
			key != null;

		if (upgradeRequested && wsVersion != null && !versionListContains(wsVersion, "13")) {
			response.setStatusCode(426);
			response.setHeader("Sec-WebSocket-Version", "13");
			response.setHeader("Content-Type", "text/plain; charset=utf-8");
			response.setHeader("Connection", "close");
			response.write("Unsupported WebSocket version; this server speaks version 13");
			response.finish();
			return;
		}

		if (upgradeRequested) {

			SocketChannel clientChannel = response.getChannel();
			HttpProtocol protocol = response.getProtocol();
			String acceptKey = calculateAcceptKey(key, protocol.getIOLoop());
			response.setStatusCode(101);
			response.setHeader("Upgrade", "websocket");
			response.setHeader("Connection", "Upgrade");
			response.setHeader("Sec-WebSocket-Accept", acceptKey);
			response.finish(); // flush response headers

			WebSocketConnection wsConn = new WebSocketConnection(clientChannel, protocol);
			protocol.upgradeToWebSocket(clientChannel, this, wsConn);
			onOpen(wsConn);
		} else {
			response.setStatusCode(400);
			response.write("Expected WebSocket Upgrade");
			response.finish();
		}
	}

	/** The GET handshake is asynchronous — it finishes the 101 response itself, so the dispatcher
	 *  must not also finish it. */
	@Override
	public boolean isMethodAsynchronous(HttpVerb verb) {
		return verb == HttpVerb.GET;
	}

	private static String calculateAcceptKey(String key, org.deftserver.io.IOLoop ioLoop) {
		int start = 0;
		int end = key.length();
		while (start < end && key.charAt(start) <= ' ') {
			start++;
		}
		while (end > start && key.charAt(end - 1) <= ' ') {
			end--;
		}
		MessageDigest sha1 = ioLoop.getSha1();
		sha1.reset();
		for (int i = start; i < end; i++) {
			sha1.update((byte) key.charAt(i));
		}
		sha1.update(MAGIC_GUID_BYTES);
		byte[] hashBytes = sha1.digest();
		return B64_ENCODER.encodeToString(hashBytes);
	}

	/** True if the comma-separated token list contains the given token (case-insensitive). */
	private static boolean connectionHasToken(String header, String token) {
		if (header == null) return false;
		int len = header.length();
		int tokenLen = token.length();
		int i = 0;
		while (i < len) {
			while (i < len && header.charAt(i) == ' ') i++;
			int start = i;
			while (i < len && header.charAt(i) != ',') i++;
			int end = i;
			while (end > start && header.charAt(end - 1) == ' ') end--;
			if (end - start == tokenLen && header.regionMatches(true, start, token, 0, tokenLen)) {
				return true;
			}
			i++;
		}
		return false;
	}

	/** True if a comma-separated version list (RFC 6455 §4.1) contains the given version string. */
	private static boolean versionListContains(String header, String version) {
		if (header == null) return false;
		int len = header.length();
		int versionLen = version.length();
		int i = 0;
		while (i < len) {
			while (i < len && header.charAt(i) == ' ') i++;
			int start = i;
			while (i < len && header.charAt(i) != ',') i++;
			int end = i;
			while (end > start && header.charAt(end - 1) == ' ') end--;
			if (end - start == versionLen && header.regionMatches(false, start, version, 0, versionLen)) {
				return true;
			}
			i++;
		}
		return false;
	}

	/** Called once the handshake completes and the connection is open. */
	public abstract void onOpen(WebSocketConnection connection);

	/** Called for each complete text message (opcode 0x1), reassembled and UTF-8 validated. */
	public abstract void onMessage(WebSocketConnection connection, String message);

	/** Called once when the connection closes, by any teardown path. */
	public abstract void onClose(WebSocketConnection connection);

	/**
	 * Called for a binary WebSocket message (opcode 0x2). The default delivers it as a UTF-8
	 * string via {@link #onMessage} for backwards compatibility; override to receive the raw bytes.
	 */
	public void onBinaryMessage(WebSocketConnection connection, byte[] data) {
		onMessage(connection, new String(data, StandardCharsets.UTF_8));
	}
}
