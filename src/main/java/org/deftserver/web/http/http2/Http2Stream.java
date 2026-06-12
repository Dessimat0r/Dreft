package org.deftserver.web.http.http2;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class Http2Stream {

	public static final int STATE_IDLE = 0;
	public static final int STATE_OPEN = 1;
	public static final int STATE_HALF_CLOSED_REMOTE = 2;
	public static final int STATE_CLOSED = 3;

	public final int streamId;
	private int state = STATE_IDLE;

	public final List<Hpack.HeaderField> requestHeaders = new ArrayList<>();
	public final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();

	public int outboundWindowSize;
	public int inboundWindowSize = 65535;

	public Http2Request request;
	public Http2Response response;

	public Http2Stream(int streamId, int initialOutboundWindowSize) {
		this.streamId = streamId;
		this.outboundWindowSize = initialOutboundWindowSize;
	}

	public int getState() {
		return state;
	}

	public void setState(int state) {
		this.state = state;
	}

	public boolean isOpen() {
		return state == STATE_OPEN;
	}

	public boolean isClosed() {
		return state == STATE_CLOSED;
	}

	public boolean isHalfClosedRemote() {
		return state == STATE_HALF_CLOSED_REMOTE;
	}
}
