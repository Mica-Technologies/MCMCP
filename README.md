# MCMCP — Minecraft Model Context Protocol

A Minecraft **1.12.2 Forge** mod that embeds a [Model Context Protocol](https://modelcontextprotocol.io)
server inside a running game. A player's client, a dedicated server, or both at once become MCP
endpoints that any MCP client can connect to, inspect and act on.

The design choice that matters most is that the **primary endpoint runs in the game client**. It
controls the local player through the same input path a keyboard uses, so it works on any server that
player can join — no server-side install, no operator rights, no inbound port on the server, and no
cooperation from whoever runs it. A server-side endpoint is also available for authoritative world
access, and it is off by default.

📖 **[Documentation & API explorer → mica-technologies.github.io/MCMCP](https://mica-technologies.github.io/MCMCP/)**

> **Status: early.** It builds, loads on both sides, and serves a full MCP handshake in CI. There is
> no published build on CurseForge or Modrinth, nothing has been through a public test, and the tool
> surface will change. Expect sharp edges.

## What it exposes

**33 tools.** World queries and bulk region read/write for survey-then-build workflows; player state
and inventory; command execution with captured output; camera, movement and interaction on the
client; screenshots, GUI inspection, chat capture and log tailing for debugging.

**7 resources**, including a subscribable chat feed that pushes as messages arrive, and a screenshot
template so a client can fetch image bytes on demand rather than paying for them in every response.

**4 prompts** that expand with live game state already gathered.

Plus per-session logging levels, resource-URI completion, progress notifications, and cooperative
cancellation. Protocol versions `2024-11-05`, `2025-03-26` and `2025-06-18` are negotiated per
session.

Full reference: [tools](https://mica-technologies.github.io/MCMCP/reference/tools/) ·
[resources](https://mica-technologies.github.io/MCMCP/reference/resources/) ·
[prompts](https://mica-technologies.github.io/MCMCP/reference/prompts/) ·
[protocol](https://mica-technologies.github.io/MCMCP/reference/protocol/)

## No dependencies

The jar is the whole install. JSON goes through the Gson that Minecraft already bundles; the
transport is the JDK's own `com.sun.net.httpserver`, which is present under both the vanilla Java 8
launch and the lwjgl3ify Java 17/21 launches. Nothing is shaded.

## Quick start

Drop the jar in `mods/`, start the game, then:

```
/mcmcp status      # confirms the endpoint is listening
/mcmcp token       # click to copy the generated bearer token
```

Point an MCP client at `http://127.0.0.1:25585/mcp` with an `Authorization: Bearer <token>` header:

```json
{
  "mcpServers": {
    "minecraft": {
      "type": "http",
      "url": "http://127.0.0.1:25585/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

The [API explorer](https://mica-technologies.github.io/MCMCP/api-explorer/) will complete the
handshake and list your instance's actual tool surface from a browser, if you would rather see it
before wiring anything up.

## Security posture

MCMCP opens a port that can move your character, run commands and read your screen. The defaults are
the security model for most installs, so they are set accordingly:

- **Loopback binding.** Use an SSH tunnel for remote access rather than binding a public address.
- **A bearer token**, generated on first launch and compared in constant time.
- **Origin validation**, so a web page you visit cannot drive your game.
- **The client endpoint on, the server endpoint off.** The client endpoint can only do what the
  player could do by hand; the server endpoint hands out authoritative control over everyone's world.
- **Direct world writes off by default**, because they bypass claim protection and other mods' hooks
  in a way that acting as the player does not.

Details, including what is *not* defended against:
[Security model](https://mica-technologies.github.io/MCMCP/guide/security/).

## Extending it

MCMCP is an extension point. Any mod can register tools, resources and prompts into the same
catalogue the built-ins use, and connected clients pick them up immediately via
`notifications/tools/list_changed`:

```java
McpRegistry.registerTool(McpTool.named("mymod_reactor_status")
    .title("Reactor status")
    .description("Read the output, temperature and fuel level of a reactor.")
    .schema(JsonSchema.object()
        .integer("x", "Block X coordinate of any reactor block.")
        .integer("y", "Block Y coordinate.")
        .integer("z", "Block Z coordinate.")
        .required("x", "y", "z")
        .build())
    .serverOnly()
    .readOnly()
    .handler(context -> ToolResult.structured(
        context.onGameThread(() -> readReactorStatus(context))))
    .build());
```

Guide: [Extending MCMCP](https://mica-technologies.github.io/MCMCP/dev/extending/).

## Building

Set `JAVA_HOME` to a **Java 21** install before each invocation; the compiler and mod code target
Java 8 regardless.

```bash
./gradlew setupDecompWorkspace   # first time
./gradlew build                  # build and test
./gradlew runClient              # dev client, MCP on :25585 and :25586
./gradlew runServer              # dev server, MCP on :25587 and :25588
```

Build system is the [GregTechCEu Buildscripts](https://github.com/GregTechCEu/Buildscripts) wrapper
around [RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle). `build.gradle` is that
buildscript verbatim and should not be edited; project configuration lives in
`buildscript.properties` and the four `*.gradle` sidecars.

More: [Building](https://mica-technologies.github.io/MCMCP/dev/building/) ·
[Architecture](https://mica-technologies.github.io/MCMCP/dev/architecture/) ·
[Testing](https://mica-technologies.github.io/MCMCP/dev/testing/)

## Licence

LGPL 2.1. See [`LICENSE.txt`](LICENSE.txt).
