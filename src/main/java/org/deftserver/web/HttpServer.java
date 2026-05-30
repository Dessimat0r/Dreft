package org.deftserver.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.util.List;

import org.deftserver.io.IOLoop;
import org.deftserver.util.Closeables;
import org.deftserver.web.http.HttpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;

public class HttpServer {
	
	private final Logger logger = LoggerFactory.getLogger(HttpServer.class);
	
	private static final int MIN_PORT_NUMBER = 0;
	private static final int MAX_PORT_NUMBER = 65535;
	
	private ServerSocketChannel serverChannel;
	private final List<IOLoop> ioLoops = new LinkedList<>();
	
	private final Application application;
	private javax.net.ssl.SSLContext sslContext;
	
	private int maxConnections = -1;
	private int maxConnectionsPerIp = -1;

	public HttpServer(Application application) {
		this.application = application;
	}

	public void setMaxConnections(int max) {
		this.maxConnections = max;
	}

	/** Caps simultaneous connections from a single remote IP (<= 0 disables). */
	public void setMaxConnectionsPerIp(int max) {
		this.maxConnectionsPerIp = max;
	}

	public int getPort() {
		return serverChannel == null ? -1 : serverChannel.socket().getLocalPort();
	}

	public void enableSSL(String keystorePath, String keystorePassword, String keyPassword) throws Exception {
		java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
		try (java.io.InputStream in = new java.io.FileInputStream(keystorePath)) {
			ks.load(in, keystorePassword.toCharArray());
		}
		javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance("SunX509");
		kmf.init(ks, keyPassword.toCharArray());
		sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
		sslContext.init(kmf.getKeyManagers(), null, null);
	}

	public void enableSSL(javax.net.ssl.SSLContext sslContext) {
		this.sslContext = sslContext;
	}

	public boolean isSSLEnabled() {
		return sslContext != null;
	}

	public javax.net.ssl.SSLContext getSSLContext() {
		return sslContext;
	}

	/**
	 * If you want to run Deft on multiple threads first invoke {@link #bind(int)} then {@link #start(int)} 
	 * instead of {@link #listen(int)} (listen starts Deft http server on a single thread with the default IOLoop 
	 * instance: {@code IOLoop.INSTANCE}).
	 * 
	 * @return this for chaining purposes
	 */
	public void listen(int port) throws IOException {
		bind(port);
		ioLoops.add(IOLoop.INSTANCE);
		HttpProtocol protocol = new HttpProtocol(application);
		if (maxConnections > 0) {
			protocol.setMaxConnections(maxConnections);
		}
		if (maxConnectionsPerIp > 0) {
			protocol.setMaxConnectionsPerIp(maxConnectionsPerIp);
		}
		if (isSSLEnabled()) {
			protocol.enableSSL(sslContext);
		}
		registerHandler(IOLoop.INSTANCE, protocol);
	}
	
	public void bind(int port) throws IOException {
		if (port < MIN_PORT_NUMBER || port > MAX_PORT_NUMBER) {
			throw new IllegalArgumentException("Invalid port number. Valid range: [" + 
					MIN_PORT_NUMBER + ", " + MAX_PORT_NUMBER + ")");
		}
		serverChannel = ServerSocketChannel.open();
		serverChannel.configureBlocking(false);
		InetSocketAddress endpoint = new InetSocketAddress(port);	// use "any" address
		try {
			if (serverChannel != null) {
				serverChannel.socket().setReuseAddress(true);
			}
			serverChannel.socket().bind(endpoint);
		} catch (IOException e) {
			logger.error("Could not bind socket: {}", e);
			Closeables.closeQuietly(IOLoop.INSTANCE, serverChannel);
			throw e;
		}
	}
	
	public void start(int numThreads) throws IOException {
		for (int i = 0; i < numThreads; i++) {
			final IOLoop ioLoop = new IOLoop();
			ioLoops.add(ioLoop);
			final HttpProtocol protocol = new HttpProtocol(ioLoop, application);
			if (maxConnections > 0) {
				protocol.setMaxConnections(maxConnections / numThreads > 0 ? maxConnections / numThreads : 1);
			}
			if (maxConnectionsPerIp > 0) {
				protocol.setMaxConnectionsPerIp(maxConnectionsPerIp);
			}
			if (isSSLEnabled()) {
				protocol.enableSSL(sslContext);
			}
			Thread.ofPlatform()
				.name("I/O-LOOP-" + i)
				.start(() -> {
					try {
						registerHandler(ioLoop, protocol);
						ioLoop.start();
					} catch (IOException e) {
						logger.error("Couldn't register handler or start I/O loop.", e);
					}
				});
		}
	}
	
	/**
	 * Unbinds the port and shutdown the HTTP server
	 */
	public void stop() {
		logger.debug("Stopping HTTP server");
		for (IOLoop ioLoop : ioLoops) {
			ioLoop.addCallback(() -> {
				Closeables.closeQuietly(ioLoop, serverChannel);
				if (ioLoop != IOLoop.INSTANCE) {
					ioLoop.dispose();
				}
			});
		}
	}
	
	private void registerHandler(IOLoop ioLoop, HttpProtocol protocol) throws IOException {
		ioLoop.addHandler(
			serverChannel,
			protocol, 
			SelectionKey.OP_ACCEPT,
			null /*attachment*/
		);
	}

}
