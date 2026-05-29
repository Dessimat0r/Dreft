package org.deftserver.web.handler;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.deftserver.web.HttpVerb;
import org.deftserver.web.http.HttpProtocol;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.deftserver.web.http.WebSocketConnection;

public abstract class WebSocketHandler extends RequestHandler {

	private static final String MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	@Override
	public void get(HttpRequest request, HttpResponse response) throws IOException {
		String upgrade = request.getHeader("Upgrade");
		String connection = request.getHeader("Connection");
		String key = request.getHeader("Sec-WebSocket-Key");

		if (upgrade != null && upgrade.equalsIgnoreCase("websocket") &&
			connection != null && connection.toLowerCase().contains("upgrade") &&
			key != null) {

			String acceptKey = calculateAcceptKey(key);
			response.setStatusCode(101);
			response.setHeader("Upgrade", "websocket");
			response.setHeader("Connection", "Upgrade");
			response.setHeader("Sec-WebSocket-Accept", acceptKey);
			response.finish(); // flush response headers

			SocketChannel clientChannel = response.getChannel();
			HttpProtocol protocol = response.getProtocol();
			WebSocketConnection wsConn = new WebSocketConnection(clientChannel, protocol);
			protocol.upgradeToWebSocket(clientChannel, this, wsConn);
			onOpen(wsConn);
		} else {
			response.setStatusCode(400);
			response.write("Expected WebSocket Upgrade");
			response.finish();
		}
	}

	@Override
	public boolean isMethodAsynchronous(HttpVerb verb) {
		return verb == HttpVerb.GET;
	}

	private String calculateAcceptKey(String key) {
		try {
			String input = key.trim() + MAGIC_GUID;
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.US_ASCII));
			return Base64.getEncoder().encodeToString(hashBytes);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-1 algorithm not available", e);
		}
	}

	public abstract void onOpen(WebSocketConnection connection);
	public abstract void onMessage(WebSocketConnection connection, String message);
	public abstract void onClose(WebSocketConnection connection);
}
