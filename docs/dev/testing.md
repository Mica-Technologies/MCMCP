# Testing

MCMCP is tested at two levels, and the split is deliberate: the wire format is verified without a
game, and the parts that only exist inside a game are verified by starting one.

## Unit tests

JUnit 5, run by `./gradlew test`. They cover `protocol/`, `mcp/` and `json/` — everything that
imports no Minecraft class.

That constraint is what makes them possible. Asserting protocol behaviour through a running game
would be slow enough that it would not get done, so `McpDispatcher` takes a session and a parsed
`JsonObject` and returns a `JsonObject`, and knows nothing about HTTP.

| Class | Covers |
| --- | --- |
| `JsonRpcTest` | Envelope classification, id type preservation, validation |
| `McpProtocolTest` | Version negotiation, feature gating, the pre-initialize gate |
| `McpSessionTest` | Handshake state, subscriptions, outbound queue, cancellation |
| `UriTemplatesTest` | Template matching and containment rules |
| `JsonSchemaTest` | Schema building, required-property validation |
| `JsonTest` | Tolerant accessors and coercion |

```bash
./gradlew test
./gradlew test --tests '*McpSessionTest'
```

### What they are actually protecting

Most of these assert a specific past or plausible mistake rather than restating the implementation:

- **`preservesIdTypeExactly`.** JSON-RPC ids may be strings or numbers, and `"1"` and `1` are
  different requests. Normalising them into a Java type is the classic way to mismatch a response to
  its request.
- **`treatsExplicitNullIdAsNotARequest`.** Treating `{"id": null, "method": "ping"}` as a request
  would leave the client waiting forever for a response that was never coming.
- **`dropsTheOldestMessageWhenTheQueueIsFull`.** Pins the behaviour a client with no attached SSE
  stream depends on. Unbounded growth inside the game process is not an option, and when something
  has to go, the stale notification is worth less than the fresh one.
- **`isNotInitializedUntilTheNotificationArrives`.** `applyInitialize` must not flip the flag; the
  handshake completes on `notifications/initialized`, and a client may hang up in between.
- **`rejectsEmptyVariableBindings`.** Without it, `minecraft://world/chunk//5` parses as a chunk at
  an empty x — a URI nobody meant to write, resolving to a resource that then fails confusingly.
- **`refusesToRequireAnUndeclaredProperty`.** Catches at class-init time what would otherwise ship as
  a schema naming a required property no client can supply.
- **`doesNotHtmlEscapeOutput`.** Gson escapes `< > & = '` by default, which mangles file paths and
  chat text for no benefit — this payload never enters an HTML document.

Assertions that only restate the code are not worth their maintenance cost. These are here because
getting each one wrong produces a bug that is hard to trace back.

## Server smoke test

`.github/scripts/server-smoke-test.sh`, run on every pull request.

It starts a dedicated server via `./gradlew runServer`, waits for `Done (`, then drives a real MCP
handshake against the endpoint that server exposes.

### Why both halves exist

**Can the mod load on a dedicated server?** MCMCP is deliberately full of client-only code —
`ClientThreadBridge`, the screenshot and input tools — and the only thing keeping it off the server is
the proxy split. One import of a client class from common code compiles perfectly and kills the server
at startup with `for invalid side SERVER`. `./gradlew build` is completely happy right up until
production dies.

**Does the endpoint work?** A compiling transport proves nothing about whether it binds,
authenticates, negotiates a version, or lists the right tools for its side. Unit tests cover the wire
format with no socket; only a running server covers the rest.

### What it asserts

1. The server reaches `Done (` without any of a set of known fatal signatures, `for invalid side`
   among them.
2. MCMCP wrote `config/mcmcp.cfg` and generated a token into it.
3. `/mcp/health` responds and identifies itself as the **server** side.
4. An **unauthenticated** `initialize` returns `401`. A regression that accepted anonymous requests
   would pass every other check in the script.
5. An authenticated `initialize` negotiates `2025-06-18`, returns `serverInfo`, and returns an
   `Mcp-Session-Id` header.
6. `notifications/initialized` returns `202`.
7. `tools/list` contains `mcmcp_endpoint_info` (common path) and `server_get_blocks` (server-only
   path).
8. `tools/list` does **not** contain `client_screenshot`. A client-only tool on the server endpoint
   means the side filter is broken, which would hand a model a tool that can never work.
9. `tools/call mcmcp_endpoint_info` returns `isError: false` and reports the server side.
10. `DELETE` cleans up the session.

Every failure path dumps matching failure lines, recent `MCMCP` log lines and the log tail. The server
log is uploaded as an artifact.

### Running it locally

```bash
export JAVA_HOME=~/.jdks/azul-21.0.x
bash .github/scripts/server-smoke-test.sh
```

Environment:

