"""Drives the orchestrator with TWO games connected.

Every other check runs with one instance linked, where most of the interesting behaviour is
unreachable by construction: a single instance is always unambiguous, always in focus, and a union
of one catalogue is that catalogue. This is the configuration the whole project exists for, so it is
the one worth proving.

**Local only, and deliberately not in CI.** It needs a dev *client*, which needs a display, and a
headless runner has neither. Run it by hand after anything that touches aggregation, focus, or
routing:

    # terminal 1 — a dedicated server
    export JAVA_HOME=...; ./gradlew runServer

    # terminal 2 — a client, which links from the main menu without joining anything
    export JAVA_HOME=...; ./gradlew runClient

    # terminal 3 — once both say they are retrying or connected
    python .github/scripts/two-instance-check.py <state-dir>

Nothing needs to be running at 25580 first: this starts its own orchestrator, and both games find it
on their next retry.

The cross-instance tools are the most worthwhile part to run against games that genuinely differ —
`mcmcp_compare_instances` has a trivially empty answer when both instances were built from the same
source, and the question it exists to answer ("which of these is the mod I am working on") only means
anything when they are not.

`--stop <fragment>` additionally kills the JVM whose command line contains `fragment` and checks that
the tool surface shrinks and says so. Destructive to whatever it matches, so the fragment must be
given explicitly and is never guessed.
"""

import json
import os
import subprocess
import sys
import threading
import time

BINARY = os.environ.get(
    "ORCH_BINARY",
    "orchestrator/target/debug/mcmcp-orchestrator.exe"
    if os.name == "nt"
    else "orchestrator/target/debug/mcmcp-orchestrator",
)
_args = [a for a in sys.argv[1:] if not a.startswith("--")]
STATE = _args[0] if _args else os.path.join(os.getcwd(), "run", "orchestrator-test")

# --stop <fragment>: also check that the tool surface tracks a game going away. Destructive to
# whatever it matches, so it is opt-in and the fragment is never guessed.
STOP_FRAGMENT = None
if "--stop" in sys.argv:
    _index = sys.argv.index("--stop")
    if _index + 1 < len(sys.argv):
        STOP_FRAGMENT = sys.argv[_index + 1]


