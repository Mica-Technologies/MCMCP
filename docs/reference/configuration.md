# Configuration

Everything lives in `config/mcmcp.cfg`, in Forge's standard config format. It is created on first
launch with defaults and inline documentation.

`/mcmcp reload` re-reads the file. **Network settings only take effect on `/mcmcp restart`** — the
bind address, port and worker pool are fixed at socket-creation time, so a reload that did not rebind
would silently apply only half the file.

## `endpoints`

### `enableClientEndpoint`

`boolean` · default `true`

Run an MCP endpoint inside the game client.

This is the endpoint that works everywhere: it controls the local player on whatever server they are
connected to, and needs no server-side install, no operator rights and no inbound port on the server.
It can do exactly what the player could do by hand.

### `enableServerEndpoint`

`boolean` · default `false`

Run an MCP endpoint inside the server — dedicated, or the integrated server behind a singleplayer
world.

Off by default. This endpoint sees authoritative world state for every player and runs commands with
server authority, which is a much larger grant than the client endpoint and should be an explicit
decision by whoever operates the server.

In a development launch `addon.gradle` passes `-Dmcmcp.dev.autostart=true`, which flips the *default*
to true. An explicit value in the config file still wins.

### `bindAddress`

`string` · default `127.0.0.1`

The interface both endpoints bind.

**Leave this alone unless you know why you are changing it.** Any other value makes a remote-control
interface for this game reachable from the network. To reach it from another machine, forward the
port over SSH rather than binding a public address:

```bash
ssh -N -L 25585:127.0.0.1:25585 you@gaming-box
```

Binding beyond loopback produces a warning at startup and in `/mcmcp status`.

### `clientPort` / `serverPort`

`integer` 1024–65535 · defaults `25585` / `25586`

Must differ: a singleplayer world runs both endpoints in one process.

In a development launch these default from `-Dmcmcp.dev.port`, which is a **base** — the client
endpoint takes it and the server endpoint takes base+1. `runClient` gets 25585/25586 and `runServer`
gets 25587/25588, so four endpoints across two JVMs never collide.

### `endpointPath`

`string` · default `/mcp`

The URL path the endpoint is served from. A liveness probe is served at this path + `/health` and
needs no authentication.

Normalised on read: a leading `/` is added if missing and trailing slashes are stripped.

### `requireAuth`

`boolean` · default `true`

Require `Authorization: Bearer <token>` on every request.

Turning this off means any process on the machine — including any web page you visit, if it can guess
the port — can control the game. Only appropriate on an isolated test instance, and MCMCP warns at
startup when it is off.

### `authToken`

`string` · default empty, generated on first launch

The bearer token. 128 bits from `SecureRandom`, hex-encoded, written back into the config the first
time the mod starts with `requireAuth` on.

Treat it like a password: anyone holding it can act as you in game. Rotate it by clearing the value
and restarting.

Retrieve with `/mcmcp token`, which in game gives a click-to-copy component rather than plain chat
text — chat scrollback ends up in screenshots.

### `allowedOrigins`

`string list` · default `http://localhost`, `http://127.0.0.1`

Origins accepted on cross-origin requests, matched ignoring port (an entry of `http://localhost`
covers `http://localhost:5173`).

This is the DNS-rebinding defence. Without it, a web page you visit could have your browser POST to
this endpoint and drive your game — a blind write needs no readable response. Requests with **no**
`Origin` header, which is every non-browser MCP client, are unaffected.

`*` disables the check.

!!! note "The hosted API explorer"

    The [API explorer](../api-explorer.md) is a web page, so `https://mica-technologies.github.io`
    must be listed for it to reach your instance. That means any page from that origin can drive
    your game — reasonable on a development instance, worth removing afterwards. The explorer is a
    single self-contained file; saving it and opening it locally avoids the question.

## `permissions`

Turning a group off does not hide its tools. They stay listed and return an error naming the setting
that disabled them, so a model can read the state via `mcmcp_endpoint_info` and stop planning around
capabilities it does not have.

