# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Purpose

MCMCP is a Minecraft 1.12.2 Forge mod (mod id `mcmcp`) that embeds a Model Context Protocol server in
a running game. It produces a single mod jar with no third-party runtime dependencies.

It also contains the **orchestrator** (`orchestrator/`, Rust): a separate program that lets several
running game instances be driven through one MCP endpoint. See "The orchestrator" below.

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

bash .github/scripts/server-smoke-test.sh     # boot a server + drive a real MCP handshake
bash .github/scripts/orchestrator-e2e.sh     # boot a server + drive a real MCP client through the orchestrator

# Two instances at once — the configuration the orchestrator exists for, and the only place
# aggregation, focus and fan-out are reachable. Local only: it needs a dev CLIENT, which needs a
# display, so CI cannot run it. Start runServer and runClient, then:
python .github/scripts/two-instance-check.py
```

The orchestrator is a separate Cargo workspace. **Rust is not on the default PATH here**:

```bash
export PATH="$HOME/.cargo/bin:$PATH"
cd orchestrator
cargo test --workspace              # unit tests
cargo fmt --all --check             # CI enforces this
cargo clippy --workspace --all-targets   # CI runs with -D warnings
cargo build -p mcmcp-orchestrator   # headless binary and shim
cargo build -p mcmcp-orchestrator-app    # the desktop app

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

### The orchestrator

`orchestrator/` is a Cargo workspace: `core` (the library), `cli` (headless binary **and** the stdio
shim), `app` (Tauri desktop). It exists because `clientPort` defaults to 25585 everywhere, so a
second game cannot bind — and because three MCP entries means three copies of a 45-tool catalogue in
every request a model makes.

**The link is inverted.** The game dials out (`transport/ReverseTransport`), so nothing in the game
listens and nothing can collide. Presence is the socket: no registry file, no heartbeat. The wire
format is newline-delimited JSON-RPC over plain TCP, and `link/LinkProtocol.java` and
`orchestrator/core/src/link/protocol.rs` are **one protocol with two implementations**. Nothing but
discipline keeps them in step, which is why `orchestrator-e2e.sh` exists — it is the only place the
two halves meet, and a mismatch fails at runtime rather than at compile time.

**The router is a proxy, not a tool server.** It works on `serde_json::Value` throughout and never
deserialises MCP messages into typed structs: it forwards messages it did not author between two
peers that may both be newer than it is, and a typed layer would silently drop fields this build has
not been taught about.

**Errors follow the mod's rule.** Addressing failures — nothing connected, ambiguous focus, a tool
that instance lacks — are `isError: true` results, never JSON-RPC errors.

**Every routed result is stamped with its instance**, three ways. Focus is sticky and a human can
change it at any time; without the stamp a model would keep acting on its memory of what was focused.

**The GUI has no privileged path into the core.** Approvals and gate prompts are channels the CLI
answers by policy and the app answers with a dialog. Approving, revoking and changing gating are
`Authority::Human` only and are deliberately not tools — a model that can approve an instance can
widen its own reach.

**Never create a git tag for the orchestrator.** `build.gradle:1555` resolves the mod's version with
`git describe --abbrev=0 --tags` and no `--match`, so *any* tag in this repository becomes the mod's
version. A tag like `app-0.1.0` would silently land in the jar manifest, `mcmod.info` and
`McmcpConstants.MOD_VERSION`. The orchestrator's version lives in `orchestrator/Cargo.toml`, and its
binaries attach to the mod's release.

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
- **`EntityPlayerSP.getPosition()` rounds, it does not floor.** It is overridden as
  `new BlockPos(posX + 0.5, posY + 0.5, posZ + 0.5)`, so at a block centre it names the block one over
  on X and Z. `EntityPlayerMP` has no such override, so using it made the two endpoints disagree about
  where the same player stood. Always use `GameJson.blockPosOf(entity)`.
- **`mc.objectMouseOver` is a frame stale inside a scheduled task.** It is recomputed by
  `EntityRenderer.getMouseOver` during rendering, so a tool that turns the camera and reads it in the
  same task sees the pre-turn target. Post-action reports use `ClientStateTools.freshLookTarget`;
  `client_looking_at` keeps `objectMouseOver` because that is what interaction actually acts on.
- `ScreenShotHelper.saveScreenshot` must run on the client thread; it reads the framebuffer, which
  needs a live GL context.
- Setting `mc.player.inventory.currentItem` is enough to change hotbar slot;
  `PlayerControllerMP.syncCurrentPlayItem` sends the packet next tick.
- Synthetic input goes through `KeyBinding.setKeyBindState` + `KeyBinding.onTick`. Both are needed:
  `setKeyBindState` drives `isKeyDown()` for movement, `onTick` drives `isPressed()` for click-style
  bindings.
- Game directory comes from `Loader.instance().getConfigDir().getParentFile()` — stable on both sides,
  unlike the client-only `Minecraft.gameDir`.
- **`EntityPlayerSP.sendChatMessage` is not how chat is sent.** It only sends the packet. The real
  path is `GuiScreen.sendChatMessage`: Forge's `ClientChatEvent`, the sent-message history, then
  `ClientCommandHandler.executeCommand`, and only then the packet. Skipping it meant every
  client-side command — `/malisis` and anything else registered with `ClientCommandHandler` — went to
  the server and came back "Unknown command", indistinguishable from the mod not registering it.
  Same shape as the `mouseClicked` bug below: an inner step mistaken for the entry point.
- **`GuiScreen.mouseClicked` is not how a click is delivered.** The real path is `handleInput()` →
  `handleMouseInput()` → `mouseClicked`, and a screen may override `handleMouseInput` and never reach
  the last step. MalisisCore's screens hit-test `Mouse.getX()/getY()` themselves, so calling
  `mouseClicked` on them clicked nothing while reporting success — every Malisis screen was silently
  unclickable, and it produced a false bug report against a working widget. `SyntheticMouse` sets
  LWJGL's mouse state and calls the screen's own `handleMouseInput()`, press then release. This is
  the mouse twin of the `handleKeyboardInput` problem `client_gui_key` already worked around; assume
  any new input tool has the same trap.
- **LWJGL's mouse origin is bottom-left**, GUI space and screenshots are top-left. `SyntheticMouse`
  flips Y once, in `toLwjglY`. If clicks land a consistent distance from the wrong edge, start there.
- **Set both `x`/`y` and `event_x`/`event_y`.** Vanilla's `handleMouseInput` reads the event pair;
  MalisisGui reads the plain pair. Setting one works on half the screens in the wild.

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

To test the client endpoint, get a dev client into a world without touching the GUI:

```bash
./gradlew runServer                                              # terminal 1
DEV_USERNAME=McmcpDev ./gradlew runClient -PmcJoin=127.0.0.1:25565   # terminal 2
```

`-PmcJoin` becomes vanilla's `--server`/`--port`, so `Minecraft.init()` opens `GuiConnecting` instead
of `GuiMainMenu`. 1.12.2's menu buttons are mouse-only and MCMCP has no tool for driving GUI widgets,
so this is the only way to reach a world unattended.

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
