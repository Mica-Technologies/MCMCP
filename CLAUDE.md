# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Purpose

MCMCP is a Minecraft 1.12.2 Forge mod (mod id `mcmcp`) that embeds a Model Context Protocol server in
a running game. It produces a single mod jar with no third-party runtime dependencies.

Two independent MCP endpoints:

- **Client** (`:25585`, on by default) — runs in a player's game client, controls that player through
  the same input path a keyboard uses. Works on any server the player can join.
- **Server** (`:25586`, off by default) — runs in a dedicated or integrated server. Authoritative
  world state, direct block writes, commands with server authority.

## Commands

Set `JAVA_HOME` to a **Java 21** install before each `./gradlew` invocation. RFG requires the Gradle
JVM to be 21+ (17 works with a deprecation notice), Gradle 8.9 supports running on ≤ 22, and CI uses
21. The compiler and mod code target Java 8 regardless.

```bash
export JAVA_HOME="C:/Users/<user>/.jdks/azul-21.0.x"      # Windows
export JAVA_HOME="/Users/<user>/Library/Java/JavaVirtualMachines/azul-21.0.x/Contents/Home"  # macOS

./gradlew setupDecompWorkspace          # first time, or after clean
./gradlew build                         # compile + test + jar
./gradlew test                          # unit tests only
./gradlew test --tests '*McpSessionTest'
./gradlew runClient                     # dev client — MCP on 25585 / 25586
./gradlew runServer                     # dev server — MCP on 25587 / 25588
./gradlew runClient -Prosetta           # Apple Silicon: LWJGL2 under Rosetta 2
./gradlew clean

bash .github/scripts/server-smoke-test.sh   # boot a server + drive a real MCP handshake

python -m pip install -r docs/requirements.txt && mkdocs serve   # wiki preview
```

## Architecture

Full detail in `docs/dev/architecture.md`. The parts that are not obvious from the file listing:

### Two package boundaries are load-bearing

**`protocol/` imports no Minecraft class.** That is what makes the wire format unit-testable without a
running game. `McpDispatcher` takes a session and a parsed `JsonObject` and returns a `JsonObject` or
null; it knows nothing about HTTP, headers or sockets. Keep it that way — it is also what would let a
second transport be added without touching the protocol.

**`client/` is client-only, in full.** Everything in it references `Minecraft`. A dedicated server
that class-loads any of it dies at startup with Forge's "for invalid side SERVER". **Nothing in
common code may name these types** — they are reached only through `McmcpClientProxy`. This is the
single easiest way to break the mod, and the CI smoke test exists largely to catch it.

### The threading rule

Minecraft's world state is owned by one thread per side and guarded by nothing. Touching it from an
HTTP worker does not throw — it corrupts, and surfaces minutes later somewhere unrelated.

1. Every tool touching game state goes through `ToolContext.onGameThread`.
2. Build the JSON **inside** the callable; the finished `JsonObject` is what crosses back.
3. No Minecraft object reference ever escapes the game thread. `GameJson` enforces this by
   construction.

Both `GameThreadBridge` implementations short-circuit when already on the game thread (scheduling onto
the thread you are on and then waiting is an instant deadlock) and complete futures exceptionally
rather than rethrowing (an escaping exception on the game thread crashes the game).

### Protocol errors vs tool errors

This distinction is the thing most likely to be got wrong when adding a tool.

- `JsonRpcException` → a JSON-RPC `error` response. For malformed or unroutable requests only.
- `ToolResult.error(...)` → a **successful** response with `isError: true`. For "the tool ran and the
  action failed": no player logged in, block out of range, permission disabled in config.

A JSON-RPC error is eaten by the client's plumbing and frequently never reaches the model, which then
retries the identical call forever. `McpDispatcher.handleToolsCall` converts a handler's
`JsonRpcException` into a tool error, except for cancellation and timeout.

### Registry and side filtering

One `McpRegistry` for both endpoints. Everything declares its supported sides; filtering happens at
list time *and* call time. Filtering the listing matters as much as the call — a tool listed but
permanently broken produces a plan that cannot work and a failure the model cannot diagnose.

