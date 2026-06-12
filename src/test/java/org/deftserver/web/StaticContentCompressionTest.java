package org.deftserver.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import org.deftserver.util.HttpUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class StaticContentCompressionTest {

	private static HttpServer server;
	private static int PORT;
	private static File tempWebroot;

	@BeforeClass
	public static void setUp() throws Exception {
		tempWebroot = Files.createTempDirectory("dreft-static-test").toRealPath().toFile();

		writeFile(new File(tempWebroot, "index.html"), "<html><body>Hello Static Content Compression!</body></html>");
		
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 500; i++) {
			sb.append("console.log('Hello compression caching!');");
		}
		writeFile(new File(tempWebroot, "main.js"), sb.toString());
		writeFile(new File(tempWebroot, "main-stale.js"), sb.toString());
		
		StringBuilder sb2 = new StringBuilder();
		for (int i = 0; i < 1000; i++) {
			sb2.append("fake png content fake png content");
		}
		writeFile(new File(tempWebroot, "image.png"), sb2.toString());

		File subDir = new File(tempWebroot, "sub");
		subDir.mkdirs();
		
		StringBuilder css = new StringBuilder();
		for (int i = 0; i < 500; i++) {
			css.append("body { background-color: #f0f0f0; }");
		}
		writeFile(new File(subDir, "style.css"), css.toString());
		
		StringBuilder json = new StringBuilder();
		for (int i = 0; i < 500; i++) {
			json.append("{\"status\": \"ok\", \"data\": \"hello\"}");
		}
		writeFile(new File(subDir, "data.json"), json.toString());

		try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
			serverChannel.bind(new InetSocketAddress(0));
			PORT = serverChannel.socket().getLocalPort();
		}

		Application app = new Application(new HashMap<>());
		app.setStaticContentDir(tempWebroot.getAbsolutePath());

		server = new HttpServer(app);
		server.bind(PORT);
		server.start(1);
		TestServerSupport.awaitListening(PORT);
	}

	@AfterClass
	public static void tearDown() throws Exception {
		server.stop();
		deleteRecursive(tempWebroot);
		Thread.sleep(100);
	}

	private static void writeFile(File file, String content) throws IOException {
		try (FileOutputStream fos = new FileOutputStream(file)) {
			fos.write(content.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void deleteRecursive(File file) {
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursive(child);
				}
			}
		}
		file.delete();
	}

	private static class RawResponse {
		Map<String, String> headers = new HashMap<>();
		byte[] body;
		int statusCode;
	}

	private RawResponse sendRequest(String path, String acceptEncoding) throws Exception {
		try (java.net.Socket socket = new java.net.Socket("localhost", PORT)) {
			socket.setSoTimeout(3000);
			java.io.OutputStream out = socket.getOutputStream();
			StringBuilder req = new StringBuilder();
			req.append("GET ").append(path).append(" HTTP/1.1\r\n");
			req.append("Host: localhost\r\n");
			if (acceptEncoding != null) {
				req.append("Accept-Encoding: ").append(acceptEncoding).append("\r\n");
			}
			req.append("Connection: close\r\n\r\n");
			out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
			out.flush();

			java.io.InputStream in = socket.getInputStream();
			java.io.ByteArrayOutputStream headerBytes = new java.io.ByteArrayOutputStream();
			int b;
			while (true) {
				b = in.read();
				if (b == -1) {
					throw new java.io.EOFException("EOF while reading headers");
				}
				headerBytes.write(b);
				byte[] current = headerBytes.toByteArray();
				if (current.length >= 4 &&
					current[current.length - 4] == '\r' &&
					current[current.length - 3] == '\n' &&
					current[current.length - 2] == '\r' &&
					current[current.length - 1] == '\n') {
					break;
				}
			}
			String headerStr = headerBytes.toString(StandardCharsets.ISO_8859_1);
			RawResponse resp = new RawResponse();
			String[] lines = headerStr.split("\r\n");
			String statusLine = lines[0];
			resp.statusCode = Integer.parseInt(statusLine.split(" ")[1]);

			for (int i = 1; i < lines.length; i++) {
				int colon = lines[i].indexOf(":");
				if (colon > 0) {
					resp.headers.put(lines[i].substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), lines[i].substring(colon + 1).trim());
				}
			}

			if (resp.headers.containsKey("content-length")) {
				int len = Integer.parseInt(resp.headers.get("content-length"));
				byte[] bodyBytes = new byte[len];
				int read = 0;
				while (read < len) {
					int r = in.read(bodyBytes, read, len - read);
					if (r == -1) throw new java.io.EOFException("EOF in body");
					read += r;
				}
				resp.body = bodyBytes;
			} else {
				resp.body = new byte[0];
			}
			return resp;
		}
	}

	@Test
	public void testDefaultCompressionBrotli() throws Exception {
		if (!HttpUtil.isBrotliSupported()) {
			return;
		}
		RawResponse resp = sendRequest(tempWebroot.getAbsolutePath() + "/main.js", "br");
		assertEquals(200, resp.statusCode);
		assertEquals("br", resp.headers.get("content-encoding"));
		assertTrue(resp.headers.get("vary").contains("Accept-Encoding"));
		assertTrue(resp.headers.get("etag").endsWith("-br\""));
		
		File cachedFile = new File(tempWebroot, ".dreft/main.js.br");
		assertTrue(cachedFile.exists());
		
		byte[] decompressed = HttpUtil.decompress(resp.body, "br");
		String decompressedStr = new String(decompressed, StandardCharsets.UTF_8);
		assertEquals("console.log('Hello compression caching!');", decompressedStr.substring(0, 42));
	}

	@Test
	public void testDefaultCompressionZstd() throws Exception {
		if (!HttpUtil.isZstdSupported()) {
			return;
		}
		RawResponse resp = sendRequest(tempWebroot.getAbsolutePath() + "/main.js", "zstd");
		assertEquals(200, resp.statusCode);
		assertEquals("zstd", resp.headers.get("content-encoding"));
		
		File cachedFile = new File(tempWebroot, ".dreft/main.js.zstd");
		assertTrue(cachedFile.exists());
		
		byte[] decompressed = HttpUtil.decompress(resp.body, "zstd");
		String decompressedStr = new String(decompressed, StandardCharsets.UTF_8);
		assertEquals("console.log('Hello compression caching!');", decompressedStr.substring(0, 42));
	}

	@Test
	public void testDefaultCompressionGzip() throws Exception {
		RawResponse resp = sendRequest(tempWebroot.getAbsolutePath() + "/main.js", "gzip");
		assertEquals(200, resp.statusCode);
		assertEquals("gzip", resp.headers.get("content-encoding"));
		
		File cachedFile = new File(tempWebroot, ".dreft/main.js.gzip");
		assertTrue(cachedFile.exists());
		
		byte[] decompressed = HttpUtil.decompress(resp.body, "gzip");
		String decompressedStr = new String(decompressed, StandardCharsets.UTF_8);
		assertEquals("console.log('Hello compression caching!');", decompressedStr.substring(0, 42));
	}

	@Test
	public void testNoCompressionForPng() throws Exception {
		RawResponse resp = sendRequest(tempWebroot.getAbsolutePath() + "/image.png", "br, gzip");
		assertEquals(200, resp.statusCode);
		assertFalse(resp.headers.containsKey("content-encoding"));
		
		File cachedFile = new File(tempWebroot, ".dreft/image.png.br");
		assertFalse(cachedFile.exists());
		File cachedFileGz = new File(tempWebroot, ".dreft/image.png.gzip");
		assertFalse(cachedFileGz.exists());
	}

	@Test
	public void testDreftCfgDisable() throws Exception {
		File subDir = new File(tempWebroot, "sub");
		writeFile(new File(subDir, ".dreft-cfg"), "[compression]\nenabled=false\n");
		
		// Force config reload by sleeping to trigger the 2s throttle or deleting cache entry
		org.deftserver.web.handler.StaticContentHandler.getInstance().setStaticContentDir(tempWebroot.getAbsolutePath());
		
		RawResponse resp = sendRequest(tempWebroot.getAbsolutePath() + "/sub/style.css", "br, gzip");
		assertEquals(200, resp.statusCode);
		assertFalse(resp.headers.containsKey("content-encoding"));
		
		File cachedFile = new File(tempWebroot, ".dreft/sub/style.css.br");
		assertFalse(cachedFile.exists());
	}

	@Test
	public void testDreftCfgIncludeExclude() throws Exception {
		File subDir = new File(tempWebroot, "sub");
		writeFile(new File(subDir, ".dreft-cfg"), "[compression]\nenabled=true\ninclude=*.css\nexclude=*data*\n");
		
		org.deftserver.web.handler.StaticContentHandler.getInstance().setStaticContentDir(tempWebroot.getAbsolutePath());
		
		RawResponse resp1 = sendRequest(tempWebroot.getAbsolutePath() + "/sub/style.css", "gzip");
		assertEquals(200, resp1.statusCode);
		assertEquals("gzip", resp1.headers.get("content-encoding"));
		
		RawResponse resp2 = sendRequest(tempWebroot.getAbsolutePath() + "/sub/data.json", "gzip");
		assertEquals(200, resp2.statusCode);
		assertFalse(resp2.headers.containsKey("content-encoding"));
	}

	@Test
	public void testStaleCacheInvalidation() throws Exception {
		RawResponse resp1 = sendRequest(tempWebroot.getAbsolutePath() + "/main-stale.js", "gzip");
		assertEquals(200, resp1.statusCode);
		
		File cachedFile = new File(tempWebroot, ".dreft/main-stale.js.gzip");
		assertTrue(cachedFile.exists());
		long lastMod1 = cachedFile.lastModified();
		
		Thread.sleep(1010);
		
		writeFile(new File(tempWebroot, "main-stale.js"), "new modified content console.log('hello');".repeat(100));
		
		RawResponse resp2 = sendRequest(tempWebroot.getAbsolutePath() + "/main-stale.js", "gzip");
		assertEquals(200, resp2.statusCode);
		
		assertTrue(cachedFile.lastModified() > lastMod1);
		
		byte[] decompressed = HttpUtil.decompress(resp2.body, "gzip");
		String decompressedStr = new String(decompressed, StandardCharsets.UTF_8);
		assertTrue(decompressedStr.startsWith("new modified content"));
	}

	@Test
	public void testStartupCleanup() throws Exception {
		File cacheDir = new File(tempWebroot, ".dreft");
		File subDir = new File(cacheDir, "sub");
		subDir.mkdirs();
		
		File orphan = new File(subDir, "orphan.js.gzip");
		writeFile(orphan, "some orphan cached data");
		assertTrue(orphan.exists());
		
		org.deftserver.web.handler.StaticContentHandler.getInstance().setStaticContentDir(tempWebroot.getAbsolutePath());
		
		assertFalse(orphan.exists());
	}
}
