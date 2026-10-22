# Protocol support

MCMCP implements the Model Context Protocol server role over the Streamable HTTP transport.

## Versions

| Version | Supported | Notes |
| --- | --- | --- |
| `2025-06-18` | Yes | Default. Adds `structuredContent`, elicitation, the `MCP-Protocol-Version` header. Removed batching. |
| `2025-03-26` | Yes | Introduced Streamable HTTP and JSON-RPC batching. |
| `2024-11-05` | Yes | Original HTTP+SSE era. Still what a long tail of shipped clients sends. |

Negotiation follows the spec: the client proposes a version in `initialize`, and MCMCP answers with
that version if it supports it, otherwise with its latest. An unsupported proposal is **not an
error** — it is a negotiation outcome, and returning `-32602` would break clients that would happily
have downgraded, as well as every future client whose version is not yet known here.

The negotiated version is per session. One endpoint routinely serves several clients at once, and
they must not see each other's wire dialect, so nothing downstream reads a global constant.

Version-dependent behaviour:

- `structuredContent` on tool results, and `outputSchema` on tool listings, are emitted only on
  `2025-06-18`.
- Elicitation requires both `2025-06-18` and the client's `elicitation` capability.
- Batches are **accepted** on any version — refusing one a client already sent helps nobody — but
  MCMCP never originates one.

The `MCP-Protocol-Version` header is advisory. An unrecognised value is logged, not rejected; the
version from `initialize` is authoritative.

## Server capabilities

```json
{
  "tools":       { "listChanged": true },
  "resources":   { "subscribe": true, "listChanged": true },
  "prompts":     { "listChanged": true },
  "logging":     {},
  "completions": {}
}
```

Empty objects are the correct declaration for capabilities with no sub-options. Omitting them would
mean "not supported" and stop clients from ever calling `logging/setLevel` or `completion/complete`.

## Methods

### Client to server

| Method | Support |
| --- | --- |
| `initialize` | Full. Returns capabilities, `serverInfo` and `instructions`. |
| `ping` | Full. Allowed before initialization. |
| `tools/list` | Full. No pagination — the catalogue is tens of entries, and pagination that never triggers is pagination that never gets tested. |
| `tools/call` | Full, including `_meta.progressToken`. On the orchestrator link, `_meta["mcmcp/caller"]` names the agent connection a call came from; a direct client is named by its session. |
| `resources/list` | Full. |
| `resources/templates/list` | Full. |
| `resources/read` | Full. |
| `resources/subscribe` | Full. Refuses resources that do not emit updates. |
| `resources/unsubscribe` | Full. |
| `prompts/list` | Full. |
| `prompts/get` | Full. Required arguments validated before the generator runs. |
| `logging/setLevel` | Full. Per-session threshold. |
| `completion/complete` | Resource URIs by prefix. Prompt arguments return empty — see below. |

`completion/complete` fires on every keystroke in a client's UI. Prompt-argument values are world
state that would have to be read on the game thread, and paying that per keystroke is not acceptable;
it belongs behind a cached snapshot, which does not exist yet.

### Server to client

Issued over the SSE stream. All require the client to have declared the matching capability.

| Method | Support |
| --- | --- |
| `sampling/createMessage` | Transport implemented; no built-in tool originates one yet. |
| `elicitation/create` | Transport implemented; requires `2025-06-18`. |
| `roots/list` | Transport implemented. |

`McpSession.sendRequest` returns a `CompletableFuture` that a tool can await. Pending requests are
failed when the session closes, so a tool blocked on a sampling round-trip unblocks on disconnect
rather than sitting on a worker thread until its timeout.

### Notifications

