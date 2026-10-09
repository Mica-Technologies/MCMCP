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
#   3. That the ORCHESTRATOR LINK dials out and serves the same dispatcher. A stub orchestrator
#      listens before the server starts, accepts the instance's outbound link, and drives the same
#      MCP sequence over it. LinkFraming and LinkHandshake are unit-tested against byte arrays,
#      which proves nothing about whether a socket is ever opened or whether what arrives on it
#      reaches the dispatcher. It also cross-checks the instance id from the handshake against the
#      one mcmcp_endpoint_info reports, because two sources for one fact is how they drift.
#
# `runServer` never returns on success, so it runs in the background while the log is tailed for a
# verdict, then gets shut down.
#
# Env:
#   SMOKE_TIMEOUT   seconds to wait for startup (default 900)
#   SMOKE_LOG       log file path (default server-smoke.log)
#   MCP_PORT        server-endpoint port (default 25588; see addon.gradle for how it is derived)
#   LINK_PORT       stub orchestrator port (default 25580, matching the mod's config default)

set -uo pipefail

TIMEOUT="${SMOKE_TIMEOUT:-900}"
LOG="${SMOKE_LOG:-server-smoke.log}"

# addon.gradle passes -Dmcmcp.dev.port=25587 to runServer, and McmcpConfig treats that as a BASE:
# clientPort = base, serverPort = base + 1. The server endpoint is therefore 25588.
MCP_PORT="${MCP_PORT:-25588}"

# The mod's orchestrator.orchestratorPort default. The stub has to be listening BEFORE the server
# starts: the link dials during FMLServerStartingEvent, and while it would retry with backoff, a
# stub started afterwards would turn a wiring bug into a timing-dependent pass.
LINK_PORT="${LINK_PORT:-25580}"
LINK_RESULT="$(mktemp)"
STUB_SCRIPT="$(dirname "$0")/stub-orchestrator.py"

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

PYTHON_BIN="$(command -v python3 || command -v python || true)"
if [ -z "$PYTHON_BIN" ]; then
  echo "==> FAIL: no python3 on PATH; the stub orchestrator needs it"
  exit 1
fi

echo "==> Starting stub orchestrator on 127.0.0.1:${LINK_PORT}"
"$PYTHON_BIN" "$STUB_SCRIPT" "$LINK_PORT" "$LINK_RESULT" "$TIMEOUT" &
STUB_PID=$!

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
  kill "$STUB_PID" 2>/dev/null
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
for expected in '"mcmcp_endpoint_info"' '"game_dump_registries"' '"server_get_blocks"' '"server_stop"' \
                '"server_profile_ticking"' '"server_census"' '"game_cpu_sample"' \
                '"game_heap_histogram"'; do
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

# The process identity. It is what tells two games launched from one directory apart, and the
# failure it exists to prevent is silent -- a stale endpoint answering for a build nobody is
# working on -- so its absence has to be loud here instead.
case "$CALL_BODY" in
  *'"pid"'*) ;;
  *) mcp_failure "endpoint_info did not report a process id: ${CALL_BODY}" ;;
esac
case "$CALL_BODY" in
  *'"startedAt"'*) ;;
  *) mcp_failure "endpoint_info did not report a process start time: ${CALL_BODY}" ;;
esac

# The ticking profile is the one performance tool that cannot be unit-tested: it drives Forge's
# TimeTracker on the server thread and reads live tile entities back. One second is enough to prove
# the round trip -- enable, tick, read, reset -- on a real dedicated server, which is also where a
# client class leaking into PerformanceTools would surface.
echo "==> tools/call server_profile_ticking"
PROFILE_BODY="$(curl -fsS --max-time 30 \
  -X POST "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Mcp-Session-Id: ${SESSION_ID}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"server_profile_ticking","arguments":{"duration_seconds":1,"top":1}}}' 2>&1)" \
  || mcp_failure "server_profile_ticking request failed: ${PROFILE_BODY}"

case "$PROFILE_BODY" in
  *'"isError":false'*) echo "    call succeeded" ;;
  *) mcp_failure "server_profile_ticking did not return a successful result: ${PROFILE_BODY}" ;;
