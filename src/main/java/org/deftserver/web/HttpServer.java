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
	
	private final List<ServerSocketChannel> serverChannels = new LinkedList<>();
	private final List<ServerSocketChannel> sslServerChannels = new LinkedList<>();
	private final List<IOLoop> ioLoops = new LinkedList<>();
	
	private final Application application;
	private javax.net.ssl.SSLContext sslContext;
	
	private int maxConnections = -1;
	private int maxConnectionsPerIp = -1;

	/** Creates a server for the given application (handlers). Configure TLS/limits, then bind+start. */
	public HttpServer(Application application) {
		this.application = application;
	}

	/** Caps the total number of simultaneous connections across the server ({@code <= 0} disables). */
	public void setMaxConnections(int max) {
		this.maxConnections = max;
	}

	/** Caps simultaneous connections from a single remote IP (<= 0 disables). */
	public void setMaxConnectionsPerIp(int max) {
		this.maxConnectionsPerIp = max;
	}

	public int getPort() {
		if (serverChannels.isEmpty()) return -1;
		try {
			return serverChannels.get(0).socket().getLocalPort();
		} catch (UnsupportedOperationException e) {
			return -1;
		}
	}

	/** Enables HTTPS by loading a PKCS12 keystore from disk and building a TLS {@link SSLContext}
	 *  from it (key-manager only; no client-certificate trust). Call before {@link #listen}/{@link #start}. */
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

	/** Enables HTTPS using a caller-supplied {@link SSLContext} (e.g. for custom key/trust managers). */
	public void enableSSL(javax.net.ssl.SSLContext sslContext) {
		this.sslContext = sslContext;
	}

	/** True if HTTPS has been enabled (a TLS context is configured). */
	public boolean isSSLEnabled() {
		return sslContext != null;
	}

	/** The configured TLS context, or null if running plaintext HTTP. */
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
	
	/** Opens the shared non-blocking server socket and binds it to {@code port} (with
	 *  {@code SO_REUSEADDR}). Use 0 for an ephemeral port. Closes the channel and rethrows on failure. */
	public void bind(int port) throws IOException {
		bind(port, isSSLEnabled());
	}

	public void bind(int port, boolean ssl) throws IOException {
		if (port < MIN_PORT_NUMBER || port > MAX_PORT_NUMBER) {
			throw new IllegalArgumentException("Invalid port number. Valid range: [" + 
					MIN_PORT_NUMBER + ", " + MAX_PORT_NUMBER + ")");
		}
		ServerSocketChannel channel = ServerSocketChannel.open();
		channel.configureBlocking(false);
		InetSocketAddress endpoint = new InetSocketAddress(port);
		try {
			channel.socket().setReuseAddress(true);
			channel.socket().bind(endpoint, org.deftserver.web.http.HttpServerDescriptor.ACCEPT_BACKLOG);
		} catch (IOException e) {
			logger.error("Could not bind socket", e);
			try { channel.close(); } catch (IOException ignore) {}
			throw e;
		}
		serverChannels.add(channel);
		if (ssl) {
			sslServerChannels.add(channel);
		}
	}

	public void bind(java.nio.file.Path path) throws IOException {
		bind(path, isSSLEnabled());
	}

	public void bind(java.nio.file.Path path, boolean ssl) throws IOException {
		java.nio.file.Files.deleteIfExists(path);
		ServerSocketChannel channel = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX);
		channel.configureBlocking(false);
		java.net.UnixDomainSocketAddress endpoint = java.net.UnixDomainSocketAddress.of(path);
		try {
			channel.bind(endpoint, org.deftserver.web.http.HttpServerDescriptor.ACCEPT_BACKLOG);
		} catch (IOException e) {
			logger.error("Could not bind Unix domain socket at {}", path, e);
			try { channel.close(); } catch (IOException ignore) {}
			throw e;
		}
		serverChannels.add(channel);
		if (ssl) {
			sslServerChannels.add(channel);
		}
	}
	
	/** Runs the server across {@code numThreads} independent I/O loops, each on its own platform
	 *  thread with its own {@link HttpProtocol}, all sharing the one server socket for OP_ACCEPT
	 *  (a multi-reactor design). Call {@link #bind} first. */
	public void start(int numThreads) throws IOException {
		for (int i = 0; i < numThreads; i++) {
			final IOLoop ioLoop = new IOLoop();
			ioLoops.add(ioLoop);
			final HttpProtocol protocol = new HttpProtocol(ioLoop, application);
			if (maxConnections > 0) {
				protocol.setMaxConnections((maxConnections + numThreads - 1) / numThreads);
			}
			if (maxConnectionsPerIp > 0) {
				protocol.setMaxConnectionsPerIp((maxConnectionsPerIp + numThreads - 1) / numThreads);
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
				if (ioLoop != IOLoop.INSTANCE) {
					ioLoop.dispose();
				}
			});
		}
		for (ServerSocketChannel channel : serverChannels) {
			Closeables.closeQuietly(channel);
		}
	}

	private void registerHandler(IOLoop ioLoop, HttpProtocol protocol) throws IOException {
		for (ServerSocketChannel channel : serverChannels) {
			boolean isSsl = sslServerChannels.contains(channel);
			ioLoop.addHandler(
				channel,
				protocol,
				SelectionKey.OP_ACCEPT,
				isSsl
			);
		}
	}

}
