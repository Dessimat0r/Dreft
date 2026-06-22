package org.deftserver.web.http.http2;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.deftserver.web.http.HttpProtocol;
import org.deftserver.web.http.HttpResponse;
import org.deftserver.web.http.Cookie;

public class Http2Response extends HttpResponse {

	private final Http2Connection connection;
	private final Http2Stream stream;
	private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

	public Http2Response(HttpProtocol protocol, Http2Connection connection, Http2Stream stream, boolean headRequest) {
		// suppressBody mirrors the HTTP/1.1 path: a HEAD response carries the headers (incl. the
		// would-be Content-Length) but no body. Previously hardcoded false, so HEAD over HTTP/2 wrongly
		// sent a DATA frame.
		super(protocol, connection.getSelectionKey(), true, headRequest);
		this.connection = connection;
		this.stream = stream;
	}

	private void runOnLoop(org.deftserver.web.AsyncCallback action) {
		getProtocol().getIOLoop().addCallback(action);
	}

	@Override
	public void setStatusCode(int sc) {
		runOnLoop(() -> super.setStatusCode(sc));
	}

	@Override
	public void setHeader(String header, String value) {
		// Content-Length is computed by Http2Response itself from the body it frames, so a handler/parent
		// setHeader("Content-Length", …) is ignored for HTTP/2 framing to guarantee it can never disagree
		// with the actual DATA (RFC 7540 §8.1.2.6). The file/streaming write paths pass the known length
		// to sendHeaders directly.
		if (header.equalsIgnoreCase("Content-Length")) {
			return;
		}
		runOnLoop(() -> super.setHeader(header, value));
	}

	@Override
	public void setCookie(Cookie cookie) {
		runOnLoop(() -> super.setCookie(cookie));
	}

	@Override
	public HttpResponse write(String data) {
		byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
		runOnLoop(() -> buffer.write(bytes, 0, bytes.length));
		return this;
	}

	@Override
	public long write(ByteBuffer data) {
		int len = data.remaining();
		byte[] bytes = new byte[len];
		data.get(bytes);
		runOnLoop(() -> buffer.write(bytes, 0, len));
		return len;
	}

	@Override
	public long write(File file) {
		long len = file.length();
		runOnLoop(() -> {
			if (!headersCreated) {
				sendHeaders(false, len); // advertise the exact file length
			}
			if (isBodySuppressed()) {
				return;
			}
			try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
				byte[] buf = new byte[16384];
				int read;
				while ((read = fis.read(buf)) != -1) {
					byte[] data = new byte[read];
					System.arraycopy(buf, 0, data, 0, read);
					connection.sendData(stream.streamId, data, false);
				}
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
		return file.length();
	}

	@Override
	public long write(File file, long start, long length) {
		runOnLoop(() -> {
			if (!headersCreated) {
				sendHeaders(false, length); // advertise the exact range length
			}
			if (isBodySuppressed()) {
				return;
			}
			try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
				raf.seek(start);
				byte[] buf = new byte[16384];
				long remaining = length;
				while (remaining > 0) {
					int toRead = (int) Math.min(buf.length, remaining);
					int read = raf.read(buf, 0, toRead);
					if (read == -1) {
						break;
					}
					byte[] data = new byte[read];
					System.arraycopy(buf, 0, data, 0, read);
					connection.sendData(stream.streamId, data, false);
					remaining -= read;
				}
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
		return length;
	}

	@Override
	public long flush() {
		runOnLoop(() -> {
			if (finished) {
				return;
			}
			if (!headersCreated) {
				// Streaming: the total body length is not known yet, so omit content-length.
				sendHeaders(false, -1);
			}
			byte[] data = buffer.toByteArray();
			buffer.reset();
			if (data.length > 0) {
				connection.sendData(stream.streamId, data, false);
			}
		});
		return 0;
	}

	@Override
	public long finish() {
		runOnLoop(() -> {
			if (finished) {
				return;
			}
			finished = true;
			byte[] data = buffer.toByteArray();
			buffer.reset();
			if (isBodySuppressed()) {
				// HEAD: advertise the would-be body length, end the stream, send no DATA.
				if (!headersCreated) {
					sendHeaders(true, data.length);
				} else {
					connection.sendData(stream.streamId, new byte[0], true); // close a headers-already-sent stream
				}
				return;
			}
			if (!headersCreated) {
				// The complete body is known now, so advertise its exact length and, if it is empty,
				// end the stream on the HEADERS frame (no DATA needed).
				boolean endStream = (data.length == 0);
				sendHeaders(endStream, data.length);
				if (endStream) {
					return;
				}
			}
			connection.sendData(stream.streamId, data, true);
		});
		return 0;
	}

	/**
	 * Emits the response HEADERS. {@code bodyLength} is the exact number of body bytes that will follow
	 * when it is known (the buffered-response and file paths), or {@code -1} when the body is being
	 * streamed and its total length is not yet known. A {@code content-length} is advertised only when
	 * the length is known and one was not already set explicitly (e.g. via {@code write(File)}) — this
	 * is critical: HTTP/2 clients treat a {@code content-length} that disagrees with the summed DATA as
	 * a PROTOCOL_ERROR (RFC 7540 §8.1.2.6), and connection-specific headers are stripped (§8.1.2.2).
	 */
	private void sendHeaders(boolean endStream, long bodyLength) {
		headersCreated = true;
		List<Hpack.HeaderField> headerFields = new ArrayList<>();
		headerFields.add(new Hpack.HeaderField(":status", String.valueOf(statusCode)));
		boolean hasContentLength = false;
		for (Map.Entry<String, String> entry : headers.entrySet()) {
			String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
			if (name.equals("connection") || name.equals("keep-alive") || name.equals("transfer-encoding")
					|| name.equals("upgrade") || name.equals("proxy-connection")) {
				continue;
			}
			if (name.equals("content-length")) {
				hasContentLength = true;
			}
			headerFields.add(new Hpack.HeaderField(name, entry.getValue()));
		}
		// Advertise content-length when it is known, preferring a value the handler set explicitly (the
		// parent routes setHeader("Content-Length", …) into the `contentLength` field — e.g. write(File))
		// over the measured buffered-body length. When neither is known (streaming) it is omitted and
		// END_STREAM delimits the body. Critically it must never disagree with the actual DATA (§8.1.2.6).
		if (!hasContentLength && bodyLength >= 0) {
			headerFields.add(new Hpack.HeaderField("content-length", String.valueOf(bodyLength)));
		}
		for (Cookie cookie : cookies) {
			headerFields.add(new Hpack.HeaderField("set-cookie", cookie.toString()));
		}
		connection.sendHeaders(stream.streamId, headerFields, endStream);
	}
}
