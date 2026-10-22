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

# An emergency stop survives a restart; one left by an earlier local run would refuse every write.
rm -f "${RUN_DIR}/world/mcmcp-estop.json"

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

# No player ever joins this server, so the overworld stops ticking tile entities 300 ticks after it
# starts (#101). The idle count is read by reflection; -1 would mean the field was not found.
printf '%s' "$WORLD_BODY" | "$PYTHON_BIN" -c '
import json, sys
body = json.load(sys.stdin)
for dim in body["result"]["structuredContent"]["dimensions"]:
    assert "entitiesTicking" in dim, dim
    idle = dim.get("idleTicks", 0)
    assert idle != -1, "the idle tick count could not be read"
    assert dim["entitiesTicking"] == (idle < 300), dim
' || mcp_failure "server_world_info did not report entitiesTicking consistently: ${WORLD_BODY}"
echo "    every dimension reports entitiesTicking"

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
MINE_BODY="$(mcp_call 221 server_undo '{"op":"list","by":"me"}')" || mcp_failure "server_undo list by me failed: ${MINE_BODY}"
case "$MINE_BODY" in
  *"\"id\":\"${UNDO_ID}\""*'"yours":true'*) echo "    listed as this caller's own with by: me" ;;
  *) mcp_failure "server_undo list by me did not include ${UNDO_ID} as yours: ${MINE_BODY}" ;;
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

echo "==> Region reads: packed and surface"
PACKED_BODY="$(mcp_call 44 server_get_blocks "{\"x\":${SPAWN_X},\"y\":$((SPAWN_Y - 4)),\"z\":${SPAWN_Z},\"toX\":$((SPAWN_X + 31)),\"toY\":$((SPAWN_Y + 3)),\"toZ\":$((SPAWN_Z + 31)),\"encoding\":\"packed\"}")" \
  || mcp_failure "packed read failed: ${PACKED_BODY}"
case "$PACKED_BODY" in
  *'"encoding":"rle-varint+zlib+base64"'*'"count":8192'*) echo "    8,192 cells packed" ;;
  *) mcp_failure "packed read did not return a packed index array: ${PACKED_BODY}" ;;
esac
SURFACE_BODY="$(mcp_call 45 server_get_blocks "{\"x\":${SPAWN_X},\"y\":255,\"z\":${SPAWN_Z},\"toX\":$((SPAWN_X + 15)),\"toY\":0,\"toZ\":$((SPAWN_Z + 15)),\"mode\":\"surface\"}")" \
  || mcp_failure "surface read failed: ${SURFACE_BODY}"
case "$SURFACE_BODY" in
  *'"mode":"surface"'*'"heights":'*) echo "    surface of 256 columns read" ;;
  *) mcp_failure "surface read did not return heights: ${SURFACE_BODY}" ;;
esac

echo "==> Region reads: boxes, slices and states"
BOX_Y=$((UNDO_Y + 10))
WOOL_BODY="$(mcp_call 570 server_set_blocks "{\"mode\":\"fill\",\"block\":\"minecraft:wool\",\"metadata\":3,\"x\":$((SPAWN_X + 1)),\"y\":${BOX_Y},\"z\":$((SPAWN_Z + 1)),\"toX\":$((SPAWN_X + 3)),\"toY\":$((BOX_Y + 1)),\"toZ\":$((SPAWN_Z + 3))}")" \
  || mcp_failure "the wool fill failed: ${WOOL_BODY}"
WOOL_UNDO="$(printf '%s' "$WOOL_BODY" | grep -o '"undoPoint":"u[0-9]*"' | head -1 | sed 's/.*"\(u[0-9]*\)"/\1/')"
BOXES_BODY="$(mcp_call 571 server_get_blocks "{\"x\":${SPAWN_X},\"y\":$((BOX_Y - 1)),\"z\":${SPAWN_Z},\"toX\":$((SPAWN_X + 4)),\"toY\":$((BOX_Y + 2)),\"toZ\":$((SPAWN_Z + 4)),\"format\":\"boxes\",\"states\":true}")" \
  || mcp_failure "boxes read failed: ${BOXES_BODY}"
case "$BOXES_BODY" in
  *"\"boxes\":[[1,$((SPAWN_X + 1)),${BOX_Y},$((SPAWN_Z + 1)),$((SPAWN_X + 3)),$((BOX_Y + 1)),$((SPAWN_Z + 3))]]"*) echo "    18 wool cells in 100 came back as one box" ;;
  *) mcp_failure "boxes read did not merge the wool into one box: ${BOXES_BODY}" ;;
esac
case "$BOXES_BODY" in
  *'"color":"light_blue"'*'"stateToString":{"color":"lightBlue"}'*) echo "    states named as blockstate JSON names them, with toString beside" ;;
  *) mcp_failure "states did not name light blue wool both ways: ${BOXES_BODY}" ;;
esac
SLICE_BODY="$(mcp_call 572 server_get_blocks "{\"x\":${SPAWN_X},\"y\":${BOX_Y},\"z\":${SPAWN_Z},\"toX\":$((SPAWN_X + 4)),\"toZ\":$((SPAWN_Z + 4)),\"format\":\"slice\"}")" \
  || mcp_failure "slice read failed: ${SLICE_BODY}"
case "$SLICE_BODY" in
  *"\"w\":\"minecraft:wool:3\""*"|.www.\""*) echo "    a floor plan drew the wool as w" ;;
  *) mcp_failure "slice read did not draw the wool: ${SLICE_BODY}" ;;
esac
STATES_BODY="$(mcp_call 573 game_block_states '{"block":"minecraft:wool","metas":[0,3]}')" \
  || mcp_failure "game_block_states failed: ${STATES_BODY}"
case "$STATES_BODY" in
  *'"meta":3'*'"color":"light_blue"'*'"validStates":16'*) echo "    game_block_states decoded wool meta 3 with no world write" ;;
  *) mcp_failure "game_block_states did not decode wool: ${STATES_BODY}" ;;
esac
[ -n "$WOOL_UNDO" ] && mcp_call 574 server_undo "{\"op\":\"restore\",\"id\":\"${WOOL_UNDO}\"}" > /dev/null

echo "==> Change tracking: MCMCP's own writes are not listed as someone else's"
TOKEN_BODY="$(mcp_call 46 server_changes_since "{\"x\":${SPAWN_X},\"z\":${SPAWN_Z},\"toX\":$((SPAWN_X + 15)),\"toZ\":$((SPAWN_Z + 15))}")" \
  || mcp_failure "server_changes_since failed: ${TOKEN_BODY}"
