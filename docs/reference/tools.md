# Tools

47 tools ship built in. Each declares which endpoints it is available on; the registry filters both
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

### Block ids

Every tool that takes a `block` argument resolves it the same way, and rejects an id that is not
registered rather than falling back to anything.

That guarantee is worth stating because Forge does not give it for free. The block registry is a
*defaulted* registry whose default is `minecraft:air`, so the obvious lookup returns air — not
null — for an id nobody registered. Before this was handled, a search for a misspelled id came back
as a search for air: `blocksScanned` equal to `matches`, contiguous "hits" hanging in mid-air, and a
confident report that a block sat somewhere it had never been. On the writing side the same slip was
destructive, a fill with a mistyped id quietly erasing the region it was meant to build.

An unresolvable id now returns an error naming the closest ids actually registered in that
namespace, or saying the namespace is not loaded at all and listing the ones that are. Air itself
stays perfectly addressable as `minecraft:air`; what is no longer possible is reaching it by
accident.

### World inspection

#### `server_get_block`

:material-eye: Read-only · `x`, `y`, `z` required · optional `dimension`

Block id, metadata, state properties, light levels, hardness and biome at one position.

Reports `loaded: false` without reading if the chunk is not loaded. `getBlockState` on an unloaded
position silently returns air, so a naive read would confidently describe a mountain as empty space —
and on a server, asking can force a chunk load, turning a read into a write with a disk hit.

Two fields appear only when they have something to say, so their presence is itself information.

`actualState` is the state the block is really drawn and interacted with, reported when it differs
from the stored `state`. Blocks that connect or mount to their neighbours — fences, walls, redstone,
and most modded blocks with attachment hardware — keep placeholder values in the chunk and resolve
the real ones in `Block.getActualState` at draw time. The placeholder is commonly *every connection
present*, so `state` alone describes a fence standing alone in a field as connected on all four
sides, and reports two blocks being compared as identical when they render completely differently.
When present, `actualState` carries the full property set, not just what changed.

`boundingBox` is the block's selection box in **block-relative** coordinates, reported when it is
not a full cube. Block-relative rather than world coordinates because that is the frame the box is
written in, so a value here compares directly against the source. This is the shape a crosshair
actually catches: a box on the wrong face is invisible in a screenshot but makes a block
unclickable from the side it should be clickable from.

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