Names are unique registry-wide and collisions throw, so behaviour never depends on mod load order.

### Configuration snapshots

`McpEndpointSettings` is immutable and built once at endpoint start; the transport never reads
`McmcpConfig`. Network settings therefore need `/mcmcp restart`, not just `/mcmcp reload`. Permission
and limit checks *are* read live, per call.

## Conventions & gotchas

### Gson is pinned to 2.8.0

Minecraft 1.12.2 bundles it, and MCMCP uses that copy rather than shading a newer one. Missing APIs
worth knowing before reaching for them:

- `JsonParser.parseString` — 2.8.6+. Use `new JsonParser().parse(...)`.
- `JsonObject.keySet` — 2.8.1+. Use `entrySet()`.
- `JsonObject.deepCopy` — 2.8.2+. `JsonSchema.build()` round-trips through `Json.write`/`Json.parse`.

`json/Json.java` wraps around these gaps and does tolerant coercion, because MCP arguments are
frequently model-generated and arrive with the wrong primitive type.

### 1.12.2 API notes

- `Minecraft.running` is package-private with no accessor. `ClientThreadBridge` uses
  `getFramebuffer() != null` as the liveness signal — non-null means `Minecraft.init()` completed.
- `ScreenShotHelper.saveScreenshot` must run on the client thread; it reads the framebuffer, which
  needs a live GL context.
- Setting `mc.player.inventory.currentItem` is enough to change hotbar slot;
  `PlayerControllerMP.syncCurrentPlayItem` sends the packet next tick.
- Synthetic input goes through `KeyBinding.setKeyBindState` + `KeyBinding.onTick`. Both are needed:
  `setKeyBindState` drives `isKeyDown()` for movement, `onTick` drives `isPressed()` for click-style
  bindings.
- Game directory comes from `Loader.instance().getConfigDir().getParentFile()` — stable on both sides,
  unlike the client-only `Minecraft.gameDir`.

### Do not edit `build.gradle`

It is the GregTechCEu buildscript verbatim. Project configuration lives in `buildscript.properties`,
`gradle.properties`, `dependencies.gradle`, `repositories.gradle` and `addon.gradle`. The auto-update
check is disabled via `systemProp.DISABLE_BUILDSCRIPT_UPDATE_CHECK`; it would overwrite the file and
wipe the `addon.gradle` customisations.

### Dev launch ports

`-Dmcmcp.dev.port` is a **base**: the client endpoint takes it, the server endpoint takes base+1. A
`runClient` that opens a singleplayer world runs both endpoints in one JVM. `runClient` gets
25585/25586 and `runServer` gets 25587/25588. Override with `-PmcpBasePort=26000`.

Both dev properties are defaults only — a value in `config/mcmcp.cfg` always wins.

### Version

Derived from the latest git tag (`YYYY.MM.DD`, with `+N` for a second same-day release). A checkout
with no tags builds as `NO-GIT-TAG-SET`, which is expected locally; CI tags before building.

### Adding a tool

Descriptions are mandatory and the builder enforces it — the description is the entire basis on which
a model decides to call the tool. Set the MCP annotations (`readOnly()`, `destructive()`,
`idempotent()`) honestly: clients auto-approve read-only tools and gate destructive ones behind human
confirmation.

Publish bounds in the schema. They are load-bearing, not decorative: a model will set a scan radius to
100000 unless the ceiling is stated.

### Testing

Anything in `protocol/`, `mcp/` or `json/` gets a unit test. Anything needing a world does not — put
the assertion in `.github/scripts/server-smoke-test.sh`, which boots a real dedicated server and
drives an actual MCP handshake against it. That script is also what catches a client class leaking
into common code.

Test names are sentences describing the guarantee, not the method under test.

### Documentation

The wiki under `docs/` is published to GitHub Pages on push to `main`. CI builds with
`mkdocs build --strict`, so a broken internal link fails the build. Keep the tool, resource and prompt
reference pages in sync when the catalogue changes.
