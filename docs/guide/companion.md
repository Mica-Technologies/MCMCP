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

2. Allow the players who may use it, from the console or in game:

    ```
    /mcmcp companion allow Builder
    ```

    This saves their UUID to `companion.allowedPlayers` and takes effect at once. You can also edit
    the list in `config/mcmcp.cfg`, one UUID or name per line, then run `/mcmcp reload`. Names are
    turned into UUIDs when the server starts.

3. The player's MCMCP asked for the companion when they joined. Once they're allowed, the server
   answers. Their `mcmcp_endpoint_info` shows `companion.state: "available"`, and the orchestrator
   lists `<game>.server`.

## Who can use it, and for what

A player needs **both**:

- **An allowlist entry** in `companion.allowedPlayers`. An empty list means nobody, so being an
  operator is never enough on its own.
- **A permission node for each kind of call.** A permission mod that implements Forge's
  PermissionAPI (FTB Utilities' ranks, for example) can grant or deny each node per rank. Without
  one, the defaults apply:

| Node | Unlocks | Default |
| --- | --- | --- |
| `mcmcp.companion.read` | `server_get_block(s)`, `server_changes_since`, `server_get_tile_entities`, `server_find_blocks`, `server_nearby_entities`, `server_world_info`, `server_list_players`, `server_player_state`, `server_player_inventory`, `server_tick_stats`, `mcmcp_endpoint_info`, `game_list_mods`, `game_health` | operators |
| `mcmcp.companion.write` | `server_set_block(s)`, `server_undo`, `server_teleport_player` | operators |
| `mcmcp.companion.command` | `server_run_command` and `server_run_commands` as yourself, `server_tell_player` | operators |
| `mcmcp.companion.load` | `server_keep_loaded`, `server_release_loaded`, and `load: true` on reads and writes | operators |
| `mcmcp.companion.others` | naming a player other than yourself in those tools | operators |
| `mcmcp.companion.command.console` | `server_run_command` with the console's authority | **nobody** |

Anything not in the table is never served through the companion: `server_stop`, `server_save_world`,
`server_broadcast`, the profilers, the log readers, `game_storage`, and resources and prompts. A
player's `tools/list` shows only the tools their grants unlock.

- **As whom.** The player's identity always comes from the connection a request arrives on, never
  from anything in the request. A `server_run_command` with no `asPlayer` runs as the player, with
  their own command permissions, not with the console's. `blockedCommands` applies either way.
  Naming another player in `asPlayer` or `player` needs `mcmcp.companion.others`.
- **Independent of the endpoint.** The endpoint's `permissions.*` switches don't apply to companion
  calls, and a dedicated server leaves them off. The grants decide.
- **Refusals.** A refused player is told why, for example "You are not on this server's MCMCP
  companion allowlist. Ask an operator to run /mcmcp companion allow <name>", and stays connected
  to the server.
- **Changes apply live.** Grants are re-checked every five seconds and after every
  `/mcmcp companion` or `/mcmcp reload`. Allowing a player hands their client the tools straight
  away. Revoking one, turning the companion off, or removing a node stops their running calls and
  withdraws the tools, with no rejoin.

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
- **Calls per player are capped.** `companion.maxConcurrentCalls` (4) are allowed at once.

## What is recorded

- **The request journal.** The server's [journal](../reference/configuration.md#journal) is on by
  default. It records every companion call with the player it ran as.
- **The server log.** Every write and command gets an INFO line, for example
  `MCMCP companion: Builder server_set_blocks written=9 undoPoint=u1791579262093`.
- **Operators in chat.** Online operators see a grey line in chat, at most one per player every ten
  seconds: `[MCMCP] Builder's agent: server_set_blocks written=9 (and 1 more)`. Turn it off with
  `companion.notifyOps`.
- **Undo points.** Every write leaves an [undo point](../reference/tools.md#server_undo), single
  blocks included, whatever `undo.*` says. The point records who made it (`"by"`).

## Status

- On the server: `/mcmcp status` shows `Companion: on, N allowed, M connected`. `/mcmcp companion`
  lists each connected player's grants and running calls. See
  [commands](../reference/commands.md#mcmcp-companion).
- On the client: `mcmcp_endpoint_info` has a `companion` block. Its `state` is one of:

| `state` | Meaning |
| --- | --- |
| `absent` | Singleplayer, or the server has no companion. Nothing was sent to the server. |
| `negotiating` | Asked the server; waiting for the answer. |
| `unauthorised` | The server refused. `detail` says why. |
| `incompatible` | The two MCMCP builds speak different companion protocol versions. |
| `available` | The server's tools are this game's server endpoint. |