esac
# No ticks observed means the tick recorder never subscribed, and every tick statistic is empty.
case "$PROFILE_BODY" in
  *'ticksObserved\":0,'*|*'ticksObserved":0,'*)
    mcp_failure "server_profile_ticking observed no ticks: ${PROFILE_BODY}"
    ;;
  *'ticksObserved'*) ;;
  *) mcp_failure "server_profile_ticking did not report ticksObserved: ${PROFILE_BODY}" ;;
esac

# A misspelled argument must be refused, naming the real one, rather than ignored. Ignoring it ran
# the tool on a default nobody chose and reported success, which is the failure this guards.
echo "==> tools/call with a misspelled argument"
MISSPELT_BODY="$(curl -fsS --max-time 10 \
  -X POST "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Mcp-Session-Id: ${SESSION_ID}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"server_profile_ticking","arguments":{"durationSeconds":1}}}' 2>&1)" \
  || mcp_failure "misspelled-argument request failed: ${MISSPELT_BODY}"

case "$MISSPELT_BODY" in
  *'"isError":true'*) ;;
  *) mcp_failure "a misspelled argument was not refused: ${MISSPELT_BODY}" ;;
esac
case "$MISSPELT_BODY" in
  *"did you mean 'duration_seconds'"*) echo "    refused, naming duration_seconds" ;;
  *) mcp_failure "the refusal did not name the real argument: ${MISSPELT_BODY}" ;;
esac

# A @SideOnly(CLIENT) method on a vanilla class is stripped from a dedicated server, but unlike a
# client class it does not fail at load: it throws NoSuchMethodError on the first call. Nothing
# above calls into those paths, so Biome.getBiomeName and CreativeTabs.getIndex shipped broken
# (issue #25). These calls reach the biome lookup and the creative tab of every vanilla item.
echo "==> tools/call server_world_info (for the spawn point)"
WORLD_BODY="$(curl -fsS --max-time 10 \
  -X POST "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Mcp-Session-Id: ${SESSION_ID}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"server_world_info","arguments":{}}}' 2>&1)" \
  || mcp_failure "server_world_info request failed: ${WORLD_BODY}"

# Spawn chunks stay loaded on a dedicated server, so the spawn point is a position that is sure to
# reach the biome lookup rather than stop at loaded=false.
SPAWN="$(printf '%s' "$WORLD_BODY" | "$PYTHON_BIN" -c '
import json, sys
body = json.load(sys.stdin)
for dim in body["result"]["structuredContent"]["dimensions"]:
    if dim.get("dimension") == 0:
        p = dim["spawnPoint"]
        print(p["x"], p["y"], p["z"])
        break
' 2>&1)" || mcp_failure "could not read the overworld spawn point from server_world_info: ${WORLD_BODY}"
read -r SPAWN_X SPAWN_Y SPAWN_Z <<< "$SPAWN"

echo "==> tools/call server_get_block at spawn (${SPAWN_X} ${SPAWN_Y} ${SPAWN_Z})"
BLOCK_BODY="$(curl -fsS --max-time 10 \
  -X POST "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Mcp-Session-Id: ${SESSION_ID}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"server_get_block\",\"arguments\":{\"x\":${SPAWN_X},\"y\":${SPAWN_Y},\"z\":${SPAWN_Z}}}}" 2>&1)" \
  || mcp_failure "server_get_block request failed: ${BLOCK_BODY}"

case "$BLOCK_BODY" in
  *'"isError":false'*) ;;
  *) mcp_failure "server_get_block did not return a successful result: ${BLOCK_BODY}" ;;
esac
case "$BLOCK_BODY" in
  *'"biome":"minecraft:'*) echo "    call succeeded, biome reported" ;;
  *) mcp_failure "server_get_block did not report a biome id at spawn: ${BLOCK_BODY}" ;;
esac

echo "==> tools/call game_dump_registries for minecraft"
DUMP_BODY="$(curl -fsS --max-time 30 \
  -X POST "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Mcp-Session-Id: ${SESSION_ID}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"game_dump_registries","arguments":{"namespace":"minecraft","display_names":false}}}' 2>&1)" \
  || mcp_failure "game_dump_registries request failed: ${DUMP_BODY}"

