"""Drives a dev server's MCMCP companion through a dev client and a private orchestrator.

The companion only exists when a player's client is connected to a server that runs MCMCP, so no
headless check can reach it: this is the local proof that the whole path works, from a model's call
through the orchestrator, the client's virtual `.server` endpoint and the game connection, to the
server's tools and back.

**Local only, not in CI.** It needs a dev client, which needs a display. Run it by hand after anything
that touches the companion, its permissions, or the server tools it serves:

    # the server: companion on (the default on a dedicated server), the dev player an operator and on
    # the allowlist, its MCP endpoint on (dev default) so this script can tidy up after itself
    export JAVA_HOME=...; ./gradlew runServer
    #   in its config/mcmcp.cfg:  companion { S:allowedPlayers < McmcpDev > }

    # the client, joining it; its orchestrator link pointed at the port given below
    DEV_USERNAME=McmcpDev ./gradlew runClient -PmcJoin=127.0.0.1:25565

    python .github/scripts/companion-check.py <state-dir> [--link-port 26580]

It starts its own orchestrator on --link-port (default 26580, so an orchestrator app you use every day
on 25580 is never involved). Point the dev client's orchestrator.orchestratorPort at it. It leaves the
world with a few blocks changed near spawn + 2000 and every hold released.
"""

import argparse
import json
import os
import queue
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

failures = []


def check(label, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + label + ("" if ok else "\n     " + str(detail)[:600]))
    if not ok:
        failures.append(label)


