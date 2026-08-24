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
makes — the same 45 tools, three times over, distinguished only by a prefix.

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

## Telling several games apart

`mcmcp_compare_instances` reports only what is **different** between the connected games — the mods
each has that the others lack, and the tools only some of them offer. Reporting what they share
would bury the answer under a hundred identical lines, and the question you actually have with three
games open is "which of these is the mod I am working on".

Against a real pair it answers that outright:

```
run-d0a639 ("run", client)
  mods only here: albedo, albedocore, com.boydti.fawe, immersiveengineering, worldedit
  tools only here: none

server-fc5e56 ("server", server)
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
mcmcp-orchestrator log --instance modb-dev --grep screenshot
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
(`events.jsonl`), the cached tool catalogue, and the shim's token. Approvals store a **hash** of each
instance secret, never the secret — the secrets stay in each game's own config.

`MCMCP_ORCHESTRATOR_HOME` overrides all of it.

## What needs the app, and what does not

Only the orchestrator link needs an orchestrator, and only `ask` gating and the approval prompts need
the *desktop* one. Everything else in MCMCP — every tool, resource and prompt, the HTTP endpoint,
`/mcmcp`, the API explorer — works with the jar alone and always will.

CI proves both paths on every commit: one job drives the HTTP endpoint against a real dedicated
server, another drives a real MCP client through the orchestrator against the same thing.

!!! note "Installers are unsigned"

    Windows SmartScreen and macOS Gatekeeper will both complain about the orchestrator installers.
    That is a deliberate deferral rather than an oversight — signing certificates cost money annually
    and this is currently a personal tool. The headless binary and the jar are unaffected.