| Notification | Direction | Support |
| --- | --- | --- |
| `notifications/initialized` | in | Completes the handshake. |
| `notifications/cancelled` | in | Cooperative — see below. |
| `notifications/progress` | in | Accepted and ignored (progress on requests MCMCP sent). |
| `notifications/roots/list_changed` | in | Logged. |
| `notifications/message` | out | Log records, filtered per session. |
| `notifications/progress` | out | Emitted only when the caller supplied a `progressToken`. |
| `notifications/tools/list_changed` | out | On registry change. |
| `notifications/resources/list_changed` | out | On registry change. |
| `notifications/resources/updated` | out | To subscribers only. |
| `notifications/prompts/list_changed` | out | On registry change. |

Unknown notifications are ignored by design. JSON-RPC forbids replying to them, and future revisions
add notifications older servers must tolerate.

## Cancellation

`notifications/cancelled` sets a flag on the in-flight request's token. MCMCP **never interrupts a
running handler thread** — a half-applied world mutation is worse than a slightly late cancellation —
so long-running tools check the token between steps.

Cancellation, and on the client endpoint a timeout, are the only handler failures reported as
JSON-RPC errors. Everything else becomes a tool result with `isError: true`, including a server step
that started and did not finish in time (below).

On the server endpoint, a cancellation that arrives while a step is still queued for the server
thread drops that step: it never runs. One that arrives while a step is running waits for it, since
it cannot be stopped halfway.

## Error semantics

This distinction matters more than any other in the implementation:

| Situation | Response |
| --- | --- |
| Malformed request, unknown method, missing required parameter | JSON-RPC `error` |
| Session unknown, expired, or not initialized | JSON-RPC `error` |
| Request cancelled, or a client-thread step timed out | JSON-RPC `error` |
| The server thread never started a step in time (busy or overloaded) | Tool error with a `busy` block |
| The server thread started a step and had not finished it in time | Tool error with a `busy` block, `effectUnknown: true` |
| The server is paused or stalled and the call would change the world | Tool error with a `busy` block, before it runs |
| **Tool ran and the action failed** | **Successful response with `isError: true`** |

On the server endpoint every step a tool hands to the server thread is one of two cases when
`limits.gameThreadTimeoutMillis` runs out:

- **It never started.** It is dropped, and does nothing when the server thread reaches it. The
  call is a tool error whose structured content is
  `{"busy": {"reason": "server_overloaded", "health": "paused", "mspt": 87.2, "heapPercent": 60,
  "queuedTasks": 12, "retryAfterMs": 5000}}`. On a server whose health is `ok` the reason is
  `server_busy` instead: the server is fine, and the step waited behind other callers' work in
  MCMCP's queue, which `queuedTasks` counts. Nothing ran, so retrying after `retryAfterMs` cannot do anything twice.
  Background jobs wait and retry the step themselves. A step still queued when the server stops is
  dropped at once, and the call is a tool error saying nothing changed. When earlier steps of the
  same call had already run, the block also says `"earlierStepsRan": true`, and the message says the
  call may have changed the world in part: look before retrying it. A step that finishes a write
  already made, such as saving its undo point, goes to the front of the server's queue and waits up
  to 60 s instead, so the write is not left without its undo point.
- **It had started.** It runs to its end, since nothing can stop it halfway, and the call is a tool
  error whose structured content is `{"busy": {"reason": "started_not_finished", "effectUnknown":
  true, "ranForMs": 5003, "timeoutMs": 5000, "health": "ok", ...}}`, with no `retryAfterMs`. Its
  message says plainly that the step's effect is not known, that some, all or none of it may have
  happened, and to check the world before retrying, or it may be done twice. Background jobs do not
  retry it either: the part fails. Until #133 this was a `-32006` JSON-RPC timeout, which clients
  often swallowed, so the model resent the identical call.

