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
MCMCP endpoints:
  client: http://127.0.0.1:25585/mcp — 1 session(s), 19 tool(s)
  server: http://127.0.0.1:25586/mcp — 0 session(s), 17 tool(s)
```

With nothing running it reports the configured intent, so you can tell "disabled" from "failed to
bind":

```
No MCMCP endpoint is running.
Client endpoint enabled: true, server endpoint enabled: false. Check the game log for bind errors.
```

An endpoint bound beyond loopback is called out:

```
    Bound beyond loopback and reachable from the network.
```

as is authentication being off (`, AUTH DISABLED` on the endpoint line).

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