CHANGE_TOKEN="$(printf '%s' "$TOKEN_BODY" | grep -o '"token":"[0-9]*"' | head -1 | cut -d'"' -f4)"
[ -n "$CHANGE_TOKEN" ] || mcp_failure "no change token: ${TOKEN_BODY}"
mcp_call 47 server_set_blocks "{\"mode\":\"fill\",\"block\":\"minecraft:glass\",\"x\":$((SPAWN_X + 5)),\"y\":$((UNDO_Y + 4)),\"z\":$((SPAWN_Z + 5)),\"toX\":$((SPAWN_X + 6)),\"toZ\":$((SPAWN_Z + 6))}" > /dev/null \
  || mcp_failure "the tracked write failed"
SINCE_BODY="$(mcp_call 48 server_changes_since "{\"x\":${SPAWN_X},\"z\":${SPAWN_Z},\"toX\":$((SPAWN_X + 15)),\"toZ\":$((SPAWN_Z + 15)),\"since\":\"${CHANGE_TOKEN}\"}")" \
  || mcp_failure "server_changes_since with a token failed: ${SINCE_BODY}"
case "$SINCE_BODY" in
  *'"count":0'*'"overflow":false'*|*'"overflow":false'*'"count":0'*) echo "    MCMCP's write is not reported as an outside change" ;;
  *) mcp_failure "change tracking listed MCMCP's own write: ${SINCE_BODY}" ;;
esac

echo "==> Palette writes: a cell that no longer holds what was expected is not overwritten"
PX=$((SPAWN_X + 8)); PY=$((UNDO_Y + 6)); PZ=$((SPAWN_Z + 8))
mcp_call 49 server_set_blocks "{\"mode\":\"fill\",\"block\":\"minecraft:air\",\"x\":${PX},\"y\":${PY},\"z\":${PZ},\"toX\":$((PX + 3)),\"toZ\":${PZ}}" > /dev/null
mcp_call 50 server_set_block "{\"x\":$((PX + 1)),\"y\":${PY},\"z\":${PZ},\"block\":\"minecraft:stone\"}" > /dev/null
PALETTE_BODY="$(mcp_call 51 server_set_blocks "{\"mode\":\"palette\",\"palette\":[\"minecraft:wool:14\"],\"expect\":\"minecraft:air\",\"origin\":{\"x\":${PX},\"y\":${PY},\"z\":${PZ}},\"cells\":[0,0,0,0,1,0,0,0,2,0,0,0,3,0,0,0]}")" \
  || mcp_failure "palette write failed: ${PALETTE_BODY}"
case "$PALETTE_BODY" in
  *'"applied":3'*'"conflicts":1'*) echo "    3 written, the stone cell left as it was" ;;
  *) mcp_failure "compare-and-set did not skip the changed cell: ${PALETTE_BODY}" ;;
esac

echo "==> server_run_commands: many commands, one structured reply"
RUN_BODY="$(mcp_call 52 server_run_commands "{\"commands\":[\"setblock ${PX} $((PY + 1)) ${PZ} minecraft:glowstone\",\"setblock 0 300 0 minecraft:stone\",\"say batch done\"]}")" \
  || mcp_failure "server_run_commands failed: ${RUN_BODY}"
case "$RUN_BODY" in
  *'"ran":3'*'"i":1,"succeeded":false'*) echo "    3 run, the out-of-world one reported as failed" ;;
  *) mcp_failure "server_run_commands did not report per command: ${RUN_BODY}" ;;
esac
case "$RUN_BODY" in
  *'"warning":"'*' of 3 command(s) failed; see results.'*) echo "    the failure is counted at the top, not only in its line" ;;
  *) mcp_failure "a failed command was not flagged at the top level: ${RUN_BODY}" ;;
esac
FAR_X=$((SPAWN_X + 3000))
FAR_BODY="$(mcp_call 609 server_run_commands "{\"commands\":[\"setblock ${FAR_X} 70 ${SPAWN_Z} minecraft:stone\"],\"load\":false}")" \
  || mcp_failure "a write with load off failed outright: ${FAR_BODY}"
case "$FAR_BODY" in
  *'"failed":1'*'"warning":'*'run them with load on'*) echo "    load: false into an unloaded chunk fails, and says to load" ;;
  *) mcp_failure "a write into an unloaded chunk with load off was not flagged: ${FAR_BODY}" ;;
esac
NEAR_BODY="$(mcp_call 610 server_run_commands "{\"commands\":[\"setblock ${FAR_X} 70 ${SPAWN_Z} minecraft:stone\"]}")" \
  || mcp_failure "a write with the default load failed outright: ${NEAR_BODY}"
case "$NEAR_BODY" in
  *'"chunks":{"inBox":1,'*'"ungenerated":1'*'never generated'*) echo "    by default the write tried to load its chunk, and says that one was never generated" ;;
  *) mcp_failure "the default did not try to load the chunk, or did not say why it could not: ${NEAR_BODY}" ;;
esac
LOADBOX_BODY="$(mcp_call 594 server_run_commands "{\"commands\":[\"say no coordinates here\"],\"loadBoxes\":[[$((SPAWN_X + 2000)),${SPAWN_Z},0]]}")" \
  || mcp_failure "server_run_commands with loadBoxes failed: ${LOADBOX_BODY}"
case "$LOADBOX_BODY" in
  *'"chunks":{"inBox":1,'*'"noCoordinatesRead":1'*) echo "    loadBoxes held a chunk no command named, and said none was read from the text" ;;
  *) mcp_failure "loadBoxes did not load its box: ${LOADBOX_BODY}" ;;
esac
PROF_BODY="$(mcp_call 611 server_run_command "{\"command\":\"fill ${SPAWN_X} $((UNDO_Y + 20)) ${SPAWN_Z} $((SPAWN_X + 15)) $((UNDO_Y + 23)) $((SPAWN_Z + 15)) minecraft:stone\",\"profile\":{\"interval_ms\":1}}")" \
  || mcp_failure "server_run_command with profile failed: ${PROF_BODY}"
case "$PROF_BODY" in
  *'"tickMs":'*'"commandMs":'*'"sample":{"scope":"commands"'*) echo "    profile measured the command's tick and its own time, and sampled it" ;;
  *) mcp_failure "server_run_command's profile did not report tickMs, commandMs and a sample: ${PROF_BODY}" ;;
esac
PROFS_BODY="$(mcp_call 612 server_run_commands "{\"commands\":[\"fill ${SPAWN_X} $((UNDO_Y + 20)) ${SPAWN_Z} $((SPAWN_X + 15)) $((UNDO_Y + 23)) $((SPAWN_Z + 15)) minecraft:air\",\"say profiled\"],\"profile\":{}}")" \
  || mcp_failure "server_run_commands with profile failed: ${PROFS_BODY}"
case "$PROFS_BODY" in
  *'"tickMs":'*'"slowestCommands":[{"i":0,'*'"sample":{'*) echo "    a profiled batch names its slowest command, the fill" ;;
  *) mcp_failure "server_run_commands' profile did not report the slowest command: ${PROFS_BODY}" ;;
