# Tools

62 tools ship built in. Each declares which endpoints it is available on; the registry filters both
the listing and the call path, so a tool never appears on an endpoint that cannot run it.

Every tool also carries MCP annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`,
`openWorldHint`). Clients use these — many auto-approve read-only tools and require confirmation for
destructive ones — so they are set honestly rather than defensively.

**An argument a tool does not declare is refused**, with the closest valid name — `world_type`
gets "did you mean 'worldType'?" — and nothing is done. Ignoring it used to run the tool on a
default the caller never chose, and the result read as success.

!!! tip "Try them live"

    The [API explorer](../api-explorer.md) lists the tools your own instance actually exposes,
    with their real schemas, and will call them for you.

## Common

Available on both endpoints.

### `mcmcp_endpoint_info`

:material-eye: Read-only · no arguments

Reports which side the endpoint runs on, which protocol versions it speaks, which permission groups
are enabled, the configured limits, and which game — and which *process* — is answering.

**Call this first.** Several tool families can be turned off in configuration, and disabled tools stay
listed rather than vanishing — so this is how a client learns what will actually work before it plans
around something that will not.

```json
{
  "side": "client",
  "gameAvailable": true,
  "negotiatedProtocolVersion": "2025-06-18",
  "instance": { "id": "modb-dev-3f2a1c", "name": "modB dev" },
  "process": { "pid": 28056, "startedAt": "2026-09-11T08:14:02Z", "uptimeSeconds": 412 },
  "permissions": {
    "commands": true, "playerControl": true, "inventoryChanges": true,
    "worldEdits": false, "screenshots": true, "logAccess": true, "chat": true,
    "processControl": true
  },
  "limits": { "maxScanRadius": 32, "maxInputTicks": 200, "maxLogLines": 500 }
}
```

`instance.id` lives in a config file, so two games launched from one directory share it. `process` is
what tells those two apart — and only one of them is answering. See the warning under
[`client_runtime_info`](#client_runtime_info) for how that goes wrong.

### `game_list_mods`

:material-eye: Read-only · no arguments

Every loaded mod, with id, name and version.

Not trivia on a modded instance: what can be crafted, what a block does, and what mechanics exist all
depend on which mods are present.

### `game_dump_registries`

:material-eye: Read-only

| Argument | Type | Notes |
| --- | --- | --- |
| `namespace` | string | Required. The part before the colon in an id, e.g. `csm`. |
| `file` | string | File name inside `mcmcp/dumps/`. Default `registries-<namespace>.json`. No path separators. |
| `display_names` | boolean | Default true. Include each block's and item's translated display name. |

Writes every block, item, tile-entity key, sound event, recipe and entity registered under one
namespace to a JSON file in the game directory, in registry order, with each block's and item's
class, creative tab (index on both sides, label on the client) and display name. The response
carries the path, a SHA-256 of the file and per-registry counts; the entries are in the file.

Each block with a tile entity also names the tile entity it creates (`tileEntityClass`,
`tileEntityKey`). Dumped from the **client** endpoint it adds what draws it: `renderer` is the
special renderer (TESR) class or `"none"`, with `globalRenderer` and `maxRenderDistance` where the
renderer answers them. `counts.blocksWithRenderer` is the number drawn by a TESR — the question that
otherwise took placing every tile-entity block and profiling it. The tile entity is made fresh for
the question, not taken from a world, so anything decided from world state is not reflected.

This exists for refactors that must not change what a mod registers — splitting a mod into
modules, rewriting registration, reordering tabs. Dump before, dump after, diff. Registry order
is what the creative inventory renders, so take both dumps in the same state: at the main menu the
order is registration order, inside a world it is the saved id mapping.

### `game_read_log`

:material-eye: Read-only

| Argument | Type | Notes |
| --- | --- | --- |
| `lines` | integer 1–5000 | Default 100. Capped by `limits.maxLogLines`. |
| `filter` | string | Case-insensitive substring. |
| `file` | string | File name inside `logs/`. Default `latest.log`. No path separators. |

Reads the tail of a log file, seeking backwards from the end. A filtered read scans up to 200,000
lines back; an unfiltered one stops as soon as it has enough.

Returns text only: a header line — `# <path> — N lines matching "<filter>"` — and then the lines.
There is deliberately no structured form. A client may show `structuredContent` in place of the text
when both are present, so a structured payload carrying only the counts hid the lines themselves, and
one carrying the lines as well doubled the cost of every log read.

### `game_health`

:material-eye: Read-only

| Argument | Type | Notes |
| --- | --- | --- |
| `sample_seconds` | integer 0–30 | Default 0. Also measure a window of this length. |

