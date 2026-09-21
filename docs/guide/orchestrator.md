# Running several instances at once

MCMCP has two ways in, and they are not alternatives — both run at the same time, and either works
on its own.

| | HTTP endpoint | Orchestrator link |
| --- | --- | --- |
| Direction | Something connects **to** the game | The game connects **out** |
| Needs a free port | Yes, one per instance | No |
| Needs a bearer token in your MCP client | Yes | No |
| Needs anything else installed | No | Yes — the orchestrator |
| Entries in your MCP client config | One per instance | One, for all instances |

**If you run one game at a time, you do not need any of this.** Drop the jar in `mods/`, point your
MCP client at `http://127.0.0.1:25585/mcp`, and skip the rest of this page. That path is unchanged
and is not going away.

## The problem this solves

`clientPort` defaults to `25585` in every install. Start a second game with the same default and it
cannot bind: MCMCP logs the failure and that instance runs with **no MCP endpoint at all**. Nothing
in-game says so — the only sign is a line in `logs/latest.log`.

You can fix that by hand. Give each instance a different port, add a separate entry per instance to
your MCP client's config, and keep track of which port is which game. It works, and it costs a config
edit for every instance you add, plus one tool catalogue per instance in every request your model
makes — the same 62 tools, three times over, distinguished only by a prefix.

The orchestrator replaces that with one connection out of each game to one place.

## Which piece is which

Three programs, and you will not use all of them.

**The mod** dials out. Nothing listens, so nothing can collide, and a second instance on a default
config now works.

**The orchestrator** accepts those links and presents them to your MCP client as one server. It comes
in two forms sharing the same core:

- **`MCMCP Orchestrator`** — the desktop app. What you want if you are running games interactively.
- **`mcmcp-orchestrator`** — the same thing headless, for CI or a server. Also the **shim**, which is
  what an MCP client actually spawns when the app is what serves it.

## Connecting an MCP client

