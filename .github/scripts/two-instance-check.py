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
"""

import json
import os
import subprocess
import sys
import time

BINARY = os.environ.get(
    "ORCH_BINARY",
    "orchestrator/target/debug/mcmcp-orchestrator.exe"
    if os.name == "nt"
    else "orchestrator/target/debug/mcmcp-orchestrator",
)
STATE = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.getcwd(), "run", "orchestrator-test")


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
            line = self.p.stdout.readline()
            if not line:
                raise RuntimeError("orchestrator closed stdout")
            frame = json.loads(line.decode().strip())
            if frame.get("id") == rid:
                if "error" in frame:
                    raise RuntimeError(f"{method}: {frame['error']}")
                return frame.get("result", {})
        raise RuntimeError(f"no answer to {method}")

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
        # The orchestrator's own three are not from either game, so they are not part of the saving.
        game_tools = len(names - {"mcmcp_instances", "mcmcp_focus", "mcmcp_set_label"})
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
