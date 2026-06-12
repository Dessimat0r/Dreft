package org.deftserver.web.http.http2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import org.deftserver.web.http.HttpRequest;

public class Http2Request extends HttpRequest {

	private final String bodyString;

	public Http2Request(String method, String path, Map<String, String> headers, byte[] bodyBytes) {
		super(method + " " + path + " HTTP/1.1", headers);
		this.complete = true;

		if (bodyBytes != null && bodyBytes.length > 0) {
			String contentEncoding = getHeader("content-encoding");
			byte[] decompressed = bodyBytes;
			if (contentEncoding != null && !contentEncoding.trim().isEmpty()) {
				try {
					decompressed = org.deftserver.util.HttpUtil.decompress(bodyBytes, contentEncoding);
				} catch (Exception e) {
				}
			}
			this.bodyString = new String(decompressed, java.nio.charset.StandardCharsets.ISO_8859_1);
			String contentTypeHeader = getHeader("content-type");
			if (contentTypeHeader != null) {
				String contentType = contentTypeHeader.trim().toLowerCase(java.util.Locale.ROOT);
				if (contentType.startsWith("multipart/form-data")) {
					this.multipart = true;
					int boundaryIdx = contentType.indexOf("boundary=");
					if (boundaryIdx != -1) {
						String boundary = contentType.substring(boundaryIdx + 9).trim();
						if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
							boundary = boundary.substring(1, boundary.length() - 1);
						}
						this.multipartBoundary = boundary;
						this.mpBoundaryBPre = ("\r\n--" + boundary).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
						this.mpBoundaryBStart = ("--" + boundary + "\r\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
						this.mpParts = new java.util.LinkedHashMap<>();
						this.um_mpParts = java.util.Collections.unmodifiableMap(this.mpParts);
						try {
							parseMultipartParts(ByteBuffer.wrap(decompressed));
						} catch (IOException e) {
						}
					}
				} else {
					this.multipart = false;
					this.postParameters = parseParameters2(this.bodyString);
				}
			}
		} else {
			this.bodyString = null;
		}
	}

	@Override
	public String getBody() {
		return bodyString;
	}
}