esac
# at moves the player a command runs as (#127). No player joins this server, so what is checked is
# the refusal: the console has no position to move, and nothing runs.
AT_BODY="$(mcp_call 921 server_run_commands "{\"commands\":[{\"command\":\"say from there\",\"at\":{\"x\":${SPAWN_X},\"y\":70,\"z\":${SPAWN_Z}}}]}")" \
  || mcp_failure "server_run_commands with at failed outright: ${AT_BODY}"
case "$AT_BODY" in
  *'"isError":true'*'needs asPlayer'* | *'needs asPlayer'*'"isError":true'*) echo "    at without asPlayer is refused as a tool error" ;;
  *) mcp_failure "at without asPlayer was not refused: ${AT_BODY}" ;;
esac
AT_ONE_BODY="$(mcp_call 922 server_run_command "{\"command\":\"say from there\",\"at\":{\"x\":${SPAWN_X},\"y\":70,\"z\":${SPAWN_Z}}}")" \
  || mcp_failure "server_run_command with at failed outright: ${AT_ONE_BODY}"
case "$AT_ONE_BODY" in
  *'"isError":true'*'needs asPlayer'* | *'needs asPlayer'*'"isError":true'*) echo "    and so is server_run_command's" ;;
  *) mcp_failure "server_run_command's at without asPlayer was not refused: ${AT_ONE_BODY}" ;;
esac
# loadMargin grows each command's load box (#119): a point 16 blocks out each way is 3 x 3 chunks.
MARGIN_BODY="$(mcp_call 923 server_run_commands "{\"commands\":[\"setblock ${FAR_X} 70 ${SPAWN_Z} minecraft:stone\"],\"loadMargin\":16}")" \
  || mcp_failure "server_run_commands with loadMargin failed outright: ${MARGIN_BODY}"
case "$MARGIN_BODY" in
  *'"chunks":{"inBox":9,'*) echo "    loadMargin 16 grew one point's chunk to nine" ;;
  *) mcp_failure "loadMargin did not grow the load box: ${MARGIN_BODY}" ;;
esac
# A reply naming something unloaded is quoted at the top. The player name is echoed in the reply.
UNLOADED_BODY="$(mcp_call 924 server_run_commands "{\"commands\":[\"scoreboard players list landing_unloaded\"]}")" \
  || mcp_failure "server_run_commands with an unloaded reply failed outright: ${UNLOADED_BODY}"
case "$UNLOADED_BODY" in
  *'"saysUnloaded":true'*'"warning":"'*'replied that something was unloaded'*'#0 '*'landing_unloaded'*) echo "    a reply saying unloaded is quoted in the top-level warning" ;;
  *) mcp_failure "an unloaded reply was not quoted at the top: ${UNLOADED_BODY}" ;;
esac

echo "==> Tile entities: one call reads a box's, filtered, projected and paged"
TX=$((SPAWN_X + 12)); TY=$((UNDO_Y + 8)); TZ=$((SPAWN_Z + 12))
mcp_call 53 server_set_blocks "{\"mode\":\"palette\",\"palette\":[\"minecraft:chest\",{\"block\":\"minecraft:standing_sign\",\"nbt\":\"{Text1:\\\"{\\\\\\\"text\\\\\\\":\\\\\\\"Smoke\\\\\\\"}\\\"}\"}],\"origin\":{\"x\":${TX},\"y\":${TY},\"z\":${TZ}},\"cells\":[0,0,0,0,2,0,0,0,4,0,0,1]}" > /dev/null \
  || mcp_failure "placing tile entities failed"
TE_BODY="$(mcp_call 54 server_get_tile_entities "{\"x\":${TX},\"y\":${TY},\"z\":${TZ},\"toX\":$((TX + 4)),\"toY\":${TY},\"toZ\":${TZ},\"blocks\":[\"minecraft:chest\"],\"keys\":[\"id\"],\"limit\":1}")" \
  || mcp_failure "server_get_tile_entities failed: ${TE_BODY}"
case "$TE_BODY" in
  *'"matched":2'*'"returned":1'*'"next":1'*) echo "    2 chests matched, paged one at a time, the sign filtered out" ;;
  *) mcp_failure "server_get_tile_entities did not filter or page: ${TE_BODY}" ;;
esac
TE_POS_BODY="$(mcp_call 541 server_get_tile_entities "{\"positions\":[{\"x\":${TX},\"y\":${TY},\"z\":${TZ}},{\"x\":$((TX + 1)),\"y\":${TY},\"z\":${TZ}}],\"summary\":true}")" \
  || mcp_failure "server_get_tile_entities positions failed: ${TE_POS_BODY}"
case "$TE_POS_BODY" in
  *'"matched":1'*'"returned":2'*'"summary"'*'No tile entity here'*) echo "    positions: the chest summarised, the empty cell reported" ;;
  *) mcp_failure "server_get_tile_entities positions/summary wrong: ${TE_POS_BODY}" ;;
esac
TE_ALL_BODY="$(mcp_call 605 server_get_tile_entities "{\"x\":${TX},\"y\":${TY},\"z\":${TZ},\"toX\":$((TX + 4)),\"toY\":${TY},\"toZ\":${TZ},\"keys\":[\"id\"]}")" \
  || mcp_failure "reading every tile entity failed: ${TE_ALL_BODY}"
case "$TE_ALL_BODY" in
  *'"tileEntities":{"columns":['*'"rows":[['*) echo "    three tile entities came back as columns and rows" ;;
  *) mcp_failure "three tile entities were not a table: ${TE_ALL_BODY}" ;;
esac
# groupBy reads in server-thread slices chunk by chunk (#132), and still counts everything it read.
TE_GROUP_BODY="$(mcp_call 1001 server_get_tile_entities "{\"x\":${TX},\"y\":${TY},\"z\":${TZ},\"toX\":$((TX + 4)),\"toY\":${TY},\"toZ\":${TZ},\"groupBy\":[\"id\"]}")" \
  || mcp_failure "groupBy failed: ${TE_GROUP_BODY}"
case "$TE_GROUP_BODY" in
  *'"truncated"'*) mcp_failure "groupBy over three tile entities said it was truncated: ${TE_GROUP_BODY}" ;;
  *'"matched":3'*'"groupCount":2'*) echo "    groupBy: two chests and a sign in two groups" ;;
  *) mcp_failure "groupBy did not group the three tile entities: ${TE_GROUP_BODY}" ;;
esac

