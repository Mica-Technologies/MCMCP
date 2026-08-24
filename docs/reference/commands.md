# Commands

```
/mcmcp <status|tools|sessions|token|reload|restart|stop>
```

One command, seven subcommands. It exists because the failure modes of an embedded network service are
otherwise invisible from inside the game: "is it listening", "did anything connect", "what is my
token" and "pick up my config edit" all have to be answerable without alt-tabbing to a log file.

## Permissions

**Operator only** — permission level 2, in full.

There is no read-only subset. Every subcommand either reveals the endpoint address, reveals the
token, or restarts the service. In singleplayer you have level 4 automatically.

## `/mcmcp status`

The first thing to run when something is not connecting.

```
MCMCP instance: modB dev (modb-dev-3f2a1c)
  client — 1 session(s), 19 tool(s)
    http http://127.0.0.1:25585/mcp — listening
    orchestrator link 127.0.0.1:25580 — connected
  server — 0 session(s), 17 tool(s)
    http http://127.0.0.1:25586/mcp — listening
    orchestrator link 127.0.0.1:25580 — retrying
```

The instance name and id come first, and are shown even when nothing is running. With several games
open at once it is the only thing that says which one is answering.

Each transport is reported separately, because "the endpoint is up" stopped being one fact. An
instance whose HTTP port was taken but whose orchestrator link is connected is a perfectly working
instance; one whose port is fine but whose link is down is a different problem entirely.

With nothing running it reports the configured intent, so you can tell "disabled" from "failed to
bind":

```
No MCMCP endpoint is running.
Client endpoint enabled: true, server endpoint enabled: false. Check the game log for bind errors.
```

An endpoint bound beyond loopback is called out, as is authentication being off:

```
    HTTP authentication is DISABLED.
    Bound beyond loopback and reachable from the network.
```

## `/mcmcp link`

The orchestrator link in detail. Separate from `status` because the answers point in different
directions and someone chasing one is not chasing the other.

```
MCMCP orchestrator link for modB dev (modb-dev-3f2a1c):
  client -> 127.0.0.1:25580: connected — connected to MCMCP Orchestrator 0.1.0
    Named as mod B (dev) in the orchestrator.
```

| State | What to do |
| --- | --- |
| `disabled in the config` | Set `orchestrator.enableOrchestratorLink=true`, then `/mcmcp restart` |
| `connecting` | Nothing; it is mid-handshake |
| `retrying` | Start the orchestrator app |
| `waiting to be approved in the orchestrator` | Approve this instance in the app — it has heard you |
| `connected` | Nothing |
| `stopped` | Something only you can clear; the message says which. The link will not retry |

`stopped` covers a mismatched instance secret, a revoked approval, and an unsupported link protocol
version. Everything else retries forever, including reasons this build has never heard of — a newer
orchestrator inventing one is likelier than a genuinely fatal condition.

## `/mcmcp tools`

Every tool name the running endpoints expose, grouped by endpoint.

Useful for confirming that a tool another mod registered actually arrived, and for seeing the
client/server split first-hand — the same registry, filtered per side.

## `/mcmcp sessions`

```
client endpoint sessions:
  claude-code 2.1.0 — protocol 2025-06-18, up 214s, 1 subscription(s)
  curl 1.0 — protocol 2025-06-18, up 3s, 0 subscription(s), NOT INITIALIZED
```

Client name and version come from the `clientInfo` block sent at `initialize`.

`NOT INITIALIZED` means `notifications/initialized` was never sent — the client completed the
handshake's first half and stopped. That session can only call `ping` until it finishes.

Check this if something is acting on your world that you did not ask for.

## `/mcmcp token`

Shows the bearer token.

To a player, as a **click-to-copy** component: clicking puts it in your chat input box, ready to copy,
without it ever appearing as chat text. That is deliberate — chat scrollback ends up in screenshots
and, on some setups, in logging plugins.

To a console sender, as plain text. A console operator already has the config file.

With `requireAuth` off it says so instead:

```
Authentication is disabled, so there is no token.
Any process that can reach the endpoint can control this game.
```

## `/mcmcp reload`

Re-reads `config/mcmcp.cfg`.

Permission and limit changes take effect immediately — they are read per call. **Network settings do
not**: the bind address, port and worker pool are fixed when the socket is created. The reply says so:

```
MCMCP config reloaded. Network settings take effect on '/mcmcp restart'.
```

## `/mcmcp restart`

Stops both endpoints, re-reads the config, and starts whichever should be running. Then prints
`status`.

A full stop and start rather than mutating live settings, for the reason above: a "reload" that did
not rebind would silently apply only half the file.

Use this after a port conflict, after editing network settings, or when an endpoint is wedged.

## `/mcmcp stop`

Stops both endpoints immediately and releases the ports. Sessions are closed and any pending
server-to-client request is failed rather than left hanging.

```
MCMCP endpoints stopped. Use '/mcmcp restart' to bring them back.
```

The game keeps running. This is the fastest way to cut off access without quitting.

## Tab completion

Subcommand names complete on the first argument.