case "$DUMP_BODY" in
  *'"isError":false'*) echo "    call succeeded" ;;
  *) mcp_failure "game_dump_registries did not return a successful result: ${DUMP_BODY}" ;;
esac

# ---------------------------------------------------------------------------
# Undo points: write, list, restore, read back
# ---------------------------------------------------------------------------

# One tools/call; prints the body. $1 = id, $2 = tool, $3 = arguments as JSON.
mcp_call() {
  curl -fsS --max-time 60 \
    -X POST "http://127.0.0.1:${MCP_PORT}/mcp" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Mcp-Session-Id: ${SESSION_ID}" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json' \
    -d "{\"jsonrpc\":\"2.0\",\"id\":$1,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}" 2>&1
}

# World edits are off by default. Turned on for this check through the config and /mcmcp reload,
# which is also how an operator would do it, and put back to whatever they were afterwards — a local
# run/server config may have had them on already.
echo "==> undo points: enable world edits"
WORLD_EDITS_WERE="$(grep -o 'B:allowWorldEdits=[a-z]*' "$CONFIG_FILE" | head -1)"
sed -i 's/B:allowWorldEdits=false/B:allowWorldEdits=true/' "$CONFIG_FILE"
RELOAD_BODY="$(mcp_call 20 server_run_command '{"command":"mcmcp reload"}')" \
  || mcp_failure "reloading the config failed: ${RELOAD_BODY}"

UNDO_Y=$((SPAWN_Y + 40))
echo "==> undo points: fill gold at ${SPAWN_X} ${UNDO_Y} ${SPAWN_Z}"
FILL_BODY="$(mcp_call 21 server_set_blocks "{\"mode\":\"fill\",\"block\":\"minecraft:gold_block\",\"x\":${SPAWN_X},\"y\":${UNDO_Y},\"z\":${SPAWN_Z},\"toX\":$((SPAWN_X + 2)),\"toY\":$((UNDO_Y + 1)),\"toZ\":$((SPAWN_Z + 2))}")" \
  || mcp_failure "server_set_blocks request failed: ${FILL_BODY}"
UNDO_ID="$(printf '%s' "$FILL_BODY" | grep -o '"undoPoint":"u[0-9]*"' | head -1 | sed 's/.*"\(u[0-9]*\)"/\1/')"
[ -n "$UNDO_ID" ] || mcp_failure "server_set_blocks left no undo point: ${FILL_BODY}"
echo "    wrote 18 blocks, undo point ${UNDO_ID}"

LIST_BODY="$(mcp_call 22 server_undo '{"op":"list"}')" || mcp_failure "server_undo list failed: ${LIST_BODY}"
case "$LIST_BODY" in
  *"\"id\":\"${UNDO_ID}\""*) echo "    listed" ;;
  *) mcp_failure "server_undo list did not include ${UNDO_ID}: ${LIST_BODY}" ;;
esac

RESTORE_BODY="$(mcp_call 23 server_undo "{\"op\":\"restore\",\"id\":\"${UNDO_ID}\"}")" \
  || mcp_failure "server_undo restore failed: ${RESTORE_BODY}"
case "$RESTORE_BODY" in
  *'"isError":false'*'"restored"'*|*'"restored"'*'"isError":false'*) echo "    restored" ;;
  *) mcp_failure "server_undo restore did not succeed: ${RESTORE_BODY}" ;;
esac

AFTER_BODY="$(mcp_call 24 server_get_block "{\"x\":$((SPAWN_X + 1)),\"y\":$((UNDO_Y + 1)),\"z\":$((SPAWN_Z + 1))}")" \
  || mcp_failure "server_get_block after restore failed: ${AFTER_BODY}"
case "$AFTER_BODY" in
  *'gold_block'*) mcp_failure "the gold is still there after the restore: ${AFTER_BODY}" ;;
  *) echo "    the block is back to what it was" ;;
esac

