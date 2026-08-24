#!/usr/bin/env python3
"""
A stub MCMCP orchestrator, for the server smoke test.

Listens on loopback, accepts one instance link, drives a full MCP handshake over it, and writes a
verdict to a result file. It is deliberately not a real orchestrator: it implements exactly enough
of the link protocol to prove that the mod's half works against something that is not itself.

What this catches that a unit test cannot:

  * That the link actually dials out, at the right moment in Forge's lifecycle, and reaches a peer.
    LinkFraming and LinkHandshake are unit-tested against byte arrays; none of that proves a socket
    is ever opened.
  * That MCP traffic over the link reaches the same dispatcher HTTP does, and comes back correctly
    framed. The transport is new; the dispatcher is not, and a wiring mistake between them would
    pass every existing test.
  * That the instance identity the mod reports over the link matches the one its own tool reports.
    Two sources for one fact is exactly how they drift.

Usage:
    stub-orchestrator.py <port> <result-file> [accept-timeout-seconds]

The result file gets "PASS" or "FAIL: <reason>" as its first line, followed by detail. The caller
polls for the file rather than waiting on the process, because the interesting failure is the one
where nothing ever connects.
"""

import json
import socket
import sys
import traceback

LINK_PROTOCOL_VERSION = 1

# Every read is bounded. A hung read here would hang CI, and "nothing connected" is a result worth
# reporting rather than a reason to sit forever.
READ_TIMEOUT_SECONDS = 60.0


class LinkError(Exception):
    pass


class Link:
    """Newline-delimited JSON over one accepted socket."""

    def __init__(self, conn):
        self.conn = conn
        self.buffer = b""
        self.next_id = 1

    def read_frame(self):
        while b"\n" not in self.buffer:
            chunk = self.conn.recv(65536)
            if not chunk:
                raise LinkError("the instance closed the link")
            self.buffer += chunk
        line, self.buffer = self.buffer.split(b"\n", 1)
        line = line.rstrip(b"\r")
        if not line.strip():
            return self.read_frame()
        try:
            return json.loads(line.decode("utf-8"))
        except ValueError as exc:
            raise LinkError("frame is not valid JSON: %s (%r)" % (exc, line[:200]))

    def write_frame(self, frame):
        self.conn.sendall((json.dumps(frame) + "\n").encode("utf-8"))

    def request(self, method, params=None):
        """Sends a request and returns the response with the matching id.

        Frames that are not the response — notifications the endpoint pushes on its own, such as a
        catalogue change — are skipped rather than mistaken for one. Assuming the next frame is the
        answer is the classic way to write a JSON-RPC client that passes until the server gets
        chattier.
        """
        request_id = self.next_id
        self.next_id += 1
        message = {"jsonrpc": "2.0", "id": request_id, "method": method}
        if params is not None:
            message["params"] = params
        self.write_frame(message)

        for _ in range(50):
            frame = self.read_frame()
            if frame.get("id") == request_id:
                if "error" in frame:
                    raise LinkError("%s returned an error: %s" % (method, frame["error"]))
                return frame.get("result", {})
        raise LinkError("no response to %s after 50 frames" % method)

    def notify(self, method, params=None):
        message = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            message["params"] = params
        self.write_frame(message)


def check_hello(hello):
    """Validates the opening frame and returns the instance id it claims."""
    if hello.get("type") != "hello":
        raise LinkError("first frame was not a hello: %r" % hello)
    if hello.get("linkProtocol") != LINK_PROTOCOL_VERSION:
        raise LinkError("unexpected link protocol %r" % hello.get("linkProtocol"))

    instance_id = hello.get("instanceId") or ""
    if not instance_id:
        raise LinkError("hello carried no instanceId")

    # 256 bits, hex. The secret is what makes trust-on-first-use mean anything, so a short or
    # missing one is a real failure and not a cosmetic one.
    secret = hello.get("instanceSecret") or ""
    if len(secret) != 64 or any(c not in "0123456789abcdef" for c in secret):
        raise LinkError("hello carried a malformed instanceSecret (%d characters)" % len(secret))

    if hello.get("side") != "server":
        raise LinkError("expected the server endpoint to link, got side=%r" % hello.get("side"))

    # These are what an approval dialog shows a human. Their absence is not fatal to the protocol
    # and is fatal to the feature.
    for field in ("instanceName", "modVersion", "minecraftVersion", "gameDirectory"):
        if not hello.get(field):
            raise LinkError("hello is missing %s, which an approval prompt needs" % field)

    return instance_id