While the server is `paused` (mean tick over [`health.refuseWritesMspt`](configuration.md#health),
100 ms by default, a dedicated server's real TPS below
[`health.refuseWritesBelowTps`](configuration.md#health), 8 by default, or short of memory) or `stalled` (its thread has
finished no tick for [`health.stallDumpSeconds`](configuration.md#health)), a call to a tool that is
not read-only is refused before it runs, with the same `busy` block. Read-only tools still run, and
so do the ones needed to see what is happening and make MCMCP stop or let go: `server_estop`,
`server_release_loaded`, every `server_job_*`, `game_health`, `server_tick_stats`,
`mcmcp_endpoint_info`, and everything the [emergency stop](../guide/jobs.md#the-emergency-stop) lets through. The
client endpoint is never refused for the server's load.

A tool error is not a protocol error. "There is no player logged in", "that block is out of range",
"movement control is disabled in the config" are all results the model needs to read and react to. A
JSON-RPC error is consumed by the client's plumbing and frequently never reaches the model, which
then retries the identical call forever.

### Codes

Standard JSON-RPC: `-32700` parse, `-32600` invalid request, `-32601` method not found, `-32602`
invalid params, `-32603` internal.

MCMCP adds four in the implementation-defined server range, so a client can distinguish "the thing you
named does not exist" from "your request was shaped wrong":

| Code | Meaning |
| --- | --- |
| `-32002` | Resource not found |
| `-32003` | Tool not found |
| `-32004` | Prompt not found |
| `-32005` | Request cancelled |
| `-32006` | Request timed out (the client endpoint only) |

## Transport

Streamable HTTP, one path.

| Method | Behaviour |
| --- | --- |
| `POST` | One JSON-RPC message, or an array. Requests get `200` with a JSON response; notifications and responses get `202` with no body. |
| `GET` with `Accept: text/event-stream` | Opens the server-to-client stream. |
| `GET` without it | `405`, with an explanation. |
| `DELETE` | Terminates the session. `204`. |
| `OPTIONS` | CORS preflight. `204`. |

### Status codes

| Code | Meaning |
| --- | --- |
| `200` | Response body follows |
| `202` | Notification or response accepted; nothing to return |
| `204` | Session deleted, or preflight |
| `400` | Parse error, malformed envelope, or missing `Mcp-Session-Id` |
| `401` | Missing or invalid bearer token |
| `403` | Origin not allow-listed |
| `404` | Unknown or expired session — **re-initialize** |
| `405` | Unsupported HTTP method |
| `413` | Body over 4 MB |
| `503` | Endpoint at its session limit |

`404` specifically is the transport's signal that a session is gone; compliant clients re-initialize
automatically rather than failing.

### Sessions

`initialize` mints a session and returns its id in the `Mcp-Session-Id` response header. Every
subsequent request must echo it.

Ids are 128 bits from `SecureRandom`, hex-encoded. Possession of one is enough to keep issuing
requests on an already-authenticated session, which is why they are not sequential or
timestamp-derived.

A second `initialize` always creates a fresh session, even if one was named. Re-initializing an
existing session would leave its version, subscriptions and log level from the previous handshake in
place — a subtle state to debug.

### The event stream

```
id: 1
event: message
data: {"jsonrpc":"2.0","method":"notifications/resources/updated","params":{...}}

: keepalive

```

Keep-alive comments every 15 seconds. An idle TCP connection to a process that has gone away looks
identical to a healthy one until something is written to it; the keep-alive is what surfaces the
difference.

Without an attached stream, server-originated messages queue per session — 512 maximum, oldest
dropped first. A client that never opens the stream still works for request/response and simply
misses notifications.

Each open stream holds one HTTP worker thread for its lifetime, which is why the pool is sized
`workerThreads + maxSessions`. Sizing it for POSTs alone means the first few clients to open streams
consume every thread and all subsequent requests hang — with no error, which makes it a genuinely
nasty thing to diagnose.

### Not implemented

- **Resumability.** `Last-Event-ID` is accepted in CORS headers but replay is not implemented. A
  reconnecting client gets whatever is still queued.
- **OAuth.** MCP's authorization spec is not implemented. Authentication is a static bearer token —
  appropriate for a loopback service, insufficient for anything else, which is why loopback is the
  default and SSH tunnelling is the documented way out.
- **stdio transport.** MCMCP lives inside a game process that already owns its standard streams. Use
  an HTTP-to-stdio bridge.
