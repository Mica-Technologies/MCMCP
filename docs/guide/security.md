# Security model

MCMCP opens a network port that can move your character, run commands and read your screen. This page
states plainly what protects that, what does not, and where the boundaries actually are.

## Threat model

**What MCMCP defends against**

1. **Other processes on the same machine.** Any local process can connect to a loopback port. The
   bearer token is what stops one that has no business doing so.
2. **Web pages you visit.** A page in your browser can issue requests to `http://127.0.0.1:25585`
   without your involvement. Origin validation is what stops that.
3. **The wider network.** Loopback binding by default means there is nothing to reach from outside
   the machine.

**What it does not defend against**

1. **A compromised MCP client.** If the client holding your token is malicious, it can do anything
   the endpoint permits. The token grants full access; there are no per-tool scopes.
2. **Anyone who can read your config file.** The token is stored in plaintext in `config/mcmcp.cfg`,
   like every other Forge config value.
3. **Your own model's mistakes.** MCP tool annotations mark destructive tools so clients can require
   confirmation, but MCMCP cannot make a client honour that.

## The three controls

### Loopback binding

`bindAddress` defaults to `127.0.0.1` and **should stay there**.

Binding `0.0.0.0` exposes an interface that moves your player, runs commands and captures your screen
to every machine that can route to you. MCP's own transport security guidance is explicit about this,
and it is the single setting most worth leaving alone.

To reach the endpoint from another machine, forward the port:

```bash
ssh -N -L 25585:127.0.0.1:25585 you@gaming-box
```

SSH supplies the encryption and authentication that a raw bound port does not. MCMCP speaks plain
HTTP; it has no TLS, and a token crossing an untrusted network in a plaintext header is a token
someone else now has.

If you bind beyond loopback anyway, MCMCP says so at startup and in `/mcmcp status`:

```
[mcmcp]: MCMCP client endpoint is bound to 0.0.0.0, which is reachable from outside this
         machine. Anyone who can reach it and holds the token can control this game instance.
         Bind 127.0.0.1 and use an SSH tunnel unless you specifically intend otherwise.
```

### The bearer token

128 bits from `SecureRandom`, generated on first launch and written to `config/mcmcp.cfg`. Presented
as `Authorization: Bearer <token>` on every request.

Comparison is constant-time. `String.equals` returns as soon as it finds a differing byte, which over
enough requests reveals a token one character at a time — a real attack against a loopback service
that a local process can hammer.

Rotate by blanking the value and restarting; a new one is generated.

`requireAuth=false` disables the check entirely. There is no situation on a machine you use for
anything else where that is a good idea, and MCMCP warns loudly:

```
[mcmcp]: MCMCP client endpoint has authentication DISABLED. Any process that can reach
         http://127.0.0.1:25585/mcp can control this game instance.
```

### Origin validation

This one is less obvious and matters more than it looks.

A web page you visit can make your browser POST to `http://127.0.0.1:25585`. It cannot read the
response cross-origin without CORS permission, but a blind write is plenty: `tools/call` with
`server_run_command` needs no response to have already happened. DNS rebinding makes even the read
side achievable.

So requests carrying an `Origin` header must have that origin in `allowedOrigins`, which defaults to
`http://localhost` and `http://127.0.0.1` (matched ignoring port). Requests with **no** `Origin` — 
every non-browser MCP client — are unaffected.

Setting `allowedOrigins` to `*` disables the check. Do not, unless the instance is disposable.

!!! note "Using the hosted API explorer"

    The [API explorer](../api-explorer.md) on this site is a web page, so its origin
    (`https://mica-technologies.github.io`) must be allow-listed for it to reach your instance.

    That is a real trade-off, not a formality: allow-listing it means any page served from that
    origin can drive your game. Add it on a development instance if the convenience is worth it, and
    remove it afterwards. The explorer page is a single self-contained file — save it and open it
    locally if you would rather not.

## Capability permissions

The `permissions` category gates whole families of tools:

| Setting | Default | Gates |
| --- | --- | --- |
| `allowCommands` | on | Command execution on both endpoints |
| `allowPlayerControl` | on | Movement, camera, keys, interaction |
| `allowInventoryChanges` | on | Hotbar selection and inventory manipulation |
| `allowWorldEdits` | **off** | Direct block writes, player teleports |
| `allowScreenshots` | on | Screen capture |
| `allowLogAccess` | on | Reading the game log |
| `allowChat` | on | Sending chat |

Two design notes:

**Disabled tools stay listed.** They return an error naming the setting that disabled them, rather
than vanishing. A model that can read the permission state — via `mcmcp_endpoint_info`, which reports
all of it — stops planning around capabilities it does not have. A tool that silently disappears
leaves it guessing why its plan is impossible.

**`allowWorldEdits` is off by default** because direct world writes are a categorically different
grant from "act as the player". They bypass claim protection, block-place events and every other
mod's hooks. Everything else defaults on because it is bounded by what the player could already do.

### The command blocklist

`permissions.blockedCommands` refuses a configurable set outright, defaulting to `stop`, `op`,
`deop`, `ban`, `ban-ip`, `whitelist`.

It is **a backstop, not a security boundary**. Command names can be spelled in ways a name match does
not catch, and it does not inspect arguments. The real boundary is the permission level the command
runs at: on the client endpoint that is the player's own level, and on the server endpoint,
`CapturingCommandSender` delegates `canUseCommand` to the real sender rather than returning true.

Treat the blocklist as protection against a model doing something obviously wrong by accident, not
against one trying to get around it.

## Resource limits

An endpoint inside a game process must not be able to consume that process. The bounds:

| Bound | Default | Why |
| --- | --- | --- |
| Request body | 4 MB | Caps what an unauthenticated request can allocate before auth runs |
| Sessions per endpoint | 8 | Each reserves a worker thread; refuses rather than evicting |
| Outbound queue per session | 512 messages | A client that never opens its stream cannot grow unbounded |
| Session idle timeout | 30 min | Crashed clients never say goodbye |
| Game-thread task timeout | 5 s | A stuck endpoint is noticed instead of pinning a worker |
| Scan radius | 32 blocks | Scans run on the game thread; cost grows with the cube |
| Bulk block volume | 32,768 | One bulk call is one game-thread task; this bounds the stall |
| Input hold | 200 ticks | Bounds how far one call moves the player before the model looks again |

## Filesystem access

Two tools take a caller-supplied file name, and both reject path syntax outright rather than trying
to sanitise it:

- `game_read_log` accepts a plain name inside `logs/`. Any `/`, `\` or `..` is refused.
- The screenshot resource accepts a plain name inside `screenshots/`. The URI template cannot match
  across a `/`, and `..` is refused explicitly.

A name-only rule is the version of that check that cannot be subtly wrong.

## Operational advice

- Leave `bindAddress` at `127.0.0.1`. Tunnel with SSH when you need remote access.
- Do not paste the token into chat, screenshots, or issue reports. `/mcmcp token` uses a
  click-to-copy component specifically to keep it out of chat scrollback.
- On a shared server, `/mcmcp` is operator-only in full. There is no read-only subset.
- Leave `allowWorldEdits` off unless you are building, and turn it back off afterwards.
- `/mcmcp sessions` lists who is connected, with client name, version and uptime. Check it if
  something is acting on your world that you did not ask for.
- `/mcmcp stop` shuts both endpoints down immediately without restarting the game.