echo "==> Two-part blocks: a door's lower half brings its upper half, and replaces a different door"
DX=$((SPAWN_X + 16)); DY=$((UNDO_Y + 10)); DZ=$((SPAWN_Z + 16))
mcp_call 542 server_set_blocks "{\"mode\":\"fill\",\"block\":\"minecraft:stone\",\"x\":${DX},\"y\":${DY},\"z\":${DZ}}" > /dev/null
DOOR_BODY="$(mcp_call 543 server_set_blocks "{\"mode\":\"list\",\"blocks\":[{\"x\":${DX},\"y\":$((DY + 1)),\"z\":${DZ},\"block\":\"minecraft:wooden_door\",\"metadata\":1,\"upper\":9}]}")" \
  || mcp_failure "placing a door failed: ${DOOR_BODY}"
case "$DOOR_BODY" in
  *'"written":1'*'"partnersAdded":1'*) ;;
  *) mcp_failure "the door was not written as a pair: ${DOOR_BODY}" ;;
esac
UPPER_BODY="$(mcp_call 544 server_get_block "{\"x\":${DX},\"y\":$((DY + 2)),\"z\":${DZ}}")" \
  || mcp_failure "reading the upper half failed: ${UPPER_BODY}"
case "$UPPER_BODY" in
  *'wooden_door'*'"metadata":9'*) echo "    the upper half is there, with the hinge asked for" ;;
  *) mcp_failure "no upper half above the door: ${UPPER_BODY}" ;;
esac
SWAP_BODY="$(mcp_call 545 server_set_blocks "{\"mode\":\"palette\",\"palette\":[\"minecraft:iron_door:1\"],\"origin\":{\"x\":${DX},\"y\":$((DY + 1)),\"z\":${DZ}},\"cells\":[0,0,0,0]}")" \
  || mcp_failure "swapping the door failed: ${SWAP_BODY}"
SWAPPED_BODY="$(mcp_call 546 server_get_block "{\"x\":${DX},\"y\":$((DY + 2)),\"z\":${DZ}}")" \
  || mcp_failure "reading the swapped upper half failed: ${SWAPPED_BODY}"
case "$SWAPPED_BODY" in
  *'iron_door'*) echo "    a palette write swapped it for an iron door, both halves" ;;
  *) mcp_failure "the swap left the old upper half: ${SWAP_BODY} / ${SWAPPED_BODY}" ;;
esac
case "$SWAP_BODY" in
  *"\"derivedAt\":[{\"x\":${DX},\"y\":$((DY + 2)),\"z\":${DZ}}]"*) echo "    the made-up upper half is named in derivedAt" ;;
  *) mcp_failure "the derived upper half was not listed: ${SWAP_BODY}" ;;
esac
mcp_call 591 server_set_blocks "{\"mode\":\"fill\",\"block\":\"minecraft:stone\",\"x\":$((DX + 2)),\"y\":${DY},\"z\":${DZ}}" > /dev/null
ALONE_BODY="$(mcp_call 592 server_set_blocks "{\"mode\":\"list\",\"pairTwoPart\":false,\"neighbourUpdates\":false,\"blocks\":[{\"x\":$((DX + 2)),\"y\":$((DY + 1)),\"z\":${DZ},\"block\":\"minecraft:wooden_door\",\"metadata\":1}]}")" \
  || mcp_failure "writing an unpaired door failed: ${ALONE_BODY}"
ABOVE_BODY="$(mcp_call 593 server_get_block "{\"x\":$((DX + 2)),\"y\":$((DY + 2)),\"z\":${DZ}}")" \
  || mcp_failure "reading above the unpaired door failed: ${ABOVE_BODY}"
case "$ABOVE_BODY" in
  *'wooden_door'*) mcp_failure "pairTwoPart:false still derived an upper half: ${ALONE_BODY}" ;;
  *) echo "    pairTwoPart:false wrote the one cell given and nothing above it" ;;
esac

echo "==> Replaying fill and setblock lines on the server, writing only the difference"
AX=$((SPAWN_X + 20)); AY=$((UNDO_Y + 10)); AZ=$((SPAWN_Z + 20))
OPS="[\"fill ${AX} ${AY} ${AZ} $((AX + 2)) ${AY} $((AZ + 2)) minecraft:stone\",\"setblock $((AX + 1)) $((AY + 1)) $((AZ + 1)) minecraft:wooden_door 0\",\"setblock $((AX + 1)) $((AY + 2)) $((AZ + 1)) minecraft:wooden_door 8\",\"fill ${AX} ${AY} ${AZ} $((AX + 2)) ${AY} $((AZ + 2)) minecraft:planks 0 replace minecraft:stone\",\"setblock ${AX} $((AY + 1)) ${AZ} minecraft:glass\",\"say not a build op\"]"
mcp_call 553 server_set_blocks "{\"mode\":\"fill\",\"block\":\"minecraft:air\",\"x\":${AX},\"y\":${AY},\"z\":${AZ},\"toX\":$((AX + 2)),\"toY\":$((AY + 2)),\"toZ\":$((AZ + 2))}" > /dev/null
DRY_BODY="$(mcp_call 550 server_apply_ops "{\"ops\":${OPS},\"dryRun\":true}")" \
  || mcp_failure "server_apply_ops dry run failed: ${DRY_BODY}"
case "$DRY_BODY" in
  *'"attempted":12'*'"wouldWrite":'*'"refusedOps"'*'"sample"'*) ;;
  *) mcp_failure "the dry run did not replay the ops: ${DRY_BODY}" ;;
esac
APPLY_BODY="$(mcp_call 551 server_apply_ops "{\"ops\":${OPS}}")" \
  || mcp_failure "server_apply_ops failed: ${APPLY_BODY}"
case "$APPLY_BODY" in
  *'"attempted":12,"written":'*'"refused":0'*'"byPartner":1'*) ;;
  *) mcp_failure "server_apply_ops did not write the replay, door as a pair: ${APPLY_BODY}" ;;
esac
AGAIN_BODY="$(mcp_call 552 server_apply_ops "{\"ops\":${OPS}}")" \
  || mcp_failure "replaying again failed: ${AGAIN_BODY}"
case "$AGAIN_BODY" in
  *'"attempted":12,"written":0,"unchanged":12'*) echo "    replayed, door paired, and a second replay wrote nothing" ;;
  *) mcp_failure "a second replay was not all unchanged: ${AGAIN_BODY}" ;;
esac

echo "==> Counting and relighting a box on the server"
COUNT_BODY="$(mcp_call 548 server_count_blocks "{\"x\":${DX},\"y\":${DY},\"z\":${DZ},\"toY\":$((DY + 2)),\"include\":[\"minecraft:iron_door\",\"minecraft:stone\"]}")" \
  || mcp_failure "server_count_blocks failed: ${COUNT_BODY}"
case "$COUNT_BODY" in
  *'"counted":3'*'"block":"minecraft:iron_door","count":2'*) echo "    2 door halves and the stone under them, nothing else" ;;
  *) mcp_failure "server_count_blocks counted wrong: ${COUNT_BODY}" ;;
