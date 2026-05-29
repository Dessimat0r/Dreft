package org.deftserver.web.http;

import java.io.IOException;

/**
 * Thrown by {@link SSLSessionHandler} when the remote peer sends a TLS
 * {@code close_notify} alert ({@code SSLEngineResult.Status.CLOSED}).
 * This is a <em>graceful</em> peer-initiated shutdown, not an error condition.
 * Callers should close the channel quietly rather than logging a warning or
 * returning a 400 Bad Request to the client.
 */
class SSLConnectionClosedException extends IOException {
    SSLConnectionClosedException() {
        super("TLS close_notify received (peer closed connection gracefully)");
    }
}