Start the app once, open **Settings**, and copy the snippet. It looks like this:

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "C:\\Program Files\\MCMCP Orchestrator\\mcmcp-orchestrator.exe",
      "args": ["shim"]
    }
  }
}
```

No port, no token, no URL. The shim finds the app — and starts it if it is not running — so this
entry never has to change, and games can come and go underneath a connection that never drops.

Prefer no app at all? The headless orchestrator speaks MCP over stdio directly:

```json
{
  "mcpServers": {
    "minecraft": { "command": "mcmcp-orchestrator", "args": ["serve"] }
  }
}
```

## Addressing a game

Every tool gains an optional `instance` argument. Omit it and the call goes to the **focused**
instance; with only one game connected, that is always the right one.

What you address is one **endpoint**, not one game. Instance ids read `<game>.client` and
`<game>.server`:

```
atm9-3f2a1c.client     the camera, the keyboard, the screenshots
atm9-3f2a1c.server     authoritative world state, commands, direct block writes
```

A world open in singleplayer is **both**, from one process — and they are separately addressable
because their tool surfaces genuinely differ, while `mcmcp_instances` reports the `game` they share
so you can see they are one running game rather than two. Approving, revoking and renaming all apply
to the game, so you do those once and both endpoints follow.

```
mcmcp_instances            # what is connected, what each one is, which is focused
mcmcp_focus                # read or change where unaimed calls go
mcmcp_set_label            # rename an instance so it can be told apart
mcmcp_compare_instances    # what differs between the connected games
mcmcp_read_logs            # every game's log, merged in time order and tagged
```

**Every result says which instance produced it**, in three places: a prefix on the text, a
`_meta` entry, and `structuredContent` where the tool's own schema allows it. That is not decoration.
Focus is sticky, you can change it in the app at any time, and without the result saying where it
came from a model would keep acting on its memory of what was focused.

Read-only tools also accept `instance: "*"`, which runs them on every connected game at once and
returns the answers together — the fastest way to compare a mod against a control. Tools that change
anything do not offer it, and are refused if asked.

### A call the focused endpoint cannot take goes to the other half of its game

With the client focused, `server_run_command` has nowhere to run on the client — but the same game's
server endpoint has it, and that is the only thing the call can have meant. So when `instance` is
omitted and exactly one other endpoint of the **same game** offers the tool, the call goes there and
the result says so on the line under the banner: `(Routed to atm9-3f2a1c.server: the focused instance
atm9-3f2a1c.client has no server_run_command.)`. A call that names its instance is never redirected,
and an endpoint of a different game never counts as a sibling.

Destructive calls stay under the gating setting that requires them to name their instance. A refusal
from it says which `instance` to pass.

### `connected` is not the same as `ready`

`mcmcp_instances` reports both, and the difference matters when a game is misbehaving. An instance is
registered the moment its link is up, which is *before* anything has asked it what it can do —
`connected: true` only means the socket exists. `ready: true` means its catalogue has actually been
loaded and its tools are reachable.

A game that is `connected` but not `ready` is either still coming up or retrying a bootstrap that is
failing. Its tools are genuinely not callable yet, and a call naming it comes back saying so. The
orchestrator keeps retrying for as long as the link is up, so this normally resolves itself within
seconds; one that stays that way has something wrong, and the reason is in `orchestrator.log` and in
the event log as a failed-bootstrap entry.

An instance that is not ready contributes nothing to the aggregated tool list — deliberately, and not
merely by having nothing to add. The aggregate is cached, so letting an empty catalogue into it would
replace the tool surface every other instance had.

### Relaunching a game does not disturb the tool list

Connecting and disconnecting only tells your client its lists changed when they *actually* changed.
That comparison is worth more than it sounds. A `list_changed` makes a host re-read `tools/list` and
swap some fifty tool definitions into the prompt it sends for every subsequent turn, which throws
away its prompt cache and re-bills the conversation so far — and the loop the orchestrator exists to
serve is relaunching a dev client over and over, each time with a byte-identical catalogue.

This is also why the `instance` enum covers games seen recently rather than only connected ones: a
relaunch that rewrote every tool's schema would be a change, and would announce itself. A game that
brings tools the others lack still announces, because then the surface really is different.

Opening and closing a singleplayer world is the same situation. The server endpoint only exists
while a world is open, so its twenty-odd `server_*` tools used to be withdrawn and re-announced on
every world change. They now stay listed while the game's client is still connected. Calling one
with no world open answers with which endpoint owns it and how to bring it back — open a world with
`client_world_load` or `client_world_create`, then `client_wait` with `waitFor=worldLoaded` — rather
than a bare "not available". When the whole game closes, its tools leave with it.

### Two launches of the same game

Every row also carries `pid` and `startedAt`. They are the only fields that differ between two games
launched from the same directory: the instance id lives in that directory's config file, so a second
launch reports the same id, the same label, the same directory and the same mod version.

That is not hypothetical. MCMCP's ports are fixed, and the game that bound them keeps them. A Gradle
`runClient` forks its own JVM, so stopping the Gradle task leaves the **game** running and still
holding `clientPort`; the next launch cannot bind, and every call keeps reaching the first one. The
endpoint answers normally — from the previous build, with nothing to say so.

Compare `startedAt` against when you last built. [`client_quit`](../reference/tools.md#client_quit)
is how to avoid getting there in the first place, and the pid is what you kill when a client has
stopped responding and cannot be asked.

**Run one game client per game directory.** Two clients launched from one directory fight over its
ports, and to the orchestrator they are the same instance id arriving with a new pid. When the
process behind an instance changes between two of your calls, the next result carries a warning line
under its banner — `Warning: atm9-3f2a1c.client is a different game process than on your last call
(pid 1234 → 5678, …)` — because the world, open screens and anything set up earlier may all be gone.
Every result's `_meta` entry also carries the `pid` and `startedAt` it came from.

### How a game ended

A game this orchestrator saw stop carries `lastExit` in its `knownButNotRunning` entry: how long ago,
its pid, and any `crashReport` (path, `description` and the exception line) Minecraft wrote to its
`crash-reports` folder during that session, plus the JVM's `hs_err_pid<pid>.log` as `jvmErrorLog` if
the process died underneath the game. A call that names the gone instance repeats the crash in its
error. Only games that stopped while this orchestrator was running have a `lastExit`.

## Telling several games apart

`mcmcp_compare_instances` reports only what is **different** between the connected games — the mods
each has that the others lack, and the tools only some of them offer. Reporting what they share
would bury the answer under a hundred identical lines, and the question you actually have with three
games open is "which of these is the mod I am working on".

Against a real pair it answers that outright:

```
run-d0a639.client ("run", client)
  mods only here: albedo, albedocore, com.boydti.fawe, immersiveengineering, worldedit
  tools only here: none