Heap and memory pools, off-heap direct buffers against their ceiling (`memory.offHeap`, as
described under [`client_runtime_info`](#client_runtime_info)), garbage collection per collector (count, total and mean pause, share of
uptime) and the five most recent collections with how long ago and how long, process and system CPU
load, thread counts and free disk.

Look here when ticks or frames hitch *at intervals* rather than staying uniformly slow. That pattern
is usually garbage collection, and no amount of profiling blocks will find it.

With `sample_seconds`, a `window` object reports what happened during exactly that window: GC
collections and pause time, and the game thread's CPU share and **allocation rate** in MB/s. The
allocation rate is how garbage-heavy code shows up before it becomes a GC pause — measure it before
and after a change. The per-thread figures come from HotSpot's extension of `ThreadMXBean` and are
simply absent on a JVM that lacks it.

### `game_heap_histogram`

:material-eye: Read-only

| Argument | Type | Notes |
| --- | --- | --- |
| `top` | integer 1–50 | Default 20. Rows per list. |
| `filter` | string | Only classes whose name contains this, e.g. `com.mymod`. |
| `live_only` | boolean | Default false. Count reachable objects only — **forces a full GC pause**. |

Which classes fill the heap: the largest by bytes with instance counts, and bytes rolled up by
package. A mod's own classes are rarely what fills a heap — its share is the sum of many small ones —
so the roll-up is usually the more telling half.

```json
{"liveOnly": false, "classes": 5126, "totalInstances": 4342333, "totalMb": 554.2,
 "largest": [{"class": "int[]", "instances": 108998, "kb": 260280.3},
             {"class": "byte[]", "instances": 99813, "kb": 116817.9}],
 "byPackage": [{"package": "java.lang", "instances": 420813, "kb": 13588.5}],
 "file": ".../mcmcp/dumps/heap-histogram-client-1789913000000.txt"}
```

To find a leak: take one, exercise the suspect — open and close the GUI fifty times, place and break
the block — take another, and compare the suspect package. Use `live_only` for that, since it is the
objects being *kept* that matter.

!!! warning "`live_only` pauses the game"

    By default every object on the heap is counted, garbage included, which pauses nothing but makes
    two readings only roughly comparable. `live_only` counts reachable objects only, and the JVM can
    only know which those are by running a full collection first: the whole game stops, typically
    for 100–300 ms on a modded heap. Harmless in a dev environment; think before doing it on a
    server with players on it.

Rows are in kilobytes, because the rows worth filtering down to — a mod's own classes — are small.
Arrays of primitives (`byte[]`, `int[]`) lead `largest` on any heap and are left out of `byPackage`,
where they would only repeat themselves: a `byte[]` belongs to whoever holds it, and a histogram does
not know who that is.
The JVM's full table is written, untouched, to `mcmcp/dumps/`. Needs a HotSpot JVM; on anything else
the tool reports that rather than failing.

### `game_cpu_sample`

:material-eye: Read-only

| Argument | Type | Notes |
| --- | --- | --- |
| `duration_seconds` | integer 1–30 | Default 10. |
| `interval_ms` | integer 1–100 | Default 4. |
| `thread` | string | Thread name, exact or substring. Default: this side's game thread. |
| `include_idle` | boolean | Default false. Keep samples where the thread was parked. |
| `only_over_ms` | integer 0–10000 | Default 0: keep all. Keep only samples from ticks (client: frames) at least this long. Game thread only. |
| `min_percent` | number 0.1–50 | Default 2. Prune tree branches below this share. |
| `max_lines` | integer 10–300 | Default 60. Cap on tree lines returned. |
| `top` | integer 1–50 | Default 15. Rows in `hottestFrames` and `byPackage`. |

A sampling profiler: dumps one thread's stack every few milliseconds and reports where the samples
landed. This is the tool that names a *method*. Reach for it once
[`server_profile_ticking`](#server_profile_ticking) or a section profile has said which block or
phase is slow and the question has become why.

Every figure is a share of samples, not a measured duration — a method in 30% of samples was
running, or waiting on something it called, about 30% of the time. Samples in which the thread was
parked (the server sleeping out its 50 ms, the client's frame limiter) are dropped and reported as
`idlePercent`, so the percentages describe the work rather than the waiting.

- `hottestFrames` — methods by samples in which they were the *executing* frame.
- `byPackage` — each sample credited to the nearest package on the stack that is not vanilla, Forge,
  the JDK or a bundled library. A vanilla `getBlockState` called from a mod's tile entity counts
  towards the mod here and towards vanilla in `hottestFrames`. The two answer different questions.
- The call tree, as indented text after the JSON, hottest branch first. Runs of frames with nothing
  branching off are folded onto one line: `a > (3) > b` is `a`, three frames elided, then `b`.

The full unpruned tree is written to `mcmcp/dumps/cpu-sample-<side>-<time>.json` and its path
returned as `file`. Blocks for the duration, so reproduce the load during the window.

!!! tip "Profiling a hitch: `only_over_ms`"

    A server that hitches once a minute spends 99.9% of its time not hitching, so an ordinary
    profile of it is a profile of a healthy server. With `only_over_ms`, samples are held back per
    tick — per frame on the client — and kept only if that tick's *measured* duration reached the
    threshold. Thirty seconds with `only_over_ms: 50` yields a tree made of nothing but the slow
    ticks. The result reports `ticksSeen` and `ticksKept` (`framesSeen`, `framesKept`) in place of
    `idlePercent`; `ticksKept: 0` means no tick in the window was slow enough, not that the tool
    failed. It applies only to the game thread, since ticks and frames are that thread's.

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

:material-eye: Read-only · `x`, `y`, `z` required · optional `dimension`, `nbt`

Block id, metadata, state properties, light levels, hardness and biome at one position.

`nbt: true` adds `blockEntity` — the tile entity's saved tag, as
`{"source": "server", "nbt": {...}}`, or `{"present": false}` when the block has none. Off by default,
because a machine's tag can run to kilobytes. NBT's number widths are dropped (`3b` reads as `3`);
arrays and lists past 256 entries are cut to `{"elided": "int[]", "length": N}` rather than ending
silently; and a tag over 32 KB comes back as its top-level `keys` with `truncated: true`. The tile
entity is looked up without creating one, so the read stays a read.

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

### Performance

The ladder for a slow tick: [`server_tick_stats`](#server_tick_stats) says whether there is a
problem, [`server_profile_ticking`](#server_profile_ticking) names the block,
[`server_profile_sections`](#server_profile_sections) names the phase, and
[`game_cpu_sample`](#game_cpu_sample) names the method. None of them uses ASM or a mixin — they
read hooks Forge and vanilla already have.

#### `server_tick_stats`

:material-eye: Read-only · no arguments

TPS and tick duration over the last 5 seconds, 1 minute and 5 minutes: mean, min, median, p95, p99
and max in milliseconds, plus `ticksOver50Ms` — and, when that is not zero, `ofThoseDuringGc`: how
many of the slow ticks were under way during a garbage collection. "Seven slow ticks" is a block to
go hunting for; "seven slow ticks, six of them during a collection" is a heap setting. It is a
coincidence in time, reported because it is very probably the explanation, not because it must be.
A window is omitted when the server has not been up
long enough for it to differ from the one before it. `dimensions` splits the last 100 ticks by
dimension — mean and worst — which is the only thing that says *which world* a slow tick belongs to
without running a profile.

```json
{"last5s": {"samples": 100, "meanMs": 5.4, "minMs": 3.98, "medianMs": 4.9, "p95Ms": 9.1,
            "p99Ms": 31.6, "maxMs": 74.4, "tps": 20.0, "ticksOver50Ms": 1},
 "dimensions": [{"dim": 0, "meanMs": 4.9, "maxMs": 71.2}, {"dim": -1, "meanMs": 0.3, "maxMs": 1.1}]}
```

`server_world_info` reports only a mean over 100 ticks, which cannot tell a server that is uniformly
slow from one that hitches once a minute. A high mean is steady load; a low median with a high max
is a hitch. Measure before and after a change to see what it cost.

#### `server_profile_ticking`

:material-eye: Read-only

| Argument | Type | Notes |
| --- | --- | --- |
| `duration_seconds` | integer 1–30 | Default 5, which already fills the per-object history. |
| `top` | integer 1–50 | Default 15. Rows per list. |
| `dimension` | integer | Only this dimension. Default: all loaded. |

Times every ticking tile entity and entity and reports the most expensive. **This is the tool that
names the block behind a slow tick.** Costs are microseconds per tick; the whole tick has 50,000.

```json
{"tileEntities": {
   "tracked": 74, "totalMicrosPerTick": 44.3,
   "costliest": [{"block": "minecraft:hopper", "pos": {"x": -212, "y": 75, "z": 196}, "dim": 0,
                  "meanMicros": 5.5, "maxMicros": 37.8}],
   "byType": [{"block": "minecraft:hopper", "class": "net.minecraft.tileentity.TileEntityHopper",
               "count": 36, "totalMicros": 25.4, "worstMicros": 5.5}]},
 "entities": {"tracked": 334, "totalMicrosPerTick": 1528.9, "costliest": [], "byType": []},
 "chunks": [{"dim": 0, "chunkX": -13, "chunkZ": 12, "totalMicros": 35.4,
             "tileEntities": 35, "entities": 4}],
 "ticksObserved": 101, "meanTickMs": 3.0}
```

`costliest` answers "which one"; `byType` answers "which kind" — forty cheap blocks of one type
outweigh one expensive one, and only `byType` shows it; `chunks` answers "where".

It uses the hooks Forge put in `World.updateEntities` for `/forge track`, so it needs no bytecode
changes — and it resets any `/forge track` in progress. Means are taken over the updates that
actually happened; Forge's own average divides by a fixed 99 and under-reports anything that ticked
for less than five seconds.

!!! note "What it cannot see"

    Only `update()` is timed. Work a block does anywhere else — neighbour updates, scheduled and
    random ticks, event handlers, packet handling — is not attributed here. If the tick is slow and
    this report does not add up to it, [`server_profile_sections`](#server_profile_sections) shows
    which phase holds the rest, and [`game_cpu_sample`](#game_cpu_sample) finds it by method.
    Players are not tracked, and a passenger's cost is charged to its vehicle.

#### `server_census`

:material-eye: Read-only

| Argument | Type | Notes |
| --- | --- | --- |
| `top` | integer 1–50 | Default 10. Rows per list. |
| `dimension` | integer | Only this dimension. Default: all loaded. |

Counts what is loaded, per dimension: entities by type, tile entities by block with how many of them
tick, and the most crowded chunks with what fills them.

```json
{"dimensions": [{"dim": 0, "loadedChunks": 625,
  "entities": {"total": 334, "byType": [{"entity": "minecraft:sheep", "count": 148}]},
  "tileEntities": {"total": 74, "ticking": 67,
                   "byType": [{"block": "minecraft:hopper", "count": 36, "ticking": 36}]},
  "crowdedChunks": [{"chunkX": -13, "chunkZ": 12, "entities": 4, "tileEntities": 35,
                     "mostly": "minecraft:hopper x35"}]}]}
```

[`server_profile_ticking`](#server_profile_ticking) ranks objects by what each one costs, and the
commonest cause of a slow tick is not on that list: nothing is expensive, there are simply four
thousand of it. This is also the quick check that a farm or a test build has not leaked entities.

#### `server_profile_sections`

:material-eye: Read-only

| Argument | Type | Notes |
| --- | --- | --- |
| `duration_seconds` | integer 1–30 | Default 5. |
| `min_percent` | number 0.1–50 | Default 1. Hide sections below this share of the tick. |
| `max_depth` | integer 1–12 | Default 8. |

Runs the vanilla section profiler — what `/debug start` records — and returns the tick by phase as
an indented text tree: share of the tick, and approximate milliseconds per tick.

```text
# percent of tick, ~ms per tick (mean tick 5.4 ms)
levels 98.2% 5.3ms
 world 98.0% 5.29ms
  tick 96.3% 5.2ms
   entities 64.2% 3.46ms
   tickBlocks 28.6% 1.55ms
    pollingChunks 28.5% 1.54ms
     tickBlocks 21.2% 1.14ms
      randomTick 10.3% 0.56ms
```

This is where scheduled and random block ticks show up (`tickPending`, `tickBlocks`), and block
entities are broken down by registry key under `blockEntities`. `unspecified` is time spent in a
section but in none of its sub-sections. The profiler reports shares only; the milliseconds are the
share multiplied by the measured mean tick. If `/debug` was already recording, it is left running.

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

One block. `block`, `x`, `y`, `z` required; optional `metadata`, `nbt`, `dimension`. Returns the
previous and current state. `nbt` is merged into the tile entity after placing, as for
`server_set_blocks`, and the result says whether it was `applied`, `unchanged` or found
`no_tile_entity`.

#### `server_set_blocks`

:material-alert: Destructive · requires `permissions.allowWorldEdits`

| Argument | Type | Notes |
| --- | --- | --- |
| `mode` | `fill` \| `list` | Inferred from whether `blocks` is present. |
| `block`, `metadata` | string, integer | Fill mode. |
| `x`,`y`,`z`,`toX`,`toY`,`toZ` | integer | Fill mode region. |
| `nbt` | string \| object | Fill mode: tile-entity data merged into every block filled. |
| `blocks` | array | List mode: `{x, y, z, block, metadata, nbt}` objects. |
| `replaceOnly` | string | Only write where the existing block matches this id. |
| `dimension` | integer | Default 0. |

Fill a cuboid, or apply an explicit list. The intended shape of a build is: fill the bulk volumes
first, then one list call for the detail.

`replaceOnly: "minecraft:air"` builds without destroying anything already there.

The response separates `skipped` (excluded by `replaceOnly`) from `unchanged` (the world refused the
write — usually the same block was already there). That distinction is what tells you whether a
filter or the world stopped you.

**Tile-entity data.** `nbt` on a placement (or on a fill) is merged into the tile entity after the
block is placed, exactly as `/blockdata` merges: name only the fields you care about. Give it as an
SNBT string (`'{CustomName:"Panel A",Mode:2b}'`) or a JSON object; JSON has one kind of number, so
use the SNBT string when a field must be a byte, short, long or float. SNBT has no `\n` escape — a
line break inside a string is a literal line feed. The response's `nbt` object counts `applied`,
`unchanged` and `noTileEntity`, with the first few positions that had none. Read it back with
`server_get_block` `nbt: true`.

**Size.** `limits.maxBlockVolume` bounds one game-thread task, because that is how long one task
holds the tick loop. A larger region or list is written in batches of that size, one task after
another with ticks between, up to 32 batches per call — so clearing a scene is one call rather than
one per layer. `batches` in the response says how many it took.

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

`player`, `x`, `y`, `z` required; optional `yaw`, `pitch`, `fly`.

Goes through the player's connection so the client actually moves. A bare `setPosition` desyncs the
player and gets them rubber-banded back by the movement check.

**Returns once the client has accepted the teleport** (`confirmed: true`, or false after two
seconds). Until it has, the client still holds the old position and facing, and a `client_look`
made in that window is overwritten when the teleport lands. That is why this, not `/tp` through
`server_run_command`, is the way to set a camera for a screenshot or a benchmark.

`fly: true` puts the player into flight before moving them, so a pose in mid-air holds instead of
falling to the ground a second later; it needs a game mode that allows flight, and the result says
if it was refused. `fly: false` lands them.

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

`succeeded` is Minecraft's own verdict — false whenever the command returned 0. `error` is present
only when it failed with an error message (the red text a player would see). That includes no-ops:
`/blockdata` setting a value that is already set fails with `The data tag did not change`, which a
loop should treat as "already done".

Two vanilla behaviours worth knowing when scripting tile-entity state:

- `/setblock` with a block state **replaces the tile entity**, so data set on it before is lost.
  Change tile-entity data with `/blockdata`, or with `nbt` on `server_set_block(s)`.
- SNBT rejects the two-character escape `\n` ("Invalid escape of 'n'"). A **literal line feed**
  inside a quoted string in `command` is accepted and stored as a newline — the only way to set a
  newline-separated text field with `/blockdata`.

#### `server_broadcast`

Requires `permissions.allowChat` · `message` required

Chat message to every connected player. Worth using before acting on someone's world.

#### `server_tell_player`

Requires `permissions.allowChat` · `player`, `message` required

### Lifecycle

#### `server_stop`

:material-alert: Destructive · requires `permissions.allowProcessControl` · no arguments

Stops the server, exactly as `/stop` does: players are disconnected, every world is saved, and the
process exits.

Not reversible from here — nothing in MCMCP can start a server back up, because afterwards there is
no endpoint left to ask. On a shared server, use `server_broadcast` first.

`stop` is in `permissions.blockedCommands` by default and should stay there. A blocklist is how the
irreversible members of a whole command set are kept out; a tool is the opposite shape — one action,
one switch, and a reply that says what was actually stopped.

On a singleplayer client running an integrated server this stops the *world*, not the game: the
client returns to the main menu and keeps running. The reply's `dedicated` says which it was.
[`client_quit`](#client_quit) is what ends a client.

```json
{"stopping": true, "dedicated": true, "onlinePlayers": 0, "pid": 28056}
```

The reply is written and flushed before the shutdown begins. A dropped connection instead of a reply
would leave a caller unable to tell a clean stop from a crash, which is the one case where a clear
answer is worth most.

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

:material-eye: Read-only · `x`, `y`, `z` required · optional `relative`, `nbt`

`relative: true` treats the coordinates as offsets from the player's block position.

`nbt: true` adds `blockEntity`, in the shape [`server_get_block`](#server_get_block) describes — but
with `"source": "client-synced"`, and that is the part to read. A client is sent only what the server
syncs so the block can be *drawn*, so a key missing here means "not synced", not "not set". It is
still the only NBT there is on a server you do not run, and it replaces sending `/blockdata` and
reading the reply out of chat.

Positions outside the loaded view distance report `loaded: false` and nothing else. The client
genuinely does not know what is there and will not be told until it gets closer.

!!! note "Why this is not `World.isBlockLoaded(pos)`"
    On a client that call is always true, and the read behind it lands on a shared empty chunk that
    answers air, sky light 15 and Plains — a complete, plausible reading of open sky for terrain the
    client was never sent. `GameJson.isLoaded` asks the question that has an answer.

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

Returns the angles **read back once the game has run with them** — two frames, because the mouse
turns the camera per frame, and one tick, because a mount clamps its rider and a server corrects a
player per tick; about 40 ms at 120 fps — with yaw as -180 to 180, and what the crosshair is now on. If the camera is no longer where it was put, the call is an error that says
where it ended up, whether the window has focus and whether the input lock is held. Writing the
rotation always succeeds; what goes wrong is something else turning the camera on the next frame —
almost always mouse movement reaching the window — and a reply that echoed the requested angles made
every screenshot after it silently wrong. [`client_input_lock`](#client_input_lock) is the remedy for
the mouse; when the player is riding, the error says so instead, since a boat allows its rider only
105° either side of its own heading.

#### `client_move`

Requires `permissions.allowPlayerControl` · `direction`, `ticks` required · optional `sprint`, `jump`,
`sneak`

Direction is relative to the camera. Use `client_look` first, then move forward.

Returns the **distance actually travelled**, which is zero if something was in the way. Check it.

20 ticks is roughly 4.3 blocks sprinting or 2.2 walking. Bounded by `limits.maxInputTicks`.

#### `client_key`

Requires `permissions.allowPlayerControl` · `key` or `keys`, optional `ticks`

The general-purpose input tool. Named bindings only: `forward`, `back`, `left`, `right`, `jump`,
`sneak`, `sprint`, `attack`, `use`, `drop`, `inventory`, `pickBlock`, `swapHands`.

Pass `keys` to hold a combination, such as `["sneak", "use"]` or `["sprint", "jump", "forward"]`.
Movement, `jump`, `sneak` and `sprint` go down one tick before any action key and stay down until it
releases. Minecraft handles clicks before it updates the player, so pressed in the same tick, sneak +
use would right-click as a player who is not sneaking yet and open the chest it was meant to place
against.

Arbitrary key codes are deliberately not accepted — they are meaningless to a model, and could hit
any key another mod has bound.

#### `client_interact`

:material-alert: Destructive · requires `permissions.allowPlayerControl` · `action` required

`attack` is left click, `use` is right click. Acts on the current crosshair target — check
`client_looking_at` first. Reach is about 4.5 blocks in survival.

Breaking a block takes many ticks of held attack and depends on the tool held. Hold longer and
re-check rather than expecting one call to finish.

Set `sneak: true` to click while sneaking. With `use`, this places a block or uses the held item
against a block that would otherwise open or activate. Sneak leads by one tick, as in `client_key`.
For other combinations, use `client_key` with `keys`.

Returns the target before and after, plus the held item.

!!! note "Post-action targets are brief"

    `client_look`, `client_move` and `client_interact` report their target as id, position and face
    only — enough to confirm where the camera landed or whether what you were aiming at changed.
    For the full block, with its state properties, bounding box, light levels and hardness, call
    `client_looking_at` or `client_get_block`. Those are what the detail is for; carrying it on
    every movement call made it 79% of a `client_move` response.

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

Requires `permissions.allowScreenshots` · optional `name`, `inline`, `max_dimension`

Saves a PNG under `screenshots/` and returns the absolute path plus a `resource_link`.

**Not inline by default.** An inline frame costs a model roughly `width × height / 750` tokens, spent
whether or not it ends up looking at the picture. Pass `inline: true` when it needs to see the frame.

**`max_dimension` caps what that costs.** It bounds the long edge of the inline copy only — the file
on disk is always full resolution. Without it, the price of a screenshot is set by however large the
player dragged the game window, which is not a property of the question being asked.

| Long edge | Approximate tokens | Good for |
|---|---|---|
| 640 | ~550 | Which screen is open, roughly where the player is looking |
| 1280 (default) | ~1,230 | Reading GUI labels and the F3 overlay |
| 1568 | ~1,850 | Fine detail — and the ceiling |

Above 1568 nothing is gained: the image is downscaled to that before it reaches the model either way,
having been paid for in transfer the whole distance. The tool clamps to it regardless of what is
asked for. Scaling is done in halving steps so small text survives the reduction.

The result reports `capturedWidth`/`capturedHeight`, the `inlineWidth`/`inlineHeight` actually sent,
and `approximateImageTokens`, so the cost of the size chosen is visible in the response.

Captures the last rendered frame, so open GUIs, chat and the F3 overlay all appear.

#### `client_screen_stats`

:material-eye: Read-only · requires `permissions.allowScreenshots`

| Argument | Type | Notes |
| --- | --- | --- |
| `count` | integer 1–600 | Default 20. How many reads. |
| `interval_ms` | integer 0–5000 | Default 0: every rendered frame. |
| `x`, `y`, `width`, `height` | integer | Crop, in window pixels from the top-left, as in a screenshot. Default: the whole window. |
| `threshold` | number 0–255 | Default 8. Luminance difference from the first read that counts as changed. |
| `per_channel` | boolean | Default false. Also return mean red, green and blue per read. |

Reads the rendered frame repeatedly and returns each read's mean brightness — no image is saved or
sent. This is for effects too short for a screenshot to catch reliably: a strobe lit for 75 ms of
every second, a flicker, a light that should blink. Screenshots taken back to back land 100–250 ms
apart, so whether a burst hits the flash is luck; this reads at the frame rate.

```json
{"region": {"x": 600, "y": 300, "width": 200, "height": 200}, "reads": 60, "spanMs": 995,
 "minLum": 31.2, "maxLum": 188.4, "meanLum": 44.0, "maxDeltaFromFirst": 157.2, "changed": 5,
 "samples": [{"ms": 0, "lum": 31.2}, {"ms": 16, "lum": 31.4}]}
```

**Crop to the effect.** Averaged over the whole window, a small light is diluted by everything else
in view and may not cross the threshold at all. Luminance uses Rec. 709 weights on the stored
values — a relative brightness for spotting change, not a photometric measurement. Regions larger
than 250,000 pixels are averaged on a grid. One call may take at most 60 seconds.

#### `client_gui_state`

:material-eye: Read-only · no arguments

Whether a GUI is open and which one, window focus, whether a world is loaded, display size, and how
many synthetic key holds are still in flight.

`inGameFocus` is Minecraft's own flag: whether the game, rather than a screen, takes the mouse.
`windowFocused` is whether the window is actually the one in front. They differ for a client launched
in the background, which Minecraft can believe is focused until somebody clicks into it.

Call this when an input tool appears to have had no effect — an open GUI swallows movement keys.

#### `client_read_chat`

:material-eye: Read-only · optional `lines`, `filter`

The rolling 300-line buffer of received chat: command output, other players, death messages, and
anything mods print to chat. This is how you see the result of `client_send_chat`.

Each line reads `[HH:MM:SS] message`. Anything that is not ordinary player chat is tagged after the
time — `(SYSTEM)` for command output and server messages, `(GAME_INFO)` for the action bar.

Returns text only, opening with `# N of M buffered line(s)`. There is no structured form, for the
reason given under [`game_read_log`](#game_read_log).

#### `client_runtime_info`

:material-eye: Read-only · no arguments

Frame rate, heap usage, render distance, graphics settings, and the game and screenshot directories.
Roughly what F3 shows.

`memory.offHeap` reports **direct buffer** memory: `directUsedMb`, `directBuffers`, `directMaxMb`
and `directUsedPercentOfMax`. Minecraft's vertex buffers, LWJGL's scratch buffers and Netty's pools
live outside the heap, in a pool with its own ceiling. A client drawing a very dense chunk section
has died with `OutOfMemoryError: Direct buffer memory` while its heap had room to spare, so watch
this figure in stress tests, not only the heap. `directMaxFrom` says where the ceiling came from:
`-XX:MaxDirectMemorySize` when it was set, otherwise HotSpot's default, the maximum heap size.

Also `process`, with this game's `pid`, `startedAt` and `uptimeSeconds`:

```json
{"pid": 28056, "startedAt": "2026-09-11T08:14:02Z", "uptimeSeconds": 412}
```

!!! warning "If a freshly built change appears to have done nothing, check `startedAt` first"

    MCMCP's ports are fixed, and the game that bound them keeps them. A Gradle `runClient` forks its
    own JVM, so stopping the Gradle task leaves the **game** running and still holding `clientPort`.
    The next `runClient` produces a second game that cannot bind — and every call keeps reaching the
    first one.

    Nothing reports an error. The endpoint answers normally; it is just answering from the previous
    build. Two clients launched from one directory are identical in every other field MCMCP reports:
    same instance id, same game directory, same mod version. `pid` and `startedAt` are the only
    fields that tell them apart, and `mcmcp_instances` carries them too.

    [`client_quit`](#client_quit) is how to avoid getting there — stop cleanly, relaunch, re-measure.
    When a client has stopped responding and cannot be asked to quit, the pid is what you kill.

#### `client_reload_resources`

No arguments

Reloads every client resource from disk — textures, models, sounds, language files and shaders —
exactly as F3+T does. The only way to re-run a mod's resource-reload listeners without restarting
the game, which is what makes it useful for iterating on shaders.

Starts the reload and does not wait for it: a full reload takes seconds and grows with the pack, so
blocking would report a timeout for a reload that is going fine. The game thread is busy throughout,
so the next tool call queues behind it and returns once the reload has finished.

### Performance

A block draws one of two ways, and only one can be timed per block. A **tile entity renderer** is
called once per block per frame through a public map, so it can be wrapped and attributed to a
position — [`client_profile_rendering`](#client_profile_rendering). A **baked model** is compiled
into its chunk's vertex buffer and drawn with everything else in the chunk; there is no per-block
call to time. Its cost appears as `terrain` and `updatechunks` in
[`client_profile_sections`](#client_profile_sections), and the way to measure one is the blunt one:
[`client_frame_stats`](#client_frame_stats) before and after placing a few hundred.

#### `client_frame_stats`

:material-eye: Read-only

| Argument | Type | Notes |
| --- | --- | --- |
| `sample_seconds` | integer 0–30 | Default 0: report history. Otherwise measure a fresh window. |

FPS, the 1% low, and the frame-time distribution, for the last 5 seconds and last minute — or, with
`sample_seconds`, for exactly that window from now. Hold the camera still on the scene under test.

```json
{"sampled": {"fps": 120.1, "low1PercentFps": 87.7, "framesOver50Ms": 0,
   "frame":      {"samples": 360, "meanMs": 8.33, "medianMs": 8.33, "p95Ms": 9.8, "maxMs": 11.87},
   "renderWork": {"samples": 360, "meanMs": 1.37, "medianMs": 1.34, "p95Ms": 1.67, "maxMs": 4.39}},
 "settings": {"fpsLimit": 120, "vsync": true, "renderDistanceChunks": 12}}
```

!!! tip "Compare `renderWork`, not `fps`"

    `frame` is the interval the player sees, and it includes the frame limiter's wait. In the
    example the client is capped at 120, so `frame` reads 8.33 ms whether drawing took one
    millisecond or seven — a change that tripled render cost would not move it. `renderWork` is
    the CPU time spent drawing each frame and moves regardless. It is the number to compare before
    and after a change.

`low1PercentFps` is the mean of the slowest 1% of frames, as a rate: an average of 140 with a 1% low
of 20 is a game that hitches. When `framesOver50Ms` is not zero, `ofThoseDuringGc` says how many of
those frames coincided with a garbage collection, as [`server_tick_stats`](#server_tick_stats) does
for ticks.

#### `client_profile_sections`

:material-eye: Read-only

Same arguments and output as [`server_profile_sections`](#server_profile_sections), for the frame:
client tick, `terrain`, `updatechunks` (chunk rebuilds), `entities`, `blockentities`, `particles`,
`gui` and so on, as share of the frame and approximate milliseconds per frame.

The client only profiles while the F3 pie chart is on screen, and re-decides every frame — so this
turns F3 and the chart on for the duration and restores them afterwards. They will appear in any
screenshot taken meanwhile, and drawing F3 inflates the `gui` section.

#### `client_profile_rendering`

:material-eye: Read-only

| Argument | Type | Notes |
| --- | --- | --- |
| `duration_seconds` | integer 1–30 | Default 5. |
| `warmup_seconds` | integer 0–15 | Default 0. Run the timers this long first and discard it. |
| `top` | integer 1–1000 | Default 15. Rows per list; each is roughly 100 bytes of response. |
| `offset` | integer | Default 0. Skip this many of the costliest rows, to page. |
| `type` | string | Only rows for this block or entity id. |
| `x`, `y`, `z`, `radius` | integer | Only rows within `radius` blocks of the point on each axis (a box). All four together. |
| `min_micros` | number | Only rows costing at least this many µs per frame. |
| `gl_finish` | boolean | Default false. Wait for the GPU around each renderer call. |

Times every tile entity renderer and entity renderer call and reports the most expensive blocks and
entities in view, with position, and totals by type with the renderer's class. **This is the tool
that names the block or entity behind a slow frame.** Costs are microseconds per frame; at 60 FPS a
whole frame has 16,667.

```json
{"tileEntities": {"rendered": 27, "totalMicrosPerFrame": 78.1,
   "costliest": [{"block": "minecraft:ender_chest", "pos": {"x": -208, "y": 76, "z": 200},
                  "microsPerFrame": 4.6, "microsPerCall": 4.6, "framesDrawn": 361}],
   "byType": [{"block": "minecraft:ender_chest",
               "renderer": "net.minecraft.client.renderer.tileentity.TileEntityEnderChestRenderer",
               "count": 27, "totalMicrosPerFrame": 78.1, "worstMicrosPerFrame": 4.6,
               "microsPerCall": 2.9, "callStdDevMicros": 0.8}]},
 "entities": {"rendered": 0, "totalMicrosPerFrame": 0.0, "costliest": [], "byType": []},
 "frames": 361, "timerOverheadMicrosPerCall": 0.05, "meanRenderWorkMs": 1.5, "glFinish": false}
```

**Reading a low number.** `microsPerFrame` averages over every profiled frame, so a block that was
culled or out of view for part of the window reads low. `framesDrawn` (out of `frames`) and
`microsPerCall` tell "cheap on every frame" from "hardly drawn". `callStdDevMicros` per type says how
settled a figure is. `timerOverheadMicrosPerCall` is the wrapper's own cost, included once in every
call's figure — negligible for a two-microsecond renderer, not for a hundred cheap calls a frame.

**Warm up a freshly built scene.** Renderers that have barely run are measured before the JIT has
compiled them: a first window read up to twice the settled figure for mid-cost renderers.
`warmup_seconds` runs the timers that long first and discards it, in the same call.

**Filters and paging.** `type`, the `x`/`y`/`z`/`radius` box and `min_micros` apply before anything
is summed, so `byType` describes the same rows as `costliest`; `matched` says how many rows passed.
`rendered` and `totalMicrosPerFrame` stay whole-scene. `moreRows` says how many are left past
`offset + top`.

Every `byType` row has a `renderer`, taken from the wrapper at the call — `"unknown"` only if even
that was unavailable.

An entity's time includes its shadow and fire overlay. Players are not covered: their renderers live
in a separate private map.

Only what is actually being rendered is measured, so face the scene under test. By default
the times are CPU time submitting draw calls, which understates a renderer whose cost is on the GPU;
`gl_finish` drains the GPU before and after every call, which is more truthful for heavy geometry
and lowers FPS while it runs. A `FastTESR`'s time covers filling the shared vertex buffer, not the
batch's draw.

Works by swapping each registered renderer — in the tile entity dispatcher's map and the render
manager's — for a timing wrapper for the length of the profile, and putting the originals back
afterwards. A mod that fetches its own renderer out of either map *per frame* and casts it would
fail during that window. Vanilla never does, and it is rare in mods (fetching once at startup to add
a layer is the common pattern, and is unaffected); it is why the wrappers are never left in. One client profile runs at a time.

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

### Lifecycle

#### `client_quit`

:material-alert: Destructive · requires `permissions.allowProcessControl`

| Argument | Notes |
| --- | --- |
| `leaveWorld` | Leave the current world first, so a singleplayer save is flushed. Defaults to `true`. |

Ends the client's process, exactly as the Quit Game button does.

Use this to restart a client rather than killing it from outside. That matters more than it sounds:
a game left running by a stopped launcher task goes on answering every call from a build you are no
longer working on, with no error to say so — see the warning under
[`client_runtime_info`](#client_runtime_info).

```json
{"quitting": true, "leftWorld": true, "wasSingleplayer": true, "pid": 28056}
```

The reply is written and flushed before the shutdown begins, so a caller can tell "quit worked" from
"quit crashed". After it, the endpoint stops answering and the port is released within a second or
two.

`leaveWorld: true` runs the full [`client_world_leave`](#client_world_leave) sequence and waits for
it, so `leftWorld` in the reply is a statement about a save that has actually completed. The leave
and the shutdown deliberately land in different ticks: squeezing them into one races the integrated
server's shutdown.

[`server_stop`](#server_stop) is the counterpart for a server.