class Orchestrator:
    def __init__(self, state_dir, link_port):
        exe = os.path.join(ROOT, "orchestrator", "target", "debug",
                           "mcmcp-orchestrator.exe" if os.name == "nt" else "mcmcp-orchestrator")
        os.makedirs(state_dir, exist_ok=True)
        self.proc = subprocess.Popen([exe, "serve", "--link-port", str(link_port), "--state-dir", state_dir],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=open(os.path.join(state_dir, "stderr.log"), "w"),
                                     text=True, encoding="utf-8")
        self.messages = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()
        self.next_id = 0
        self.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                "clientInfo": {"name": "companion-check", "version": "1"}})
        self.send({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def _read(self):
        for line in self.proc.stdout:
            self.messages.put(json.loads(line))

    def send(self, message):
        self.proc.stdin.write(json.dumps(message) + "\n")
        self.proc.stdin.flush()

    def rpc(self, method, params=None, timeout=180):
        self.next_id += 1
        message = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        if params is not None:
            message["params"] = params
        self.send(message)
        deadline = time.time() + timeout
        while time.time() < deadline:
            reply = self.messages.get(timeout=max(0.1, deadline - time.time()))
            if reply.get("id") == self.next_id:
                return reply
        raise TimeoutError(method)

    def call(self, name, arguments=None):
        reply = self.rpc("tools/call", {"name": name, "arguments": arguments or {}})
        result = reply.get("result", reply)
        text = "".join(block.get("text", "") for block in result.get("content", []))
        return result.get("structuredContent") or {}, text, bool(result.get("isError"))

    def stop(self):
        try:
            self.proc.stdin.close()
        finally:
            self.proc.terminate()


def find_companion(orch, wait_seconds=90):
    deadline = time.time() + wait_seconds
    while time.time() < deadline:
        state, _, _ = orch.call("mcmcp_instances")
        for entry in state.get("connected", []):
            if entry.get("via") == "companion":
                return entry, state
        time.sleep(1)
    return None, state


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("state_dir")
    parser.add_argument("--link-port", type=int, default=26580)
    args = parser.parse_args()

    orch = Orchestrator(args.state_dir, args.link_port)
    try:
        companion, roster = find_companion(orch)
        check("the server's tools appear as the game's .server, via the companion", companion is not None, roster)
        if companion is None:
            return 1
        server = companion["instance"]
        game = companion["game"]
        client = game + ".client"

        tools = [tool["name"] for tool in orch.rpc("tools/list")["result"]["tools"]]
        for absent in ("server_stop", "server_save_world", "server_broadcast"):
            check(absent + " is never offered through the companion", absent not in tools)
        for present in ("server_get_blocks", "server_set_blocks", "server_run_commands", "server_keep_loaded",
                        "server_changes_since", "server_get_tile_entities", "server_use_item_on_block"):
            check(present + " is offered", present in tools)

        info, _, _ = orch.call("mcmcp_endpoint_info", {"instance": client})
        check("the client says the companion is available", info.get("companion", {}).get("state") == "available", info)

        world, text, error = orch.call("server_world_info", {"instance": server})
        spawn = (world.get("dimensions") or [{}])[0].get("spawnPoint", {})
        x, z = spawn.get("x", 0) + 2000, spawn.get("z", 0) + 2000

        hold, text, error = orch.call("server_keep_loaded", {"instance": server, "x": x, "z": z, "toX": x + 15, "toZ": z + 15})
        held = not error and "id" in hold
        check("a hold through the companion", held or "ungenerated" in text, text)

        # Start from air, whatever an earlier run left there.
        orch.call("server_set_blocks", {"instance": server, "mode": "palette", "palette": ["minecraft:air"], "load": True,
                                        "origin": {"x": x, "y": 120, "z": z}, "cells": [0, 0, 0, 0, 1, 0, 0, 0]})
        token, _, _ = orch.call("server_changes_since", {"instance": server, "x": x, "z": z, "toX": x + 15, "toZ": z + 15})
        wrote, text, error = orch.call("server_set_blocks", {
            "instance": server, "mode": "palette", "palette": ["minecraft:glass"], "load": True,
            "origin": {"x": x, "y": 120, "z": z}, "cells": [0, 0, 0, 0, 1, 0, 0, 0], "expect": "minecraft:air"})
        check("a compare-and-set write through the companion, with an undo point naming who",
              not error and wrote.get("applied", 0) + wrote.get("unchanged", 0) + wrote.get("conflicts", 0) == 2
              and wrote.get("undoPoint") is not None, text)
        since, _, _ = orch.call("server_changes_since", {"instance": server, "x": x, "z": z, "toX": x + 15,
                                                        "toZ": z + 15, "since": token.get("token", "0")})
        check("the companion's own write is not reported as someone else's", since.get("count") == 0, since)

        ran, text, error = orch.call("server_run_commands", {"instance": server,
                                                            "commands": ["say companion check", "setblock 0 300 0 stone"]})
        check("commands through the companion run as the caller, failures listed",
              not error and ran.get("ranAs") not in (None, "server console") and ran.get("failed") == 1, text)

        console, text, error = orch.call("server_run_command", {"instance": server, "command": "say hi",
                                                               "asPlayer": "SomeoneElse"})
        check("naming another player is refused without mcmcp.companion.others, or allowed with it",
              error or "may only name you" not in text, text)

        if held:
            orch.call("server_release_loaded", {"instance": server, "id": hold["id"]})
            listed, _, _ = orch.call("server_keep_loaded", {"instance": server, "op": "list"})
            check("released: the caller holds nothing", all(h.get("id") != hold["id"] for h in listed.get("holds", [])), listed)

        listed = orch.rpc("tools/list")["result"]["tools"]
        size = len(json.dumps({"tools": listed}, separators=(",", ":")))
        new = [t for t in listed if t["name"] in ("server_keep_loaded", "server_release_loaded", "server_changes_since",
                                                  "server_get_tile_entities", "server_run_commands",
                                                  "server_use_item_on_block", "server_activate_block")]
        print("     tools/list through the orchestrator: %d bytes, of which the companion-era tools are %d"
              % (size, len(json.dumps(new, separators=(",", ":")))))
    finally:
        orch.stop()
    print()
    print("%d check(s) failed" % len(failures) if failures else "All checks passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
