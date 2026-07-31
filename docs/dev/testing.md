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

## Manual client testing

The client endpoint has no automated equivalent. A dev client needs a display, and its most valuable
tools — screenshots, synthetic input, GUI state — are precisely the ones a headless runner cannot
exercise.

```bash
./gradlew runClient
```

Then, against `http://127.0.0.1:25585/mcp`:

1. `mcmcp_endpoint_info` — confirm `side: client` and the permission set.
2. `client_gui_state` at the main menu — confirms it works with no world loaded.
3. Open a world. `client_player_state` — confirm position and surroundings.
4. `client_screenshot` — confirm the file appears at the returned path.
5. `client_look` with `lookAtX/Y/Z`, then `client_looking_at` — confirm the crosshair moved onto the
   intended target.
6. `client_move` forward 20 ticks — confirm `distanceMoved` is non-zero.
7. `client_send_chat` with `/time query daytime`, then `client_read_chat` — confirm the reply arrived.
8. Subscribe to `minecraft://client/chat/recent` over an SSE stream, type in chat, confirm a
   `notifications/resources/updated` arrives.

The [API explorer](../api-explorer.md) makes this sequence considerably less tedious than curl.

## Adding tests

Anything in `protocol/`, `mcp/` or `json/` should have a unit test. Anything that needs a world does
not — put the assertion in the smoke test instead, where a real server can provide one.

Test names are sentences describing the guarantee, not the method under test:
`dropsTheOldestMessageWhenTheQueueIsFull`, not `testEnqueue`. When a test fails months later, the name
is the fastest available explanation of what broke.
