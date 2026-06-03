package org.deftserver.example.kv;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Mocked KeyValueStoreHandler server (accepts a connection and echoes back the input)
 *
 */
public class KeyValueStore extends Thread {

	private final static Logger logger = LoggerFactory.getLogger(KeyValueStore.class);

	public static final String HOST = "127.0.0.1";
	public static final int PORT = 0;

	private final static Map<String, String> dict = new HashMap<String, String>() {
		{ put("deft", "kickass"); }
	};

	private ServerSocket serverSocket;

	/** Creates the store, binding its server socket, and marks the thread as a daemon. */
	public KeyValueStore() {
		logger.debug("Initializing KeyValueStore");
		initialize();
		setDaemon(true);
	}

	/** Accepts client connections and serves simple line-based get/put commands. */
	public void run() {
		try (ServerSocket server = serverSocket) {
			boolean served = false;
			while (!served) {
				logger.debug("KeyValueStore waiting for clients...");	
				Socket clientSocket = server.accept();
				logger.debug("KeyValueStore client connected...");
				BufferedWriter os = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
				BufferedReader is = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
				String input = is.readLine();
				logger.debug("KeyValueStore received input: {}", input);
				if (input == null) {
					closeQuietly(is, os, clientSocket);
					continue;
				}
				if (input.split("\\s+").length == 2) {
					input = input.split("\\s+")[1];	// "GET deft" => "deft"
				}
				int sleep = 250;
				logger.debug("KeyValueStore server sleeps " + sleep + "ms..." );
				try {
					Thread.sleep(sleep);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				logger.debug("KeyValueStore woke up...");
				String value = dict.get(input) + "\r\n";
				os.write(value, 0, value.length());
				logger.debug("KeyValueStore echoed back: " + value);
				os.flush();
				served = true;
				closeQuietly(is, os, clientSocket);
			}
		} catch (IOException e) { e.printStackTrace(); }
		logger.debug("Closing KeyValueStore");
	}

	/** Closes the client streams and socket, ignoring errors. */
	private void closeQuietly(BufferedReader is, BufferedWriter os, Socket clientSocket) {
		try {
			if (is != null)
				is.close();
			if (os != null)
				os.close();
			if (clientSocket != null)
				clientSocket.close();
		}
		catch (IOException ignore) {}
	}

	/** Binds the server socket on an ephemeral port. */
	private void initialize() {
		try {
			serverSocket = new ServerSocket();
			serverSocket.setReuseAddress(true);
			serverSocket.bind(new java.net.InetSocketAddress(PORT));
		} catch (IOException e) {
			throw new IllegalStateException("Could not start KeyValueStore on " + HOST + ":" + PORT, e);
		}
	}

	/** The bound listening port. */
	public int getPort() {
		return serverSocket.getLocalPort();
	}

}