echo "==> undo points: /fill through server_run_command, then restore it"
CMD_BODY="$(mcp_call 26 server_run_command "{\"command\":\"fill ${SPAWN_X} ${UNDO_Y} ${SPAWN_Z} $((SPAWN_X + 1)) ${UNDO_Y} $((SPAWN_Z + 1)) minecraft:diamond_block\"}")" \
  || mcp_failure "the fill command failed: ${CMD_BODY}"
CMD_UNDO="$(printf '%s' "$CMD_BODY" | grep -o '"undoPoint":"u[0-9]*"' | head -1 | sed 's/.*"\(u[0-9]*\)"/\1/')"
[ -n "$CMD_UNDO" ] || mcp_failure "the fill command left no undo point: ${CMD_BODY}"
CMD_RESTORE="$(mcp_call 27 server_undo "{\"op\":\"restore\",\"id\":\"${CMD_UNDO}\"}")" \
  || mcp_failure "restoring the fill failed: ${CMD_RESTORE}"
CMD_AFTER="$(mcp_call 28 server_get_block "{\"x\":${SPAWN_X},\"y\":${UNDO_Y},\"z\":${SPAWN_Z}}")" \
  || mcp_failure "server_get_block after the fill restore failed: ${CMD_AFTER}"
case "$CMD_AFTER" in
  *'diamond_block'*) mcp_failure "the diamond is still there after restoring ${CMD_UNDO}: ${CMD_AFTER}" ;;
  *) echo "    /fill left ${CMD_UNDO}, and restoring it put the blocks back" ;;
esac

# ---------------------------------------------------------------------------
# Chunk loading
#
# Nothing loads a chunk implicitly: a write into terrain that was never generated writes nothing and
# generates nothing. A hold loads what it needs and its release gives everything back at once,
# rather than at the next autosave.
# ---------------------------------------------------------------------------

echo "==> Chunk loading: a write far outside the world generates nothing"
FAR_BODY="$(mcp_call 40 server_set_blocks '{"mode":"fill","block":"minecraft:glass","x":900000,"y":100,"z":900000,"toX":900001,"toZ":900001}')" \
  || mcp_failure "the far write failed: ${FAR_BODY}"
case "$FAR_BODY" in
  *'"written":0'*'"unloaded":4'*'"ungenerated":1'*) echo "    nothing written, the chunk reported as ungenerated" ;;
  *) mcp_failure "a write into ungenerated terrain did something: ${FAR_BODY}" ;;
esac

echo "==> Chunk loading: hold a region beside spawn, then release it"
HOLD_BODY="$(mcp_call 41 server_keep_loaded "{\"x\":$((SPAWN_X + 160)),\"z\":${SPAWN_Z},\"toX\":$((SPAWN_X + 191)),\"toZ\":$((SPAWN_Z + 31))}")" \
  || mcp_failure "server_keep_loaded failed: ${HOLD_BODY}"
HOLD_ID="$(printf '%s' "$HOLD_BODY" | grep -o '"id":"h[0-9]*"' | head -1 | cut -d'"' -f4)"
[ -n "$HOLD_ID" ] || mcp_failure "server_keep_loaded returned no hold id: ${HOLD_BODY}"
case "$HOLD_BODY" in
  *'"inBox":'*) echo "    held as ${HOLD_ID}" ;;
  *) mcp_failure "the hold reported no chunks block: ${HOLD_BODY}" ;;
esac
mcp_call 42 server_release_loaded "{\"id\":\"${HOLD_ID}\"}" > /dev/null || mcp_failure "server_release_loaded failed"
sleep 1
LIST_BODY="$(mcp_call 43 server_keep_loaded '{"op":"list"}')" || mcp_failure "op list failed: ${LIST_BODY}"
case "$LIST_BODY" in
  *'"heldChunks":0'*) echo "    released; MCMCP holds nothing" ;;
  *) mcp_failure "chunks still held after release: ${LIST_BODY}" ;;
esac

sed -i "s/B:allowWorldEdits=true/${WORLD_EDITS_WERE:-B:allowWorldEdits=false}/" "$CONFIG_FILE"
mcp_call 25 server_run_command '{"command":"mcmcp reload"}' >/dev/null

# ---------------------------------------------------------------------------
# Request journal
#
# On by default on a dedicated server. Every call above should be in it as a start and an end, and
# game_health must say the journal is on, so an empty unfinished list is never a false all-clear.
# ---------------------------------------------------------------------------

