#!/usr/bin/env bash
#
# Boot a dedicated server via `./gradlew runServer`, assert it starts, then drive a real MCP
# handshake against the server endpoint it exposes.
#
# Two distinct things are being checked here, and both are invisible to `./gradlew build`:
#
#   1. That the mod can LOAD on a dedicated server. A client-only class referenced from common
#      code, a client mod declared as a hard dependency, or a Side.CLIENT handler touching
#      Minecraft compiles perfectly and only fails at server startup. For MCMCP this is a live
#      risk on every commit — the mod is deliberately full of client-only code (ClientThreadBridge,
#      the screenshot and input tools) and the only thing keeping it off the server is the proxy
#      split. A missed import in common code breaks that silently.
#
#   2. That the MCP endpoint actually WORKS. Unit tests cover the wire format with no socket, and
#      a compiling transport proves nothing about whether it binds, authenticates, negotiates a
#      protocol version and lists tools. This drives the same sequence a real client does:
#      initialize -> notifications/initialized -> tools/list.
#
# `runServer` never returns on success, so it runs in the background while the log is tailed for a
# verdict, then gets shut down.
#
# Env:
#   SMOKE_TIMEOUT   seconds to wait for startup (default 900)
#   SMOKE_LOG       log file path (default server-smoke.log)
#   MCP_PORT        server-endpoint port (default 25588; see addon.gradle for how it is derived)

set -uo pipefail

TIMEOUT="${SMOKE_TIMEOUT:-900}"
LOG="${SMOKE_LOG:-server-smoke.log}"

# addon.gradle passes -Dmcmcp.dev.port=25587 to runServer, and McmcpConfig treats that as a BASE:
# clientPort = base, serverPort = base + 1. The server endpoint is therefore 25588.
MCP_PORT="${MCP_PORT:-25588}"

# buildscript.properties sets separateRunDirectories = true, so runServer's working directory is
# run/server rather than run.
RUN_DIR="run/server"
CONFIG_FILE="${RUN_DIR}/config/mcmcp.cfg"

SUCCESS_RE='Done \([0-9.]+s\)!'
FAILURE_RE='Encountered an unexpected exception|MissingModsException|for invalid side|A fatal error has occurred|The state engine was in incorrect state|Failed to start the minecraft server|FML has found a problem'

# Write the EULA into both candidate directories. separateRunDirectories is true today, so
# run/server is the one that matters — but writing both keeps this working if it is ever flipped
# back. Getting it wrong is invisible locally, where an already-accepted run/eula.txt lingers from
# earlier manual runs, and only shows up on a clean CI checkout as "Minecraft EULA not accepted".
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

verdict="timeout"
elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  if grep -qE "$FAILURE_RE" "$LOG" 2>/dev/null; then
    verdict="crash"
    break
  fi
  if grep -qE "$SUCCESS_RE" "$LOG" 2>/dev/null; then
    verdict="ok"
    break
  fi
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    # Gradle exited without ever printing "Done (" — build failure or early abort.
    verdict="exited"
    break
  fi
  sleep 5
  elapsed=$((elapsed + 5))
done

shutdown_server() {
  echo "==> Stopping server"
  kill "$GRADLE_PID" 2>/dev/null
  # The Gradle wrapper spawns the server in a child JVM; kill that too so the runner does not
  # hang waiting on an orphan.
  pkill -f 'net.minecraft.server' 2>/dev/null
  pkill -f 'GradleWrapperMain' 2>/dev/null
  wait "$GRADLE_PID" 2>/dev/null
}

report_failure() {
  echo "==> FAIL: $1"
  echo "----- matching failure lines -----"
  grep -nE "$FAILURE_RE" "$LOG" | head -20
  echo "----- last 120 log lines -----"
  tail -120 "$LOG"
}

if [ "$verdict" != "ok" ]; then
  shutdown_server
  report_failure "dedicated server did not start (${verdict}, after ${elapsed}s)"
  exit 1
fi

echo "==> Server started after ${elapsed}s"
grep -E "$SUCCESS_RE" "$LOG" | head -1

# ---------------------------------------------------------------------------
# MCP handshake
# ---------------------------------------------------------------------------

mcp_failure() {
  echo "==> FAIL: $1"
  echo "----- MCMCP log lines -----"
  grep -n 'MCMCP' "$LOG" | tail -40
  echo "----- last 60 log lines -----"
  tail -60 "$LOG"
  shutdown_server
  exit 1
}

echo "==> Reading the generated API token from ${CONFIG_FILE}"
if [ ! -f "$CONFIG_FILE" ]; then
  mcp_failure "MCMCP never wrote a config file — the mod did not reach preInit"
fi

# The Forge config format is `S:authToken=<value>`; take the last match so a commented example
# above the real entry cannot win.
TOKEN="$(grep -E '^\s*S:authToken=' "$CONFIG_FILE" | tail -1 | cut -d= -f2- | tr -d '[:space:]')"
if [ -z "$TOKEN" ]; then
  mcp_failure "no authToken in ${CONFIG_FILE} — token generation did not run"
