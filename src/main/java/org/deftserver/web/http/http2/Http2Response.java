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

	public Http2Response(HttpProtocol protocol, Http2Connection connection, Http2Stream stream) {
		super(protocol, connection.getSelectionKey(), true, false);
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
		setHeader("Content-Length", String.valueOf(file.length()));
		runOnLoop(() -> {
			if (!headersCreated) {
				sendHeaders(false);
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
				sendHeaders(false);
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
				sendHeaders(false);
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
			if (!headersCreated) {
				boolean endStream = (buffer.size() == 0);
				sendHeaders(endStream);
				if (endStream) {
					return;
				}
			}
			byte[] data = buffer.toByteArray();
			buffer.reset();
			connection.sendData(stream.streamId, data, true);
		});
		return 0;
	}

	private void sendHeaders(boolean endStream) {
		headersCreated = true;
		List<Hpack.HeaderField> headerFields = new ArrayList<>();
		headerFields.add(new Hpack.HeaderField(":status", String.valueOf(statusCode)));
		for (Map.Entry<String, String> entry : headers.entrySet()) {
			String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
			if (name.equals("connection") || name.equals("keep-alive") || name.equals("transfer-encoding")) {
				continue;
			}
			headerFields.add(new Hpack.HeaderField(name, entry.getValue()));
		}
		if (contentLength != -1) {
			headerFields.add(new Hpack.HeaderField("content-length", String.valueOf(contentLength)));
		}
		for (Cookie cookie : cookies) {
			headerFields.add(new Hpack.HeaderField("set-cookie", cookie.toString()));
		}
		connection.sendHeaders(stream.streamId, headerFields, endStream && buffer.size() == 0);
	}
}