### `allowCommands`

`boolean` · default `true`

Tools that run chat commands. On the client endpoint these execute with the player's own permission
level, exactly as if typed.

### `blockedCommands`

`string list` · default `stop`, `op`, `deop`, `ban`, `ban-ip`, `whitelist`

Command names refused by the command tools, without the leading slash.

A backstop, not a security boundary — command names can be spelled in ways a name match does not
catch, and arguments are not inspected. The real boundary is the permission level the command runs
at.

### `allowPlayerControl`

`boolean` · default `true`

Movement, camera, keys and mouse buttons. Client endpoint only.

### `allowInventoryChanges`

`boolean` · default `true`

Hotbar selection, swapping, dropping.

### `allowWorldEdits`

`boolean` · default **`false`**

Tools that write world state directly — setting blocks, teleporting players — rather than through
player actions.

Off by default because direct writes bypass protections, claims and the event handlers other mods
rely on. That is a fundamentally different grant from "act as the player".

### `allowScreenshots`

`boolean` · default `true`

Screen capture. Client endpoint only.

Screenshots capture whatever is on screen, including any other window content composited into the
game's framebuffer.

### `allowLogAccess`

`boolean` · default `true`

Reading the tail of the game log. Logs can contain server addresses, player names and mod
diagnostics.

### `allowChat`

`boolean` · default `true`

Sending chat messages.

## `identity`

Who this game instance is. Generated on first launch; see
[Running several instances](../guide/orchestrator.md).

### `instanceId`

`string` · generated

Stable id for this instance, in the form `<folder-slug>-<random>`. An orchestrator stores its
approval of this instance against this id.

Deliberately not derived from the game directory at read time: an instance you move or rename is the
same instance, and being asked to approve it again because a path changed would teach you to click
through the one prompt that is meant to mean something. The random suffix exists because copying an
instance folder is how people make a test variant of a pack, and two instances both calling
themselves `atm9` would collide in an approval store — where the consequence is one instance
inheriting another's access.

Clearing it generates a new one, and costs you the approval.

### `instanceSecret`

`string` · generated

64 hex characters — 256 bits — proving this instance is the one an orchestrator approved.

Treat it like a password. Without it, any process on this machine could open a link, claim an
approved id, and be believed; trust-on-first-use with no secret is not trust. It is never logged,
never printed by `/mcmcp`, and never included in a tool result.

Rotate it by clearing the value and restarting. You will be asked to approve the instance again, and
until you do the link reports `the orchestrator knows this instance id but not this secret` and stops
retrying.

### `instanceName`

`string` · defaults to the instance folder's name

The human-readable label, and the only field in this section meant to be edited.

It is how you and a model tell several running games apart, so name it after what you are doing in
it — `mymod dev`, `vanilla control`. A label typed into the orchestrator's roster wins over this one
for display there, because somebody chose it on purpose.

## `orchestrator`

The outbound link that lets several instances be driven through one MCP endpoint. See
[Running several instances](../guide/orchestrator.md).

### `enableOrchestratorLink`

`boolean` · default `true`

Connect out to an orchestrator.

On by default and harmless without one: the link logs a single line saying nothing is listening, then
retries quietly in the background. It does not replace the HTTP endpoint — both run, and either works
alone.

### `orchestratorHost`

`string` · default `127.0.0.1`

Where the orchestrator is listening.

**Loopback only.** The link is not encrypted and carries the instance secret followed by full control
of this game. Forward the port over SSH rather than pointing this across a network; MCMCP logs a
warning if you set anything else.

### `orchestratorPort`

`integer` 1024–65535 · default `25580`

The orchestrator's port. Unlike `clientPort`, nothing here binds it — several instances dial the same
port with no conflict, which is the point.

### `reconnectBackoffMillis`

`integer` 100–60000 · default `1000`

Delay before the first reconnect attempt. Doubles after each failure, up to
`reconnectBackoffMaxMillis`, and resets on every successful handshake.