server-fc5e56.server ("server", server)
  mods only here: jei, theoneprobe
  tools only here: server_broadcast, server_find_blocks, …
```

That first instance labelled itself `run`, from its folder name — which is exactly why this exists,
and a good reason to give it a real name with `mcmcp_set_label`.

`mcmcp_read_logs` merges every game's `latest.log` in time order, each line tagged with the game it
came from. Reading them one at a time gives you two lists to merge by eye, and the case this is for
is precisely where the order matters — a crash in one game immediately after an action in the other.
Stack traces stay attached to the line that opened them.

There is also a **`compare_instances` prompt**, for the A/B workflow: run the same thing in two games
and report what differs. It arrives already knowing what is connected, and it insists on naming
instances explicitly rather than leaning on focus — a comparison that silently ran twice in the same
game is worse than no comparison at all.

## First connection

The first time a game dials in, the app asks whether you recognise it, showing its name, folder, side
and versions. Three answers:

- **Approve** — remembered; it connects without asking again.
- **Not now** — refused this time. It keeps retrying and will ask again.
- **Never** — refused and recorded. Un-revoke it from the roster to undo.

Turn on **Ask before every connection** in Settings if trust-on-first-use is not good enough for you.

The headless orchestrator approves on first use, because nothing is on screen to ask. Pass
`--strict-approval` to refuse unknown instances instead, then approve them with
`mcmcp-orchestrator approve <id>`.

## Instance identity

Three values in the `identity` section of `config/mcmcp.cfg`, generated on first launch:

```
identity {
    S:instanceId=modb-dev-3f2a1c
    S:instanceName=modB dev
    S:instanceSecret=<64 hex characters>
}
```

`instanceId` is what an orchestrator stores your approval against. Generated once and unchanged
thereafter, deliberately including when you move or rename the instance folder — an instance you
moved is the same instance, and being asked to re-approve it every time a path changed would teach
you to click through the one prompt that is meant to mean something.

`instanceName` is the only one meant to be edited, and it is worth editing. It defaults to the
folder's name and is how you and a model tell several running games apart. Name it after what you are
doing in it — `mymod dev`, `vanilla control`. Renaming it in the app wins over this value.

A client shows the name in its window title (`Minecraft 1.12.2 - MCMCP [mymod dev]`), in the top
right of the main menu, and as a line of the F3 overlay. A rename in the app reaches the game the next
time it connects.

`instanceSecret` is a password: it is what stops any other process on your machine from claiming to
be an instance you have already approved. Never logged, never printed by a command, never in a tool
result. Rotate it by clearing the value and restarting; you will be asked to approve again.

## Tools that ask you something

A tool can ask a question back — MCP calls these **sampling** (ask the model) and **elicitation**
(ask the human). Nothing MCMCP ships uses them; they are there so a mod registering its own tools
can.

The orchestrator routes them in both directions, rewriting ids on each hop, and answers rather than
drops a request it cannot forward — an unanswered request leaves a tool blocked inside the game
until its own timeout, with no way to tell a slow answer from one that is never coming.

One wrinkle worth knowing: a game connects long before any MCP client does, so the orchestrator tells
it optimistically that these capabilities exist. If the client that eventually attaches did not offer
them, the tool gets a clear error naming the missing capability. That is a better failure than
telling the game the capability was absent and having it never try.

## Starting with your computer

Off by default, and you do not need it — your MCP client starts the app on demand through the shim.

Tick **Start when I log in** in Settings if you would rather the app were already there when you
launch your games. The difference is *when you get asked to approve them*: with the app already
running, a game's approval prompt appears while you are launching it, instead of arriving a few
turns into a conversation once your MCP client has brought the app up.

It writes a normal login item — a Run key entry on Windows, a LaunchAgent on macOS, a `.desktop`
entry on Linux — and the checkbox reads back from the system rather than from a setting of its own,
so removing the entry by hand is reflected honestly.

### Only one copy runs

Starting the app while it is already running shows the running one's window and exits. That matters
more than it sounds: a login item and a shim's on-demand launch land within seconds of each other,
and the copy that loses can bind neither port — so every game and every MCP client is attached to
the first, while the second would sit in the tray with an empty roster saying "no game connected".

If something other than the app holds the link port — a headless `mcmcp-orchestrator serve`, say —
the app cannot be reached by any game, and says so: the header reads **not listening** rather than
"no game connected", with the bind error beneath it.

## Gating

Off by default, because the orchestrator is not a security boundary — your MCP client is already
trusted to drive the game — and a default that blocked things would teach you to turn it off.

Tools are classified by their own MCP annotations, so the rules cover a tool another mod registered
five minutes ago:

| | |
| --- | --- |
| **Read-only** | Cannot change game state |
| **Changes things** | Writes something, not destructively |
| **Destructive** | Can change the world irreversibly |

Each gets `allow`, `ask` or `deny`, per instance or as a default. **`ask` needs the app** — the
headless orchestrator denies instead, and says so, because allowing what you asked to be prompted
about fails in the direction that loses work.

**Destructive calls must name their instance** is worth knowing about. It closes the gap focus leaves
open: if you move focus and the model does not notice, its next world-changing call lands somewhere
it did not intend. Everything else makes that visible; this makes it impossible.

## The log

Every call the model makes, every approval, every focus change, filtered by instance, by who acted,
and by level. Hover a row to copy it as JSON — which is what you reach for when a call misbehaves and
you want to paste it back into the conversation that caused it. **Export** writes a slice to a file.

Also available headless:

```bash
mcmcp-orchestrator log --instance modb-dev.client --grep screenshot
mcmcp-orchestrator log --json --limit 20
```

## Configuration

```
orchestrator {
    B:enableOrchestratorLink=true
    S:orchestratorHost=127.0.0.1
    I:orchestratorPort=25580
    I:reconnectBackoffMillis=1000
    I:reconnectBackoffMaxMillis=30000
}
```

On by default and harmless when no orchestrator is running: one log line saying nothing is listening,
then quiet retries backing off to 30 seconds. Your HTTP endpoint is unaffected either way.

Only loopback is supported, and `orchestratorHost` should stay `127.0.0.1`. The link is not
encrypted and carries your instance secret followed by full control of the game.

That is not the same as "one machine only" — see below.

## A game on another machine

A dedicated server elsewhere can reach your orchestrator over an **SSH tunnel**, with no
configuration change on either side. The game still dials `127.0.0.1:25580`; SSH is what carries it.

This is the case with the most to gain, because the game server never needs an inbound port opened
for it — which is normally the hard part of reaching a box you do not fully control.

Whichever direction you can already SSH in, the result is the same: a listener on the game server's
own loopback, forwarded to your orchestrator.

```bash
# If the game server can reach your workstation — run this ON THE GAME SERVER:
ssh -N -L 25580:127.0.0.1:25580 you@workstation

