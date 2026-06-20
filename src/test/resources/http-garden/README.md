# Dreft × HTTP Garden

[The HTTP Garden](https://github.com/narfindustries/http-garden) is a differential fuzzer for HTTP/1.1
implementations: it feeds the same request stream to many servers/proxies and compares **how each one
parsed it**, surfacing parsing discrepancies (request smuggling, `Transfer-Encoding`/`Content-Length`
confusion, chunked/obs-fold edge cases, …). It's a strong external complement to Dreft's own
`HttpFuzzTest` / smuggling tests and directly serves the HTTP/1.\* compliance goal.

This directory contains everything needed to add Dreft as an **origin server** in the Garden.

## How it works

Each Garden origin server echoes the request **as it parsed it** in a standardized JSON shape (every
field base64 over ISO-8859-1 bytes):

```json
{"version":"<b64>","method":"<b64>","uri":"<b64>","headers":[["<b64name>","<b64val>"],...],"body":"<b64>"}
```

Dreft's adapter is [`GardenServer`](../../java/org/deftserver/garden/GardenServer.java) (in
Dreft's **test** sources — it's a compliance harness, not framework code). It wraps `Application` so every
request Dreft *accepts* is echoed, while Dreft's own rejections (a `400` for a malformed/invalid request)
pass through unchanged as the differential **"rejected"** signal. It listens on port `80` (override with
the `GARDEN_PORT` env var or the first CLI arg).

The adapter is verified by
[`GardenServerTest`](../../java/org/deftserver/garden/GardenServerTest.java)
(`mvn test -Dtest=GardenServerTest`), which boots it on an ephemeral port and asserts the decoded
round-trip for plain requests, arbitrary paths/methods, headers, raw body bytes, and malformed→4xx.

## Integrating it

1. Clone the Garden and drop this folder in as a new image:

   ```bash
   git clone https://github.com/narfindustries/http-garden
   cp -r /path/to/Dreft/src/test/resources/http-garden http-garden/images/dreft
   ```

2. Register the service in `http-garden/docker-compose.yml` (mirroring the other origin servers — pin
   `APP_VERSION` to the Dreft commit you want to test):

   ```yaml
     dreft:
       build:
         context: ./images/dreft
         args:
           APP_REPO: https://github.com/Dessimat0r/Dreft.git
           APP_BRANCH: master
           APP_VERSION: <dreft-commit-hash>
       <<: *origin-defaults   # whatever anchor the compose file uses for origin servers
   ```

   (If the compose file lists origin servers in an array/profile, add `dreft` there too so the fuzzer
   and `repl.py` discover it.)

3. Build and run the Garden as usual, e.g.:

   ```bash
   cd http-garden
   docker compose build dreft
   # interactive exploration:
   python3 tools/repl.py
   # then, in the REPL, include `dreft` among the targeted servers and run the differential fuzzer.
   ```

   See the Garden's own README for `repl.py` / fuzzing usage.

## Notes / assumptions

- **Build:** the image installs Temurin **JDK 25** (Dreft targets `<release>25</release>`) + Maven, clones
  Dreft, runs `mvn -DskipTests test-compile` (GardenServer is a test class) and
  `dependency:copy-dependencies`, then launches `org.deftserver.garden.GardenServer` on port 80. The exact
  build+run classpath was validated outside Docker; the apt/Temurin layer assumes the `http-garden-soil`
  base is Debian-derived (as the other Java images — netty, jetty — assume).
- **`JAVA_HOME`** is set to the `amd64` Temurin path; on an `arm64` host adjust the suffix (the upstream
  netty image symlinks an arch-agnostic path — do likewise if you build on mixed architectures).
- **brotjli** is an optional, system-scoped dependency vendored at the Dreft repo root
  (`brotjli-0.1.0.jar`); the `CMD` adds it to the classpath explicitly since
  `dependency:copy-dependencies` skips system/optional deps.
- **Scope:** the adapter echoes the methods Dreft dispatches (GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS).
  An unknown method (Dreft → `501`) or a request Dreft rejects (→ `4xx`) is returned as Dreft's genuine
  verdict rather than an echo — which is exactly the differential signal the Garden compares.