An unregistered `block` is an error, never an empty result and never a search for air — see
[Block ids](#block-ids).

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

#### `server_save_world`

:material-cog: Idempotent · optional `players`

Flushes every loaded dimension to disk, the same call `/save-all` makes.

Worth having as a tool because an **integrated** server has no `/save-all` — that command is
registered by the dedicated server only — and its autosave interval is long enough that a session
can end with changes a tool already reported as applied still not on disk. The workaround that does
force a save, leaving the world, also unloads it and leaves a client wedged at the main menu.

Saves non-silently, so the familiar `Saving chunks for level ...` line still reaches the log; that
is the only externally visible confirmation the save happened. `players` (default true) also writes
player data. Saving blocks the server thread for its duration, which is noticeable on a large world.

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

Carries the same `actualState` and `boundingBox` fields as
[`server_get_block`](#server_get_block) — and on the client is where `actualState` is most directly
the truth, since this is the side that draws the block.

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

The message takes the same route as pressing Enter in the chat box: Forge's `ClientChatEvent`, the
sent-message history, then Forge's client command handler, and only then the packet. The reply
reports `handledOnClient`, which is `true` when a client-side command consumed the message — in that
case no server reply is ever coming, and whatever the command printed went straight to the chat
window.

!!! warning "Client-side commands need the whole path"

    Sending through `EntityPlayerSP.sendChatMessage` alone only sends the packet, and that was this
    tool's original bug. Any command registered with Forge's `ClientCommandHandler` — MalisisCore's
    `/malisis` among them — never ran at all. It went to the server, which answered "Unknown
    command", which reads exactly like the mod having failed to register it. Mods that rewrite or
    cancel chat through `ClientChatEvent` were bypassed for the same reason.

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

#### `client_input_lock`

Requires `permissions.allowPlayerControl` · `locked` required, optional `seconds`, `reason`

Holds the human's own keyboard and mouse out of the game so a stray movement cannot disturb what a
model is doing. While locked, nothing from the physical keyboard or mouse reaches the game — no
camera movement, no clicks, no keys, no pause menu, and no auto-pause when the window loses focus.
MCMCP's own input tools are unaffected.

Three things release it, and a model cannot suppress any of them:

- **Pressing Escape twice** within about three quarters of a second. A single press does nothing.
- **Expiry.** `seconds` defaults to 300 and is capped by `limits.maxInputLockSeconds` (default 1800).
  Locking again before it runs out extends it.
- **Leaving the world**, and anything that stops MCMCP ticking. The blocking is work done every
  tick rather than a state the game is put into, so nothing can strand the input.

The lock says so on screen and in chat when it engages. Check `locked` in a later result rather than
assuming you still hold it — nothing notifies a model when a human takes it back.

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

#### `client_reload_resources`

No arguments

Reloads every client resource from disk — textures, models, sounds, language files and shaders —
exactly as F3+T does. The only way to re-run a mod's resource-reload listeners without restarting
the game, which is what makes it useful for iterating on shaders.

Starts the reload and does not wait for it: a full reload takes seconds and grows with the pack, so
blocking would report a timeout for a reload that is going fine. The game thread is busy throughout,
so the next tool call queues behind it and returns once the reload has finished.

### GUI control

`client_gui_state` says *which* screen is open; these act on it. Between them they reach everything
before a player exists — the main menu, the world list — and everything whose real interface is a
screen rather than a block face.

Clicks go through the screen's own `mouseClicked` at the button's centre, not through
`actionPerformed`. Screens routinely override `mouseClicked` to do their own hit testing or to
refuse clicks while loading; calling `actionPerformed` would bypass all of it and fire actions the
real UI would have declined.

#### `client_gui_widgets`

:material-eye: Read-only · no arguments

Buttons on the current screen with label, index, id, enabled/visible state and geometry, plus every
text field with its contents and focus.

Call this first: the other two address widgets by label or index, and this is where both come from.
Text fields are found by *type*, so they are listed even on a mod's own screen where no field name
could have been known in advance.

#### `client_gui_click`

Requires `permissions.allowPlayerControl` · optional `label`, `index`

Click a button. `label` matches case-insensitively — exact first, then a unique substring, so
"Game Mode" finds "Game Mode: Survival". An ambiguous substring is an error rather than a
first-match guess.

Reports the screen before and after, and whether it changed.

#### `client_gui_text`

Requires `permissions.allowPlayerControl` · `text` required · optional `index`, `clear`, `submit`

Set a text field's contents.

Fields are found by **shape, not type** — anything exposing `String getText()` and `setText(String)`
qualifies, which is the convention vanilla and every mod widget framework examined so far follow.
Finding them needs a bounded walk of the screen's object graph, because widgets are often held in a
container's child list rather than as fields of the screen itself.

The value is **set outright rather than typed**. Replaying keystrokes depends on where the caret
happens to be, and a click leaves it in the middle of the existing text — so "clear and type" quietly
becomes "insert halfway through", and backspacing a fixed number of times either overshoots or leaves
a tail. Setting the string is the operation actually wanted.

The widget can still refuse part of the input to a length cap or character filter, so the reply
carries `textBefore`, the resulting `text`, and `fullyAccepted`.

!!! note "The graph walk stops at `java.*` and `net.minecraft.*`"

    A screen holds a `Minecraft` reference, and following it reaches the world, every loaded entity
    and the render stack. Values are shape-checked before that filter applies, so a vanilla text box
    held directly by a screen is still found.

#### `client_gui_click_at`

Requires `permissions.allowPlayerControl` · `x`, `y` required · optional `space`, `button`

Click a coordinate rather than a named widget.

**This is the fallback that makes modded screens reachable at all.** `client_gui_widgets` only sees
vanilla widgets, and many mods build their interfaces out of their own classes — SuperMartijn642's
Core Lib among them — so a screen full of controls can report zero buttons. Clicking a point needs
none of that.

The workflow is screenshot → read the pixel → click it, which is why `pixel` is the default space.
`gui` is Minecraft's scaled space, matching the positions `client_gui_widgets` reports.

The click is delivered the way the game delivers one: LWJGL's mouse state is set to the target point
and the screen's own `handleMouseInput()` is called, press then release. The reply reports `via` —
`lwjgl` for that path, or `mouseClicked` if LWJGL's state could not be driven and the older direct
call was used instead — and `handlesOwnMouseInput`, which says whether this screen takes over mouse
handling rather than leaving it to `GuiScreen`.

!!! warning "Why not just call `mouseClicked`?"

    Because it is one branch *inside* how a click is delivered, not the delivery itself. The real
    path is `handleInput()` → `handleMouseInput()` → `mouseClicked`, and a screen may override
    `handleMouseInput` and never reach the last step.

    MalisisCore's screens do exactly that: they hit-test against `Mouse.getX()/getY()` and dispatch
    to their own component tree. Until this tool drove LWJGL's state, every MalisisDoors,
    MalisisSwitches and MalisisCore screen was silently unclickable — the call returned success, the
    screen did not change, and nothing was logged. It produced a false bug report against a widget
    that was working perfectly. This is the mouse counterpart of the `handleKeyboardInput` problem
    described under `client_gui_key`.

#### `client_gui_key`

Requires `permissions.allowPlayerControl` · optional `text`, `key`, `repeat`

Send characters or a named key to the screen's key handler. The keyboard counterpart to
`client_gui_click_at`: a mod's own text widget is not a `GuiTextField`, so `client_gui_text` cannot
find it, but it still receives keys through the screen. Click the field first to focus it.

Dispatch is tried in order — the screen's own `charTyped(char)` / `keyPressed(int)` first, then
vanilla `keyTyped`. Those two names are vanilla's own from 1.13 onwards and several frameworks
mirror them, including Core Lib, which overrides `handleKeyboardInput` and never calls `keyTyped` at
all. The reply reports `dispatchedVia` so you can see which path was taken.

!!! warning "The symptom when this is missed is misleading"

    A screen that reimplements keyboard input still closes on Escape, because that is handled by the
    inherited `keyTyped` — while every character silently vanishes. It reads as a focus problem and
    is not one.

#### `client_gui_close`

Requires `permissions.allowPlayerControl` · no arguments

Escape. Small, and the difference between a recoverable session and a stuck one — Minecraft opens the
pause menu whenever its window loses focus, and it stays until something closes it.

Escape goes to the screen's own key handler, so a screen that declines to close stays open and the
reply says so.

#### `client_view`

Requires `permissions.allowScreenshots` · optional `hideHud`, `perspective`, `pauseOnLostFocus`

F1, F5, and the setting that makes unattended work possible.

Hiding the HUD removes the hotbar, crosshair, hand and chat from the frame, which is the difference
between a usable capture and a debug one. Both persist until changed.

`pauseOnLostFocus: false` stops Minecraft pausing when its window is backgrounded. Without it an
unattended session is unusable: the pause menu reopens faster than `client_gui_close` can dismiss it,
and it renders over every screenshot. The call also closes the menu if one is already open. It is a
runtime override and is deliberately not saved to `options.txt` — it disables a safety behaviour on
somebody's real client, so it resets on restart.

### Timing

#### `client_wait`

:material-eye: Read-only · optional `ticks`, `waitFor`, `screenName`, `chatContains`

Let game time pass, for a fixed duration or until the game reaches a state:
`worldLoaded`, `worldUnloaded`, `screenOpen`, `screenClosed`, `chat`.

Prefer a condition over a fixed delay. A fixed sleep is a guess that fails intermittently when too
short and wastes every run when too long; a condition returns the moment it holds and reports
honestly when it timed out.

`worldLoaded` means world *and* player *and* terrain — the client spends several seconds on
`GuiDownloadTerrain` after the world exists, and during that window screenshots capture the loading
screen and block reads report `loaded=false` for terrain that is merely late.

`chat` only matches lines that arrive after the wait starts, so it cannot return instantly on
something from minutes ago.

### Worlds

Singleplayer world management, by calling `launchIntegratedServer` directly rather than clicking
through eight screens. One call with named arguments, indifferent to the client's language, and it
cannot half-succeed and strand the client on an intermediate screen.

#### `client_world_list`

:material-eye: Read-only · no arguments

Saved worlds, newest first, with game mode, cheats and last-played. `folderName` is what
`client_world_load` takes — not the display name, which is not unique.

#### `client_world_create`

:material-alert: Destructive · Requires `permissions.allowPlayerControl` · `name` required · optional
`worldType`, `gameMode`, `seed`, `generateStructures`, `allowCheats`, `hardcore`

Create a world and load it. Marked destructive because it writes a save directory that leaving the
world does not undo. An existing save is never overwritten — the folder gets a suffix, and the reply
says which was used.

There is deliberately **no tool to delete a world**. The failure mode of getting that wrong is
somebody's survival save, and the recovery is nothing.

#### `client_world_load`

Requires `permissions.allowPlayerControl` · `folderName` required

Load an existing world, leaving any current one first.

#### `client_world_leave`

Requires `permissions.allowPlayerControl` · no arguments

Back to the main menu, saving on the way out.

!!! warning "Loading returns before the world is ready"

    `client_world_create` and `client_world_load` return as soon as loading has *started*. Generating
    spawn chunks blocks the client thread for far longer than a scheduled task is allowed, so waiting
    on the call itself reports a timeout for a world that is loading perfectly well.

    Follow both with `client_wait` and `waitFor: "worldLoaded"`.
