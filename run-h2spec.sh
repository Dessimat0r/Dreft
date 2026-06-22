#!/usr/bin/env bash
#
# Runs the h2spec HTTP/2 conformance suite against a freshly-built Dreft server.
#
#   ./run-h2spec.sh [port] [extra h2spec args...]
#
# Builds the project, launches org.deftserver.web.H2SpecHarness on a cleartext port (default 18080),
# waits for it to listen, runs h2spec against it, then tears the server down. Exits with h2spec's
# status. Requires `h2spec` on PATH (https://github.com/summerwind/h2spec; `brew install h2spec`).
#
# See HTTP2_COMPLIANCE.md for the expected result (146 tests: 144 pass, 1 skipped, 1 documented
# exception — http2/3.5.2, a property of the dual HTTP/1.1+h2c cleartext port).
set -euo pipefail

cd "$(dirname "$0")"

PORT="${1:-18080}"
shift || true

if ! command -v h2spec >/dev/null 2>&1; then
	echo "error: h2spec not found on PATH. Install it: brew install h2spec" >&2
	echo "       (or see https://github.com/summerwind/h2spec/releases)" >&2
	exit 127
fi

echo "==> Building project + resolving classpath..."
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/h2spec-cp.txt
CP="target/test-classes:target/classes:$(cat target/h2spec-cp.txt)"

echo "==> Launching H2SpecHarness on port ${PORT}..."
java -cp "$CP" org.deftserver.web.H2SpecHarness "$PORT" &
HARNESS_PID=$!
trap 'kill "$HARNESS_PID" 2>/dev/null || true' EXIT

# Wait (up to ~10s) for the port to accept connections.
for _ in $(seq 1 50); do
	if nc -z 127.0.0.1 "$PORT" 2>/dev/null; then break; fi
	sleep 0.2
done

echo "==> Running h2spec against 127.0.0.1:${PORT}..."
set +e
h2spec -p "$PORT" -h 127.0.0.1 -o 3 "$@"
STATUS=$?
set -e

exit "$STATUS"