esac
RELIGHT_BODY="$(mcp_call 549 server_relight "{\"x\":${DX},\"y\":${DY},\"z\":${DZ},\"toX\":$((DX + 15)),\"toY\":$((DY + 3)),\"toZ\":$((DZ + 15))}")" \
  || mcp_failure "server_relight failed: ${RELIGHT_BODY}"
case "$RELIGHT_BODY" in
  *'"cells":1024'*) echo "    1,024 cells relit" ;;
  *) mcp_failure "server_relight did not relight the box: ${RELIGHT_BODY}" ;;
esac

echo "==> Watching blocks: a change made during a watch is recorded with its tick"
WX=$((PX + 2)); WY=$((PY + 2)); WZ=${PZ}
# The world outlives a local run, so clear what the last run left before watching for a change.
mcp_call 547 server_set_block "{\"x\":${WX},\"y\":${WY},\"z\":${WZ},\"block\":\"minecraft:air\"}" > /dev/null
WATCH_BODY="$(mcp_call 55 server_watch_blocks "{\"x\":${WX},\"y\":${WY},\"z\":${WZ},\"ticks\":2000,\"wait\":false,\"hold\":true}")" \
  || mcp_failure "server_watch_blocks start failed: ${WATCH_BODY}"
case "$WATCH_BODY" in
  *'"hold":"h'*) echo "    the watch holds its chunk for its span" ;;
  *) mcp_failure "a watch with hold: true took no hold: ${WATCH_BODY}" ;;
esac
WATCH_ID="$(printf '%s' "$WATCH_BODY" | grep -o '"id":"w[0-9]*"' | head -1 | cut -d'"' -f4)"
[ -n "$WATCH_ID" ] || mcp_failure "no watch id: ${WATCH_BODY}"
mcp_call 56 server_set_block "{\"x\":${WX},\"y\":${WY},\"z\":${WZ},\"block\":\"minecraft:wool\",\"metadata\":14}" > /dev/null \
  || mcp_failure "the watched write failed"
TICK_BODY="$(mcp_call 57 server_wait_tick '{"modulo":2}')" || mcp_failure "server_wait_tick failed: ${TICK_BODY}"
case "$TICK_BODY" in
  *'"tick":'*) echo "    waited for the next even tick" ;;
  *) mcp_failure "server_wait_tick did not return a tick: ${TICK_BODY}" ;;
esac
STOP_BODY="$(mcp_call 58 server_watch_blocks "{\"op\":\"stop\",\"id\":\"${WATCH_ID}\"}")" \
  || mcp_failure "server_watch_blocks stop failed: ${STOP_BODY}"
case "$STOP_BODY" in
  *'"changeCount":1'*'wool[color=red]'*) echo "    the red wool was recorded as one change" ;;
  *) mcp_failure "the watch did not record the write: ${STOP_BODY}" ;;
esac

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
RELEASE_BODY="$(mcp_call 42 server_release_loaded "{\"id\":\"${HOLD_ID}\"}")" \
  || mcp_failure "server_release_loaded failed: ${RELEASE_BODY}"
case "$RELEASE_BODY" in
  *'"released":["'"${HOLD_ID}"'"]'*) echo "    release answered with JSON naming ${HOLD_ID}" ;;
  *) mcp_failure "server_release_loaded did not return its released ids as JSON: ${RELEASE_BODY}" ;;
esac
LIST_BODY="$(mcp_call 43 server_keep_loaded '{"op":"list"}')" || mcp_failure "op list failed: ${LIST_BODY}"
case "$LIST_BODY" in
  *'"heldChunks":0'*) echo "    released; MCMCP holds nothing" ;;
  *) mcp_failure "chunks still held after release: ${LIST_BODY}" ;;
esac

echo "==> Activating a block with nobody online: MCMCP's fake player flips a lever"
LX=$((SPAWN_X + 30)); LY=$((UNDO_Y + 12)); LZ=$((SPAWN_Z + 30))
mcp_call 560 server_set_blocks "{\"mode\":\"list\",\"blocks\":[{\"x\":${LX},\"y\":${LY},\"z\":${LZ},\"block\":\"minecraft:stone\"},{\"x\":${LX},\"y\":$((LY + 1)),\"z\":${LZ},\"block\":\"minecraft:lever\",\"metadata\":5}]}" > /dev/null \
  || mcp_failure "could not place the lever"
LEVER_BODY="$(mcp_call 561 server_activate_block "{\"x\":${LX},\"y\":$((LY + 1)),\"z\":${LZ}}")" \
  || mcp_failure "server_activate_block with no player failed: ${LEVER_BODY}"
case "$LEVER_BODY" in
  *'"fakePlayer":true'*'"changed":true'*|*'"changed":true'*'"fakePlayer":true'*) echo "    the lever flipped, clicked by the fake player" ;;
  *) mcp_failure "the fake player did not flip the lever: ${LEVER_BODY}" ;;
esac

echo "==> Removing entities with nobody near: two summoned villagers go, nothing else"
mcp_call 562 server_run_commands "{\"commands\":[\"summon minecraft:villager ${LX} $((LY + 1)) $((LZ + 2)) {NoAI:1b}\",\"summon minecraft:villager $((LX + 1)) $((LY + 1)) $((LZ + 2)) {NoAI:1b}\"]}" > /dev/null \
  || mcp_failure "could not summon the villagers"
# server_get_entity_nbt / server_set_entity_nbt (#126): the nearest villager's NBT is read, a key merged
# in, and the change reported before and after as the entity holds it, with the SNBT that puts it back.
ENBT_READ="$(mcp_call 5621 server_get_entity_nbt "{\"x\":${LX},\"y\":$((LY + 1)),\"z\":$((LZ + 2)),\"type\":\"villager\",\"keys\":[\"Invulnerable\"]}")" \
  || mcp_failure "server_get_entity_nbt failed: ${ENBT_READ}"
case "$ENBT_READ" in
  *'"matched":2'*'"nbt":{"Invulnerable":0}'*) echo "    read the nearest villager's NBT, narrowed to one key" ;;
  *) mcp_failure "server_get_entity_nbt did not read the villager: ${ENBT_READ}" ;;
esac
ENBT_WRITE="$(mcp_call 5622 server_set_entity_nbt "{\"x\":${LX},\"y\":$((LY + 1)),\"z\":$((LZ + 2)),\"type\":\"villager\",\"nbt\":\"{Invulnerable:1b}\"}")" \
  || mcp_failure "server_set_entity_nbt failed: ${ENBT_WRITE}"
case "$ENBT_WRITE" in
  *'"Invulnerable":{"before":0,"after":1}'*'"restore":"{'*'Invulnerable:0b'*) echo "    merged a key, reported before and after, and how to put it back" ;;
  *) mcp_failure "server_set_entity_nbt did not report the write: ${ENBT_WRITE}" ;;
