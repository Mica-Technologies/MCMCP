#!/usr/bin/env python3
"""
Drives the orchestrator over stdio, with a real Minecraft instance linked to it.

This is the test that proves the whole idea works. Everything else checks a piece:

  * the mod's unit tests prove framing and handshake against byte arrays
  * the orchestrator's unit tests prove aggregation and routing against fixtures
  * server-smoke-test.sh proves the mod dials out and a stub can answer

None of those prove that a real game's catalogue, arriving over a real socket, comes back out of a
real MCP endpoint as one aggregated tool surface that a client can call through. That is what this
does, and it is the only place the two implementations of the link protocol meet outside a comment
promising they agree.

Usage:
    orchestrator-e2e.py <orchestrator-binary> <state-dir> [link-timeout-seconds]

Exits 0 on success, 1 on failure, printing what it checked either way.
"""

import json
import os
import subprocess
import sys
import time

# The orchestrator's own tools, which exist whether or not a game is connected.
ORCHESTRATOR_TOOLS = {"mcmcp_instances", "mcmcp_focus", "mcmcp_set_label"}


class Failure(Exception):
    pass


class Orchestrator:
    """An orchestrator process, spoken to over stdio the way an MCP client does."""

    def __init__(self, binary, state_dir):
        environment = dict(os.environ)
        environment["MCMCP_LOG"] = "info"
        self.process = subprocess.Popen(
            [binary, "--state-dir", state_dir, "serve"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=environment,
            bufsize=0,
        )
        self.next_id = 1

    def send(self, message):
        line = (json.dumps(message) + "\n").encode("utf-8")
        self.process.stdin.write(line)
        self.process.stdin.flush()

    def read_message(self, timeout=180.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            line = self.process.stdout.readline()
            if not line:
                raise Failure("the orchestrator closed stdout")
            text = line.decode("utf-8").strip()
            if not text:
                continue
            return json.loads(text)
        raise Failure("timed out waiting for a message")

    def request(self, method, params=None, timeout=180.0):
        """Sends a request and returns its result, skipping notifications along the way.

        Skipping matters: the orchestrator pushes list_changed and log notifications whenever an
        instance connects, and treating the next frame as the answer would read one of those instead.
        """
        request_id = self.next_id
        self.next_id += 1
        message = {"jsonrpc": "2.0", "id": request_id, "method": method}
        if params is not None:
            message["params"] = params
        self.send(message)

        deadline = time.time() + timeout
        while time.time() < deadline:
            frame = self.read_message(timeout=max(1.0, deadline - time.time()))
            if frame.get("id") != request_id:
                continue
            if "error" in frame:
                raise Failure(f"{method} returned an error: {frame['error']}")
            return frame.get("result", {})
        raise Failure(f"no response to {method}")

    def notify(self, method, params=None):
        message = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            message["params"] = params
        self.send(message)

    def stop(self):
        try:
            self.process.stdin.close()
        except Exception:
            pass
        try:
            self.process.wait(timeout=10)
        except Exception:
            self.process.kill()

    def stderr_tail(self, lines=25):
        try:
            self.process.stderr.flush()
        except Exception:
            pass
        try:
            data = self.process.stderr.read1(65536).decode("utf-8", "replace")
        except Exception:
            return "(no stderr captured)"
        return "\n".join(data.splitlines()[-lines:])


def wait_for_an_instance(orchestrator, timeout):
    """Polls mcmcp_instances until a game shows up.

    Polling rather than assuming: the mod retries its link on a backoff that grows to thirty
    seconds, so a game started before the orchestrator can take that long to appear. That is correct
    behaviour, and a test that did not allow for it would fail on timing rather than on substance.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = orchestrator.request("tools/call", {"name": "mcmcp_instances", "arguments": {}})
        structured = result.get("structuredContent") or {}
        connected = structured.get("connected") or []
        if connected:
            return connected
        time.sleep(3)
    raise Failure(f"no instance linked within {timeout}s")


def check(orchestrator, link_timeout):
    checked = []

    initialize = orchestrator.request(
        "initialize",
        {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "mcmcp-e2e", "version": "1.0"},
        },
    )
    if initialize.get("protocolVersion") != "2025-06-18":
        raise Failure(f"initialize did not negotiate 2025-06-18: {initialize}")
    if initialize.get("serverInfo", {}).get("name") != "mcmcp-orchestrator":
        raise Failure(f"initialize did not identify the orchestrator: {initialize}")
    checked.append("initialize negotiated 2025-06-18")

    orchestrator.notify("notifications/initialized")

    connected = wait_for_an_instance(orchestrator, link_timeout)
    instance_id = connected[0]["instance"]
    checked.append(f"instance {instance_id} linked ({connected[0].get('side')})")

    # ---- the aggregated tool surface ----
    tools = orchestrator.request("tools/list").get("tools", [])
    names = {tool.get("name") for tool in tools}
    if not ORCHESTRATOR_TOOLS.issubset(names):
        raise Failure(f"the orchestrator's own tools are missing: {sorted(ORCHESTRATOR_TOOLS - names)}")
    if "mcmcp_endpoint_info" not in names:
        raise Failure("the game's tools did not reach the aggregated catalogue")

    # The instance argument is the whole addressing model. A tool without it cannot be aimed.
    game_tools = [tool for tool in tools if tool.get("name") not in ORCHESTRATOR_TOOLS]
    for tool in game_tools:
        properties = (tool.get("inputSchema") or {}).get("properties") or {}
        if "instance" not in properties:
            raise Failure(f"tool {tool['name']} has no instance argument")
        if "enum" not in properties["instance"]:
            raise Failure(f"tool {tool['name']} has no instance enum to choose from")
    checked.append(f"{len(tools)} tools aggregated, every game tool addressable")

    # ---- a real call, routed ----
    result = orchestrator.request(
        "tools/call", {"name": "mcmcp_endpoint_info", "arguments": {"instance": instance_id}}
    )
    if result.get("isError"):
        raise Failure(f"mcmcp_endpoint_info failed: {result}")

    # Every result must say which instance produced it. This is what protects a model from a human
    # moving focus underneath it, so it is not an optional nicety.
    meta = (result.get("_meta") or {}).get("mcmcp/instance") or {}
    if meta.get("id") != instance_id:
        raise Failure(f"the result did not carry its instance in _meta: {result.get('_meta')}")
    banner = (result.get("content") or [{}])[0].get("text", "")
    if instance_id not in banner:
        raise Failure(f"the result text did not name its instance: {banner!r}")

    # And the id the tool itself reports must agree with the one the handshake established.
    reported = ((result.get("structuredContent") or {}).get("instance"))
    if reported != instance_id:
        raise Failure(f"the tool reported instance {reported!r}, the route used {instance_id!r}")
    checked.append("a routed call came back stamped with its instance, three ways")

    # ---- addressing failures are tool errors, not protocol errors ----
    missing = orchestrator.request(
        "tools/call", {"name": "mcmcp_endpoint_info", "arguments": {"instance": "no-such-instance"}}
    )
    if not missing.get("isError"):
        raise Failure("naming an unknown instance should have been a tool error")
    text = (missing.get("content") or [{}])[0].get("text", "")
    if "no-such-instance" not in text:
        raise Failure(f"the error did not name what was asked for: {text!r}")
    checked.append("an unknown instance came back as a readable tool error")

    # ---- focus ----
    focus = orchestrator.request("tools/call", {"name": "mcmcp_focus", "arguments": {}})
    if (focus.get("structuredContent") or {}).get("focused") != instance_id:
        raise Failure(f"the only connected instance should be focused: {focus}")

    unaimed = orchestrator.request("tools/call", {"name": "mcmcp_endpoint_info", "arguments": {}})
    if unaimed.get("isError"):
        raise Failure(f"a call with no instance should follow focus: {unaimed}")
    checked.append("focus resolved an unaimed call")

    # ---- resources are namespaced per instance ----
    resources = orchestrator.request("resources/list").get("resources", [])
    if resources:
        uri = resources[0]["uri"]
        if f"//{instance_id}/" not in uri:
            raise Failure(f"resource URI {uri} does not name its instance")
        read = orchestrator.request("resources/read", {"uri": uri})
        if not read.get("contents"):
            raise Failure(f"reading {uri} returned nothing")
        if read["contents"][0].get("uri") != uri:
            raise Failure("a resource read came back with a URI the client did not ask for")
        checked.append(f"{len(resources)} resources namespaced, and one read back")

    return checked


def main():
    if len(sys.argv) < 3:
        sys.stderr.write("usage: orchestrator-e2e.py <orchestrator-binary> <state-dir> [timeout]\n")
        return 2

    binary = sys.argv[1]
    state_dir = sys.argv[2]
    link_timeout = float(sys.argv[3]) if len(sys.argv) > 3 else 120.0

    orchestrator = Orchestrator(binary, state_dir)
    try:
        checked = check(orchestrator, link_timeout)
    except Failure as failure:
        print(f"==> FAIL: {failure}")
        print("----- orchestrator stderr -----")
        print(orchestrator.stderr_tail())
        orchestrator.stop()
        return 1
    except Exception as error:  # noqa: BLE001
        print(f"==> FAIL: unexpected error: {error}")
        print("----- orchestrator stderr -----")
        print(orchestrator.stderr_tail())
        orchestrator.stop()
        return 1

    for line in checked:
        print(f"    {line}")
    orchestrator.stop()
    print("==> PASS: a real game was driven through the orchestrator's MCP endpoint")
    return 0


if __name__ == "__main__":
    sys.exit(main())
