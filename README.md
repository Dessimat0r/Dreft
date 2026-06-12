# Dreft

**Dreft** is a single/multi-threaded, asynchronous, event-driven HTTP server library for the JVM — a hardened fork of [Deft] (inspired by [Tornado]). Written in **Java 25**, it uses Java NIO selectors for the event loop and virtual threads for adaptive handler offloading.

[Deft]: https://github.com/rschildmeijer/deft
[Tornado]: http://github.com/facebook/tornado

## Features

### Core
- **NIO reactor pattern** — `IOLoop` owns a `Selector`; `HttpServer.start(n)` runs `n` independent loops sharing one `ServerSocketChannel`
- **Single path for connection cleanup** — all channel closing goes through `HttpProtocol.closeChannel`, maintaining `activeChannels` for connection limits
- **Adaptive virtual-thread offloading** — synchronous handlers that exceed a time threshold are migrated to virtual threads; `finish()` is marshalled back to the I/O loop via `IOLoop.addCallback`
- **Thread-safe cross-thread entry point** — `IOLoop.addCallback(AsyncCallback)` is the sole mechanism for off-loop interaction
- **JMX instrumentation** — debuggable callback/ timeout managers exposed as MBeans
- **PriorityQueue-based timeout manager** — O(1) cancel, amortised compaction, separate keep-alive index

### HTTP/1.1 Compliance
- Request-line, header, and body parsing per RFC 9110/9112
- **Content-Length** and **Transfer-Encoding: chunked** body framing (with rejection of conflicting/duplicate framing — request-smuggling defense)
- **Chunked trailers** — parsed and exposed with strict header-field filtering
- **Expect: 100-continue** — early size check and `100 Continue` / early `413` rejection
- **Host header validation** — missing, empty, multiple, and absolute-URI authority matching (RFC 9112 §3.2.1)
- **HEAD requests** — automatic framework-level delegation to `get()` with body suppression
- **OPTIONS \*** — server-wide `Allow` header response
- **HTTP version validation** — rejects unsupported versions (`HTTP/2.0`) with `505`
- **Conditional requests** — `If-None-Match` / `If-Match` (quoted ETags, weak comparison), `If-Modified-Since`
- **Range requests** — `Range: bytes=...` on static files, `206 Partial Content`, suffix ranges
- **Content negotiation** — `Accept`, `Accept-Encoding`, `Accept-Language`, `Accept-Charset` parsing with quality values (`q=0.0` rejection)
- **Response compression** — **gzip** (JDK built-in), **Brotli** (brotjli), **Zstd** (zstd-jni)
- **Vary header management** — `Vary: Origin` on CORS, `Vary: Accept-Encoding` on gzip, merge-aware `addVary()`
- **Connection management** — keep-alive (HTTP/1.1 default), `Connection: close` enforcement, max keep-alive requests cap

### Security & Hardening
- **Path traversal defense** — percent-decoded URL normalization, null-byte rejection, `..` segment blocking (`403`), `toRealPath()` symlink resolution
- **Request smuggling prevention** — obs-fold rejection, whitespace-before-colon rejection, control-char rejection, strict `Transfer-Encoding` last-coding enforcement, lenient Content-Length rejection
- **Parser DoS protection** — all collections bounded: ≤100 header lines, ≤64 KiB header block, ≤16 MiB body, ≤10k multipart parts, ≤10k parameters, ≤32 param-nesting depth, ≤1000 keep-alive requests
- **Memory amplification prevention** — O(n) char-scan parameter parsing (no eager split), bounded header/part/trailer counts, geometric buffer growth
- **Bounded non-blocking writes** — `writeFully`/`writeBlocking` helpers with 30 s stall timeout prevent infinite CPU spin on stalled sockets
- **Error containment** — per-channel exception catching in the selector loop, `StackOverflowError` and `LinkageError` recovery, handler runtime exceptions → 500
- **Cookie injection prevention** — control-char rejection in name, value, domain, path
- **Per-IP connection limiting** — configurable `maxConnectionsPerIp`
- **WebSocket limits** — ≤16 MiB frame payload, ≤125 B pong, strict mask enforcement, control-char rejection, idle timeout (5 min default)

### WebSocket (RFC 6455)
- Frame parsing with masking enforcement
- Text and binary frame support
- Continuation-frame reassembly (capped buffer)
- Close handshake (status code echo)
- Ping/Pong with payload limits
- Secure (WSS) over TLS
- Separate idle timeout from HTTP keep-alive

### Static File Serving
- Extension-based MIME mapping with `Files.probeContentType` fallback
- Composite archive extensions (`.tar.gz`, `.tar.bz2`, `.tar.xz`)
- Range requests with `MappedByteBuffer`
- ETag and `Last-Modified` support
- `HEAD` with correct `Content-Length`
- Directory traversal and symlink boundary enforcement
- **Per-directory configuration (`.dreft-cfg`)** — analogous to Apache's `.htaccess`, enabling directory-level settings such as compression algorithms, enabled state, and glob inclusions/exclusions, loaded dynamically with a 2-second reload TTL.