esac
DRY_BODY="$(mcp_call 563 server_remove_entities "{\"types\":[\"villager\"],\"x\":$((LX - 2)),\"y\":${LY},\"z\":${LZ},\"toX\":$((LX + 3)),\"toY\":$((LY + 3)),\"toZ\":$((LZ + 4)),\"dryRun\":true}")" \
  || mcp_failure "server_remove_entities dry run failed: ${DRY_BODY}"
case "$DRY_BODY" in
  *'"wouldRemove":2'*) echo "    a dry run counts both" ;;
  *) mcp_failure "the dry run did not count two villagers: ${DRY_BODY}" ;;
esac
REMOVE_BODY="$(mcp_call 564 server_remove_entities "{\"types\":[\"villager\"],\"x\":$((LX - 2)),\"y\":${LY},\"z\":${LZ},\"toX\":$((LX + 3)),\"toY\":$((LY + 3)),\"toZ\":$((LZ + 4))}")" \
  || mcp_failure "server_remove_entities failed: ${REMOVE_BODY}"
AFTER_BODY="$(mcp_call 565 server_remove_entities "{\"types\":[\"villager\"],\"x\":$((LX - 2)),\"y\":${LY},\"z\":${LZ},\"toX\":$((LX + 3)),\"toY\":$((LY + 3)),\"toZ\":$((LZ + 4)),\"dryRun\":true}")" \
  || mcp_failure "the check after removing failed: ${AFTER_BODY}"
case "$REMOVE_BODY$AFTER_BODY" in
  *'"removed":2'*'"wouldRemove":0'*) echo "    both removed, none left" ;;
  *) mcp_failure "the villagers were not removed: ${REMOVE_BODY} then ${AFTER_BODY}" ;;
esac

echo "==> A block that checks its neighbours is whole before it hears from them (#85)"
# A cactus checks its footing only when a neighbour changes, as CSM's beds and LBE's cabinets check
# their other cells. Written before the sand under it, with a second cactus on top written in between,
# it is told of the one above at once, finds no sand, and drops. Held to the end of the slice, both
# stay. A cactus with no sand at all must be reported as not kept, and its drop counted.
NX=$((SPAWN_X + 40)); NY=$((UNDO_Y + 20)); NZ=$((SPAWN_Z + 40))
mcp_call 566 server_set_blocks "{\"mode\":\"fill\",\"block\":\"minecraft:air\",\"x\":${NX},\"y\":$((NY - 1)),\"z\":${NZ},\"toX\":$((NX + 7)),\"toY\":$((NY + 2)),\"toZ\":${NZ}}" > /dev/null
mcp_call 567 server_remove_entities "{\"types\":[\"item\"],\"x\":$((NX - 2)),\"y\":$((NY - 2)),\"z\":$((NZ - 2)),\"toX\":$((NX + 9)),\"toY\":$((NY + 4)),\"toZ\":$((NZ + 2))}" > /dev/null
CACTUS_BODY="$(mcp_call 568 server_set_blocks "{\"mode\":\"palette\",\"palette\":[\"minecraft:cactus\",\"minecraft:sand\",\"minecraft:stone\"],\"origin\":{\"x\":${NX},\"y\":${NY},\"z\":${NZ}},\"cells\":[0,1,0,0, 0,2,0,0, 0,0,0,1, 0,-1,0,2, 5,1,0,0, 6,1,0,2]}")" \
  || mcp_failure "the cactus write failed: ${CACTUS_BODY}"
CACTUS_AT="$(mcp_call 569 server_get_block "{\"x\":${NX},\"y\":$((NY + 1)),\"z\":${NZ}}")"
case "$CACTUS_AT" in
  *'minecraft:cactus'*) echo "    the cactus written before its sand stayed" ;;
  *) mcp_failure "the cactus written before its sand dropped: ${CACTUS_BODY} / ${CACTUS_AT}" ;;
esac
case "$CACTUS_BODY" in
  *'"notKept":1'*'"itemsDropped":1'*) echo "    the cactus with no sand was reported as not kept, and its drop counted" ;;
  *) mcp_failure "the cactus with no sand was not reported: ${CACTUS_BODY}" ;;
esac

echo "==> holdPhysics holds a write's sand still, then reports it and lets it fall (#118)"
# Sand on stone on a stone floor. Digging the stone out with holdPhysics takes the tick the dig gave
# the sand out of the world's queue for the call, reports the sand as unsupported, and puts the tick
# back at the end, so the sand drops onto the floor once the call returns.
HX=$((SPAWN_X + 46)); HY=$((UNDO_Y + 20)); HZ=$((SPAWN_Z + 46))
mcp_call 811 server_set_blocks "{\"mode\":\"fill\",\"block\":\"minecraft:air\",\"neighbourUpdates\":false,\"x\":${HX},\"y\":$((HY - 1)),\"z\":${HZ},\"toY\":$((HY + 3))}" > /dev/null
SETUP_BODY="$(mcp_call 812 server_set_blocks "{\"mode\":\"palette\",\"neighbourUpdates\":false,\"palette\":[\"minecraft:stone\",\"minecraft:sand\"],\"origin\":{\"x\":${HX},\"y\":${HY},\"z\":${HZ}},\"cells\":[0,-1,0,0, 0,0,0,0, 0,1,0,1]}")" \
  || mcp_failure "writing the sand on stone failed: ${SETUP_BODY}"
case "$SETUP_BODY" in
  *'"physics"'*) mcp_failure "a write without holdPhysics reported physics: ${SETUP_BODY}" ;;
esac
HOLD_BODY="$(mcp_call 813 server_set_blocks "{\"mode\":\"palette\",\"holdPhysics\":true,\"palette\":[\"minecraft:air\"],\"origin\":{\"x\":${HX},\"y\":${HY},\"z\":${HZ}},\"cells\":[0,0,0,0]}")" \
  || mcp_failure "the holdPhysics dig failed: ${HOLD_BODY}"
case "$HOLD_BODY" in
  *'"physics":{"ticksHeld":'[1-9]*'"gravityUnsupported":1,'*'minecraft:sand'*) echo "    the sand's tick was held and the sand reported unsupported" ;;
  *) mcp_failure "holdPhysics did not hold and report the sand: ${HOLD_BODY}" ;;
esac
sleep 1
FELL_BODY="$(mcp_call 814 server_get_block "{\"x\":${HX},\"y\":${HY},\"z\":${HZ}}")" \
  || mcp_failure "reading under the sand failed: ${FELL_BODY}"
case "$FELL_BODY" in
  *'minecraft:sand'*) echo "    and once the call ended its tick ran and it fell onto the floor" ;;
  *) mcp_failure "the held sand did not fall once the call ended: ${HOLD_BODY} / ${FELL_BODY}" ;;
esac

