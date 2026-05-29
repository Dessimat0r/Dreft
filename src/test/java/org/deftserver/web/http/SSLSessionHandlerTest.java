package org.deftserver.web.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.deftserver.io.IOLoop;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SSLSessionHandlerTest {
	private static final Logger logger = LoggerFactory.getLogger(SSLSessionHandlerTest.class);

	@Test
	public void testInMemoryHandshakeAndDataTransfer() throws Exception {
		// 1. Initialize Server SSLContext
		KeyStore ks = KeyStore.getInstance("PKCS12");
		try (FileInputStream fis = new FileInputStream("src/test/resources/keystore.p12")) {
			ks.load(fis, "password".toCharArray());
		}
		KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
		kmf.init(ks, "password".toCharArray());
		SSLContext serverCtx = SSLContext.getInstance("TLS");
		serverCtx.init(kmf.getKeyManagers(), null, null);

		// 2. Initialize Client SSLContext (Trust All for testing)
		SSLContext clientCtx = SSLContext.getInstance("TLS");
		TrustManager[] trustAll = new TrustManager[] {
			new X509TrustManager() {
				public X509Certificate[] getAcceptedIssuers() { return null; }
				public void checkClientTrusted(X509Certificate[] certs, String authType) {}
				public void checkServerTrusted(X509Certificate[] certs, String authType) {}
			}
		};
		clientCtx.init(null, trustAll, null);

		// 3. Create Client and Server Engines
		SSLEngine clientEngine = clientCtx.createSSLEngine();
		clientEngine.setUseClientMode(true);

		SSLEngine serverEngine = serverCtx.createSSLEngine();
		serverEngine.setUseClientMode(false);

		// Buffers
		int appSize = clientEngine.getSession().getApplicationBufferSize();
		int packetSize = clientEngine.getSession().getPacketBufferSize();

		ByteBuffer clientAppOut = ByteBuffer.wrap("Hello Secure Dreft!".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		ByteBuffer clientAppIn = ByteBuffer.allocate(appSize);
		ByteBuffer clientNetOut = ByteBuffer.allocate(packetSize);
		ByteBuffer clientNetIn = ByteBuffer.allocate(packetSize);

		ByteBuffer serverAppOut = ByteBuffer.wrap("Response from Secure Server".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		ByteBuffer serverAppIn = ByteBuffer.allocate(appSize);
		ByteBuffer serverNetOut = ByteBuffer.allocate(packetSize);
		ByteBuffer serverNetIn = ByteBuffer.allocate(packetSize);

		// Begin Handshake
		clientEngine.beginHandshake();
		serverEngine.beginHandshake();

		boolean handshakeComplete = false;
		int loopCount = 0;

		while (!handshakeComplete && loopCount < 100) {
			loopCount++;
			SSLEngineResult.HandshakeStatus clientHS = clientEngine.getHandshakeStatus();
			SSLEngineResult.HandshakeStatus serverHS = serverEngine.getHandshakeStatus();

			logger.debug("Loop {}: Client HS = {}, Server HS = {}", loopCount, clientHS, serverHS);

			if (clientHS == SSLEngineResult.HandshakeStatus.FINISHED || serverHS == SSLEngineResult.HandshakeStatus.FINISHED ||
				(clientHS == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING && serverHS == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)) {
				handshakeComplete = true;
				break;
			}

			// Client NEED_WRAP -> Server NEED_UNWRAP
			if (clientHS == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
				clientNetOut.clear();
				SSLEngineResult res = clientEngine.wrap(ByteBuffer.allocate(0), clientNetOut);
				clientNetOut.flip();
				logger.debug("Client wrapped {} bytes", clientNetOut.remaining());
				
				// Feed to server
				serverNetIn.put(clientNetOut);
				serverNetIn.flip();
				serverEngine.unwrap(serverNetIn, serverAppIn);
				serverNetIn.compact();
			}
			// Server NEED_WRAP -> Client NEED_UNWRAP
			else if (serverHS == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
				serverNetOut.clear();
				SSLEngineResult res = serverEngine.wrap(ByteBuffer.allocate(0), serverNetOut);
				serverNetOut.flip();
				logger.debug("Server wrapped {} bytes", serverNetOut.remaining());

				// Feed to client
				clientNetIn.put(serverNetOut);
				clientNetIn.flip();
				clientEngine.unwrap(clientNetIn, clientAppIn);
				clientNetIn.compact();
			}
			// Run delegated tasks
			else if (clientHS == SSLEngineResult.HandshakeStatus.NEED_TASK) {
				Runnable task;
				while ((task = clientEngine.getDelegatedTask()) != null) {
					task.run();
				}
			}
			else if (serverHS == SSLEngineResult.HandshakeStatus.NEED_TASK) {
				Runnable task;
				while ((task = serverEngine.getDelegatedTask()) != null) {
					task.run();
				}
			}
			// Client NEED_UNWRAP / Server NEED_UNWRAP without data is handled in standard state progression
			else {
				// Fallback loop step to unwrap remaining buffered data
				clientNetIn.flip();
				if (clientNetIn.hasRemaining()) {
					clientEngine.unwrap(clientNetIn, clientAppIn);
				}
				clientNetIn.compact();

				serverNetIn.flip();
				if (serverNetIn.hasRemaining()) {
					serverEngine.unwrap(serverNetIn, serverAppIn);
				}
				serverNetIn.compact();
			}
		}

		assertTrue("Handshake should complete successfully in memory", handshakeComplete);

		// Test Secure Data Exchange (Client to Server)
		clientNetOut.clear();
		clientEngine.wrap(clientAppOut, clientNetOut);
		clientNetOut.flip();

		serverNetIn.put(clientNetOut);
		serverNetIn.flip();
		serverEngine.unwrap(serverNetIn, serverAppIn);
		serverAppIn.flip();

		String serverReceived = new String(serverAppIn.array(), 0, serverAppIn.limit(), java.nio.charset.StandardCharsets.UTF_8);
		assertEquals("Hello Secure Dreft!", serverReceived);

		// Test Secure Data Exchange (Server to Client)
		serverNetOut.clear();
		serverEngine.wrap(serverAppOut, serverNetOut);
		serverNetOut.flip();

		clientNetIn.put(serverNetOut);
		clientNetIn.flip();
		clientEngine.unwrap(clientNetIn, clientAppIn);
		clientAppIn.flip();

		String clientReceived = new String(clientAppIn.array(), 0, clientAppIn.limit(), java.nio.charset.StandardCharsets.UTF_8);
		assertEquals("Response from Secure Server", clientReceived);
	}
}
