# Extending MCMCP

MCMCP is an extension point. Any mod can register tools, resources and prompts into the same
catalogue the built-ins use, and they become available to every connected client immediately — the
registry fires a `list_changed` notification so live sessions pick them up without reconnecting.

The base mod's own tools are simply the first consumer of this API.

## Depending on MCMCP

MCMCP should be a **soft** dependency. Your mod should work without it.

`build.gradle` / `dependencies.gradle`:

```groovy
dependencies {
    compileOnly "Mica-Technologies:MCMCP:${mcmcpVersion}"
}
```

`repositories.gradle` — resolve it from GitHub Releases:

```groovy
repositories {
    ivy {
        name 'MCMCP GitHub Releases'
        url 'https://github.com/Mica-Technologies/MCMCP/releases/download/'
        patternLayout { artifact '[revision]/[artifact]-[revision].[ext]' }
        metadataSources { artifact() }
        content { includeGroup 'Mica-Technologies' }
    }
}
```

In your `@Mod` annotation:

```java
@Mod(modid = "mymod", dependencies = "after:mcmcp")
```

Then gate registration so your mod loads fine without it:

```java
@EventHandler
public void init(FMLInitializationEvent event) {
    if (Loader.isModLoaded("mcmcp")) {
        MyMcpIntegration.register();
    }
}
```

Keep the integration in its own class. `MyMcpIntegration` is only class-loaded inside that branch, so
MCMCP's absence cannot produce a `NoClassDefFoundError`.

## Registering a tool

```java
McpRegistry.registerTool(McpTool.named("mymod_reactor_status")
    .title("Reactor status")
    .description("Read the current output, temperature and fuel level of a reactor. Give the "
        + "position of any block in the multiblock; the controller is resolved from it.")
    .schema(JsonSchema.object()
        .integer("x", "Block X coordinate of any reactor block.")
        .integer("y", "Block Y coordinate.")
        .integer("z", "Block Z coordinate.")
        .integer("dimension", "Dimension id. Defaults to 0.")
        .required("x", "y", "z")
        .build())
    .serverOnly()
    .readOnly()
    .handler(context -> {
        final int x = context.requireInt("x");
        final int y = context.requireInt("y");
        final int z = context.requireInt("z");
        final int dimension = context.getInt("dimension", 0);

        JsonObject status = context.onGameThread(() -> {
            World world = FMLCommonHandler.instance()
                .getMinecraftServerInstance().getWorld(dimension);
            TileEntityReactor reactor = findController(world, new BlockPos(x, y, z));
            if (reactor == null) {
                return null;
            }
            JsonObject json = new JsonObject();
            json.addProperty("outputRfPerTick", reactor.getOutput());
            json.addProperty("temperature", reactor.getTemperature());
            return json;
        });

        if (status == null) {
            return ToolResult.error("No reactor controller is connected to that position. Give the "
                + "coordinates of a block that is part of a formed multiblock.");
        }
        return ToolResult.structured(status);
    })
    .build());
```

### Rules that are not negotiable

**Touch game state only inside `context.onGameThread`.** Minecraft's world state is owned by one
thread and guarded by nothing. Reading it from the HTTP worker does not throw — it corrupts, and the
failure surfaces minutes later somewhere unrelated. Build your JSON inside the callable and return the
finished object; never let a Minecraft reference escape.

**Return `ToolResult.error` for failed actions, not an exception.** A tool error is a *successful*
JSON-RPC response with `isError: true`, and the model reads the text. A thrown exception becomes a
protocol error the client's plumbing eats, and the model retries the identical call forever. Reserve
throwing for genuinely malformed arguments — and even then, `context.requireInt` already produces the
right error.

**Write the description for a model.** It is the entire basis on which a tool gets called. Say what
the tool does, what the arguments mean in game terms, and what it returns. A tool with no description
fails the builder outright — an undescribed tool is dead weight in every request's context window.

**Set the annotations honestly.** `readOnly()`, `destructive()`, `idempotent()` drive real client
behaviour: many hosts auto-approve read-only tools and require human confirmation for destructive
ones. Wrong in the safe direction makes your tool tedious; wrong in the unsafe direction means a model
silently reshapes someone's world.

### Builder reference

| Method | Effect |
| --- | --- |
| `.title(String)` | Human-readable name. Defaults to the tool id. |
| `.description(String)` | **Required.** |
| `.schema(JsonObject)` | Input schema. Defaults to no arguments. |
| `.outputSchema(JsonObject)` | Emitted only to `2025-06-18` clients. |
| `.clientOnly()` / `.serverOnly()` / `.sides(...)` | Endpoint availability. Defaults to both. |
| `.readOnly()` | Cannot modify state. Implies idempotent, non-destructive. |
| `.destructive()` | May perform irreversible updates. |
| `.idempotent()` | Repeat calls have no additional effect. |
| `.closedWorld()` | State is under the server's control. Defaults to open. |
| `.offGameThread()` | Touches no game state; may run on the worker. Defaults to false. |
| `.handler(Handler)` | **Required.** |