# If your workstation can reach the game server — run this ON YOUR WORKSTATION:
ssh -N -R 25580:127.0.0.1:25580 you@gameserver
```

Leave `orchestratorHost=127.0.0.1` in the server's `mcmcp.cfg`. It is telling the truth: from the
game's point of view the orchestrator *is* on loopback. Nothing crosses the network unencrypted, and
the mod's non-loopback warning correctly stays quiet.

Add `ServerAliveInterval 30` to your SSH config if the tunnel is long-lived. If it does drop, the
link notices and reconnects with backoff exactly as it would for any other broken socket.

!!! note "What has been tested"

    The link has been verified through a proxied hop with 40 ms of added latency each way — a full
    handshake, catalogue fetch, tool call and resource read, over 12 connections. That covers
    everything a tunnel does to the link itself. SSH's own authentication and keepalive behaviour is
    SSH's business and has not been exercised here.

Native TLS is deliberately **not** offered. It would be more code and weaker security than an SSH
tunnel you already know how to operate and audit.

Network settings are read when the link starts, so changing them needs `/mcmcp restart`, not just
`/mcmcp reload`.

## Checking it

```
/mcmcp status    # identity, plus every transport and whether it is up
/mcmcp link      # the orchestrator link in detail
```

`/mcmcp link` exists because the answers point in different directions:

| What it says | What to do |
| --- | --- |
| `disabled in the config` | Set `enableOrchestratorLink=true`, then `/mcmcp restart` |
| `retrying` | Start the orchestrator |
| `waiting to be approved in the orchestrator` | Approve this instance in the app |
| `connected` | Nothing |
| `stopped — the orchestrator knows this instance id but not this secret` | Approve it again; it will not retry on its own |

## Where state lives

| Platform | Directory |
| --- | --- |
| Windows | `%LOCALAPPDATA%\mcmcp-orchestrator` |
| macOS | `~/Library/Application Support/mcmcp-orchestrator` |
| Linux | `$XDG_STATE_HOME/mcmcp-orchestrator`, or `~/.local/state/mcmcp-orchestrator` |

Holding approvals (`instances.json`), the gating policy (`policy.json`), the event log
(`events.jsonl`), the diagnostic log (`orchestrator.log`), the cached tool catalogue, and the shim's
token. Approvals store a **hash** of each instance secret, never the secret — the secrets stay in
each game's own config.

The two logs answer different questions. `events.jsonl` is what happened *to instances* — links,
tool calls, approvals — as a closed set of kinds the app can filter. `orchestrator.log` is the
program's own `tracing` output, and it exists because the desktop app has no terminal: without it,
everything the orchestrator said about its own workings went to a stderr nobody could read. It is
appended to across runs and rotated to `orchestrator.log.1` once it passes 8 MB.

A tool call records how long it took **and how large its answer was**:

```
run-d0a639 (modB dev): server_get_blocks ok in 412ms, 8.4 KB
```

Everything a tool returns is read into a model's context and paid for on every turn after that, and
nothing else reports what that came to — so without this, which of your tools are expensive could
only be estimated by reading their code. Records written before the field existed load as zero and
print no size rather than claiming the call returned nothing.

Bytes are not tokens, and the gap is not uniform: text runs about a token per four bytes, but an
image is billed by its area however it was encoded, so an inline 1280×720 screenshot is around
200 KB here and about 1,230 tokens to a model. Sorting your tools by this column would put
screenshots on top and be wrong about it.

`MCMCP_ORCHESTRATOR_HOME` overrides all of it.

## What needs the app, and what does not

Only the orchestrator link needs an orchestrator, and only `ask` gating and the approval prompts need
the *desktop* one. Everything else in MCMCP — every tool, resource and prompt, the HTTP endpoint,
`/mcmcp`, the API explorer — works with the jar alone and always will.

CI proves both paths on every commit: one job drives the HTTP endpoint against a real dedicated
server, another drives a real MCP client through the orchestrator against the same thing.

## Upgrading

Install the new version over the old one — the installers do a major upgrade in place, keeping your
approvals, labels and gating, which live in the state directory rather than beside the program.

The app carries the release date as its version (`26.8.26` for the `2026.08.26` release; the two-digit
year and unpadded month are what an MSI will accept). It is shown beside the name in the app's header
and in **Settings → Where things live**, which is the quickest way to tell what you are running.

Something usually has to close first, and the installer asks before closing anything:

- **the app**, if its window is open;
- **the shim**, `mcmcp-orchestrator.exe`, which is the one that actually catches people out. An MCP
  client spawns it on your behalf, so it is frequently running without anybody having started it
  knowingly, and it holds the file the installer is about to replace.

Answering *cancel* to either prompt cancels the install rather than closing your session anyway. A
closed shim is restarted by its MCP client on the next call, so the cost of letting it go is one
reconnect.

!!! note "Installers are unsigned"

    Windows SmartScreen and macOS Gatekeeper will both complain about the orchestrator installers.
    That is a deliberate deferral rather than an oversight — signing certificates cost money annually
    and this is currently a personal tool. The headless binary and the jar are unaffected.
