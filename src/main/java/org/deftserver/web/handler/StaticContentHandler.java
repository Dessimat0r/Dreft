package org.deftserver.web.handler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import javax.activation.FileTypeMap;
import javax.activation.MimetypesFileTypeMap;

import org.deftserver.util.HttpUtil;
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
	private java.nio.file.Path cacheDir;

	private final static java.util.concurrent.ConcurrentHashMap<String, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();

	private static class FolderConfig {
		final long lastCheck;
		final long configLastModified;
		final boolean exists;
		final boolean enabled;
		final java.util.List<String> includePatterns;
		final java.util.List<String> excludePatterns;
		final java.util.List<String> algorithms;

		FolderConfig(long lastCheck, long configLastModified, boolean exists, boolean enabled,
					 java.util.List<String> includePatterns, java.util.List<String> excludePatterns,
					 java.util.List<String> algorithms) {
			this.lastCheck = lastCheck;
			this.configLastModified = configLastModified;
			this.exists = exists;
			this.enabled = enabled;
			this.includePatterns = includePatterns;
			this.excludePatterns = excludePatterns;
			this.algorithms = algorithms;
		}
	}

	private final static java.util.concurrent.ConcurrentHashMap<String, FolderConfig> configCache = new java.util.concurrent.ConcurrentHashMap<>();

	private static final String ETAG_SUFFIX_BR = "-br";
	private static final String ETAG_SUFFIX_ZSTD = "-zstd";
	private static final String ETAG_SUFFIX_GZIP = "-gzip";

	private static class FileMetadata {
		final long lastModified;
		final String lastModifiedStr;
		final String etag;
		final String mimeType;
		final long length;
		final long lastChecked;
		FileMetadata(long lastModified, String lastModifiedStr, String etag, String mimeType, long length, long lastChecked) {
			this.lastModified = lastModified;
			this.lastModifiedStr = lastModifiedStr;
			this.etag = etag;
			this.mimeType = mimeType;
			this.length = length;
			this.lastChecked = lastChecked;
		}
	}

	private final static java.util.concurrent.ConcurrentHashMap<String, FileMetadata> metadataCache = new java.util.concurrent.ConcurrentHashMap<>();
	private final static java.util.concurrent.ConcurrentHashMap<String, File> pathSafetyCache = new java.util.concurrent.ConcurrentHashMap<>();

	public static class MappedFileCacheEntry {
		public final java.nio.MappedByteBuffer buffer;
		public final long lastModified;
		public MappedFileCacheEntry(java.nio.MappedByteBuffer buffer, long lastModified) {
			this.buffer = buffer;
			this.lastModified = lastModified;
		}
	}

	public final static java.util.concurrent.ConcurrentHashMap<String, MappedFileCacheEntry> mmapCache = new java.util.concurrent.ConcurrentHashMap<>();
	public final static long MMAP_MAX_SIZE = 1024 * 1024;

	public static java.nio.ByteBuffer getMappedBuffer(File file) {
		long len = file.length();
		if (len > MMAP_MAX_SIZE) {
			return null;
		}
		String path = file.getAbsolutePath();
		long lastMod = file.lastModified();
		MappedFileCacheEntry entry = mmapCache.get(path);
		if (entry != null && entry.lastModified == lastMod) {
			return entry.buffer.duplicate();
		}
		try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
			java.nio.channels.FileChannel fc = raf.getChannel();
			java.nio.MappedByteBuffer mbb = fc.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0L, len);
			mmapCache.put(path, new MappedFileCacheEntry(mbb, lastMod));
			return mbb.duplicate();
		} catch (Exception e) {
			return null;
		}
	}

	private void runStartupCleanup() {
		if (cacheDir == null || !java.nio.file.Files.exists(cacheDir)) {
			return;
		}
		try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(cacheDir)) {
			stream.filter(java.nio.file.Files::isRegularFile)
				.forEach(path -> {
					String filename = path.getFileName().toString();
					String originalRelative = null;
					if (filename.endsWith(".br")) {
						originalRelative = cacheDir.relativize(path).toString();
						originalRelative = originalRelative.substring(0, originalRelative.length() - 3);
					} else if (filename.endsWith(".zstd")) {
						originalRelative = cacheDir.relativize(path).toString();
						originalRelative = originalRelative.substring(0, originalRelative.length() - 5);
					} else if (filename.endsWith(".gzip")) {
						originalRelative = cacheDir.relativize(path).toString();
						originalRelative = originalRelative.substring(0, originalRelative.length() - 5);
					} else if (filename.endsWith(".gz")) {
						originalRelative = cacheDir.relativize(path).toString();
						originalRelative = originalRelative.substring(0, originalRelative.length() - 3);
					}
					if (originalRelative != null) {
						java.nio.file.Path originalFile = staticContentRoot.resolve(originalRelative);
						if (!java.nio.file.Files.exists(originalFile)) {
							try {
								java.nio.file.Files.delete(path);
							} catch (IOException e) {
								logger.warn("Failed to delete orphaned cache file: {}", path, e);
							}
						}
					}
				});
		} catch (IOException e) {
			logger.warn("Failed to clean up cache directory: {}", cacheDir, e);
		}
	}

	public void setStaticContentDir(String staticContentDir) {
		this.staticContentDir = staticContentDir;
		if (staticContentDir != null) {
			this.staticContentRoot = java.nio.file.Path.of(staticContentDir).toAbsolutePath().normalize();
			this.cacheDir = this.staticContentRoot.resolve(".dreft");
			configCache.clear();
			metadataCache.clear();
			pathSafetyCache.clear();
			mmapCache.clear();
			runStartupCleanup();
		} else {
			this.staticContentRoot = null;
			this.cacheDir = null;
			configCache.clear();
			metadataCache.clear();
			pathSafetyCache.clear();
		}
	}

	public void setCacheDir(String cacheDir) {
		if (cacheDir != null) {
			this.cacheDir = java.nio.file.Path.of(cacheDir).toAbsolutePath().normalize();
		} else {
			this.cacheDir = this.staticContentRoot != null ? this.staticContentRoot.resolve(".dreft") : null;
		}
		configCache.clear();
		metadataCache.clear();
		pathSafetyCache.clear();
		mmapCache.clear();
		runStartupCleanup();
	}

	private static java.util.List<String> parseCommaList(String val) {
		java.util.List<String> list = new java.util.ArrayList<>();
		for (String s : val.split(",")) {
			s = s.trim();
			if (!s.isEmpty()) {
				list.add(s);
			}
		}
		return list;
	}

	private static java.util.Map<String, java.util.Map<String, String>> parseIni(File file) {
		java.util.Map<String, java.util.Map<String, String>> sections = new java.util.HashMap<>();
		if (!file.exists()) return sections;
		try (java.io.BufferedReader reader = new java.io.BufferedReader(
				new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
			String line;
			String currentSection = "";
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
					continue;
				}
				if (line.startsWith("[") && line.endsWith("]")) {
					currentSection = line.substring(1, line.length() - 1).trim().toLowerCase(java.util.Locale.ROOT);
					continue;
				}
				int eq = line.indexOf('=');
				if (eq != -1) {
					String key = line.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
					String val = line.substring(eq + 1).trim();
					sections.computeIfAbsent(currentSection, k -> new java.util.HashMap<>()).put(key, val);
				}
			}
		} catch (IOException e) {
			logger.error("Failed to parse config file: {}", file, e);
		}
		return sections;
	}

	private FolderConfig getFolderConfig(File folder) {
		String path = folder.getAbsolutePath();
		FolderConfig cached = configCache.get(path);
		long now = System.currentTimeMillis();
		if (cached != null && now - cached.lastCheck < 2000) {
			return cached;
		}

		File configFile = new File(folder, ".dreft-cfg");
		boolean exists = configFile.exists();
		long lastMod = exists ? configFile.lastModified() : 0L;

		if (cached != null && cached.exists == exists && cached.configLastModified == lastMod) {
			FolderConfig updated = new FolderConfig(now, lastMod, exists, cached.enabled,
				cached.includePatterns, cached.excludePatterns, cached.algorithms);
			configCache.put(path, updated);
			return updated;
		}

		boolean enabled = true;
		java.util.List<String> include = java.util.Collections.emptyList();
		java.util.List<String> exclude = java.util.Collections.emptyList();
		java.util.List<String> algos = java.util.Arrays.asList("br", "zstd", "gzip");

		if (exists) {
			java.util.Map<String, java.util.Map<String, String>> sections = parseIni(configFile);
			java.util.Map<String, String> comp = sections.get("compression");
			if (comp != null) {
				String enVal = comp.get("enabled");
				if (enVal != null) {
					enabled = Boolean.parseBoolean(enVal);
				}
				String incStr = comp.get("include");
				if (incStr != null) {
					include = parseCommaList(incStr);
				}
				String excStr = comp.get("exclude");
				if (excStr != null) {
					exclude = parseCommaList(excStr);
				}
				String algStr = comp.get("algorithms");
				if (algStr != null) {
					algos = parseCommaList(algStr);
				}
			}
		}

		FolderConfig newConfig = new FolderConfig(now, lastMod, exists, enabled, include, exclude, algos);
		configCache.put(path, newConfig);
		return newConfig;
	}

	private static boolean matchPattern(String filename, String pattern) {
		String fnLower = filename.toLowerCase(java.util.Locale.ROOT);
		String patLower = pattern.toLowerCase(java.util.Locale.ROOT);
		if (patLower.equals("*")) return true;
		if (patLower.startsWith("*") && !patLower.substring(1).contains("*")) {
			return fnLower.endsWith(patLower.substring(1));
		}
		if (patLower.endsWith("*") && !patLower.substring(0, patLower.length() - 1).contains("*")) {
			return fnLower.startsWith(patLower.substring(0, patLower.length() - 1));
		}
		String regex = patLower.replace(".", "\\.")
							  .replace("?", ".")
							  .replace("*", ".*");
		return fnLower.matches(regex);
	}

	private static void compressFile(File src, File dest, String encoding) throws IOException {
		File tempFile = new File(dest.getAbsolutePath() + ".tmp");
		java.nio.file.Files.createDirectories(dest.getParentFile().toPath());
		try {
			try (java.io.InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(src));
				 java.io.OutputStream out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(tempFile))) {
				
				java.io.OutputStream compressionOut;
				if ("gzip".equals(encoding)) {
					compressionOut = new java.util.zip.GZIPOutputStream(out);
				} else if ("zstd".equals(encoding)) {
					if (HttpUtil.isZstdSupported()) {
						compressionOut = HttpUtil.createZstdOutputStream(out);
					} else {
						throw new IOException("Zstd not supported");
					}
				} else if ("br".equals(encoding)) {
					if (HttpUtil.isBrotliSupported()) {
						compressionOut = HttpUtil.createBrotliOutputStream(out);
					} else {
						throw new IOException("Brotli not supported");
					}
				} else {
					throw new IllegalArgumentException("Unsupported encoding: " + encoding);
				}
				
				try (java.io.OutputStream finalOut = compressionOut) {
					byte[] buffer = new byte[8192];
					int bytesRead;
					while ((bytesRead = in.read(buffer)) != -1) {
						finalOut.write(buffer, 0, bytesRead);
					}
				}
			}
			try {
				java.nio.file.Files.move(
					tempFile.toPath(), 
					dest.toPath(), 
					java.nio.file.StandardCopyOption.REPLACE_EXISTING, 
					java.nio.file.StandardCopyOption.ATOMIC_MOVE
				);
			} catch (java.nio.file.AtomicMoveNotSupportedException amse) {
				java.nio.file.Files.move(
					tempFile.toPath(), 
					dest.toPath(), 
					java.nio.file.StandardCopyOption.REPLACE_EXISTING
				);
			}
		} catch (IOException e) {
			if (tempFile.exists()) {
				tempFile.delete();
			}
			throw e;
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
		java.util.Map.entry("webp", "image/webp"),
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

	/** The shared singleton instance (one per JVM; configured via {@link #setStaticContentDir}). */
	public static StaticContentHandler getInstance() {
		return instance;
	}

	/** Serves the requested file (with body) — see {@link #perform}. */
	@Override
	public void get(HttpRequest request, HttpResponse response) throws IOException {
		serveOffLoop(request, response, true);
	}

	/** Serves the requested file without a body (HEAD) — headers/Content-Length only. */
	@Override
	public void head(final HttpRequest request, final HttpResponse response) throws IOException {
		serveOffLoop(request, response, false);
	}

	/** GET/HEAD are served asynchronously: all the BLOCKING filesystem work (path resolution, symlink
	 *  {@code toRealPath} check, stat, and — crucially — reading the file body) runs on a virtual thread,
	 *  off the I/O-loop thread, so a slow/cold/network filesystem can never stall the reactor. Only the
	 *  (non-blocking) socket write is marshalled back to the loop. */
	@Override
	public boolean isMethodAsynchronous(org.deftserver.web.HttpVerb verb) {
		return verb == org.deftserver.web.HttpVerb.GET || verb == org.deftserver.web.HttpVerb.HEAD;
	}

	private void serveOffLoop(final HttpRequest request, final HttpResponse response, final boolean hasBody) throws IOException {
		if (response.getProtocol() == null) {
			// Direct call (e.g. a unit test) — there is no I/O loop to offload onto, so run synchronously
			// and let any HttpException (403/404/416) propagate, preserving the throw-on-error contract
			// those tests rely on.
			byte[][] bodyOut = new byte[1][];
			File[] streamFile = new File[1];
			long[] streamRange = new long[2];
			perform(request, response, hasBody, bodyOut, streamFile, streamRange);
			sendBody(response, bodyOut[0], streamFile[0], streamRange);
			return;
		}
		final org.deftserver.io.IOLoop ioLoop = response.getProtocol().getIOLoop();
		Thread.startVirtualThread(() -> {
			final byte[][] bodyOut = new byte[1][];
			final File[] streamFile = new File[1];
			final long[] streamRange = new long[2];
			try {
				perform(request, response, hasBody, bodyOut, streamFile, streamRange); // OFF the loop: stat/realpath/read
				ioLoop.addCallback(() -> {
					try {
						sendBody(response, bodyOut[0], streamFile[0], streamRange);
					} catch (Exception e) {
						logger.debug("static send failed: {}", e.getMessage());
						try { response.getProtocol().closeChannel(response.getChannel()); } catch (Exception ignore) {}
					}
				});
			} catch (HttpException he) {
				ioLoop.addCallback(() -> sendError(response, he.getStatusCode(), he.getLongHTMLMessage()));
			} catch (Throwable t) {
				logger.error("Unhandled error serving static content", t);
				ioLoop.addCallback(() -> sendError(response, 500, "Internal Server Error"));
			}
		});
	}

	/** Sends the prepared response body on the I/O-loop thread (raw — no gzip — preserving the headers
	 *  perform() set), then finalises. A small body was pre-read off-loop into {@code body}; a larger one
	 *  is streamed via the existing mmap/windowed {@code write(File)} path ({@code streamFile} set), whose
	 *  per-window disk I/O stays on the loop but is bounded. */
	private static void sendBody(HttpResponse response, byte[] body, File streamFile, long[] streamRange) throws IOException {
		if (body != null) {
			response.write(java.nio.ByteBuffer.wrap(body));
		} else if (streamFile != null) {
			if (streamRange[1] > 0) {
				response.write(streamFile, streamRange[0], streamRange[1]);
			} else {
				response.write(streamFile);
			}
		}
		response.finish();
	}

	/** Files at or below this size are read into a heap buffer off the I/O loop; larger ones keep the
	 *  existing mmap/windowed serving (its disk I/O stays on the loop, but big static downloads are rare).
	 *  Bounds the off-loop heap allocation. */
	private static final long OFFLOAD_HEAP_READ_MAX = 16L * 1024 * 1024;

	/** Marshalled-to-loop error send for the async path (mirrors the dispatcher's HttpException handling). */
	private static void sendError(HttpResponse response, int status, String body) {
		try {
			response.setStatusCode(status);
			response.setHeader("Content-Type", "text/plain; charset=utf-8");
			response.setHeader("X-Content-Type-Options", "nosniff");
			response.write(body);
			response.finish();
		} catch (Exception e) {
			try { response.getProtocol().closeChannel(response.getChannel()); } catch (Exception ignore) {}
		}
	}

	/**
	 * @param request the <code>HttpRequest</code>
	 * @param response the <code>HttpResponse</code> 
	 * @param hasBody <code>true</code> to write the message body; <code>false</code> oth	erwise.
	 */
	private void perform(final HttpRequest request, final HttpResponse response, boolean hasBody, byte[][] bodyOut, File[] streamFile, long[] streamRange) throws IOException {
		logger.debug("req: {}, resp: {}, body: {}", request, response, hasBody);
		final String path = request.getRequestedPath();
		final String safePath = org.deftserver.util.HttpUtil.escapeHtml(path);
		File file = pathSafetyCache.get(path);
		if (file == null) {
			Path requested = Path.of(path.substring(1)).normalize();	// remove the leading '/'
			if (requested.startsWith("..") || path.contains("\0")) {
				throw new HttpException(403, "Forbidden", "Requested resource: <tt>" + safePath +  "</tt> is not allowed.");
			}
			
			file = requested.toFile();
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
						throw new HttpException(403, "Forbidden", "Requested resource: <tt>" + safePath + "</tt> is not allowed.");
					}
					if (!fileReal.startsWith(rootReal)) {
						throw new HttpException(403, "Forbidden", "Requested resource: <tt>" + safePath + "</tt> is not allowed.");
					}
				}
			}
			if (file.exists()) {
				pathSafetyCache.put(path, file);
			}
		}

		logger.debug("file: {}", file);
		File serveFile = file;
		String baseEtagSuffix = "";
		String acceptEncoding = request.getHeader("Accept-Encoding");
		String chosenEncoding = null;

		if (acceptEncoding != null && cacheDir != null) {
			File parentDir = file.getParentFile();
			FolderConfig config = getFolderConfig(parentDir);
			if (config.enabled) {
				boolean matches = false;
				String filename = file.getName();
				if (!config.includePatterns.isEmpty()) {
					for (String pat : config.includePatterns) {
						if (matchPattern(filename, pat)) {
							matches = true;
							break;
						}
					}
				} else {
					String ext = "";
					int dot = filename.lastIndexOf('.');
					if (dot != -1) {
						ext = filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
					}
					String mimeType = EXT_TO_MIME.get(ext);
					if (mimeType != null && (
						mimeType.contains("text") ||
						mimeType.contains("json") ||
						mimeType.contains("xml") ||
						mimeType.contains("javascript") ||
						mimeType.equals("application/wasm")
					)) {
						matches = true;
					}
				}

				if (matches) {
					for (String pat : config.excludePatterns) {
						if (matchPattern(filename, pat)) {
							matches = false;
							break;
						}
					}
				}

				if (matches) {
					double bestQ = 0.0;
					String bestAlgo = null;
					int bestPref = -1;
					int len = acceptEncoding.length();
					int idx = 0;
					while (idx < len) {
						int nextComma = acceptEncoding.indexOf(',', idx);
						int endSegment = (nextComma == -1) ? len : nextComma;
						
						int firstSemi = acceptEncoding.indexOf(';', idx);
						int endType = (firstSemi == -1 || firstSemi > endSegment) ? endSegment : firstSemi;
						
						String encoding = acceptEncoding.substring(idx, endType).trim().toLowerCase(java.util.Locale.ROOT);
						if (!encoding.isEmpty() && config.algorithms.contains(encoding)) {
							double q = 1.0;
							if (firstSemi != -1 && firstSemi < endSegment) {
								int pIdx = firstSemi + 1;
								while (pIdx < endSegment) {
									int nextSemi = acceptEncoding.indexOf(';', pIdx);
									int endParam = (nextSemi == -1 || nextSemi > endSegment) ? endSegment : nextSemi;
									String param = acceptEncoding.substring(pIdx, endParam).trim();
									if (param.startsWith("q=")) {
										try {
											q = Double.parseDouble(param.substring(2).trim());
										} catch (NumberFormatException e) {
											q = 0.0;
										}
										if (Double.isNaN(q)) {
											q = 0.0;
										} else if (q > 1.0) {
											q = 1.0;
										} else if (q < 0.0) {
											q = 0.0;
										}
									}
									pIdx = endParam + 1;
								}
							}
							if (q > 0.0) {
								int pref = 0;
								if (encoding.equals("br") && HttpUtil.isBrotliSupported()) {
									pref = 3;
								} else if (encoding.equals("zstd") && HttpUtil.isZstdSupported()) {
									pref = 2;
								} else if (encoding.equals("gzip")) {
									pref = 1;
								}
								if (pref > 0) {
									if (q > bestQ) {
										bestQ = q;
										bestAlgo = encoding;
										bestPref = pref;
									} else if (q == bestQ && pref > bestPref) {
										bestAlgo = encoding;
										bestPref = pref;
									}
								}
							}
						}
						idx = endSegment + 1;
					}
					chosenEncoding = bestAlgo;
				}
			}
		}

		if (chosenEncoding != null) {
			try {
				Path fileReal = file.toPath().toRealPath();
				Path rootReal = staticContentRoot.toRealPath();
				Path relativePath = rootReal.relativize(fileReal);
				File cachedFile = cacheDir.resolve(relativePath.toString() + "." + chosenEncoding).toFile();
				
				if (!cachedFile.exists() || cachedFile.lastModified() < file.lastModified()) {
					String canonicalPath = cachedFile.getCanonicalPath();
					Object lock = locks.computeIfAbsent(canonicalPath, k -> new Object());
					synchronized (lock) {
						try {
							if (!cachedFile.exists() || cachedFile.lastModified() < file.lastModified()) {
								compressFile(file, cachedFile, chosenEncoding);
							}
						} finally {
							locks.remove(canonicalPath, lock);
						}
					}
				}
				serveFile = cachedFile;
				if ("br".equals(chosenEncoding)) {
					baseEtagSuffix = ETAG_SUFFIX_BR;
				} else if ("zstd".equals(chosenEncoding)) {
					baseEtagSuffix = ETAG_SUFFIX_ZSTD;
				} else if ("gzip".equals(chosenEncoding)) {
					baseEtagSuffix = ETAG_SUFFIX_GZIP;
				} else {
					baseEtagSuffix = "-" + chosenEncoding;
				}
				response.setHeader("Content-Encoding", chosenEncoding);
				response.addVary("Accept-Encoding");
			} catch (IOException e) {
				logger.error("Failed to resolve or compress file: {}", file, e);
				serveFile = file;
				chosenEncoding = null;
			}
		}

		String absPath = file.getAbsolutePath();
		long now = System.currentTimeMillis();
		FileMetadata meta = metadataCache.get(absPath);
		if (meta == null || now - meta.lastChecked >= 1500) {
			long lastModified = file.lastModified();
			if (meta == null || meta.lastModified != lastModified) {
				String lastModifiedStr = org.deftserver.util.DateUtil.formatToRFC1123((lastModified / 1000) * 1000);
				String etagValue = org.deftserver.util.HttpUtil.getEtag(response.getProtocol().getIOLoop().getMd5(), file);
				String filename = file.getName();
				int dot = filename.lastIndexOf('.');
				String ext = "";
				if (dot != -1) {
					ext = filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
				}
				if (ext.equals("gz") && filename.regionMatches(true, filename.length() - 7, ".tar.gz", 0, 7)) {
					ext = "tar.gz";
				} else if (ext.equals("bz2") && filename.regionMatches(true, filename.length() - 8, ".tar.bz2", 0, 8)) {
					ext = "tar.bz2";
				} else if (ext.equals("xz") && filename.regionMatches(true, filename.length() - 7, ".tar.xz", 0, 7)) {
					ext = "tar.xz";
				}
				String resolvedMime = EXT_TO_MIME.get(ext);
				if (resolvedMime == null) {
					try {
						resolvedMime = java.nio.file.Files.probeContentType(file.toPath());
					} catch (IOException e) {
					}
				}
				if (resolvedMime == null) {
					resolvedMime = mimeTypeMap.getContentType(file);
				}
				meta = new FileMetadata(lastModified, lastModifiedStr, etagValue, resolvedMime, file.length(), now);
				metadataCache.put(absPath, meta);
			} else {
				meta = new FileMetadata(meta.lastModified, meta.lastModifiedStr, meta.etag, meta.mimeType, meta.length, now);
				metadataCache.put(absPath, meta);
			}
		}

		final long lastModifiedSec = (meta.lastModified / 1000) * 1000;
		response.setHeader("Last-Modified", meta.lastModifiedStr);
		response.setHeader("Cache-Control", "public");
		response.setHeader("Accept-Ranges", "bytes");

		String etag = meta.etag;
		if (!etag.isEmpty()) {
			if (!baseEtagSuffix.isEmpty()) {
				if (etag.endsWith("\"")) {
					etag = etag.substring(0, etag.length() - 1) + baseEtagSuffix + "\"";
				} else {
					etag = etag + baseEtagSuffix;
				}
			}
			response.setHeader("ETag", etag);
		}

		String mimeType = meta.mimeType;
		if (mimeType == null) {
			mimeType = "application/octet-stream";
		}
		if (mimeType.startsWith("text/")) {
			if (!mimeType.contains("charset")) {
				mimeType += "; charset=utf-8";
			}
		}
		response.setHeader("Content-Type", mimeType);

		final String ifMatch = request.getHeader("If-Match");
		final String ifNoneMatch = request.getHeader("If-None-Match");
		final String ifUnmodifiedSince = request.getHeader("If-Unmodified-Since");
		final String ifModifiedSince = request.getHeader("If-Modified-Since");

		if (ifMatch != null && !etag.isEmpty()) {
			String im = ifMatch.trim();
			if (!im.equals("*") && !etagListContains(im, etag, true)) {
				response.setStatusCode(412);
				logger.debug("if-match precondition failed");
				return;
			}
		}

		if (ifUnmodifiedSince != null && ifMatch == null) {
			long ius = parseHttpDateOrEpoch(ifUnmodifiedSince);
			if (ius != -1 && lastModifiedSec > ius) {
				response.setStatusCode(412);
				logger.debug("if-unmodified-since precondition failed");
				return;
			}
		}

		if (ifNoneMatch != null && !etag.isEmpty()) {
			String clientEtag = ifNoneMatch.trim();
			if (clientEtag.equals("*") || etagListContains(clientEtag, etag, false)) {
				response.setStatusCode(304);
				logger.debug("not modified (etag matched)");
				return;
			}
		}

		if (ifModifiedSince != null && ifNoneMatch == null) {
			long ims = parseHttpDateOrEpoch(ifModifiedSince);
			if (ims != -1 && lastModifiedSec <= ims) {
				response.setStatusCode(304);
				logger.debug("not modified");
				return;
			}
		}

		String rangeHeader = request.getHeader("Range");
		boolean rangeApplicable = true;
		String ifRange = request.getHeader("If-Range");
		if (ifRange != null && rangeHeader != null) {
			ifRange = ifRange.trim();
			if (ifRange.startsWith("\"") || ifRange.startsWith("W/")) {
				rangeApplicable = ifRange.equals(etag);
			} else {
				long irTime = org.deftserver.util.DateUtil.parseRFC1123ToMillis(ifRange);
				rangeApplicable = irTime != -1 && lastModifiedSec <= irTime;
			}
		}
		if (rangeApplicable && rangeHeader != null && rangeHeader.startsWith("bytes=")) {
			String rangeVal = rangeHeader.substring(6).trim();
			if (!rangeVal.contains(",")) {
				long fileLength = serveFile.length();
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
						if (rangeLength <= OFFLOAD_HEAP_READ_MAX) {
							// Read the requested range OFF the loop into a heap buffer; the loop sends it raw.
							byte[] rangeBytes = new byte[(int) rangeLength];
							try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(serveFile, "r")) {
								raf.seek(start);
								raf.readFully(rangeBytes);
							}
							bodyOut[0] = rangeBytes;
						} else {
							// Large range — stream via the existing mmap/windowed path on the loop.
							streamFile[0] = serveFile;
							streamRange[0] = start;
							streamRange[1] = rangeLength;
						}
					}
					return;
				}
			}
		}

		if (hasBody) {
			// Read the file body OFF the loop into a heap buffer; the loop sends it raw (write(ByteBuffer)
			// flushes headers+body without gzip — static manages its own .dreft-cfg compression). The
			// Content-Length the existing code set stays authoritative. Large files keep the existing
			// mmap/windowed serving (bounded per-window) rather than reading the whole thing into heap.
			response.setHeader("Content-Length", String.valueOf(serveFile.length()));
			if (serveFile.length() <= OFFLOAD_HEAP_READ_MAX) {
				bodyOut[0] = java.nio.file.Files.readAllBytes(serveFile.toPath());
			} else {
				streamFile[0] = serveFile;
				streamRange[1] = -1; // full file (not a range)
			}
		} else {
			response.setHeader("Content-Length", String.valueOf(serveFile.length()));
		}
	}

	/**
	 * Whether the comma-separated list of entity-tags in {@code headerValue} contains the current
	 * (strong, quoted) {@code currentStrongEtag}. With {@code strongComparison} (If-Match) a weak
	 * ({@code W/}) client tag never matches; without it (If-None-Match) the weak indicator is ignored
	 * (RFC 9110 §13.1.1/§13.1.2 / §8.8.3.2).
	 */
	private static boolean etagListContains(String headerValue, String currentStrongEtag, boolean strongComparison) {
		int len = headerValue.length();
		int i = 0;
		while (i < len) {
			while (i < len && headerValue.charAt(i) == ' ') i++;
			int start = i;
			while (i < len && headerValue.charAt(i) != ',') i++;
			int end = i;
			while (end > start && headerValue.charAt(end - 1) == ' ') end--;
			
			if (end > start) {
				boolean weak = false;
				int candStart = start;
				if (end - start >= 2 && headerValue.charAt(start) == 'W' && headerValue.charAt(start + 1) == '/') {
					weak = true;
					candStart += 2;
				}
				if (!(weak && strongComparison)) {
					int candLen = end - candStart;
					if (candLen == currentStrongEtag.length() && headerValue.regionMatches(candStart, currentStrongEtag, 0, candLen)) {
						return true;
					}
				}
			}
			i++;
		}
		return false;
	}

	/** Parses an HTTP date (RFC 1123), falling back to a bare epoch-millis value some clients send;
	 *  returns -1 if neither parses (so the caller treats the precondition as not evaluable). */
	private static long parseHttpDateOrEpoch(String value) {
		long t = org.deftserver.util.DateUtil.parseRFC1123ToMillis(value);
		if (t == -1) {
			try {
				t = Long.parseLong(value.trim());
			} catch (NumberFormatException nfe) {
				return -1;
			}
		}
		return t;
	}
}
