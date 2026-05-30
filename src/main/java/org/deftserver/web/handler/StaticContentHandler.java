package org.deftserver.web.handler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import javax.activation.FileTypeMap;
import javax.activation.MimetypesFileTypeMap;

import org.deftserver.web.http.HttpException;
import org.deftserver.web.http.HttpRequest;
import org.deftserver.web.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *	A RequestHandler that serves static content (files) from a predefined directory. 
 *
 *	"Cache-Control: public" indicates that the response MAY be cached by any cache, even if it would normally be 
 *  non-cacheable or cacheable only within a non- shared cache.
 *
 */
public class StaticContentHandler extends RequestHandler {

	private final static Logger logger = LoggerFactory.getLogger(StaticContentHandler.class);

	private final static StaticContentHandler instance = new StaticContentHandler();

	private String staticContentDir;
	private java.nio.file.Path staticContentRoot;

	public void setStaticContentDir(String staticContentDir) {
		this.staticContentDir = staticContentDir;
		if (staticContentDir != null) {
			this.staticContentRoot = java.nio.file.Path.of(staticContentDir).toAbsolutePath().normalize();
		} else {
			this.staticContentRoot = null;
		}
	}

	private final static java.util.Map<String, String> EXT_TO_MIME = java.util.Map.ofEntries(
		java.util.Map.entry("html", "text/html"),
		java.util.Map.entry("htm", "text/html"),
		java.util.Map.entry("css", "text/css"),
		java.util.Map.entry("js", "application/javascript"),
		java.util.Map.entry("mjs", "application/javascript"),
		java.util.Map.entry("cjs", "application/javascript"),
		java.util.Map.entry("json", "application/json"),
		java.util.Map.entry("png", "image/png"),
		java.util.Map.entry("jpg", "image/jpeg"),
		java.util.Map.entry("jpeg", "image/jpeg"),
		java.util.Map.entry("gif", "image/gif"),
		java.util.Map.entry("svg", "image/svg+xml"),
		java.util.Map.entry("tif", "image/tiff"),
		java.util.Map.entry("tiff", "image/tiff"),
		java.util.Map.entry("txt", "text/plain"),
		java.util.Map.entry("text", "text/plain"),
		java.util.Map.entry("md", "text/markdown"),
		java.util.Map.entry("markdown", "text/markdown"),
		java.util.Map.entry("yml", "application/x-yaml"),
		java.util.Map.entry("yaml", "application/x-yaml"),
		java.util.Map.entry("ico", "image/x-icon"),
		java.util.Map.entry("xml", "application/xml"),
		java.util.Map.entry("xhtml", "application/xhtml+xml"),
		java.util.Map.entry("xht", "application/xhtml+xml"),
		java.util.Map.entry("pdf", "application/pdf"),
		java.util.Map.entry("zip", "application/zip"),
		java.util.Map.entry("mpeg", "video/mpeg"),
		java.util.Map.entry("mpg", "video/mpeg"),
		java.util.Map.entry("mp4", "video/mp4"),
		java.util.Map.entry("m4v", "video/mp4"),
		java.util.Map.entry("webm", "video/webm"),
		java.util.Map.entry("mov", "video/quicktime"),
		java.util.Map.entry("wav", "audio/wav"),
		java.util.Map.entry("mkv", "video/x-matroska"),
		java.util.Map.entry("mp3", "audio/mpeg"),
		java.util.Map.entry("ogg", "audio/ogg"),
		java.util.Map.entry("ogv", "video/ogg"),
		java.util.Map.entry("weba", "audio/webm"),
		java.util.Map.entry("aac", "audio/aac"),
		java.util.Map.entry("flac", "audio/flac"),
		java.util.Map.entry("webmanifest", "application/manifest+json"),
		java.util.Map.entry("woff", "font/woff"),
		java.util.Map.entry("woff2", "font/woff2"),
		java.util.Map.entry("ttf", "font/ttf"),
		java.util.Map.entry("otf", "font/otf"),
		java.util.Map.entry("wasm", "application/wasm"),
		java.util.Map.entry("csv", "text/csv"),
		java.util.Map.entry("7z", "application/x-7z-compressed"),
		java.util.Map.entry("tar", "application/x-tar"),
		java.util.Map.entry("gz", "application/gzip"),
		java.util.Map.entry("rar", "application/vnd.rar"),
		java.util.Map.entry("tar.gz", "application/x-gtar"),
		java.util.Map.entry("tgz", "application/x-gtar"),
		java.util.Map.entry("tar.bz2", "application/x-bzip2"),
		java.util.Map.entry("tbz2", "application/x-bzip2"),
		java.util.Map.entry("tar.xz", "application/x-xz"),
		java.util.Map.entry("txz", "application/x-xz"),
		java.util.Map.entry("bz2", "application/x-bzip2"),
		java.util.Map.entry("xz", "application/x-xz")
	);

