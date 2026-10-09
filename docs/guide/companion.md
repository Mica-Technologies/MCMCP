# Server companion

The companion lets an agent use a multiplayer server's tools without the server opening a port. A
player's own MCMCP reaches the MCMCP on the server through the player's game connection. Only the
players an operator names can use it.

Without it, an agent on a remote server can only do what the player can do by hand: chat commands
limited to 256 characters, and reads and writes only in chunks loaded around the player, with a
teleport before each region. With it, the agent gets the server's own tools (`server_get_blocks`,
`server_set_blocks`, `server_run_command`, `server_undo` and the rest), running server-side as that
player.

## How it works

```
agent ──MCP──▶ orchestrator ──link──▶ player's game
                                        ├─ <game>.client   the player's own endpoint
                                        └─ <game>.server   virtual: forwards to the server
                                                 │  mcmcp:companion channel, inside the
                                                 ▼  player's existing game connection
                                        server's MCMCP: the normal server dispatcher,
                                        no HTTP port, no orchestrator link
```

- **Nothing on the server listens.** The server's MCMCP answers only on its `mcmcp:companion`
  channel, inside the connections of players already logged in.
- **The tools are the server's own.** The client forwards MCP messages to the server unchanged, so
  tool names, schemas and descriptions all come from the server's MCMCP build. The two builds don't
  have to match. They check compatibility with each other when the player joins.
- **To an agent it looks like singleplayer.** The orchestrator lists the companion as the game's
  `.server` endpoint, with `"via": "companion"` in [`mcmcp_instances`](../reference/tools.md). A
  `server_*` call made while the client is focused is routed to it automatically. The companion
  exists only while the player is connected and allowed. When it's gone, a call to a server tool
  says so and suggests reconnecting.
- **Players without MCMCP can still join.** MCMCP accepts any version of itself, or none, on the
  other side of a connection. A player with no MCMCP, or a different MCMCP, can join a server that
  runs it.

## Setting it up on a server

1. Put the MCMCP jar in the server's `mods/` folder and start the server once. A dedicated server
   generates a config with the companion **on and its allowlist empty**. The MCP endpoint and the
   orchestrator link are both **off**. The log says so:

    ```
    MCMCP companion mode: nothing listens, and nobody is allowed yet. To let a player's MCMCP use
    this server's tools: /mcmcp companion allow <player>
    ```

2. Allow the players who may use it. Edit `companion.allowedPlayers` in `config/mcmcp.cfg`, one UUID
   (or name) per line, then run `/mcmcp reload`:

    ```
    companion {
        S:allowedPlayers <
            d04d5aaa-c6c9-386f-97c8-ff8571aa906a
         >
    }
    ```

3. The player rejoins. Their MCMCP asks the server for the companion as they join. Their
   `mcmcp_endpoint_info` shows `companion.state: "available"`, and the orchestrator lists
   `<game>.server`.

## Who can use it

A player needs **both**:

- **An allowlist entry** in `companion.allowedPlayers`. An empty list means nobody, so being an
  operator is never enough on its own.
- **The `mcmcp.companion.use` permission node.** It defaults to operators. A permission mod that
  implements Forge's PermissionAPI (FTB Utilities' ranks, for example) can grant or deny it per
  rank.

The player's identity always comes from the connection the request arrives on, never from anything
in the request. A refused player is told why, for example "You are not on this server's MCMCP
companion allowlist. Ask an operator to run /mcmcp companion allow <name>", and stays connected
to the server.

## What it costs the server

- **Replies are paced.** A reply waits in a per-player queue and goes out at most
  `companion.sendBudgetKBPerTick` per tick (512 KB by default, about 10 MB/s). Nothing is sent
  while the player's connection reports that it's full, so a large read never crowds out the
  player's own game traffic. If a reply would overfill the queue (`companion.maxQueuedMB`), it
  becomes a tool error instead.
- **Requests are bounded.** A message from a client may be at most `companion.maxMessageMB` once
  decompressed, and at most `companion.maxOpenMessages` may be partly sent at once.
- **A broken client loses only its companion.** One that breaks either limit, or sends something
  unreadable, has its companion session dropped and logged. The player is never kicked.
- **Every call is recorded.** The server's [request journal](../reference/configuration.md#journal)
  is on by default and records every call the companion runs.

## Status

- On the server: `/mcmcp status` shows `Companion: on, N allowed, M connected`.
- On the client: `mcmcp_endpoint_info` has a `companion` block. Its `state` is one of:

| `state` | Meaning |
| --- | --- |
| `absent` | Singleplayer, or the server has no companion. Nothing was sent to the server. |
| `negotiating` | Asked the server; waiting for the answer. |
| `unauthorised` | The server refused. `detail` says why. |
| `incompatible` | The two MCMCP builds speak different companion protocol versions. |
| `available` | The server's tools are this game's server endpoint. |
