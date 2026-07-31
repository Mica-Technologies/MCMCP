# Tools

33 tools ship built in. Each declares which endpoints it is available on; the registry filters both
the listing and the call path, so a tool never appears on an endpoint that cannot run it.

Every tool also carries MCP annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`,
`openWorldHint`). Clients use these — many auto-approve read-only tools and require confirmation for
destructive ones — so they are set honestly rather than defensively.

!!! tip "Try them live"

    The [API explorer](../api-explorer.md) lists the tools your own instance actually exposes,
    with their real schemas, and will call them for you.

## Common

Available on both endpoints.

### `mcmcp_endpoint_info`

:material-eye: Read-only · no arguments

Reports which side the endpoint runs on, which protocol versions it speaks, which permission groups
are enabled, and the configured limits.

**Call this first.** Several tool families can be turned off in configuration, and disabled tools stay
listed rather than vanishing — so this is how a client learns what will actually work before it plans
around something that will not.

```json
{
  "side": "client",
  "gameAvailable": true,
  "negotiatedProtocolVersion": "2025-06-18",
  "permissions": {
    "commands": true, "playerControl": true, "inventoryChanges": true,
    "worldEdits": false, "screenshots": true, "logAccess": true, "chat": true
  },
  "limits": { "maxScanRadius": 32, "maxInputTicks": 200, "maxLogLines": 500 }
}
```

### `game_list_mods`

:material-eye: Read-only · no arguments

Every loaded mod, with id, name and version.

Not trivia on a modded instance: what can be crafted, what a block does, and what mechanics exist all
depend on which mods are present.

### `game_read_log`

:material-eye: Read-only

| Argument | Type | Notes |
| --- | --- | --- |
| `lines` | integer 1–5000 | Default 100. Capped by `limits.maxLogLines`. |
| `filter` | string | Case-insensitive substring. |
| `file` | string | File name inside `logs/`. Default `latest.log`. No path separators. |

Reads the tail of a log file, seeking backwards from the end. A filtered read scans up to 200,000
lines back; an unfiltered one stops as soon as it has enough.

Returns the lines as text, with a structured form alongside.

## Server endpoint

### World inspection

#### `server_get_block`

:material-eye: Read-only · `x`, `y`, `z` required · optional `dimension`

Block id, metadata, state properties, light levels, hardness and biome at one position.

Reports `loaded: false` without reading if the chunk is not loaded. `getBlockState` on an unloaded
position silently returns air, so a naive read would confidently describe a mountain as empty space —
and on a server, asking can force a chunk load, turning a read into a write with a disk hit.

#### `server_get_blocks`

:material-eye: Read-only

| Argument | Type | Notes |
| --- | --- | --- |
| `x`, `y`, `z` | integer | One corner. Required. |
| `toX`, `toY`, `toZ` | integer | Opposite corner. Default: same as `x`/`y`/`z`. |
| `dimension` | integer | Default 0. |
| `format` | `palette` \| `summary` \| `list` | Default `palette`. |
| `includeAir` | boolean | Defaults true for `palette`, false otherwise. |

Reads a whole cuboid in one call. **This is how to survey before building.**

The `palette` format is what Minecraft itself uses for chunk sections: a palette of the distinct
blocks present, plus a flat array of indices into it. A 16³ region of mostly stone and air is two
palette entries and 4,096 small integers — a few kilobytes, against well over a megabyte if every
block were its own object.

Index order is documented in the response: `index = dx + sizeX * (dz + sizeZ * dy)`, relative to
`origin`. That is `y` outermost, matching how a builder thinks about a structure — layer by layer
from the bottom.

`summary` returns only the palette with counts, which is the whole useful answer to "what am I in the
middle of" for a fraction of the tokens. `list` returns one object per block: the most readable and by
far the largest.

Unloaded positions appear in the palette as `mcmcp:unloaded`, never as air.

Bounded by `limits.maxBlockVolume` (default 32,768).

#### `server_find_blocks`

:material-eye: Read-only · `block`, `x`, `y`, `z` required

Searches expanding shells outward from the centre, so results are nearest-first and hitting the limit
early returns the closest matches rather than an arbitrary corner of the box.

Skips unloaded chunks; never force-loads. Radius bounded by `limits.maxScanRadius`.

#### `server_nearby_entities`

:material-eye: Read-only · `x`, `y`, `z` required · optional `radius`, `dimension`, `type`

Entities within a radius, with distances. Filter by namespaced type.

#### `server_world_info`

:material-eye: Read-only · no arguments

Server identity, mean tick time, derived TPS, and per-dimension time, weather, difficulty, loaded
chunk count, entity count and spawn point.

Worth checking before an expensive scan: ticks are 50 ms apart, so a mean above 50 ms means the
server is already behind and every scheduled task is queued behind it.

### Building

#### `server_set_block`

:material-alert: Destructive · requires `permissions.allowWorldEdits`

One block. `block`, `x`, `y`, `z` required; optional `metadata`, `dimension`. Returns the previous and
current state.

#### `server_set_blocks`

:material-alert: Destructive · requires `permissions.allowWorldEdits`

| Argument | Type | Notes |
| --- | --- | --- |
| `mode` | `fill` \| `list` | Inferred from whether `blocks` is present. |
| `block`, `metadata` | string, integer | Fill mode. |
| `x`,`y`,`z`,`toX`,`toY`,`toZ` | integer | Fill mode region. |
| `blocks` | array | List mode: `{x, y, z, block, metadata}` objects. |
| `replaceOnly` | string | Only write where the existing block matches this id. |
| `dimension` | integer | Default 0. |

Fill a cuboid, or apply an explicit list. The intended shape of a build is: fill the bulk volumes
first, then one list call for the detail.

`replaceOnly: "minecraft:air"` builds without destroying anything already there.

The response separates `skipped` (excluded by `replaceOnly`) from `unchanged` (the world refused the
write — usually the same block was already there). That distinction is what tells you whether a
filter or the world stopped you.

Bounded by `limits.maxBlockVolume`, because the whole operation runs in a single game-thread task: the
volume bound is directly a bound on how long one call can stall the tick loop.

!!! warning "Direct writes bypass hooks"

    These do not fire block-place events. Claim protection, machinery callbacks and other mods'
    hooks do not run. On a world with protection mods, build through commands or player actions
    instead.

### Players

#### `server_list_players`

:material-eye: Read-only · no arguments

Every connected player with position, dimension, health, ping and operator status. Names from here
are what the other player tools accept.

#### `server_player_state`

:material-eye: Read-only · `player` required

Full state, plus the block underfoot, the biome, and the world's time and weather.

An unknown name returns an error listing who *is* online — a model working from a stale plan needs to
see that the player it wanted has left.

#### `server_player_inventory`

:material-eye: Read-only · `player` required

Main inventory, hotbar, armour and both hands. Empty slots are omitted and reported as a count.

#### `server_teleport_player`

:material-alert: Destructive · requires `permissions.allowWorldEdits`

`player`, `x`, `y`, `z` required; optional `yaw`, `pitch`.

Goes through the player's connection so the client actually moves. A bare `setPosition` desyncs the
player and gets them rubber-banded back by the movement check.

Gated on `allowWorldEdits` rather than `allowPlayerControl`: the latter governs driving your own
input on the client endpoint, which is bounded by what you could do anyway. Moving someone else is a
different thing.

### Commands and chat

#### `server_run_command`

:material-alert: Destructive · requires `permissions.allowCommands`

| Argument | Notes |
| --- | --- |
| `command` | With or without the leading slash. |
| `asPlayer` | Run as this player, with their permission level and position. Omit for console authority. |

Returns everything the command printed, plus its integer result. That matters: without captured
output, `/tp` failing because the target is offline and `/tp` failing because the coordinates are out
of range are the same integer.

`asPlayer` is what you want for anything using relative coordinates or `@s` — the command's origin is
that player's position.

Refuses names in `permissions.blockedCommands`.

#### `server_broadcast`

Requires `permissions.allowChat` · `message` required

Chat message to every connected player. Worth using before acting on someone's world.

#### `server_tell_player`

Requires `permissions.allowChat` · `player`, `message` required

## Client endpoint

### State

#### `client_player_state`

:material-eye: Read-only · no arguments

The usual first call. Position, orientation, health, hunger, held item, block underfoot, biome, world
time and weather, what the crosshair is on, and render distance.

#### `client_looking_at`

:material-eye: Read-only · no arguments

What the crosshair is pointing at: a block with the face being looked at, an entity, or `miss`.

Separate from `client_player_state` because it is the one query called in a loop — look, check,
adjust, check again — and a full state response each time is wasteful.

#### `client_get_block`

:material-eye: Read-only · `x`, `y`, `z` required · optional `relative`

`relative: true` treats the coordinates as offsets from the player's block position.

Positions outside the loaded view distance report `loaded: false`. The client genuinely does not know
what is there and will not be told until it gets closer.

#### `client_nearby_entities`

:material-eye: Read-only · optional `radius`, `type`

Limited to the client's entity tracking range, which is smaller than the server's view.

#### `client_inventory`

:material-eye: Read-only · no arguments

Slots 0–8 are the hotbar; 9–35 are the main inventory.

#### `client_connection_info`

:material-eye: Read-only · no arguments

Singleplayer or remote, server address, latency, and the player list.

Worth its own tool: singleplayer versus a shared server changes what a model should assume about
permissions, other people, and how reversible its actions are.

### Input

All input goes through `KeyBinding` state — the same path the keyboard handler uses — so it is subject
to reach distance, break progress, cooldowns and server-side movement checks exactly as a human's
input is.

#### `client_send_chat`

Requires `permissions.allowChat` · `message` required

Chat, or a command if prefixed with `/`. Runs at the player's own permission level.

**The reply is not in the result.** The server answers asynchronously into the chat window; read it
with `client_read_chat`.

256-character limit — the vanilla one. Longer messages are rejected by the server, not truncated.

#### `client_look`

Requires `permissions.allowPlayerControl`

| Argument | Notes |
| --- | --- |
| `yaw`, `pitch` | Absolute. |
| `deltaYaw`, `deltaPitch` | Relative. |
| `lookAtX`, `lookAtY`, `lookAtZ` | Aim at a world position — usually what you want. |

`lookAt*` computes the angles from the player's **eye** position. Aiming from the origin points the
camera about 1.6 blocks low.

Pitch is clamped to `[-90, 90]`; yaw is wrapped, so repeated relative turns do not accumulate without
bound.

Returns the new angles and what the crosshair is now on.

#### `client_move`

Requires `permissions.allowPlayerControl` · `direction`, `ticks` required · optional `sprint`, `jump`,
`sneak`

Direction is relative to the camera. Use `client_look` first, then move forward.

Returns the **distance actually travelled**, which is zero if something was in the way. Check it.

20 ticks is roughly 4.3 blocks sprinting or 2.2 walking. Bounded by `limits.maxInputTicks`.

#### `client_key`

Requires `permissions.allowPlayerControl` · `key`, optional `ticks`

The general-purpose input tool. Named bindings only: `forward`, `back`, `left`, `right`, `jump`,
`sneak`, `sprint`, `attack`, `use`, `drop`, `inventory`, `swapHands`.

Arbitrary key codes are deliberately not accepted — they are meaningless to a model, and could hit
any key another mod has bound.

#### `client_interact`

:material-alert: Destructive · requires `permissions.allowPlayerControl` · `action` required

`attack` is left click, `use` is right click. Acts on the current crosshair target — check
`client_looking_at` first. Reach is about 4.5 blocks in survival.

Breaking a block takes many ticks of held attack and depends on the tool held. Hold longer and
re-check rather than expecting one call to finish.

Returns the target before and after, plus the held item.

#### `client_select_slot`

Requires `permissions.allowInventoryChanges` · `slot` 0–8

Do this before using an item or placing a block. Returns what is now held.

### Debugging

#### `client_screenshot`

Requires `permissions.allowScreenshots` · optional `name`, `inline`

Saves a PNG under `screenshots/` and returns the absolute path plus a `resource_link`.

**Not inline by default.** A 1080p PNG base64-encodes to 1.4–2.7 MB of JSON per call. Pass
`inline: true` when the model needs to see the frame.

Captures the last rendered frame, so open GUIs, chat and the F3 overlay all appear.

#### `client_gui_state`

:material-eye: Read-only · no arguments

Whether a GUI is open and which one, window focus, whether a world is loaded, display size, and how
many synthetic key holds are still in flight.

Call this when an input tool appears to have had no effect — an open GUI swallows movement keys.

#### `client_read_chat`

:material-eye: Read-only · optional `lines`, `filter`

The rolling 300-line buffer of received chat: command output, other players, death messages, and
anything mods print to chat. This is how you see the result of `client_send_chat`.

#### `client_runtime_info`

:material-eye: Read-only · no arguments

Frame rate, heap usage, render distance, graphics settings, and the game and screenshot directories.
Roughly what F3 shows.