# The companion is on by default on a dedicated server, with an empty allowlist. Its channel being
# registered is what lets players' MCMCP find it; nothing here can join as a player, so the live
# check of the companion itself is the local two-JVM run in docs/dev/testing.md.
echo "==> Checking the companion's generated config"
awk '/^companion \{/,/^\}/' "$CONFIG_FILE" | grep -q 'B:enabled=true' \
  || mcp_failure "companion.enabled is not on by default on a dedicated server"
grep -q 'S:allowedPlayers' "$CONFIG_FILE" || mcp_failure "companion.allowedPlayers was not generated"
grep -q 'Netty.*mcmcp:companion\|MCMCP companion mode' "$LOG" \
  || mcp_failure "the server never reported its companion mode at startup"
echo "    companion on, allowlist generated"

JOURNAL_FILE="${RUN_DIR}/mcmcp/journal/server-requests.log"
echo "==> Checking the request journal at ${JOURNAL_FILE}"
if [ ! -s "$JOURNAL_FILE" ]; then
  mcp_failure "no request journal at ${JOURNAL_FILE}; it should be on by default on a dedicated server"
fi
grep -q '"ev":"open"' "$JOURNAL_FILE" || mcp_failure "the journal has no open line"
grep -q '"ev":"start".*"tool":"server_get_block"' "$JOURNAL_FILE" \
  || mcp_failure "the journal has no start line for server_get_block"
grep -q '"ev":"end".*"status":"ok"' "$JOURNAL_FILE" || mcp_failure "the journal has no successful end line"
if grep -q '"blocks":\[' "$JOURNAL_FILE"; then
  mcp_failure "the journal stored a block list whole; arguments must be summarised"
fi
HEALTH_BODY="$(mcp_call 29 game_health '{}')" || mcp_failure "game_health failed: ${HEALTH_BODY}"
case "$HEALTH_BODY" in
  *'previousRun'*'journal'*'enabled'*) echo "    journal is on, and game_health reports the previous run" ;;
  *) mcp_failure "game_health did not report previousRun with the journal enabled: ${HEALTH_BODY}" ;;
esac

# ---------------------------------------------------------------------------
# Orchestrator link
# ---------------------------------------------------------------------------

echo "==> Checking the instance identity in ${CONFIG_FILE}"
INSTANCE_ID="$(grep -E '^\s*S:instanceId=' "$CONFIG_FILE" | tail -1 | cut -d= -f2- | tr -d '[:space:]')"
INSTANCE_SECRET="$(grep -E '^\s*S:instanceSecret=' "$CONFIG_FILE" | tail -1 | cut -d= -f2- | tr -d '[:space:]')"
if [ -z "$INSTANCE_ID" ]; then
  mcp_failure "no instanceId in ${CONFIG_FILE} — identity generation did not run"
fi
if [ "${#INSTANCE_SECRET}" != "64" ]; then
  mcp_failure "instanceSecret is ${#INSTANCE_SECRET} characters, expected 64"
fi
echo "    instance ${INSTANCE_ID}"

# The stub writes its verdict when it finishes, which is some way after "Done (" — the link dials
# during server startup but the MCP sequence over it takes a moment. Poll rather than assuming.
echo "==> Waiting for the stub orchestrator's verdict"
link_waited=0
while [ "$link_waited" -lt 120 ]; do
  if [ -s "$LINK_RESULT" ]; then
    break
  fi
  if ! kill -0 "$STUB_PID" 2>/dev/null && [ ! -s "$LINK_RESULT" ]; then
    mcp_failure "the stub orchestrator exited without writing a verdict"
  fi
  sleep 2
  link_waited=$((link_waited + 2))
done

if [ ! -s "$LINK_RESULT" ]; then
  echo "----- MCMCP link log lines -----"
  grep -n 'orchestrator' "$LOG" | tail -20
  mcp_failure "the stub orchestrator never reported (waited ${link_waited}s); the link never connected"
fi