def run(port, accept_timeout):
    detail = []

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", port))
    listener.listen(1)
    listener.settimeout(accept_timeout)
    detail.append("listening on 127.0.0.1:%d" % port)

    try:
        conn, _ = listener.accept()
    except socket.timeout:
        raise LinkError("no instance connected within %ss" % accept_timeout)
    finally:
        listener.close()

    conn.settimeout(READ_TIMEOUT_SECONDS)
    link = Link(conn)
    try:
        hello = link.read_frame()
        instance_id = check_hello(hello)
        detail.append("hello from %s (%r) at %s"
                      % (instance_id, hello.get("instanceName"), hello.get("gameDirectory")))

        # An assigned name on the welcome is how a label typed into a roster reaches the instance.
        link.write_frame({
            "type": "welcome",
            "linkProtocol": LINK_PROTOCOL_VERSION,
            "orchestrator": {"name": "smoke-test-stub", "version": "0.0.1"},
            "instanceName": "smoke-test-label",
        })

        initialize = link.request("initialize", {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "mcmcp-smoke-stub", "version": "1.0"},
        })
        if initialize.get("protocolVersion") != "2025-06-18":
            raise LinkError("initialize over the link did not negotiate 2025-06-18: %r" % initialize)
        if "serverInfo" not in initialize:
            raise LinkError("initialize over the link returned no serverInfo: %r" % initialize)
        detail.append("initialize negotiated %s" % initialize.get("protocolVersion"))

        link.notify("notifications/initialized")

        tools = link.request("tools/list").get("tools", [])
        names = {tool.get("name") for tool in tools}
        if "mcmcp_endpoint_info" not in names:
            raise LinkError("tools/list over the link is missing mcmcp_endpoint_info")
        if "server_get_blocks" not in names:
            raise LinkError("tools/list over the link is missing server_get_blocks")
        # The side filter has to hold over the link too, not just over HTTP.
        if "client_screenshot" in names:
            raise LinkError("tools/list over the link exposed a client-only tool on the server side")
        detail.append("tools/list returned %d tools" % len(names))

        called = link.request("tools/call", {"name": "mcmcp_endpoint_info", "arguments": {}})
        if called.get("isError"):
            raise LinkError("mcmcp_endpoint_info over the link reported an error: %r" % called)

        structured = called.get("structuredContent") or {}
        reported = (structured.get("instance") or {}).get("id")
        if reported != instance_id:
            # Two sources for one fact, disagreeing. An orchestrator keys its approvals off the
            # handshake id and a model reads the tool's, so a mismatch means the roster and the
            # conversation are talking about different games.
            raise LinkError("the handshake said instance %r but mcmcp_endpoint_info says %r"
                            % (instance_id, reported))
        if structured.get("side") != "server":
            raise LinkError("mcmcp_endpoint_info over the link did not report the server side: %r"
                            % structured)
        detail.append("tools/call agreed on instance id %s" % reported)

        return detail
    finally:
        try:
            conn.close()
        except OSError:
            pass


def main():
    if len(sys.argv) < 3:
        sys.stderr.write("usage: stub-orchestrator.py <port> <result-file> [accept-timeout]\n")
        return 2

    port = int(sys.argv[1])
    result_path = sys.argv[2]
    accept_timeout = float(sys.argv[3]) if len(sys.argv) > 3 else 600.0

    try:
        detail = run(port, accept_timeout)
        verdict = "PASS"
    except Exception as exc:  # noqa: BLE001 - the verdict file is the only reporting channel
        detail = [traceback.format_exc()]
        verdict = "FAIL: %s" % exc

    with open(result_path, "w", encoding="utf-8") as handle:
        handle.write(verdict + "\n")
        for line in detail:
            handle.write(str(line) + "\n")

    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