## Writing schemas

`JsonSchema` builds the JSON Schema documents clients validate against.

```java
JsonSchema.object()
    .string("mode", "What to do.")
    .enumeration("axis", "Which axis to work along.", "x", "y", "z")
    .integer("radius", "Search radius in blocks.", 1, 64)
    .number("threshold", "Trigger threshold, 0 to 1.", 0.0D, 1.0D)
    .bool("dryRun", "Report what would change without changing it.")
    .stringArray("tags", "Tags to match.")
    .required("mode", "axis")
    .strict()
    .build();
```

`.required()` throws if a name was never declared — catching at class-init time beats shipping a
schema whose `required` list names a property no client can supply.

Publish bounds. They are load-bearing, not decorative: a model will happily set a scan radius to
100000, and the ceiling in the schema makes it pick a sane value instead of discovering the clamp
through an error.

Use `.strict()` on destructive tools, where a silently ignored misspelled argument (`blocks` vs
`block`) means running with a default nobody intended.

## Registering a resource

```java
McpRegistry.registerResource(McpResource
    .at("minecraft://mymod/reactor/status")
    .name("reactor-status")
    .title("Reactor status")
    .description("Live output and temperature of the primary reactor.")
    .mimeType("application/json")
    .serverOnly()
    .subscribable()
    .reader((context, uri) -> Collections.singletonList(
        McpContent.textResource(uri, "application/json", Json.writePretty(buildStatus()))))
    .build());
```

Only mark a resource `.subscribable()` if something actually calls
`McpRegistry.notifyResourceUpdated(uri)` for it. A resource advertised as subscribable that never
fires looks, to a client, exactly like one that never changes — a lie that costs a poll loop.

Fire updates from wherever the state changes:

```java
@SubscribeEvent
public void onReactorChanged(ReactorStateEvent event) {
    McpRegistry.notifyResourceUpdated("minecraft://mymod/reactor/status");
}
```

That runs on the game thread, so it does nothing but append to per-session queues. Keep it that way.

### Templates

```java
McpResource.template("minecraft://mymod/reactor/{id}/status")
```

Resolve the variables in the reader:

```java
.reader((context, uri) -> {
    Map<String, String> vars =
        UriTemplates.extract("minecraft://mymod/reactor/{id}/status", uri);
    String id = vars == null ? null : vars.get("id");
    ...
})
```

Variables never match across a `/` and never match empty, so a template cannot swallow a sibling
resource's URIs.

## Registering a prompt

```java
McpRegistry.registerPrompt(McpPrompt.named("mymod_diagnose_reactor")
    .title("Diagnose a reactor")
    .description("Work out why a reactor is not producing its rated output.")
    .argument("position", "Coordinates of a reactor block, as 'x y z'.", true)
    .serverOnly()
    .generator(context -> {
        String position = context.getString("position", "");
        return CommonPrompts.one(McpPrompt.userMessage(
            "Diagnose the reactor at " + position + ".\n\n"
                + "Current state:\n```json\n" + Json.writePretty(readState(position)) + "\n```\n\n"
                + "Check fuel, coolant flow and control-rod insertion before concluding."));
    })
    .build());
```

Required arguments are validated by the dispatcher before the generator runs.

Prompts are user-initiated, so they are the right place for a multi-step workflow *plus* the state
that workflow needs — saving the model several discovery calls before real work begins.

## Choosing tool names

Built-ins use `<scope>_<verb>_<noun>`: `server_get_blocks`, `client_send_chat`,
`mcmcp_endpoint_info`.

Prefix yours with your mod id. Names are unique registry-wide, and a collision throws at registration
— which is the correct outcome, but better avoided.

## Threading summary

| Where your code runs | What it may touch |
| --- | --- |
| Handler body, before `onGameThread` | Your own state, config, files. **Never** Minecraft. |
| Inside `onGameThread` | Anything. Build and return your JSON here. |
| `notifyResourceUpdated` callers | Game thread. Keep it to a queue append. |

`context.getCancellation().throwIfCancelled()` between the steps of a long operation. MCMCP never
interrupts a running handler — a half-applied world mutation is worse than a late cancellation — so
cancellation is cooperative.

For long work, report progress:

```java
context.reportProgress(processed, total, "Scanned " + processed + " of " + total + " chunks");
```

That silently does nothing when the caller did not supply a progress token, which is correct:
unsolicited progress notifications are a protocol violation and strict clients drop the connection
over them.
