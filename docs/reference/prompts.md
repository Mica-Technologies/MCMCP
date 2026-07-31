# Prompts

MCP prompts are user-initiated. Someone picks one out of their client's UI; the model does not choose
to invoke it. That makes them the right home for multi-step workflows a model would otherwise have to
discover by trial, and it means the prompt can arrive pre-loaded with the state the task needs.

MCMCP's four expand against live game state at `prompts/get` time, so what the model sees is the world
as it is when the prompt runs, not as it was when the server started.

They establish the task, the constraints that are not obvious from the tool list, and the relevant
state — and then stop. None of them tells the model what conclusion to reach.

## Both endpoints

### `diagnose_errors`

| Argument | Required | Notes |
| --- | --- | --- |
| `filter` | No | Extra substring — a mod id, a class name. |

Collects recent `ERROR` and `WARN` lines from `logs/latest.log`, plus anything matching `filter`, and
asks for a diagnosis.

This is the one workflow that is genuinely tedious by hand. A Minecraft error is usually one stack
trace buried in thousands of lines of mod chatter, and finding it means several filtered log reads.
Doing that work at expansion time is exactly what prompts are for.

The expansion is explicit that the collected lines are a filtered tail — not necessarily in order,
and a stack trace may be cut off — and points at `game_read_log` for full context. Without that, a
model reads a truncated trace as the whole story.

When log access is disabled, or nothing matched, it says so rather than producing an empty prompt that
reads like "there is nothing wrong".

### `build_structure`

| Argument | Required | Notes |
| --- | --- | --- |
| `description` | **Yes** | What to build. |
| `location` | No | Coordinates, or a description. |

Sets up a build task with the survey-then-build loop the block tools are designed around:

1. Establish where you are.
2. **Survey before building** — `server_get_blocks` over the intended region plus a margin.
3. Plan the volume as cuboids; `fill` mode per cuboid, `list` mode for detail.
4. `replaceOnly: "minecraft:air"` to extend rather than destroy.
5. Survey again and confirm.

The expansion also reports the live volume cap and whether `permissions.allowWorldEdits` is currently
enabled — with concrete advice for the disabled case, since a model that discovers this through a
failed call has already wasted a turn planning around it.

## Client endpoint

### `survey_surroundings`

No arguments.

Expands with the player's current state already gathered — position, world, biome, what the crosshair
is on — and asks for a description of where they are and what is nearby.

The saved tool calls are the point: a user picking "look around and tell me where I am" should not
have to watch three discovery calls establish that the game is in a world at all.

At the main menu it says so and points at `client_gui_state` and `client_screenshot`, rather than
expanding into a prompt about a world that does not exist.

The expansion states the client's visibility limit explicitly: positions outside render distance come
back as `loaded: false`, which means *unknown*, not *empty*. Models otherwise read that as "nothing
is there".

### `verify_visually`

| Argument | Required | Notes |
| --- | --- | --- |
| `action` | **Yes** | What to do. |

The screenshot-driven verification loop, and the workflow the debug tools were built for:

1. Capture the starting state.
2. Act.
3. **Let the world react.** Input tools return once input has been applied, not once the result has
   settled.
4. Capture again and compare — `client_gui_state` and `client_read_chat` often answer more cheaply
   than a second screenshot.
5. If it did not work, report what was observed rather than retrying blindly.

Step 3 is why this exists as a prompt: the sequencing is easy to get wrong and invisible from the
tool descriptions alone. So is step 5's diagnosis triad — a no-op is almost always an open GUI
swallowing input, the crosshair not on the target, or the server rejecting the action, and
`client_looking_at` plus `client_read_chat` distinguish all three.

## Registering your own

```java
McpRegistry.registerPrompt(McpPrompt.named("tune_reactor")
    .title("Tune the reactor")
    .description("Bring the reactor to a target output without exceeding thermal limits.")
    .argument("targetOutput", "Desired output in RF/t.", true)
    .serverOnly()
    .generator(context -> {
        String target = context.getString("targetOutput", "unspecified");
        return CommonPrompts.one(McpPrompt.userMessage(
            "Tune the reactor to " + target + " RF/t.\n\nCurrent state:\n" + readState()));
    })
    .build());
```

Required arguments are validated by the dispatcher before the generator runs, so a generator never
sees a missing one. See [Extending MCMCP](../dev/extending.md).
