package org.deftserver.io;

import java.io.IOException;
import java.nio.channels.SelectionKey;

/**
 * {@code IOHandler}s are added to the IOLoop via {@link IOLoop#addHandler} method.
 * The callbacks defined in the {@code IOHandler} will be invoked by the {@code IOLoop} when io is ready.
 *
 */
public interface IOHandler {
	void handleAccept(SelectionKey key)  throws IOException;
	void handleConnect(SelectionKey key) throws IOException;
	void handleRead(SelectionKey key)    throws IOException;
	void handleWrite(SelectionKey key)   throws IOException;

	/**
	 * Invoked by the {@code IOLoop} when it closes a channel owned by this handler outside the
	 * handler's own code paths (e.g. on an I/O exception or a cancelled key). Lets the handler
	 * release any per-channel state it keeps so nothing leaks when the loop tears a channel down.
	 * Default: no-op.
	 */
	default void onClose(java.nio.channels.SelectableChannel channel) { }
}