class Orchestrator:
    def __init__(self):
        env = dict(os.environ)
        env["MCMCP_LOG"] = "warn"
        env["MCMCP_ORCHESTRATOR_HOME"] = STATE
        self.p = subprocess.Popen(
            [BINARY, "serve"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env=env, bufsize=0,
        )
        self.n = 1
        self.answers = {}
        self.notifications = []
        self.lock = threading.Lock()
        # A reader thread rather than reading inline: notifications arrive whenever they arrive, and
        # one of the checks below is specifically that a notification was sent. Reading only while
        # waiting for an answer would miss the ones that arrive in between.
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        while True:
            line = self.p.stdout.readline()
            if not line:
                return
            try:
                frame = json.loads(line.decode().strip())
            except Exception:
                continue
            with self.lock:
                if frame.get("id") is not None:
                    self.answers[frame["id"]] = frame
                elif frame.get("method", "").startswith("notifications/"):
                    self.notifications.append((time.time(), frame["method"]))

    def request(self, method, params=None, timeout=120):
        rid = self.n
        self.n += 1
        msg = {"jsonrpc": "2.0", "id": rid, "method": method}
        if params is not None:
            msg["params"] = params
        self.p.stdin.write((json.dumps(msg) + "\n").encode())
        self.p.stdin.flush()
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self.lock:
                if rid in self.answers:
                    frame = self.answers.pop(rid)
                    if "error" in frame:
                        raise RuntimeError(f"{method}: {frame['error']}")
                    return frame.get("result", {})
            time.sleep(0.05)
        raise RuntimeError(f"no answer to {method}")

    def saw(self, method, since, seconds):
        """Whether `method` arrived after `since`, waiting up to `seconds` for it."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            with self.lock:
                if any(t >= since and m == method for (t, m) in self.notifications):
                    return True
            time.sleep(0.2)
        return False

    def notify(self, method):
        self.p.stdin.write((json.dumps({"jsonrpc": "2.0", "method": method}) + "\n").encode())
        self.p.stdin.flush()

    def call(self, tool, args=None):
        return self.request("tools/call", {"name": tool, "arguments": args or {}})

    def stop(self):
        try:
            self.p.stdin.close()
            self.p.wait(timeout=5)
        except Exception:
            self.p.kill()


def stop_java_matching(fragment):
    """Kills the JVM whose command line contains `fragment`.

    PowerShell rather than wmic, which Windows 11 has removed. The fragment is required rather than
    inferred: a developer's machine routinely has several unrelated Minecraft processes on it, and
    something like "java" or even "server" would find them all.
    """
    if os.name != "nt":
        out = subprocess.run(["pgrep", "-f", fragment], capture_output=True, text=True).stdout
        pids = [line.strip() for line in out.split() if line.strip().isdigit()]
        for pid in pids:
            subprocess.run(["kill", "-9", pid], capture_output=True)
        return pids

    script = (
        "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
        f"Where-Object {{ $_.CommandLine -like '*{fragment}*' }} | "
        "Select-Object -ExpandProperty ProcessId"
    )
    out = subprocess.run(
        ["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True
    ).stdout
    pids = [p.strip() for p in out.split() if p.strip().isdigit()]
    for pid in pids:
        subprocess.run(["taskkill", "/PID", pid, "/F"], capture_output=True)
    return pids


def main():
    o = Orchestrator()
    checks = []
    try:
        o.request("initialize", {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "two-instance-test", "version": "1.0"},
        })
        o.notify("notifications/initialized")

        # Both games have to reconnect after this orchestrator replaced the previous one, which
        # takes up to a backoff.
        deadline = time.time() + 150
        connected = []
        while time.time() < deadline:
            got = (o.call("mcmcp_instances").get("structuredContent") or {}).get("connected") or []
            if len(got) >= 2:
                connected = got
                break
            time.sleep(3)
        if len(connected) < 2:
            raise RuntimeError(f"only {len(connected)} instance(s) connected: {connected}")

        ids = sorted(i["instance"] for i in connected)
        sides = {i["instance"]: i["side"] for i in connected}
        checks.append(f"two instances connected: {ids[0]} ({sides[ids[0]]}), {ids[1]} ({sides[ids[1]]})")

        client_id = next(i for i in ids if sides[i] == "client")
        server_id = next(i for i in ids if sides[i] == "server")

        # ---- the union, and what it saves ----
        tools = o.request("tools/list")["tools"]
        names = {t["name"] for t in tools}
        per_instance = {i["instance"]: i["tools"] for i in connected}
        naive = sum(per_instance.values())
        # The orchestrator's own tools are not from either game, so they are not part of the
        # saving. Asserted rather than subtracted blindly: this list went stale once already when
        # two tools were added, and the symptom was a tool count that looked like a real anomaly in
        # the aggregation. Missing one now fails here instead of quietly inflating the number.
        orchestrator_own = {
            "mcmcp_instances",
            "mcmcp_focus",
            "mcmcp_set_label",
            "mcmcp_compare_instances",
            "mcmcp_read_logs",
        }
        missing = orchestrator_own - names
        if missing:
            raise RuntimeError(f"the orchestrator's own tools are missing: {sorted(missing)}")
        game_tools = len(names - orchestrator_own)
        checks.append(
            f"{game_tools} game tools aggregated from {naive} across both "
            f"({per_instance[client_id]} + {per_instance[server_id]}) — "
            f"{naive - game_tools} duplicates collapsed"
        )

        # A client-only tool must be listed and must say where it works.
        screenshot = next(t for t in tools if t["name"] == "client_screenshot")
        if "Available on" not in (screenshot.get("description") or ""):
            raise RuntimeError("a tool only one instance has did not say where it is available")
        checks.append("a client-only tool is listed with an availability note")

        # ---- what an unaimed call does with two games open ----
        #
        # It follows focus rather than refusing, and that is deliberate: the first instance to
        # connect takes focus, so a session already working happily in one game does not start
        # erroring the moment a second game is launched. The safety net is not a refusal, it is that
        # the answer says where it came from — checked below.
        focused_now = (o.call("mcmcp_focus").get("structuredContent") or {}).get("focused")
        unaimed = o.call("mcmcp_endpoint_info")
        if unaimed.get("isError"):
            raise RuntimeError(f"an unaimed call should follow focus: {unaimed}")
        landed = (unaimed.get("structuredContent") or {}).get("instance")
        if landed != focused_now:
            raise RuntimeError(f"focus was {focused_now} but the call landed on {landed}")
        if landed not in unaimed["content"][0]["text"]:
            raise RuntimeError("an unaimed call did not say which game answered it")
        checks.append(f"an unaimed call followed focus to {landed}, and said so")

        # ---- explicit addressing ----
        for target in (client_id, server_id):
            result = o.call("mcmcp_endpoint_info", {"instance": target})
            if result.get("isError"):
                raise RuntimeError(f"addressing {target} failed: {result}")
            reported = (result.get("structuredContent") or {}).get("instance")
            if reported != target:
                raise RuntimeError(f"asked {target}, got {reported}")
            if target not in result["content"][0]["text"]:
                raise RuntimeError("the result text did not name its instance")
        checks.append("each instance answered for itself, stamped correctly")

        # ---- side filtering survives the union ----
        wrong_side = o.call("client_screenshot", {"instance": server_id})
        if not wrong_side.get("isError"):
            raise RuntimeError("a client-only tool should not run on the server instance")
        if "available on" not in wrong_side["content"][0]["text"].lower():
            raise RuntimeError(f"the refusal did not say where it works: {wrong_side}")
        checks.append("a client-only tool aimed at the server is refused, and says where it works")

        # ---- moving focus moves where unaimed calls land ----
        other = server_id if focused_now == client_id else client_id
        o.call("mcmcp_focus", {"target": other})
        moved = o.call("mcmcp_endpoint_info")
        if (moved.get("structuredContent") or {}).get("instance") != other:
            raise RuntimeError("focus did not take effect")
        if other not in moved["content"][0]["text"]:
            raise RuntimeError("the result did not name the newly focused instance")
        checks.append(f"focus moved to {other}; the next unaimed call followed and said so")

        # ---- fan-out ----
        fanned = o.call("mcmcp_endpoint_info", {"instance": "*"})
        by_instance = (fanned.get("structuredContent") or {}).get("byInstance") or {}
        if set(by_instance) != set(ids):
            raise RuntimeError(
                f"fan-out did not reach both: {list(by_instance)}; raw="
                + json.dumps(fanned)[:900]
            )
        checks.append(f"instance:'*' answered from both in one call")

        # Destructive fan-out must be refused even though the schema never offers it.
        refused = o.call("server_set_block", {"instance": "*", "x": 0, "y": 64, "z": 0,
                                              "block": "minecraft:stone"})
        if not refused.get("isError"):
            raise RuntimeError("a destructive fan-out should have been refused")
        checks.append("a destructive tool aimed at '*' is refused")

        # ---- what is different between them ----
        #
        # The answer is only interesting when the two games genuinely differ; against two instances
        # built from the same source this is correctly empty and says so.
        compared = o.call("mcmcp_compare_instances")
        if compared.get("isError"):
            raise RuntimeError(f"compare failed: {compared}")
        summaries = (compared.get("structuredContent") or {}).get("instances") or []
        if len(summaries) < 2:
            raise RuntimeError("the comparison did not cover both instances")
        if not all(entry["modsReadable"] for entry in summaries):
            raise RuntimeError("a mod list could not be read")
        distinctive = {e["instance"]: e["modsOnlyHere"] for e in summaries if e["modsOnlyHere"]}
        if distinctive:
            for instance, mods in sorted(distinctive.items()):
                checks.append(f"{instance} is identifiable by: {', '.join(sorted(mods)[:6])}")
        else:
            checks.append("neither instance has a mod the other lacks, and the tool says so")

        # ---- logs, merged in time order ----
        logs = o.call("mcmcp_read_logs", {"lines": 25})
        merged = logs["content"][0]["text"]
        sources = {line.split("|")[0].strip() for line in merged.splitlines() if "|" in line}
        if len(sources) < 2:
            raise RuntimeError(f"merged logs came from only {sources}")
        total = (logs.get("structuredContent") or {}).get("lines", 0)
        narrowed = (o.call("mcmcp_read_logs", {"lines": 25, "filter": "MCMCP"})
                    .get("structuredContent") or {}).get("lines", 0)
        if narrowed > total:
            raise RuntimeError("filtering returned more lines than not filtering")
        checks.append(f"{total} log lines merged from {len(sources)} games; a filter narrowed it to {narrowed}")

        # ---- the orchestrator's own prompt ----
        prompts = [p["name"] for p in o.request("prompts/list").get("prompts", [])]
        if "compare_instances" not in prompts:
            raise RuntimeError(f"the orchestrator's prompt is missing: {prompts}")
        expanded = o.request("prompts/get", {
            "name": "compare_instances",
            "arguments": {"action": "place a torch", "first": ids[0], "second": ids[1]},
        })["messages"][0]["content"]["text"]
        for expected in ("place a torch", ids[0], ids[1], "naming each instance explicitly"):
            if expected not in expanded:
                raise RuntimeError(f"the prompt did not mention {expected!r}")
        checks.append("the compare prompt expanded with the live roster and both named instances")

        # ---- optional: does the surface track a game going away, and say so ----
        if STOP_FRAGMENT:
            before = {t["name"] for t in o.request("tools/list")["tools"]}
            mark = time.time()
            if not stop_java_matching(STOP_FRAGMENT):
                raise RuntimeError(f"nothing matched --stop {STOP_FRAGMENT!r}")

            if not o.saw("notifications/tools/list_changed", mark, 30):
                raise RuntimeError("no tools/list_changed after a game disconnected")

            # Announcing a change is not the same as making one.
            deadline, after = time.time() + 30, before
            while time.time() < deadline:
                after = {t["name"] for t in o.request("tools/list")["tools"]}
                if after != before:
                    break
                time.sleep(1)
            if after == before:
                raise RuntimeError("the tool list did not change after a game disconnected")
            if "mcmcp_instances" not in after:
                raise RuntimeError("the orchestrator's own tools should survive")
            checks.append(
                f"a game left: list_changed fired and {len(before)} tools became {len(after)}"
            )

    except Exception as error:
        print(f"==> FAIL: {error}")
        for line in checks:
            print(f"    {line}")
        try:
            print("----- stderr -----")
            print(o.p.stderr.read1(20000).decode("utf-8", "replace")[-2000:])
        except Exception:
            pass
        o.stop()
        return 1

    for line in checks:
        print(f"    {line}")
    o.stop()
    print("==> PASS: two games driven through one MCP endpoint")
    return 0


if __name__ == "__main__":
    sys.exit(main())