fi
echo "==> Token present (${#TOKEN} characters)"

# The endpoint binds during FMLServerStartingEvent, which fires just before "Done (". Give the
# socket a moment rather than racing it.
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if curl -fsS --max-time 5 "http://127.0.0.1:${MCP_PORT}/mcp/health" > /dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "==> Health probe"
HEALTH="$(curl -fsS --max-time 5 "http://127.0.0.1:${MCP_PORT}/mcp/health" 2>&1)" \
  || mcp_failure "health endpoint did not respond on port ${MCP_PORT}: ${HEALTH}"
echo "    ${HEALTH}"
case "$HEALTH" in
  *'"side":"server"'*) ;;
  *) mcp_failure "health endpoint did not identify itself as the server side: ${HEALTH}" ;;
esac

# Authentication has to actually be enforced. A regression that accepts unauthenticated requests
# would pass every other check in this script.
echo "==> Rejecting an unauthenticated request"
UNAUTH_STATUS="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
  -X POST "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}')"
if [ "$UNAUTH_STATUS" != "401" ]; then
  mcp_failure "an unauthenticated initialize returned HTTP ${UNAUTH_STATUS}, expected 401"
fi

echo "==> initialize"
INIT_HEADERS="$(mktemp)"
INIT_BODY="$(curl -fsS --max-time 10 -D "$INIT_HEADERS" \
  -X POST "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mcmcp-smoke-test","version":"1.0"}}}' 2>&1)" \
  || mcp_failure "initialize request failed: ${INIT_BODY}"

echo "    ${INIT_BODY}"
case "$INIT_BODY" in
  *'"protocolVersion":"2025-06-18"'*) ;;
  *) mcp_failure "initialize did not negotiate 2025-06-18: ${INIT_BODY}" ;;
esac
case "$INIT_BODY" in
  *'"serverInfo"'*) ;;
  *) mcp_failure "initialize response has no serverInfo: ${INIT_BODY}" ;;
esac

SESSION_ID="$(grep -i '^Mcp-Session-Id:' "$INIT_HEADERS" | tail -1 | cut -d: -f2- | tr -d '[:space:]')"
rm -f "$INIT_HEADERS"
if [ -z "$SESSION_ID" ]; then
  mcp_failure "initialize did not return an Mcp-Session-Id header"
fi
echo "==> Session ${SESSION_ID}"

echo "==> notifications/initialized"
NOTIFY_STATUS="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
  -X POST "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Mcp-Session-Id: ${SESSION_ID}" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}')"
if [ "$NOTIFY_STATUS" != "202" ]; then
  mcp_failure "notifications/initialized returned HTTP ${NOTIFY_STATUS}, expected 202"
fi

echo "==> tools/list"
TOOLS_BODY="$(curl -fsS --max-time 10 \
  -X POST "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Mcp-Session-Id: ${SESSION_ID}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' 2>&1)" \
  || mcp_failure "tools/list request failed: ${TOOLS_BODY}"

# Spot-check one tool from each registration path: a common one and a server-only one. If either
# is missing, registration ran but something filtered it out.
for expected in '"mcmcp_endpoint_info"' '"server_get_blocks"'; do
  case "$TOOLS_BODY" in
    *"$expected"*) echo "    found ${expected}" ;;
    *) mcp_failure "tools/list is missing ${expected}: ${TOOLS_BODY}" ;;
  esac
done

# A client-only tool listed on the server endpoint means the side filter is broken, which would
# hand a model a tool that can never work.
case "$TOOLS_BODY" in
  *'"client_screenshot"'*)
    mcp_failure "tools/list exposed the client-only tool client_screenshot on the server endpoint"
    ;;
esac

echo "==> tools/call mcmcp_endpoint_info"
CALL_BODY="$(curl -fsS --max-time 10 \
  -X POST "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Mcp-Session-Id: ${SESSION_ID}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"mcmcp_endpoint_info","arguments":{}}}' 2>&1)" \
  || mcp_failure "tools/call request failed: ${CALL_BODY}"

case "$CALL_BODY" in
  *'"isError":false'*) echo "    call succeeded" ;;
  *) mcp_failure "tools/call did not return a successful result: ${CALL_BODY}" ;;
esac
case "$CALL_BODY" in
  *'"side": "server"'*|*'"side":"server"'*) ;;
  *) mcp_failure "endpoint_info did not report the server side: ${CALL_BODY}" ;;
esac

echo "==> DELETE session"
curl -fsS --max-time 5 -X DELETE "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Mcp-Session-Id: ${SESSION_ID}" > /dev/null 2>&1 \
  || echo "    (session delete failed; not fatal)"

shutdown_server
echo "==> PASS: dedicated server started and served a full MCP handshake"
exit 0