echo "==> Background jobs: an op replay and a paged read run to the end"
BX=$((SPAWN_X + 24)); BY=$((UNDO_Y + 10)); BZ=$((SPAWN_Z + 24))
JOB_BODY="$(mcp_call 60 server_job_submit "{\"kind\":\"apply_ops\",\"label\":\"smoke\",\"args\":{\"ops\":[\"fill ${BX} ${BY} ${BZ} $((BX + 3)) ${BY} $((BZ + 3)) minecraft:stone\",\"setblock ${BX} $((BY + 1)) ${BZ} minecraft:torch\"]}}")" \
  || mcp_failure "server_job_submit failed: ${JOB_BODY}"
JOB_ID="$(printf '%s' "$JOB_BODY" | grep -o '"id":"j[0-9a-z]*"' | head -1 | cut -d'"' -f4)"
[ -n "$JOB_ID" ] || mcp_failure "no job id: ${JOB_BODY}"
JOB_STATE=""
for _ in $(seq 1 30); do
  JOB_STATE="$(mcp_call 61 server_job_status "{\"id\":\"${JOB_ID}\"}")" || mcp_failure "server_job_status failed: ${JOB_STATE}"
  case "$JOB_STATE" in *'"state":"done"'*|*'"state":"failed"'*) break ;; esac
  sleep 1
done
case "$JOB_STATE" in
  *'"state":"done"'*'"lastOp":1'*'"totals":{'*'"attempted":17'*) echo "    ${JOB_ID} replayed both ops in the background" ;;
  *) mcp_failure "the apply_ops job did not finish: ${JOB_STATE}" ;;
esac
READ_JOB="$(mcp_call 62 server_job_submit "{\"kind\":\"get_blocks\",\"args\":{\"x\":${BX},\"y\":${BY},\"z\":${BZ},\"toX\":$((BX + 199)),\"toY\":${BY},\"toZ\":$((BZ + 199)),\"encoding\":\"packed\"}}")" \
  || mcp_failure "a get_blocks job failed to start: ${READ_JOB}"
READ_ID="$(printf '%s' "$READ_JOB" | grep -o '"id":"j[0-9a-z]*"' | head -1 | cut -d'"' -f4)"
for _ in $(seq 1 30); do
  READ_STATE="$(mcp_call 63 server_job_status "{\"id\":\"${READ_ID}\"}")" || mcp_failure "status failed: ${READ_STATE}"
  case "$READ_STATE" in *'"state":"done"'*|*'"state":"failed"'*) break ;; esac
  sleep 1
done
case "$READ_STATE" in
  *'"state":"done"'*'"parts":2'*'"pages":2'*) ;;
  *) mcp_failure "the 40,000-cell read did not finish in two pages: ${READ_STATE}" ;;
esac
PAGE_BODY="$(mcp_call 64 server_job_status "{\"id\":\"${READ_ID}\",\"page\":1}")" || mcp_failure "page read failed: ${PAGE_BODY}"
case "$PAGE_BODY" in
  *'"content":{"region":'*'"result":'*) echo "    a 200 x 200 read came back as two pages" ;;
  *) mcp_failure "page 1 had no region and result: ${PAGE_BODY}" ;;
esac

echo "==> Test scripts: power a lamp, wait for it, check, and put the world back"
LX=$((SPAWN_X + 30)); LY=$((UNDO_Y + 12)); LZ=$((SPAWN_Z + 30))
mcp_call 595 server_set_block "{\"x\":${LX},\"y\":${LY},\"z\":${LZ},\"block\":\"minecraft:redstone_lamp\"}" > /dev/null \
  || mcp_failure "placing the lamp failed"
# Single quotes keep the script's own ${...} away from the shell; the coordinates go in after.
SCRIPT='{"vars":{"x":LXV,"y":LYV,"z":LZV},"steps":[{"call":"server_set_block","args":{"x":"${x+1}","y":"${y}","z":"${z}","block":"minecraft:redstone_block"},"restore":true},{"waitUntil":{"call":"server_get_block","args":{"x":"${x}","y":"${y}","z":"${z}"},"until":{"path":"block","equals":"minecraft:lit_redstone_lamp"},"timeout":5,"every":0.1},"save":"lit"},{"check":"${lit.met}","label":"the lamp lit"},{"row":{"lamp":"${x}","lit":"${lit.met}","polls":"${lit.polls}"}}]}'
SCRIPT="${SCRIPT//LXV/$LX}"; SCRIPT="${SCRIPT//LYV/$LY}"; SCRIPT="${SCRIPT//LZV/$LZ}"
TEST_BODY="$(mcp_call 596 server_run_test "$SCRIPT")" || mcp_failure "server_run_test failed: ${TEST_BODY}"
case "$TEST_BODY" in
  *'"passed":true'*"\"table\":{\"columns\":[\"lamp\",\"lit\",\"polls\"],\"rows\":[[${LX},true,"*'"restored":1'*) echo "    the lamp lit, the check passed, one row, one restore" ;;
  *) mcp_failure "the lamp script did not pass and restore: ${TEST_BODY}" ;;
esac
BEHIND_BODY="$(mcp_call 597 server_get_block "{\"x\":$((LX + 1)),\"y\":${LY},\"z\":${LZ}}")" \
  || mcp_failure "reading the restored cell failed: ${BEHIND_BODY}"
case "$BEHIND_BODY" in
  *'redstone_block'*) mcp_failure "the redstone block was left behind: ${BEHIND_BODY}" ;;
  *) echo "    the redstone block is gone again" ;;
esac
BAD_BODY="$(mcp_call 598 server_run_test '{"steps":[{"check":"${true}"},{"call":"server_get_block","args":{"xx":1}}]}')" \
  || mcp_failure "server_run_test with a bad step failed outright: ${BAD_BODY}"
case "$BAD_BODY" in
  *'Nothing was run'*"Unknown argument 'xx'"*'"isError":true'*) echo "    a misspelt argument in step 2 stopped step 1 too" ;;
  *) mcp_failure "a bad step was not refused before running: ${BAD_BODY}" ;;
esac
TJOB_BODY="$(mcp_call 599 server_job_submit '{"kind":"test","label":"smoke test","args":{"steps":[{"check":"${1 + 1 == 2}","label":"arithmetic"}]}}')" \
  || mcp_failure "a test job failed to start: ${TJOB_BODY}"
TJOB_ID="$(printf '%s' "$TJOB_BODY" | grep -o '"id":"j[0-9a-z]*"' | head -1 | cut -d'"' -f4)"
# One call that waits, not a poll a second: it answers when the job's state changes.
TJOB_STATE="$(mcp_call 606 server_job_status "{\"id\":\"${TJOB_ID}\",\"wait\":30}")" || mcp_failure "status failed: ${TJOB_STATE}"
case "$TJOB_STATE" in *'"state":"running"'*|*'"state":"queued"'*)
  TJOB_STATE="$(mcp_call 607 server_job_status "{\"id\":\"${TJOB_ID}\",\"wait\":30}")" || mcp_failure "status failed: ${TJOB_STATE}" ;;
