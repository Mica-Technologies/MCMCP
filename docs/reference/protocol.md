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
| `tools/call` | Full, including `_meta.progressToken`. |
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

Cancellation and timeouts are the only handler failures reported as JSON-RPC errors. Everything else
becomes a tool result with `isError: true`.

## Error semantics

This distinction matters more than any other in the implementation:

| Situation | Response |
| --- | --- |
| Malformed request, unknown method, missing required parameter | JSON-RPC `error` |
| Session unknown, expired, or not initialized | JSON-RPC `error` |
| Request cancelled or timed out | JSON-RPC `error` |
| **Tool ran and the action failed** | **Successful response with `isError: true`** |

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
| `-32006` | Request timed out |

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
