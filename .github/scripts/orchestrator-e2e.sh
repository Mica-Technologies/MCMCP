#!/usr/bin/env bash
#
# Boots a dedicated server, then drives a real MCP client through the orchestrator against it.
#
# This is the only test where the two implementations of the link protocol meet. The mod's unit
# tests prove its framing against byte arrays; the orchestrator's prove its aggregation against
# fixtures; server-smoke-test.sh proves the mod dials out and a *stub* can answer. None of them
# would catch the two real halves disagreeing — a renamed field, a changed default, a version bump
# on one side only. That failure does not break a build. It produces a handshake that never
# completes, at runtime, on a user's machine.
#
# Order matters and is the opposite of the obvious one: the server starts FIRST and the orchestrator
# second. The mod retries its link on a backoff, so it finds an orchestrator that appears later,
# which is both the realistic sequence (people start games and then the app) and the one that
# exercises the retry path.
#
# Env:
#   SMOKE_TIMEOUT   seconds to wait for the server to start (default 900)
#   LINK_TIMEOUT    seconds to wait for the link to appear once the orchestrator is up (default 150)
#   ORCH_BINARY     path to the orchestrator binary (default the debug build)

set -uo pipefail

TIMEOUT="${SMOKE_TIMEOUT:-900}"
LINK_TIMEOUT="${LINK_TIMEOUT:-150}"
LOG="${SMOKE_LOG:-orchestrator-e2e-server.log}"
RUN_DIR="run/server"
STATE_DIR="$(mktemp -d)/orchestrator-state"
ORCH_BINARY="${ORCH_BINARY:-orchestrator/target/debug/mcmcp-orchestrator}"

SUCCESS_RE='Done \([0-9.]+s\)!'
FAILURE_RE='Encountered an unexpected exception|MissingModsException|for invalid side|A fatal error has occurred|Failed to start the minecraft server|FML has found a problem'

# Windows needs the extension spelled out, and the reason is not obvious. Under Git Bash
# `test -x foo` succeeds when only `foo.exe` exists, because MSYS resolves the extension for you.
# Python's CreateProcess does not, so probing with `-x` alone leaves the extensionless name in
# ORCH_BINARY and the run dies on "The system cannot find the file specified" — naming a binary
# that is sitting right there. Ask for the .exe explicitly, and take it when it answers.
if [ -f "${ORCH_BINARY}.exe" ]; then
  ORCH_BINARY="${ORCH_BINARY}.exe"
fi
if [ ! -x "$ORCH_BINARY" ]; then
  echo "==> FAIL: no orchestrator binary at ${ORCH_BINARY}; run 'cargo build -p mcmcp-orchestrator' in orchestrator/ first"
  exit 1
fi

# Present and executable is not the same as runnable, and the way this goes wrong is nasty. The
# desktop app declares the headless binary as a Tauri externalBin, and building the app copies that
# sidecar next to the app binary with the target triple stripped — which is exactly cargo's output
# path for the CLI crate. So `cargo build --workspace` leaves the placeholder text file sitting
# where the real binary was: right name, right place, executable bit set, and it dies at exec with
# an error that names a file you can see. Ask it what version it is instead of trusting the path.
if ! "$ORCH_BINARY" --version >/dev/null 2>&1; then
  echo "==> FAIL: ${ORCH_BINARY} exists but will not run."
  echo "    Building the desktop app overwrites it with the sidecar placeholder, so this is what"
  echo "    'cargo build --workspace' leaves behind. Rebuild the headless binary on its own:"
  echo "        cd orchestrator && cargo build -p mcmcp-orchestrator"
  exit 1
fi

# Hand the driver an absolute path. A relative one works everywhere bash runs it and nowhere
# CreateProcess does: Windows will not resolve a relative path with forward slashes against the
# working directory, so Python reports the file as missing while bash three lines up has just run
# it successfully. Git Bash rewrites an absolute path into a Windows one when it crosses into a
# native process, which is what makes this work on both platforms.
ORCH_BINARY="$(cd "$(dirname "$ORCH_BINARY")" && pwd)/$(basename "$ORCH_BINARY")"

PYTHON_BIN="$(command -v python3 || command -v python || true)"
if [ -z "$PYTHON_BIN" ]; then
  echo "==> FAIL: no python3 on PATH"
  exit 1
fi

mkdir -p run "${RUN_DIR}"
printf 'eula=true\n' > run/eula.txt
printf 'eula=true\n' > "${RUN_DIR}/eula.txt"

echo "==> Starting dedicated server (timeout ${TIMEOUT}s)"
./gradlew runServer \
  -Dhttp.socketTimeout=60000 -Dhttp.connectionTimeout=60000 \
  -Dorg.gradle.internal.http.socketTimeout=60000 \
  -Dorg.gradle.internal.http.connectionTimeout=60000 \
  > "$LOG" 2>&1 &
GRADLE_PID=$!

shutdown_server() {
  kill "$GRADLE_PID" 2>/dev/null
  pkill -f 'net.minecraft.server' 2>/dev/null
  pkill -f 'GradleWrapperMain' 2>/dev/null
  wait "$GRADLE_PID" 2>/dev/null
}

verdict="timeout"
elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  if grep -qE "$FAILURE_RE" "$LOG" 2>/dev/null; then verdict="crash"; break; fi
  if grep -qE "$SUCCESS_RE" "$LOG" 2>/dev/null; then verdict="ok"; break; fi
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then verdict="exited"; break; fi
  sleep 5
  elapsed=$((elapsed + 5))
done

if [ "$verdict" != "ok" ]; then
  echo "==> FAIL: the dedicated server did not start (${verdict}, after ${elapsed}s)"
  grep -nE "$FAILURE_RE" "$LOG" | head -20
  tail -80 "$LOG"
  shutdown_server
  exit 1
fi
echo "==> Server started after ${elapsed}s"

# The mod logs one line when it finds nothing listening, which is the expected state right now and
# is worth showing: if this line is absent, the link never even tried and the rest is meaningless.
grep -iE 'no orchestrator listening|orchestrator link' "$LOG" | tail -2

echo "==> Driving the orchestrator (link timeout ${LINK_TIMEOUT}s)"
"$PYTHON_BIN" .github/scripts/orchestrator-e2e.py "$ORCH_BINARY" "$STATE_DIR" "$LINK_TIMEOUT"
STATUS=$?

if [ "$STATUS" -ne 0 ]; then
  echo "----- MCMCP link lines from the server log -----"
  grep -iE 'orchestrator|mcmcp' "$LOG" | tail -30
fi

shutdown_server
rm -rf "$STATE_DIR"
exit "$STATUS"
