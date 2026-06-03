package org.deftserver.util;

import java.io.IOException;
import java.nio.channels.SelectableChannel;

import org.deftserver.io.IOLoop;

/** Helpers for unregistering and closing channels without throwing. */
public class Closeables {

	private Closeables() {}

	/** Quietly removes the channel's handler from {@link IOLoop#INSTANCE} and closes it. */
	public static void closeQuietly(SelectableChannel channel) {
		closeQuietly(IOLoop.INSTANCE, channel);
	}

	/** Quietly removes the channel's handler from the given loop and closes it, swallowing errors.
	 *  (Server connections should normally close via {@code HttpProtocol.closeChannel} to also clear
	 *  per-channel state.) */
	public static void closeQuietly(IOLoop ioLoop, SelectableChannel channel) {
		try {
			ioLoop.removeHandler(channel);
		} catch (Exception ignore) {}
		if (channel != null) {
			try {
				channel.close();
			} catch (IOException ignore) { }
		}
	}
	
}