| Variable | Default | Notes |
| --- | --- | --- |
| `SMOKE_TIMEOUT` | `900` | Seconds to wait for startup. World gen on a cold cache is the slow part. |
| `SMOKE_LOG` | `server-smoke.log` | |
| `MCP_PORT` | `25588` | The server endpoint under `runServer` — see [Building](building.md#dev-launch-ports). |

## Orchestrator end-to-end test

`.github/scripts/orchestrator-e2e.sh`, run on every pull request alongside the smoke test.

This is the **only** place the two implementations of the link protocol meet. The mod's unit tests
prove its framing against byte arrays, the orchestrator's prove aggregation against fixtures, and the
smoke test proves the mod dials out and a *stub* can answer. None of them would catch the two real
halves disagreeing — a renamed field, a changed default, a version bump on one side only. That
failure breaks no build. It produces a handshake that never completes, at runtime, on a user's
machine.

It boots a dedicated server, starts the real orchestrator binary *second* — the mod retries its link
on a backoff, so this exercises the retry path and matches the order people actually start things in
— then drives a real MCP client through it: aggregation, instance stamping, focus resolution, a
readable error for an unknown instance, and namespaced resources read back.

```bash
export JAVA_HOME=~/.jdks/azul-21.0.x
cd orchestrator && cargo build -p mcmcp-orchestrator && cd ..
bash .github/scripts/orchestrator-e2e.sh
```

| Variable | Default | Notes |
| --- | --- | --- |
| `SMOKE_TIMEOUT` | `900` | Seconds to wait for the server to start. |
| `LINK_TIMEOUT` | `150` | Seconds to wait for the link once the orchestrator is up. |
| `ORCH_BINARY` | `orchestrator/target/debug/mcmcp-orchestrator` | |

Two things that make a local run fail for reasons that are not the code:

- **The installed orchestrator app listens on `25580`**, which is the port the script's own
  orchestrator wants. With the app running, the game links to *it* rather than to the one under test
  and the script waits out its timeout. Quit the app first. The same applies to the smoke test, whose
  stub also wants `25580` — for that one you can instead point `orchestrator.orchestratorPort` in
  `run/server/config/mcmcp.cfg` at a free port and pass the same value as `LINK_PORT`.
- **Build the headless binary on its own**, not with `cargo build -p mcmcp-orchestrator-app`. The
  desktop app declares the shim as a Tauri `externalBin`, and building it copies that sidecar next to
  the app binary with the target triple stripped — which is exactly cargo's output path for the CLI
  crate, so it overwrites the real binary with the placeholder. The script now detects this and says
  so rather than failing at exec.

## Client endpoint testing

The client endpoint has no CI equivalent — a dev client needs a display, and its most valuable tools
are precisely the ones a headless runner cannot exercise. It is still fully drivable without touching
the window, which is the point of the mod:

```bash
# terminal 1
./gradlew runServer

# terminal 2 — auto-joins, no clicking through the menu
DEV_USERNAME=McmcpDev ./gradlew runClient -PmcJoin=127.0.0.1:25565
```

See [Getting a dev client into a world](building.md#getting-a-dev-client-into-a-world-without-touching-the-gui)
for why `-PmcJoin` exists. From there everything runs over HTTP against `:25585`:

1. `mcmcp_endpoint_info` — confirm `side: client` and the permission set.
2. `tools/list` — 32 tools, no `server_*` among them.
3. `client_gui_state` — `worldLoaded: true`, `screenName: none`.
4. `client_connection_info` — `singleplayer: false`, the server address, the player list.
5. `client_player_state` — position, biome, `standingOn`. **Check that `blockPosition` and
   `standingOn.position` agree on X and Z**; see the `EntityPlayerSP` note below.
6. `client_screenshot` — confirm the file appears at the returned path, then actually open it.
7. `client_look` with `lookAtX/Y/Z`, and check `lookingAt` in the *same* response names the intended
   block.
8. `client_move` forward 20 ticks — `distanceMoved` should be ≈4.
9. `client_send_chat` with a command, then `client_read_chat`.
10. `resources/subscribe` to `minecraft://client/chat/recent`, open an SSE stream, send a chat line,
    confirm a `notifications/resources/updated` frame arrives.

### Two bugs this found

Both were invisible to the unit tests and the smoke test, and both are the kind that only surface
against a running client.

**`EntityPlayerSP.getPosition()` rounds instead of flooring.** It is overridden as
`new BlockPos(posX + 0.5, posY + 0.5, posZ + 0.5)`, so at a block centre — where a player stands after
almost any spawn or teleport — it names the block one over on both horizontal axes.
`client_player_state` reported the player at (35, 64, 13) while its own `standingOn` described
(36, 63, 14). `EntityPlayerMP` has no such override, so the client and server endpoints also disagreed
about the same player. Everything now goes through `GameJson.blockPosOf`, which floors explicitly.

**`mc.objectMouseOver` is a frame stale inside a scheduled task.** It is recomputed by
`EntityRenderer.getMouseOver` during rendering, so a tool that turns the camera and then reads it in
the same task gets the pre-turn target. `client_look` would aim correctly and report `miss` — which
reads as a failed aim and invites a model to correct a rotation that was already right. Post-action
reports now use `ClientStateTools.freshLookTarget`, which ray-traces from the current rotation.
`client_looking_at` still reports `objectMouseOver`, because that is what the game will actually act
on and it is never stale when read standalone.

## Adding tests

Anything in `protocol/`, `mcp/` or `json/` should have a unit test. Anything that needs a world does
not — put the assertion in the smoke test instead, where a real server can provide one.

Test names are sentences describing the guarantee, not the method under test:
`dropsTheOldestMessageWhenTheQueueIsFull`, not `testEnqueue`. When a test fails months later, the name
is the fastest available explanation of what broke.
