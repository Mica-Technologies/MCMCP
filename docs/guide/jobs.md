# Background jobs and the emergency stop

Two things for long, large work on a server. **Jobs** let an agent hand the server an hour of writing
and come back for the result. **The emergency stop** lets a person, or an agent that notices
something is going wrong, halt everything MCMCP is doing at once.

## Jobs

A job is a large write or read submitted in one call. The server splits it into ordinary tool calls
and works through them at its own pace. Each part is exactly the call a script would have made by
hand, so it keeps that tool's own checks, limits and undo points.

```json
server_job_submit {"kind": "apply_ops", "label": "Castleton block 4",
                   "args": {"ops": ["fill ...", "setblock ...", "..."]}}
→ {"id": "j1g45861", "state": "queued", "parts": 6, ...}
```

| `kind` | Runs | Split by |
| --- | --- | --- |
| `apply_ops` | [`server_apply_ops`](../reference/tools.md#server_apply_ops) | Ops, so that each part reaches at most 4 × `limits.maxWriteCells` cells, and no more than `limits.writeCellsPerMinutePerCaller` |
| `set_blocks` | [`server_set_blocks`](../reference/tools.md#server_set_blocks) | Palette cells (at most `limits.maxWriteCells`, and no more than the write budget), list placements, or the layers of a fill |
| `get_blocks` | [`server_get_blocks`](../reference/tools.md#server_get_blocks) | The box, into reads of at most `limits.maxBlockVolume` cells; each comes back as a page |
| `relight` | [`server_relight`](../reference/tools.md#server_relight) | The box |
| `test` | [`server_run_test`](../reference/tools.md#server_run_test) | Not split: one script, one run. Its status carries the `verdict`, and a cancel still restores what the script marked |
| `pregen` | [`server_pregen_chunks`](../reference/tools.md#server_pregen_chunks) | Squares of 16 × 16 chunks, rows in order. Chunks already generated are skipped, so submitting the same area again resumes an interrupted job |

`args` are the arguments the tool itself would take, and are checked against its schema when the job
is submitted.

**Following a job.** [`server_job_status`](../reference/tools.md#server_job_status) gives its state,
the parts done, a percentage and an estimate of the time left. It also gives `totals`, the counts of
every part summed (`written`, `unchanged`, `refused`, and so on), and `refused`, the first 100
refusals with their reasons. For an op replay, `lastOp` is the index of the last op finished. A
`get_blocks` job's results are pages: pass `page` to read one. Each page holds the part's `region`
and its `result`.

Jobs also announce each change as an `mcmcp.jobs` log message, so the orchestrator app shows them
on the game's card, in its detail view, and above the task lists. A script can watch for them instead
of polling.

**Restarts.** A job belongs to the server, not to the call or script that started it. A script or
client that restarts picks it up again by id. The job's chunk holds are its own (`job:<id>`), so
neither its owner's [`server_release_loaded`](../reference/tools.md#server_release_loaded) `all` nor
the owner logging out cuts its chunks from under it. A job that was running when the server stopped
is listed afterwards as `interrupted`, with how far it got. Resume it with a new job from `lastOp + 1`.

**Pace.** Two jobs run at once and the rest queue. Each player may have four queued or running, and the
server sixteen. Every game-thread step of a job waits while the job is paused, or while the server
is slow (past [`health.holdJobsMspt`](../reference/configuration.md#health), 60 ms a tick by
default, or below `health.holdJobsBelowTps`, 15 real TPS) or worse: a job is the work that can wait. A step the server thread never got round to is
tried again, since nothing of it ran. A part over the writes-per-minute budget
(`limits.writeCellsPerMinutePerCaller`, kept per job) waits for it rather than failing: its status
`message` says so and how long, and a pause or cancel still lands within a second. So a job of any
size runs at the budget's rate, a minute's worth at a time. A part refused as busy for anything else
(chunk loads) is retried after the wait it asks for, up to eight times.

**Control.** [`server_job_pause`, `server_job_resume` and `server_job_cancel`](../reference/tools.md#server_job_cancel-server_job_pause-server_job_resume)
act on the next step. Cancelling keeps what was already written, with its undo points. Through the
companion, a player sees and controls only their own jobs. Submitting a job needs the companion class
of the tool it runs.

Finished jobs are kept 30 minutes, and interrupted ones a day. The record is in the world folder, in
`mcmcp-jobs.json`.

## The emergency stop

```
/mcmcp estop the fill is eating the plaza
```

While the stop is set:

- Every MCMCP call that is not read-only is refused, from the server endpoint, the orchestrator link
  and the companion alike. Reads keep working, so you and the agents can see what happened.
- A call already running stops at its next game-thread step. What it already wrote keeps its undo
  point.
- Every background job pauses.
- Every connected client running MCMCP is told over the companion channel. Its own MCMCP then
  refuses to move the player or change anything, and lets go of any keys an agent is holding. This
  applies whether or not the player has companion access.
- Every player is told in chat, and the orchestrator app shows the stop on the game's card.

The stop is saved with the world (`mcmcp-estop.json`), so a restart does not quietly lift it.

**Who can set it.** An operator with `/mcmcp estop [reason]`. An agent with
[`server_estop`](../reference/tools.md#server_estop), which every companion player with any access
may call. The tool can only set the stop, never lift it, so a model cannot undo its own stop.

**Finding out what was running.** `/mcmcp estop status` lists the running calls with who made each
one and on which session, the jobs, and the sessions. Then:

| Command | Does |
| --- | --- |
| `/mcmcp kill session <id>` | Closes one MCP session and cancels its calls |
| `/mcmcp kill player <name>` | Closes a player's companion sessions. Revoke their access too with `/mcmcp companion revoke` |
| `/mcmcp jobs kill <id\|all>` | Cancels jobs |

**Lifting it.** `/mcmcp estop reset`, typed by a person at the console or in the game. The command is
refused if it arrives through a tool call. Jobs the stop paused stay paused, so you can look at them
first. Resume them with `/mcmcp jobs resume <id|all>`, or let their owners resume them.

Some tools still run during a stop, because each only makes things stop or let go, or only looks:
`server_job_cancel`, `server_job_pause`, `server_estop`, `server_release_loaded`, `server_stop`,
`server_save_world`, `client_screenshot`, `client_marks`, `client_world_leave` and `client_quit`.
