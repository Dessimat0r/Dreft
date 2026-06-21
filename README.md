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
- **Inbound decompression** — a request body sent with `Content-Encoding: gzip`/`zstd` is decompressed (off the I/O loop) before the handler sees it, with the decompressed size bounded to the 16 MiB body cap (`413` on a decompression bomb)
- **Vary header management** — `Vary: Origin` on CORS, `Vary: Accept-Encoding` on gzip, merge-aware `addVary()`
- **Connection management** — keep-alive (HTTP/1.1 default), `Connection: close` enforcement, max keep-alive requests cap

### Security & Hardening
- **Path traversal defense** — percent-decoded URL normalization, null-byte rejection, `..` segment blocking (`403`), `toRealPath()` symlink resolution
- **Request smuggling prevention** — obs-fold rejection, whitespace-before-colon rejection, control-char rejection, strict `Transfer-Encoding` last-coding enforcement, lenient Content-Length rejection
- **Parser DoS protection** — all collections bounded: ≤100 header lines, ≤64 KiB header block, ≤16 MiB body, ≤10k multipart parts, ≤10k parameters, ≤32 param-nesting depth, ≤1000 keep-alive requests
- **Memory amplification prevention** — O(n) char-scan parameter parsing (no eager split), bounded header/part/trailer counts, geometric buffer growth
- **Fully non-blocking writes** — no write ever blocks the I/O loop. Response bodies, WebSocket frames, and the `100-Continue` interim response all send what the socket accepts immediately and defer any remainder to `OP_WRITE` (so a slow/non-reading peer can't freeze the reactor); a stalled deferred write is reaped by a write timeout, and a header write that stalls mid-header stashes the unwritten remainder as an O(1) pending-prefix (no O(body) buffer shift)
- **No CPU-heavy or blocking per-request work on the reactor** — static-file disk reads, inbound body decompression + form/multipart parsing, and outbound gzip + ETag hashing all run OFF the I/O-loop thread on a virtual thread, marshalling the result back via `IOLoop.addCallback`. The loop thread only does non-blocking socket I/O and the strict header parse
- **Offload-amplification hardening** — trivial terminal handlers (400/403/404/CORS-preflight/`OPTIONS *`) opt out of virtual-thread offload (`RequestHandler.isOffloadable()`), so a flood of malformed or not-found requests can't make every one spawn a virtual thread
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

### HTTP/2 (cleartext h2c)
Functional cleartext HTTP/2, negotiated by the connection **preface** — *prior knowledge* (`PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`):
- **HPACK** header compression (`Hpack`)
- Multiplexed **streams** over `HEADERS` / `DATA` / `WINDOW_UPDATE` / `RST_STREAM` / `SETTINGS` / `PING` / `GOAWAY` frames
- `SETTINGS` negotiation + validation (`ENABLE_PUSH`, `INITIAL_WINDOW_SIZE`, `MAX_FRAME_SIZE`)
- **DoS hardening** — frame-size cap, max concurrent streams, `RST_STREAM`-flood limit, idle timeout
- End-to-end tested (GET / POST / static-file over h2c) in `Http2SystemTest`, plus `Http2DosHardeningTest`

> **Scope:** negotiation is *prior-knowledge h2c only* — there is no ALPN (`h2` over TLS) and no HTTP/1.1 `Upgrade: h2c`, so browsers (which require ALPN) won't select HTTP/2; it serves clients that speak h2c with prior knowledge. The project's active compliance focus remains HTTP/1.1; HTTP/3 is not implemented.

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
mvn test                       # full suite (363 tests, JUnit 4)
mvn test -Dtest=FooTest        # one test class
mvn test -Dtest=FooTest#bar    # one test method

# Run an example server on :8080
mvn exec:java -Dexec.mainClass="org.deftserver.example.DeftServerExample"

# Run the HTTPS form/upload demo on :8443
examples/form-stuff/run.sh
```

Dependencies: JUnit 4 (test), Logback, Apache HttpClient (test), `javax.activation`, `zstd-jni`, `brotjli`.

## Test Suite

- **363 tests** across 50+ source files — true integration tests against real sockets on ephemeral ports
- Covers: byte-level protocol parsing, request smuggling, chunked encoding, multipart, cookies, WebSocket, TLS, CORS, inbound/outbound compression, concurrent load + keep-alive reuse, fuzz payloads, network impairment, per-IP limiting, timeout correctness, and loop-stall / off-loop-isolation regressions (a slow disk read, a heavy inbound finalize, or an offloaded handler must never stall or corrupt other connections)
- `forkCount=1`, `reuseForks=true`, 600 s fork timeout

### Compliance & throughput tooling
- **HTTP Garden** — a drop-in adapter ([`src/test/resources/http-garden/`](src/test/resources/http-garden/)) lets Dreft be added as an origin server in the [HTTP Garden](https://github.com/narfindustries/http-garden) differential fuzzer to surface HTTP/1.1 parsing discrepancies against other servers
- **Throughput benchmark** — `ThroughputBenchmarkManual` is a closed-loop raw-socket load generator (`mvn test -Dtest=ThroughputBenchmarkManual#benchmarkGetThroughput`); not run by the normal suite

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
