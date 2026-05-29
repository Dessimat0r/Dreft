package org.deftserver.util;

import java.io.IOException;
import java.nio.channels.SelectableChannel;

import org.deftserver.io.IOLoop;

public class Closeables {

	private Closeables() {}

	public static void closeQuietly(SelectableChannel channel) {
		closeQuietly(IOLoop.INSTANCE, channel);
	}
	
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
