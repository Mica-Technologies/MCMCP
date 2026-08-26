# Installation

## Requirements

| | |
| --- | --- |
| Minecraft | 1.12.2 |
| Mod loader | Minecraft Forge for 1.12.2 (14.23.5.2768 or newer) |
| Java | 8, or 17/21 under [lwjgl3ify](https://github.com/GTNewHorizons/lwjgl3ify) |
| Dependencies | None |

MCMCP has no required or optional mod dependencies and pulls in no third-party libraries. It uses the
Gson that ships with Minecraft and the HTTP server built into the JDK, so the jar is the whole
install.

## Installing

1. Build the jar (see [Building](../dev/building.md)) or download a release from
   [GitHub Releases](https://github.com/Mica-Technologies/MCMCP/releases).
2. Drop it into `mods/`.
3. Start the game.

The same jar works on a client and on a dedicated server. Which endpoint runs is decided by
configuration, not by which jar you install.

## First launch

On first start MCMCP writes `config/mcmcp.cfg` and generates a random bearer token into it. The log
records that it did:

```
[mcmcp]: MCMCP generated a new API token and saved it to the config file.
         Retrieve it with '/mcmcp token' in-game, or read config/mcmcp.cfg.
[mcmcp]: MCMCP client endpoint listening on http://127.0.0.1:25585/mcp
```

Confirm it in game:

```
/mcmcp status
```

```
MCMCP endpoints:
  client: http://127.0.0.1:25585/mcp — 0 session(s), 32 tool(s)
```

!!! note "`/mcmcp` requires permission level 2"

    Every subcommand either reveals the endpoint address, reveals the token, or restarts the
    service, so there is no read-only subset that is safe to open up on a shared server. In
    singleplayer you have level 4 automatically; on a server you need to be an operator.

## Getting the token

```
/mcmcp token
```

In game this prints a clickable component that puts the token into your chat input box, ready to copy
with ++ctrl+a++ ++ctrl+c++. It is deliberately not printed as plain chat text — chat scrollback is
visible in screenshots and, on some setups, to logging plugins.

From a server console, or if you would rather read it off disk, it is in `config/mcmcp.cfg`:

```ini
endpoints {
    S:authToken=3f9a1c8e5b7d204f6a1e9c3b8d5f2a70
}
```

Treat it like a password. Anyone holding it can act as you in game. To rotate it, blank the value and
restart, or run `/mcmcp restart` after clearing it.

## Enabling the server endpoint

The server endpoint is **off by default**. It grants authoritative control over everyone's world, so
it needs an explicit decision from whoever operates the server rather than arriving switched on.

In `config/mcmcp.cfg`:

```ini
endpoints {
    B:enableServerEndpoint=true
}
```

Then `/mcmcp restart`, or restart the server.

## Default ports

| Endpoint | Default port |
| --- | --- |
| Client | 25585 |
| Server | 25586 |

Both bind `127.0.0.1`. A singleplayer world runs both endpoints in one process, which is why they
need different ports.

If a port is already in use — a second game instance, or a previous one still exiting — MCMCP logs
the failure and the game continues without that endpoint:

```
[mcmcp]: MCMCP could not start the client endpoint on http://127.0.0.1:25585/mcp:
         Address already in use. The game will run without it; change the port in the
         MCMCP config and use '/mcmcp restart' to try again.
```

A failed bind never takes the game down with it.

## Verifying from outside the game

The health probe needs no authentication and exists to answer one question: is the thing on this port
actually MCMCP?

```bash
curl -s http://127.0.0.1:25585/mcp/health
```

```json
{"service":"mcmcp","side":"client","status":"ok"}
```

It deliberately reports nothing else. Session counts, versions and tool names would be useful to an
operator, but this is the one endpoint reachable without a token.

## Next steps

- [Connecting a client](connecting.md) — wire MCMCP into an MCP client
- [Security model](security.md) — what the token protects and what it does not
- [Configuration](../reference/configuration.md) — every setting, with defaults
