# Resources

MCP resources are state a client can attach, cache and re-read on its own. Unlike a tool call, reading
a resource costs the model nothing — the client decides when to fetch it and how to present it.

MCMCP publishes seven, under the `minecraft://` scheme. The scheme names the domain, not the mod: these
describe the game, and a model reading a URI list benefits from knowing that.

## Resources versus tools

The line MCMCP draws:

- **State a model wants as background** is a resource. "The mod list", "the current player roster",
  "recent chat" — a client can pin these into context once and refresh on change.
- **Anything that takes a parameter or has an effect** stays a tool. "Find diamond ore within 32
  blocks" is a question, not a fact.

Some things exist as both. The game log is a resource (the last 200 lines, for a client to keep open)
*and* a tool (filtered, deeper reads on demand).

## Both endpoints

### `minecraft://game/mods`

`application/json` · not subscribable

Every loaded mod with id, name and version.

```json
{
  "count": 4,
  "mods": [
    { "id": "minecraft", "name": "Minecraft", "version": "1.12.2" },
    { "id": "forge", "name": "Minecraft Forge", "version": "14.23.5.2860" },
    { "id": "mcmcp", "name": "MCMCP", "version": "2026.07.31" }
  ]
}
```

### `minecraft://game/log/latest`

`text/plain` · not subscribable

The last 200 lines of `logs/latest.log`.

A resource rather than only a tool because it is what a developer wants pinned open while working —
the client can re-read it whenever it likes without the model spending a turn. Use `game_read_log` for
a longer window or a filtered search.

Returns an explanatory message rather than content when `permissions.allowLogAccess` is off.

## Server endpoint

### `minecraft://server/status`

`application/json` · not subscribable

MOTD, whether the server is dedicated, player counts, mean tick time, and the state of every loaded
dimension.

### `minecraft://server/players`

`application/json` · not subscribable

Every connected player with position, dimension and health. Names from here are what the player tools
accept.

## Client endpoint

### `minecraft://client/player/state`

`application/json` · not subscribable

The controlled player's live position, orientation, health, hunger and surroundings.

Returns `{"inWorld": false, ...}` with an explanation at the main menu, rather than failing — a
resource that errors when the game is not in a world would be permanently broken for a client that
attaches at startup.

### `minecraft://client/chat/recent`

`application/json` · **subscribable**

The last few hundred chat lines, oldest first.

The one resource that genuinely pushes, and the reason the subscribe machinery exists. Every incoming
message fires `notifications/resources/updated` down the SSE stream, so a client can watch chat in
real time instead of polling. Command output, other players talking, a mod reporting an error — all
arrive without a request.

```json
{
  "count": 3,
  "lines": [
    { "text": "The time is 6000", "type": "SYSTEM", "timestamp": 1753992104233 },
    { "text": "<Steve> hello", "type": "CHAT",   "timestamp": 1753992110004 }
  ]
}
```

`type` is `CHAT`, `SYSTEM` or `GAME_INFO` — the last is the action bar above the hotbar.

## Templates

### `minecraft://client/screenshot/{name}`

`image/png` · client endpoint

The PNG bytes of a screenshot in the game's `screenshots/` directory, by file name.

This is what makes `client_screenshot`'s cheap default work: the tool returns a path and a
`resource_link`, and a client that decides it wants to look at the picture follows the link. Nothing
pays for the image until something needs it.

```json
{ "method": "resources/read",
  "params": { "uri": "minecraft://client/screenshot/mcmcp-1753992104233.png" } }
```

Constraints:

- The template cannot match across a `/`, so path traversal via separators is impossible by
  construction. `..` is rejected explicitly, since it contains no separator.
- Files over 16 MB are refused with a message pointing at the path on disk. That is a ceiling on what
  one read can make the game allocate and base64-encode, not a guess at PNG sizes.
- Refused entirely when `permissions.allowScreenshots` is off.

## Subscriptions

```json
{ "method": "resources/subscribe", "params": { "uri": "minecraft://client/chat/recent" } }
```

Subscribing to a resource that does not emit updates is refused with an explicit error rather than
silently accepted. A resource advertised as subscribable that never fires looks, to a client, exactly
like one that never changes — which is a lie that costs a poll loop.

Updates fan out only to sessions that subscribed. Broadcasting them to everyone would turn one
player's chat into traffic on every connected client's stream.

## Registering your own

Another mod can publish resources into the same catalogue:

```java
McpRegistry.registerResource(McpResource
    .at("minecraft://mymod/reactor/status")
    .name("reactor-status")
    .description("Current output and temperature of the primary reactor.")
    .mimeType("application/json")
    .serverOnly()
    .subscribable()
    .reader((context, uri) -> Collections.singletonList(
        McpContent.textResource(uri, "application/json", buildStatusJson())))
    .build());
```

Call `McpRegistry.notifyResourceUpdated(uri)` when the state changes. See
[Extending MCMCP](../dev/extending.md).