	private final FileTypeMap mimeTypeMap =  MimetypesFileTypeMap.getDefaultFileTypeMap();

	public static StaticContentHandler getInstance() {
		return instance;
	}

	/** {inheritDoc} */
	@Override
	public void get(HttpRequest request, HttpResponse response) throws IOException {
		this.perform(request, response, true);
	}
	
	/** {inheritDoc} */
	@Override
	public void head(final HttpRequest request, final HttpResponse response) throws IOException {
		this.perform(request, response, false);
	}

	/**
	 * @param request the <code>HttpRequest</code>
	 * @param response the <code>HttpResponse</code> 
	 * @param hasBody <code>true</code> to write the message body; <code>false</code> oth	erwise.
	 */
	private void perform(final HttpRequest request, final HttpResponse response, boolean hasBody) throws IOException {
		logger.debug("req: {}, resp: {}, body: {}", request, response, hasBody);
		final String path = request.getRequestedPath();
		Path requested = Path.of(path.substring(1)).normalize();	// remove the leading '/'
		if (requested.startsWith("..") || path.contains("\0")) {
			throw new HttpException(403, "Forbidden", "Requested resource: <tt>" + path +  "</tt> is not allowed.");
		}
		
		File file = requested.toFile();
		if (staticContentDir != null && staticContentRoot != null) {
			File candidate = null;
			if (file.exists()) {
				candidate = file;
			} else {
				Path absoluteRequested = Path.of(path).normalize();
				if (absoluteRequested.isAbsolute() && !absoluteRequested.startsWith("..")) {
					File absoluteFile = absoluteRequested.toFile();
					if (absoluteFile.exists()) {
						file = absoluteFile;
						candidate = absoluteFile;
					}
				}
			}

			if (candidate != null) {
				// Resolve symlinks (toRealPath) on BOTH sides before the boundary check: a
				// lexical normalize() would let a symlink inside the webroot that points outside
				// it (e.g. link -> /etc) escape the root. Compare real paths instead.
				Path fileReal;
				Path rootReal;
				try {
					fileReal = candidate.toPath().toRealPath();
					rootReal = staticContentRoot.toRealPath();
				} catch (IOException e) {
					// Can't safely resolve the path — deny rather than risk an escape.
					throw new HttpException(403, "Forbidden", "Requested resource: <tt>" + path + "</tt> is not allowed.");
				}
				if (!fileReal.startsWith(rootReal)) {
					throw new HttpException(403, "Forbidden", "Requested resource: <tt>" + path + "</tt> is not allowed.");
				}
			}
		}

		logger.debug("file: {}", file);
		if (!file.exists()) {
			throw new HttpException(404, "Not found", "Requested resource: <tt>" + path +  "</tt> was not found.");
		} else if (!file.isFile()) {
			throw new HttpException(403, "Not a file", "Requested resource: <tt>" + path +  "</tt> is not a file.");
		}

		final long lastModified = file.lastModified();
		final long lastModifiedSec = (lastModified / 1000) * 1000;
		response.setHeader("Last-Modified", org.deftserver.util.DateUtil.formatToRFC1123(lastModifiedSec));
		response.setHeader("Cache-Control", "public");
		response.setHeader("Accept-Ranges", "bytes");

		final String etag = org.deftserver.util.HttpUtil.getEtag(file);
		if (!etag.isEmpty()) {
			response.setHeader("ETag", etag);
		}

		String mimeType = null;
		try {
			mimeType = java.nio.file.Files.probeContentType(file.toPath());
		} catch (IOException e) {
			// ignore and let fallback resolve
		}
		
		// Fallback to extension maps if probe returned null or a generic octet-stream
		if (mimeType == null || "application/octet-stream".equals(mimeType)) {
			String ext = "";
			String filenameLower = file.getName().toLowerCase(java.util.Locale.ROOT);
			if (filenameLower.endsWith(".tar.gz")) {
				ext = "tar.gz";
			} else if (filenameLower.endsWith(".tar.bz2")) {
				ext = "tar.bz2";
			} else if (filenameLower.endsWith(".tar.xz")) {
				ext = "tar.xz";
			} else {
				int dot = filenameLower.lastIndexOf('.');
				if (dot != -1) {
					ext = filenameLower.substring(dot + 1);
				}
			}
			mimeType = EXT_TO_MIME.get(ext);
		}
		
		if (mimeType == null) {
			mimeType = mimeTypeMap.getContentType(file);
		}
		if (mimeType.startsWith("text/")) {
			if (!mimeType.contains("charset")) {
				mimeType += "; charset=utf-8";
			}
		}
		response.setHeader("Content-Type", mimeType);

		// ETag Cache Validation
		final String ifNoneMatch = request.getHeader("If-None-Match");
		if (ifNoneMatch != null && !etag.isEmpty()) {
			String clientEtag = ifNoneMatch.trim();
			if (clientEtag.equals(etag) || clientEtag.equals("W/" + etag)) {
				response.setStatusCode(304);	//Not Modified
				logger.debug("not modified (etag matched)");
				return;
			}
		}

		// Last-Modified Cache Validation. RFC 9110 §13.1.3: If-Modified-Since MUST be ignored when
		// If-None-Match is present (the entity-tag is the stronger validator and takes precedence).
		final String ifModifiedSince = request.getHeader("If-Modified-Since");
		if (ifModifiedSince != null && ifNoneMatch == null) {
			long ims = org.deftserver.util.DateUtil.parseRFC1123ToMillis(ifModifiedSince);
			if (ims == -1) {
				try {
					ims = Long.parseLong(ifModifiedSince);
				} catch (NumberFormatException nfe) {
					// ignore
				}
			}
			if (ims != -1 && lastModifiedSec <= ims) {
				response.setStatusCode(304);	//Not Modified
				logger.debug("not modified");
				return;
			}
		}

		// Handle Range requests. If-Range (RFC 9110 §13.1.5): only honour the Range when the
		// client's validator still matches the current representation; otherwise serve the whole
		// file (200) so the client re-syncs. ETag comparison for If-Range is strong-only, so a
		// weak ("W/") tag never enables the range.
		String rangeHeader = request.getHeader("Range");
		boolean rangeApplicable = true;
		String ifRange = request.getHeader("If-Range");
		if (ifRange != null && rangeHeader != null) {
			ifRange = ifRange.trim();
			if (ifRange.startsWith("\"") || ifRange.startsWith("W/")) {
				rangeApplicable = ifRange.equals(etag); // etag is a strong, quoted tag
			} else {
				long irTime = org.deftserver.util.DateUtil.parseRFC1123ToMillis(ifRange);
				rangeApplicable = irTime != -1 && lastModifiedSec <= irTime;
			}
		}
		if (rangeApplicable && rangeHeader != null && rangeHeader.startsWith("bytes=")) {
			String rangeVal = rangeHeader.substring(6).trim();
			if (!rangeVal.contains(",")) { // single range only
				long fileLength = file.length();
				long start = -1;
				long end = -1;
				boolean valid = true;
				
				int dashIdx = rangeVal.indexOf('-');
				if (dashIdx != -1) {
					String startStr = rangeVal.substring(0, dashIdx).trim();
					String endStr = rangeVal.substring(dashIdx + 1).trim();
					try {
						if (startStr.isEmpty()) {
							long suffixLen = Long.parseLong(endStr);
							start = fileLength - suffixLen;
							if (start < 0) start = 0;
							end = fileLength - 1;
						} else if (endStr.isEmpty()) {
							start = Long.parseLong(startStr);
							end = fileLength - 1;
						} else {
							start = Long.parseLong(startStr);
							end = Long.parseLong(endStr);
						}
					} catch (NumberFormatException nfe) {
						valid = false;
					}
				} else {
					valid = false;
				}
				
				if (valid) {
					if (start < 0 || start >= fileLength || end < start) {
						response.setStatusCode(416);
						response.setHeader("Content-Range", "bytes */" + fileLength);
						response.setHeader("Content-Length", "0");
						return;
					}
					
					if (end >= fileLength) {
						end = fileLength - 1;
					}
					long rangeLength = end - start + 1;
					response.setStatusCode(206);
					response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
					response.setHeader("Content-Length", String.valueOf(rangeLength));
					if (hasBody) {
						response.write(file, start, rangeLength);
					}
					return;
				}
			}
		}

		if (hasBody) {
			response.write(file);
		} else {
			// HEAD: report the same Content-Length a GET would, without sending the body.
			response.setHeader("Content-Length", String.valueOf(file.length()));
		}
	}
}
