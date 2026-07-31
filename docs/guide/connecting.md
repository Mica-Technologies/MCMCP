# Connecting a client

MCMCP speaks MCP's **Streamable HTTP** transport. Any client that supports it can connect; clients
that only support stdio need a bridge (see [below](#stdio-only-clients)).

## What you need

| | |
| --- | --- |
| URL | `http://127.0.0.1:25585/mcp` (client endpoint) or `:25586` (server endpoint) |
| Header | `Authorization: Bearer <token>` |
| Token | `/mcmcp token` in game, or `config/mcmcp.cfg` |

## Configuring an MCP client

Most clients accept a remote MCP server as a URL plus headers. The shape varies, but the fields do
not:

=== "Generic JSON config"

    ```json
    {
      "mcpServers": {
        "minecraft": {
          "type": "http",
          "url": "http://127.0.0.1:25585/mcp",
          "headers": {
            "Authorization": "Bearer 3f9a1c8e5b7d204f6a1e9c3b8d5f2a70"
          }
        }
      }
    }
    ```

=== "Claude Code"

    ```bash
    claude mcp add --transport http minecraft http://127.0.0.1:25585/mcp \
      --header "Authorization: Bearer 3f9a1c8e5b7d204f6a1e9c3b8d5f2a70"
    ```

=== "Both endpoints at once"

    ```json
    {
      "mcpServers": {
        "minecraft-client": {
          "type": "http",
          "url": "http://127.0.0.1:25585/mcp",
          "headers": { "Authorization": "Bearer <token>" }
        },
        "minecraft-server": {
          "type": "http",
          "url": "http://127.0.0.1:25586/mcp",
          "headers": { "Authorization": "Bearer <token>" }
        }
      }
    }
    ```

    Both endpoints share one token. Registering both gives a model a camera and player control on
    one and authoritative world queries on the other; name them distinctly so it can tell which is
    which.

Confirm the connection landed with `/mcmcp sessions`:

```
client endpoint sessions:
  claude-code 2.1.0 — protocol 2025-06-18, up 14s, 1 subscription(s)
```

## The handshake by hand

Useful for debugging a client that will not connect, and for understanding what the transport
actually does.

### 1. Health probe (no auth)

```bash
curl -s http://127.0.0.1:25585/mcp/health
```

```json
{"service":"mcmcp","side":"client","status":"ok"}
```

### 2. `initialize`

```bash
curl -s -D headers.txt -X POST http://127.0.0.1:25585/mcp \
  -H "Authorization: Bearer $MCMCP_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2025-06-18",
      "capabilities": {},
      "clientInfo": { "name": "curl", "version": "1.0" }
    }
  }'
```

The response carries the negotiated version, the server's capabilities and its instructions. The
**session id comes back in a header**, not the body:

```bash
grep -i mcp-session-id headers.txt
# Mcp-Session-Id: 8f2c1a04e77b4d6e9c3a5b1d0f8e2a64
```

Every subsequent request must carry that header. Without it you get `400`; with an expired or
unknown one you get `404`, which is the transport's signal to re-initialize.

### 3. `notifications/initialized`

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:25585/mcp \
  -H "Authorization: Bearer $MCMCP_TOKEN" \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
# 202
```

`202 Accepted` with no body is correct — a notification has no response. The session is not usable
for anything but `ping` until this is sent.

### 4. Call something

```bash
curl -s -X POST http://127.0.0.1:25585/mcp \
  -H "Authorization: Bearer $MCMCP_TOKEN" \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
       "params":{"name":"mcmcp_endpoint_info","arguments":{}}}'
```

### 5. Open the event stream (optional)

```bash
curl -N http://127.0.0.1:25585/mcp \
  -H "Authorization: Bearer $MCMCP_TOKEN" \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Accept: text/event-stream'
```

Everything the server originates travels here: resource-change notifications, log records, progress
updates, and the server-to-client requests (sampling, elicitation, roots). A `: keepalive` comment
arrives every 15 seconds so a dead peer is noticed rather than sat on.

Without a stream attached, server-originated messages queue per session — up to 512, oldest dropped
first. A client that never opens the stream still works for request/response; it just misses
notifications.

### 6. Close

```bash
curl -s -X DELETE http://127.0.0.1:25585/mcp \
  -H "Authorization: Bearer $MCMCP_TOKEN" \
  -H "Mcp-Session-Id: $SESSION"
# 204
```

Sessions also expire after 30 minutes of silence, because clients crash without saying goodbye.

## Connecting from another machine

**Do not change `bindAddress`.** Forward the port instead:

```bash
ssh -N -L 25585:127.0.0.1:25585 you@gaming-box
```

The endpoint stays on loopback; SSH provides the encryption and authentication that a raw bound port
would not. See [Security model](security.md) for the reasoning.

## stdio-only clients

Some MCP clients only speak stdio. MCMCP does not implement a stdio transport — it lives inside a
game process that already owns its standard streams — so put a bridge in front of it:

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://127.0.0.1:25585/mcp",
               "--header", "Authorization: Bearer <token>"]
    }
  }
}
```

Any HTTP-to-stdio MCP proxy works; the bridge does the transport translation and MCMCP is unaware
of it.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `Connection refused` | The endpoint is not listening. `/mcmcp status`, and check the log for a bind failure. |
| `401` | Missing or wrong bearer token. `/mcmcp token`. |
| `403` with an origin message | A browser-based client whose `Origin` is not allow-listed. See [`allowedOrigins`](../reference/configuration.md#allowedorigins). |
| `400 Missing Mcp-Session-Id` | The client is not echoing the session header from `initialize`. |
| `404 Unknown or expired session` | Idle timeout, or the endpoint restarted. Re-initialize. |
| `Session is not initialized` | `notifications/initialized` was never sent. |
| Tools listed but every call errors | A permission group is disabled. Call `mcmcp_endpoint_info` — it reports which. |

The [API explorer](../api-explorer.md) drives all of this from a browser if you would rather not use
curl.