LINK_VERDICT="$(head -1 "$LINK_RESULT")"
echo "    ${LINK_VERDICT}"
sed -n '2,$p' "$LINK_RESULT" | sed 's/^/    /'
case "$LINK_VERDICT" in
  PASS) ;;
  *)
    echo "----- MCMCP link log lines -----"
    grep -n 'orchestrator' "$LOG" | tail -20
    mcp_failure "orchestrator link check failed: ${LINK_VERDICT}"
    ;;
esac

echo "==> DELETE session"
curl -fsS --max-time 5 -X DELETE "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Mcp-Session-Id: ${SESSION_ID}" > /dev/null 2>&1 \
  || echo "    (session delete failed; not fatal)"

# ---------------------------------------------------------------------------
# server_stop
#
# The one tool whose success cannot be asserted from its own reply: a stop that answers and then
# does not stop looks exactly like a stop that worked. So this section ends the server for real and
# waits for the JVM to go, which is also what leaves the runner with nothing to clean up.
#
# A fresh session, because the one above has just been deleted.
# ---------------------------------------------------------------------------

echo "==> Re-initializing a session to call server_stop"
STOP_HEADERS="$(mktemp)"
curl -fsS --max-time 10 -D "$STOP_HEADERS" \
  -X POST "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mcmcp-smoke-test","version":"1.0"}}}' \
  > /dev/null 2>&1 \
  || mcp_failure "could not re-initialize a session for server_stop"

STOP_SESSION="$(grep -i '^Mcp-Session-Id:' "$STOP_HEADERS" | tail -1 | cut -d: -f2- | tr -d '[:space:]')"
rm -f "$STOP_HEADERS"
if [ -z "$STOP_SESSION" ]; then
  mcp_failure "the re-initialize did not return an Mcp-Session-Id header"
fi

curl -s -o /dev/null --max-time 10 \
  -X POST "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Mcp-Session-Id: ${STOP_SESSION}" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

echo "==> tools/call server_stop"
STOP_BODY="$(curl -fsS --max-time 10 \
  -X POST "http://127.0.0.1:${MCP_PORT}/mcp" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Mcp-Session-Id: ${STOP_SESSION}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"server_stop","arguments":{}}}' 2>&1)" \
  || mcp_failure "server_stop request failed: ${STOP_BODY}"

# The reply has to arrive ahead of the shutdown. A caller that gets a dropped connection instead
# cannot tell a clean stop from a crash, which is the one case where a clear answer matters most.
case "$STOP_BODY" in
  *'"isError":false'*) echo "    server_stop answered before shutting down" ;;
  *) mcp_failure "server_stop did not return a successful result: ${STOP_BODY}" ;;
esac
case "$STOP_BODY" in
  *'"dedicated": true'*|*'"dedicated":true'*) ;;
  *) mcp_failure "server_stop did not report a dedicated server: ${STOP_BODY}" ;;
esac

echo "==> Waiting for the server process to exit"
stop_waited=0
while [ "$stop_waited" -lt 120 ]; do
  if ! pgrep -f 'net.minecraft.server' > /dev/null 2>&1; then
    break
  fi
  sleep 2
  stop_waited=$((stop_waited + 2))
done
if pgrep -f 'net.minecraft.server' > /dev/null 2>&1; then
  mcp_failure "server_stop answered but the server was still running ${stop_waited}s later"
fi
echo "    server exited after ${stop_waited}s"

# A clean stop writes the journal's close line; a run without one is what previousRun calls unclean.
# Written as the server stops, which can trail the reply by a moment where pgrep is unavailable.
close_waited=0
while [ "$close_waited" -lt 30 ] && ! tail -1 "$JOURNAL_FILE" | grep -q '"ev":"close"'; do
  sleep 1
  close_waited=$((close_waited + 1))
done
tail -1 "$JOURNAL_FILE" | grep -q '"ev":"close"' \
  || mcp_failure "the journal's last line after a clean stop is not a close line: $(tail -1 "$JOURNAL_FILE")"
echo "    the journal recorded a clean exit"

shutdown_server
rm -f "$LINK_RESULT"
echo "==> PASS: dedicated server started, served a full MCP handshake over HTTP, linked to an orchestrator, and stopped itself on request"
exit 0
