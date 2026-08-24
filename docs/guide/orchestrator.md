# Running several instances at once

MCMCP has two ways in, and they are not alternatives — both run at the same time, and either works
on its own.

| | HTTP endpoint | Orchestrator link |
| --- | --- | --- |
| Direction | Something connects **to** the game | The game connects **out** |
| Needs a free port | Yes, one per instance | No |
| Needs a bearer token in your MCP client | Yes | No |
| Needs anything else installed | No | Yes — the orchestrator app |
| Entries in your MCP client config | One per instance | One, for all instances |

**If you run one game at a time, you do not need any of this.** Drop the jar in `mods/`, point your
MCP client at `http://127.0.0.1:25585/mcp`, and skip the rest of this page. That path is unchanged
and is not going away.

## The problem this solves

`clientPort` defaults to `25585` in every install. Start a second game with the same default and it
cannot bind: MCMCP logs the failure and that instance runs with **no MCP endpoint at all**. Nothing
in-game says so — the only sign is a line in `logs/latest.log`.

You can fix that by hand. Give each instance a different port, add a separate entry per instance to
your MCP client's config, and keep track of which port is which game. It works, and it costs you a
config edit for every instance you add, plus one tool catalogue per instance in every request your
model makes — the same 45 tools, three times over, distinguished only by a prefix.

The orchestrator link replaces that with one connection out of each game to one app.

## What changes when the link is on

**Nothing listens.** No port to pick, nothing to collide, no bookkeeping. A second instance on a
default config now works.

**Presence is the connection.** The orchestrator knows an instance is alive because the socket is
open. There is no registry file to go stale and no heartbeat to miss.

**You approve an instance once.** The first time a game connects, the orchestrator asks whether you
recognise it — showing the instance's name, its folder, which side it is, and its Minecraft version.
After that it is remembered.

**Your MCP client config stops changing.** One entry, pointed at the orchestrator. Games come and go
underneath it.

## Instance identity

Three values in the `identity` section of `config/mcmcp.cfg`, generated on first launch:

```
identity {
    S:instanceId=modb-dev-3f2a1c
    S:instanceName=modB dev
    S:instanceSecret=<64 hex characters>
}
```

`instanceId` is what an orchestrator stores your approval against. It is generated once and does not
change, deliberately including when you move or rename the instance folder — an instance you moved
is the same instance, and being asked to re-approve it every time a path changed would teach you to
click through the one prompt that is meant to mean something.

`instanceName` is the only one of the three meant to be edited, and it is worth editing. It defaults
to the folder's name and it is how you and a model will tell several running games apart. Name it
after what you are doing in it — `mymod dev`, `vanilla control`.

`instanceSecret` is a password. It is what stops any other process on your machine from claiming to
be an instance you have already approved. It is never logged, never printed by a command, and never
included in a tool result. Rotate it by clearing the value and restarting; you will be asked to
approve the instance again.

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

The link is **on by default** and harmless when no orchestrator is running: it logs one line saying
nothing is listening, then retries quietly with a backoff that grows to 30 seconds. Your HTTP
endpoint is unaffected either way.

Only loopback is supported. The link is not encrypted and carries your instance secret followed by
full control of the game, so `orchestratorHost` should stay `127.0.0.1`. Forward the port over SSH if
you need to reach an orchestrator on another machine.

Network settings are read when the link starts, so changing them needs `/mcmcp restart`, not just
`/mcmcp reload`.

## Checking it

```
/mcmcp status    # identity, plus every transport and whether it is up
/mcmcp link      # the orchestrator link in detail
```

`/mcmcp status` now reports each transport separately, because "the endpoint is up" stopped being one
fact. An instance whose HTTP port was taken but whose link is connected is a perfectly working
instance; one whose port is fine but whose link is down is a different problem.

`/mcmcp link` exists because the answers point in different directions:

| What it says | What to do |
| --- | --- |
| `disabled in the config` | Set `enableOrchestratorLink=true`, then `/mcmcp restart` |
| `retrying` | Start the orchestrator app |
| `waiting to be approved in the orchestrator` | Approve this instance in the app |
| `connected` | Nothing |
| `stopped — the orchestrator knows this instance id but not this secret` | Approve the instance again in the app; it will not retry on its own |

## When the app is required

Only for the orchestrator link. Everything else in MCMCP — every tool, every resource, every prompt,
the HTTP endpoint, `/mcmcp`, the API explorer — works with the jar alone and always will. The link is
additive, and CI proves both paths on every commit.
