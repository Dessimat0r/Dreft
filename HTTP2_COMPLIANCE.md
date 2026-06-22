# HTTP/2 Compliance Checklist (RFC 7540 / RFC 7541)

Tracks Dreft's conformance to HTTP/2, structured after the [h2spec](https://github.com/summerwind/h2spec)
conformance suite. Status reflects the server implementation in
`org.deftserver.web.http.http2` (`Http2Connection`, `Http2Frame`, `Hpack`, `Http2Stream`).

Legend: `[x]` conformant · `[~]` partial / wrong error semantics · `[ ]` gap.

A recurring theme in the gaps: a **connection error** must send `GOAWAY` with the last-processed
stream id and an error code before closing (RFC 7540 §5.4.1, §6.8); a **stream error** must send
`RST_STREAM` with an error code (§5.4.2). Dreft historically just closed the socket. The
`sendGoAway` / per-stream `RST_STREAM` plumbing added in the compliance pass fixes this.

## 3. Starting HTTP/2
- [x] 3.5 — validates the client connection preface (`PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`); sends a server SETTINGS preface; mismatched preface closes the connection
- [x] 3.2 — `Upgrade: h2c` (opt-in) and 3.4 prior-knowledge h2c; 3.3 ALPN `h2` over TLS

## 4. HTTP Frames
- [x] 4.1 — unknown frame types are ignored (the `switch` has no `default` action)
- [x] 4.2 — frame larger than `SETTINGS_MAX_FRAME_SIZE` → `FRAME_SIZE_ERROR`
- [x] 4.3 — HPACK decoding failure → `COMPRESSION_ERROR` (connection error)

## 5. Streams and Multiplexing
- [x] 5.1.1 — client stream ids must strictly increase; reused/old id → `PROTOCOL_ERROR`
- [x] 5.1.1 — client-initiated streams must be odd; even id → `PROTOCOL_ERROR`
- [x] 5.1.2 — `SETTINGS_MAX_CONCURRENT_STREAMS` enforced → `REFUSED_STREAM`
- [x] 5.1 — stream state: DATA/HEADERS on a half-closed/closed stream → `STREAM_CLOSED`; on an idle stream → `PROTOCOL_ERROR`; WINDOW_UPDATE on an idle stream → `PROTOCOL_ERROR`; trailers (a second END_STREAM HEADERS on an open stream) accepted
- [x] 5.3.1 — PRIORITY/HEADERS self-dependency (stream depends on itself) → `PROTOCOL_ERROR`
- [x] 5.4.1 — connection errors send `GOAWAY(last_stream_id, code)` before closing

## 6. Frame Definitions
- [x] 6.1 DATA — on stream 0 → `PROTOCOL_ERROR`; pad length ≥ frame length → `PROTOCOL_ERROR`
- [x] 6.2 HEADERS — on stream 0 → `PROTOCOL_ERROR`; pad length validation
- [x] 6.3 PRIORITY — on stream 0 → `PROTOCOL_ERROR`; length ≠ 5 → stream `FRAME_SIZE_ERROR`
- [x] 6.4 RST_STREAM — on stream 0 → `PROTOCOL_ERROR`; length ≠ 4 → `FRAME_SIZE_ERROR`; on idle stream → `PROTOCOL_ERROR`
- [x] 6.5 SETTINGS — on stream ≠ 0 → `PROTOCOL_ERROR`; length %6 ≠ 0 → `FRAME_SIZE_ERROR`; ACK with payload → `FRAME_SIZE_ERROR`; is ACKed
- [x] 6.5.2 — `ENABLE_PUSH` ∉ {0,1} → `PROTOCOL_ERROR`; `INITIAL_WINDOW_SIZE` > 2³¹−1 → `FLOW_CONTROL_ERROR`; `MAX_FRAME_SIZE` out of range → `PROTOCOL_ERROR`
- [x] 6.7 PING — on stream ≠ 0 → `PROTOCOL_ERROR`; length ≠ 8 → `FRAME_SIZE_ERROR`; non-ACK answered with ACK
- [x] 6.8 GOAWAY — on stream ≠ 0 → `PROTOCOL_ERROR`
- [x] 6.9 WINDOW_UPDATE — length ≠ 4 → `FRAME_SIZE_ERROR`; increment 0 on connection → `PROTOCOL_ERROR`, on stream → `RST_STREAM(PROTOCOL_ERROR)`; window > 2³¹−1 → `FLOW_CONTROL_ERROR`
- [x] 6.10 CONTINUATION — must follow HEADERS/CONTINUATION on the same stream, else `PROTOCOL_ERROR`; 64 KiB header-block cap

## 7. Error Codes
- [x] error codes emitted via GOAWAY / RST_STREAM as above

## 8. HTTP Message Exchange
- [x] 8.1.2 — header field names must be lowercase; an uppercase name → `PROTOCOL_ERROR` (malformed → `RST_STREAM`)
- [x] 8.1.2.1 — pseudo-headers: unknown pseudo-header, a pseudo-header after a regular header, or a duplicate → malformed
- [x] 8.1.2.2 — connection-specific headers (`Connection`, `Keep-Alive`, `Proxy-Connection`, `Transfer-Encoding`, `Upgrade`) → malformed; `TE` only if its value is exactly `trailers`
- [x] 8.1.2.3 — request requires `:method`, `:path`, `:scheme`; missing/duplicate → malformed
- [x] 8.1.2.6 — a declared `Content-Length` must equal the summed DATA payload length, else malformed

## RFC 7541 (HPACK)
- [x] 5.2 — Huffman end-of-string padding validated (≤7 bits, all-ones); EOS-in-input rejected
- [x] 6.1 — index 0 → decoding error
- [x] dynamic table size update > `SETTINGS_HEADER_TABLE_SIZE` → decoding error
- [x] Appendix B Huffman table verified (Kraft sum incl. EOS == 1.0, prefix-free, all 256 round-trip); RFC Appendix C vectors pinned in `HpackHuffmanTest`

## Out of scope
- HTTP/3 / QUIC
- Server push (`PUSH_PROMISE`) — `ENABLE_PUSH` accepted but the server never pushes