### `reconnectBackoffMaxMillis`

`integer` 1000–600000 · default `30000`

Longest gap between attempts.

The resting state of most installs is a game with no orchestrator running, so the gap grows rather
than dialling a closed port every second forever. It stops growing because the thing being waited for
is a person starting an app, and half a minute is about as long as anyone should sit wondering why
their game has not appeared in the roster.

## `limits`

### `maxSessions`

`integer` 1–64 · default `8`

Concurrent MCP sessions per endpoint. Each permitted session reserves an HTTP worker thread so its
event stream cannot starve request handling — the pool is sized `workerThreads + maxSessions`.

At the limit, new sessions are refused rather than old ones evicted: each session is a live client
that would otherwise silently stop working.

### `sessionIdleTimeoutSeconds`

`integer` 30–86400 · default `1800`

Drop a session after this long without traffic. Clients crash without saying goodbye; without this,
their sessions and queued notifications accumulate until the game restarts.

Swept every 30 seconds from a daemon thread. It used to be swept from the server tick, which never
fires on a client sitting at the main menu — harmless while the only way in was an HTTP port nobody
connects to before a world is loaded, and not harmless once an orchestrator link is up from the
moment the game finishes loading.

### `workerThreads`

`integer` 2–32 · default `4`

HTTP workers for handling requests, on top of the one reserved per session.

### `gameThreadTimeoutMillis`

`integer` 100–60000 · default `5000`

How long a tool waits for the game thread to run its work before failing.

A timeout on the game thread *getting around to* the task, not on the task itself. A loaded server can
take several ticks; 5 seconds is generous for that and short enough that a stuck endpoint is noticed.

### `maxScanRadius`

`integer` 1–128 · default `32`

Largest radius that world- and entity-scanning tools search. Scans run on the game thread and cost
grows with the cube: 32 is already 260,000 block reads per call.

### `maxBlockVolume`

`integer` 64–262144 · default `32768`

Most blocks a single bulk read or write may touch. The default is a 32×32×32 cube.

Bulk operations run in one game-thread task, so this is directly a bound on how long one MCP call can
stall the tick loop. A 64×64×64 region is 262,144 block writes and will visibly freeze the server.

### `maxInputTicks`

`integer` 1–1200 · default `200`

Longest a single input tool may hold a key down, in ticks (20 = 1 second). Bounds how far one call can
move the player before the model gets to look again.

### `maxLogLines`

`integer` 10–5000 · default `500`

Most log lines returnable in one call.

## Example

```ini
endpoints {
    B:enableClientEndpoint=true
    B:enableServerEndpoint=false
    S:bindAddress=127.0.0.1
    I:clientPort=25585
    I:serverPort=25586
    S:endpointPath=/mcp
    B:requireAuth=true
    S:authToken=3f9a1c8e5b7d204f6a1e9c3b8d5f2a70

    S:allowedOrigins <
        http://localhost
        http://127.0.0.1
     >
}

permissions {
    B:allowCommands=true
    B:allowChat=true
    B:allowInventoryChanges=true
    B:allowLogAccess=true
    B:allowPlayerControl=true
    B:allowScreenshots=true
    B:allowWorldEdits=false

    S:blockedCommands <
        stop
        op
        deop
        ban
        ban-ip
        whitelist
     >
}

identity {
    S:instanceId=modb-dev-3f2a1c
    S:instanceName=modB dev
    S:instanceSecret=<64 hex characters>
}

orchestrator {
    B:enableOrchestratorLink=true
    S:orchestratorHost=127.0.0.1
    I:orchestratorPort=25580
    I:reconnectBackoffMaxMillis=30000
    I:reconnectBackoffMillis=1000
}

limits {
    I:gameThreadTimeoutMillis=5000
    I:maxBlockVolume=32768
    I:maxInputTicks=200
    I:maxLogLines=500
    I:maxScanRadius=32
    I:maxSessions=8
    I:sessionIdleTimeoutSeconds=1800
    I:workerThreads=4
}
```