esac
case "$TJOB_STATE" in
  *'"state":"done"'*'"verdict":{"passed":true'*) echo "    a test job's verdict came back from a waiting status call" ;;
  *) mcp_failure "the test job did not give a verdict: ${TJOB_STATE}" ;;
esac
BATCH_SCRIPT='{"steps":[{"call":"server_get_block","args":{"x":BXV,"y":BYV,"z":BZV},"save":"a"},{"call":"server_world_info","args":{},"save":"w"}],"return":["a","w"]}'
BATCH_SCRIPT="${BATCH_SCRIPT//BXV/$LX}"; BATCH_SCRIPT="${BATCH_SCRIPT//BYV/$LY}"; BATCH_SCRIPT="${BATCH_SCRIPT//BZV/$LZ}"
BATCH_BODY="$(mcp_call 608 server_run_test "$BATCH_SCRIPT")" || mcp_failure "a batch of reads failed: ${BATCH_BODY}"
case "$BATCH_BODY" in
  *'"values":{"a":{'*'redstone_lamp'*'"w":{'*) echo "    two reads came back in one call through return" ;;
  *) mcp_failure "return did not hand back the saved reads: ${BATCH_BODY}" ;;
esac
MORE_SCRIPT='{"vars":{"x":MXV,"y":MYV,"z":MZV},"defs":{"note":[{"set":{"noted":true}}]},"steps":[{"mark":"start"},{"call":"server_run_commands","args":{"commands":["setblock ${x} ${y} ${z} minecraft:diamond_block"]},"restore":[["${x}","${y}","${z}"]]},{"repeat":5,"until":"${repeatIndex >= 2}","steps":[{"call":"server_get_block","args":{"x":"${x}","y":"${y}","z":"${z}"},"append":"reads"}]},{"check":"${len(reads) == 3}","label":"repeat stopped at its until"},{"check":"${reads[0].block == '"'"'minecraft:diamond_block'"'"'}","label":"the command wrote"},{"sleepUntil":1,"since":"start"},{"check":"${since.start >= 1}","label":"slept until a second after the mark"},{"include":"note"},{"check":"${noted}","label":"include ran"}]}'
MORE_SCRIPT="${MORE_SCRIPT//MXV/$((LX + 5))}"; MORE_SCRIPT="${MORE_SCRIPT//MYV/$LY}"; MORE_SCRIPT="${MORE_SCRIPT//MZV/$LZ}"
MORE_BODY="$(mcp_call 603 server_run_test "$MORE_SCRIPT")" || mcp_failure "the second script failed: ${MORE_BODY}"
case "$MORE_BODY" in
  *'"passed":true'*'"checksPassed":4'*'"restored":1'*) echo "    repeat, append, sleepUntil, include, and a /setblock put back by its named cell" ;;
  *) mcp_failure "the second script did not pass: ${MORE_BODY}" ;;
esac
DIAMOND_BODY="$(mcp_call 604 server_get_block "{\"x\":$((LX + 5)),\"y\":${LY},\"z\":${LZ}}")" || mcp_failure "reading the named cell failed"
case "$DIAMOND_BODY" in
  *'diamond_block'*) mcp_failure "the /setblock inside a command was not put back: ${DIAMOND_BODY}" ;;
esac
CANCEL_SCRIPT='{"kind":"test","args":{"steps":[{"call":"server_set_block","args":{"x":CXV,"y":CYV,"z":CZV,"block":"minecraft:gold_block"},"restore":true},{"sleep":60}]}}'
CANCEL_SCRIPT="${CANCEL_SCRIPT//CXV/$((LX + 3))}"; CANCEL_SCRIPT="${CANCEL_SCRIPT//CYV/$LY}"; CANCEL_SCRIPT="${CANCEL_SCRIPT//CZV/$LZ}"
CJOB_BODY="$(mcp_call 600 server_job_submit "$CANCEL_SCRIPT")" || mcp_failure "the cancel test job failed to start: ${CJOB_BODY}"
CJOB_ID="$(printf '%s' "$CJOB_BODY" | grep -o '"id":"j[0-9a-z]*"' | head -1 | cut -d'"' -f4)"
sleep 3
mcp_call 601 server_job_cancel "{\"id\":\"${CJOB_ID}\"}" > /dev/null || mcp_failure "cancelling the test job failed"
for _ in $(seq 1 20); do
  CJOB_STATE="$(mcp_call 61 server_job_status "{\"id\":\"${CJOB_ID}\"}")" || mcp_failure "status failed: ${CJOB_STATE}"
  case "$CJOB_STATE" in *'"state":"running"'*|*'"state":"queued"'*) sleep 1 ;; *) break ;; esac
done
GOLD_BODY="$(mcp_call 602 server_get_block "{\"x\":$((LX + 3)),\"y\":${LY},\"z\":${LZ}}")" || mcp_failure "reading the cancelled job's cell failed"
case "$GOLD_BODY" in
  *'gold_block'*) mcp_failure "a cancelled test left its block behind: ${CJOB_STATE} / ${GOLD_BODY}" ;;
  *) case "$CJOB_STATE" in *'"state":"cancelled"'*'"stopped":"cancelled"'*) ;; *) mcp_failure "the cancelled test job did not end cancelled with its verdict: ${CJOB_STATE}" ;; esac
     echo "    a test cancelled mid-sleep ended cancelled and still put its block back" ;;
esac

echo "==> Emergency stop: an agent can set it, and then nothing writes"
ESTOP_BODY="$(mcp_call 65 server_estop '{"reason":"smoke test"}')" || mcp_failure "server_estop failed: ${ESTOP_BODY}"
STOPPED_BODY="$(mcp_call 66 server_set_block "{\"x\":${BX},\"y\":$((BY + 2)),\"z\":${BZ},\"block\":\"minecraft:stone\"}")" \
  || mcp_failure "server_set_block during the stop failed at the protocol level: ${STOPPED_BODY}"
case "$STOPPED_BODY" in
  *'emergency-stopped'*'"isError":true'*) ;;
  *) mcp_failure "a write ran during an emergency stop: ${STOPPED_BODY}" ;;
esac
STOPPED_LIST="$(mcp_call 67 server_job_list '{}')" || mcp_failure "server_job_list failed: ${STOPPED_LIST}"
case "$STOPPED_LIST" in
  *'"estop":'*) echo "    writes refused, reads still answered" ;;
  *) mcp_failure "server_job_list did not report the stop: ${STOPPED_LIST}" ;;
esac
[ -f "${RUN_DIR}/world/mcmcp-estop.json" ] || mcp_failure "the emergency stop was not saved with the world"

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
rm -f "${RUN_DIR}/world/mcmcp-estop.json"

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
