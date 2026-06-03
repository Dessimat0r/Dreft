package org.deftserver.util;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;

import org.deftserver.io.IOHandler;
import org.deftserver.io.IOLoop;
import org.deftserver.web.AsyncCallback;

/** Convenience for registering an accept callback on a server socket with an I/O loop. */
public class AcceptUtil {

	/** Registers {@code cb} to fire on each accepted connection, using {@link IOLoop#INSTANCE}. */
	public static void accept(ServerSocketChannel server, final AsyncCallback cb) throws IOException {
		accept(IOLoop.INSTANCE, server, cb);
	}

	/** Registers {@code cb} to fire on each accepted connection of {@code server} with the given loop. */
	public static void accept(IOLoop ioLoop, ServerSocketChannel server, final AsyncCallback cb) throws IOException {
		ioLoop.addHandler(
				server, 
				new AcceptingIOHandler() {public void handleAccept(SelectionKey key) { cb.onCallback(); }},
				SelectionKey.OP_ACCEPT, 
				null
		);
	}
	
	private static abstract class AcceptingIOHandler implements IOHandler {

		public void handleConnect(SelectionKey key) throws IOException {}

		public void handleRead(SelectionKey key) throws IOException {}

		public void handleWrite(SelectionKey key) {}
		
	}

}