### TLS
- Non-blocking `SSLEngine` with growable buffers
- Delegated tasks run on virtual threads
- WebSocket over TLS (WSS)
- Configurable via `SSLContext` or keystore path

### CORS
- Automatic preflight (`OPTIONS`) interception via `CorsPreflightRequestHandler`
- Configurable origins, methods, headers, credentials via `CorsConfig`
- `Vary: Origin` set on all reflected-CORS responses (merged with handler-set `Vary`)
- `CorsPreflightRequestHandler` returns `204 No Content`

### HTTP Client
- Non-blocking `AsynchronousHttpClient` with `AsyncResult<Response>` callbacks
- Redirect following (with `Location` resolution via `java.net.URI`)
- Custom `Request` objects (method, headers, body, redirect policy)
- Connection failure propagation to `onFailure` callback

## Architecture

```
Application
  ├── exact-path handlers (Map<String, RequestHandler>)
  ├── regex-path handlers (List<RegexMapping>)
  └── StaticContentHandler (static file serving)

HttpServer
  └── start(n) → creates n IOLoops
       └── each IOLoop owns:
            ├── Selector
            ├── TimeoutManager (PriorityQueue)
            ├── CallbackManager (JMX-debuggable)
            └── HttpProtocol (per-loop instance)
                 ├── activeChannels (Set<SocketChannel>)
                 ├── per-channel state maps
                 └── SSLSessionHandler (per-channel, optional)

Request lifecycle:
  handleAccept → handleRead → getHttpRequest → HttpRequest.of()
  → putContentData() [body framing] → Application.getHandler()
  → HttpRequestDispatcher.dispatch() → RequestHandler.get/post/...
  → HttpResponse.finish()
```

## Build & Run

```bash
mvn compile                    # build
mvn test                       # full suite (373 tests, JUnit 4)
mvn test -Dtest=FooTest        # one test class
mvn test -Dtest=FooTest#bar    # one test method

# Run an example server on :8080
mvn exec:java -Dexec.mainClass="org.deftserver.example.DeftServerExample"

# Run the HTTPS form/upload demo on :8443
examples/form-stuff/run.sh
```

Dependencies: JUnit 4 (test), Logback, Apache HttpClient (test), `javax.activation`, `zstd-jni`, `brotjli`.

## Test Suite

- **47 test classes**, **373 `@Test` methods** — true integration tests against real sockets on ephemeral ports
- Covers: byte-level protocol parsing, request smuggling, chunked encoding, multipart, cookies, WebSocket, TLS, CORS, compression, concurrent load, fuzz payloads, network impairment, per-IP limiting, timeout correctness
- `forkCount=1`, `reuseForks=true`, 600 s fork timeout

## Examples

| Example | Description |
|---------|-------------|
| `DeftServerExample` | Basic GET/POST server on :8080 |
| `KeyValueStore` / `KeyValueStoreClient` / `KeyValueStoreExample` | Async KV store with HTTP client |
| `AsynchronousHttpClientExample` | Non-blocking HTTP client usage |
| `FormStuffServer` (in `examples/form-stuff/`) | HTTPS form/upload portal on :8443 with multipart, SSL, Base64 image preview |

## Tunables

| Constant | Default | Description |
|----------|---------|-------------|
| `KEEP_ALIVE_TIMEOUT` | 30 s | Idle keep-alive timeout |
| `REQUEST_PROCESSING_TIMEOUT_MS` | 60 s | Max time for async/offloaded handlers |
| `WEBSOCKET_IDLE_TIMEOUT_MS` | 5 min | WebSocket idle timeout |
| `MAX_KEEP_ALIVE_REQUESTS` | 1000 | Requests per keep-alive connection |
| `MAX_CONNECTIONS` | 10000 | Global connection limit |
| `MAX_CONNECTIONS_PER_IP` | 0 | Per-IP limit (0 = unlimited) |

## Configuration (.dreft-cfg)

Conceptually analogous to Apache's `.htaccess`, placing a `.dreft-cfg` file in a directory dynamically overrides serving and behavior configuration for that directory and its subdirectories (without requiring a server restart). 

The `.dreft-cfg` is an INI-style file. The configuration is cached in memory with a 2-second reload throttle (detecting file updates via modified timestamps).

### Section: `[compression]`
Currently, it supports configuring on-demand static content compression rules:

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `true` | Enables or disables compression caching for files in the directory. |
| `algorithms` | `br, zstd, gzip` | Order of preferred compression algorithms. |
| `include` | (Based on mime type) | Comma-separated list of glob patterns for files to compress (e.g. `*.html, *.css`). |
| `exclude` | (None) | Comma-separated list of glob patterns to exclude from compression. |

Example:
```ini
[compression]
enabled = true
algorithms = br, zstd, gzip
include = *.html, *.css, *.js, *.json
exclude = *large-data*
```

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).

Dreft is based on [Deft] by Rschildmeijer, originally inspired by Facebook's Tornado web server.
