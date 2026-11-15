# Watching game events

An agent working on a shared server often needs to react to other people: a teammate joining, a chat
line that tags it (`@Claude the door is backwards`), someone leaving. Polling `client_read_chat` for
that costs a tool call every few seconds all day, misses lines that arrive faster than its window, and
can only find joins by searching server messages.

Instead, every game keeps a ring of recent events and answers
[`game_events_wait`](../reference/tools.md), which blocks until a matching event arrives and returns
a cursor that loses nothing between calls. The orchestrator turns that into a stream covering every
linked game, which you can take two ways:

- **A WebSocket**, `ws://127.0.0.1:25582/events?token=…`, one JSON object per text frame.
- **A command**, `mcmcp-orchestrator events`, one JSON object per line on stdout.

Both carry the same lines and take the same filters. The WebSocket is served by the orchestrator app
and by `mcmcp-orchestrator serve`; the command attaches to the app.

## With Claude Code's Monitor

Monitor turns each frame or line into a notification, so an agent subscribes once and then simply
gets told. The WebSocket form needs no process of its own:

```
mcmcp-orchestrator events --types chat --contains @Claude --ws-url
```

prints the complete URL, token included, for those filters. Hand it to Monitor:

```js
Monitor({
  ws: { url: "ws://127.0.0.1:25582/events?token=…&types=chat&contains=%40Claude" },
  description: "chat that tags Claude",
  timeout_ms: 1800000
})
```

Or run the command form, which needs no token at all because it reads the orchestrator's own:

```js
Monitor({
  command: "mcmcp-orchestrator events --types chat,join,leave --contains @Claude",
  description: "chat that tags Claude, and who comes and goes",
  timeout_ms: 1800000
})
```

## What arrives

An event is the game's event, plus the instance it came from and a cursor:

```json
{"instance":"alto.client","seq":1234,"t":"2026-11-15T14:02:11Z","type":"chat","player":"AngelWingsPanda","text":"@Claude the door is backwards","cursor":"alto.client@a1b2c3d4:1234"}
{"instance":"alto.server","seq":88,"t":"2026-11-15T14:03:40Z","type":"join","player":"Akselhok","online":["MicaAlex","Akselhok"],"cursor":"alto.server@9f8e7d6c:88"}
```

Event types are `chat`, `join`, `leave`, `death`, `advancement` and `command`.

A singleplayer world is two endpoints, `.client` and `.server`, and both see the same chat. Pass
`instance` to watch one of them.

Notices say when the stream itself changed, so a quiet stream always means nothing happened:

| `type` | Meaning |
|---|---|
| `link` | A game came (`"state":"up"`) or went (`"down"`). Its cursor is kept, so a game that relinks carries on. |
| `orchestrator` | The command lost the app (`down`) or found it again (`up`). It keeps retrying. |
| `unsupported` | That game's MCMCP predates events. Update the mod. |
| `reset` | The game restarted since the cursor; its events start again from the oldest it holds. |
| `missed` | `count` events fell off the game's ring before they were read. |
| `error` | Polling that game failed, with the reason. Said once per distinct failure while it retries. |

## Filters

| Command flag | WebSocket parameter | |
|---|---|---|
| `--types chat,join` | `types=chat,join` | Only these types. |
| `--players A,B` | `players=A,B` | Only events about these players. |
| `--exclude-players Me` | `exclude_players=Me` | Never these: usually the agent's own player. |
| `--contains @Claude` | `contains=%40Claude` | Only text containing this, ignoring case. |
| `--instance alto.client` | `instance=alto.client` | Only these games. Repeatable; every game by default. |
| `--resume <cursor>` | `resume=<cursor>` | Pick a game up after this cursor. One per game, repeatable. |
| `--backlog` | `backlog=1` | Start from the oldest event each game still holds, not from now. |

WebSocket parameter values are URL-encoded; lists may be comma-separated or repeated.

## Resuming

Monitor stops a watch after at most 30 minutes. To carry on without losing anything, re-arm it with
the last `cursor` you saw from each game:

```
mcmcp-orchestrator events --types chat --resume alto.client@a1b2c3d4:1234
```

A cursor from before the game restarted still works: the game answers with a `reset` notice and
everything it holds.

## Security

The WebSocket listens on loopback only and wants the orchestrator's MCP token as `?token=`. It sits
in the URL because neither browsers nor Monitor can send a header on a WebSocket. Pages from another
origin are refused, so a website cannot open the socket from your browser. The stream is read-only.

## Configuration

| Where | Setting | Default |
|---|---|---|
| App | `MCMCP_EVENTS_PORT` environment variable | `25582` |
| `mcmcp-orchestrator serve` | `--events-port` | `25582` |

`0` turns the WebSocket off. The command form needs only the app's MCP port (`--port`, default
`25581`).

The relay's own polls stay out of the orchestrator's log, activity view and sessions: a watch held
open all day would otherwise be a call a minute per game in all three.
