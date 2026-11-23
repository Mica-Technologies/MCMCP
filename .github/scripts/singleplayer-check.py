"""Checks that a singleplayer world's server tools are served over the orchestrator link (#215).

With endpoints.enableServerEndpoint off, which is the default in every game but MCMCP's own dev runs,
a singleplayer world used to expose the client endpoint alone. Since #215 the integrated server's tools
are served as this game's `.server` endpoint over the orchestrator link (`via: singleplayer`), with no
port of their own. This is the local proof: a world opened through the client makes the endpoint
appear, a server tool answers through it, and leaving the world takes it away again.

**Local only, not in CI.** It needs a dev client, which needs a display.

    # run/client/config/mcmcp.cfg:  B:enableServerEndpoint=false, I:orchestratorPort=26580
    # (and ports of its own if another game is running: I:clientPort=26000, I:serverPort=26001)
    export JAVA_HOME=...; DEV_USERNAME=McmcpDev ./gradlew runClient

    python .github/scripts/singleplayer-check.py <state-dir> [--link-port 26580]

It starts its own orchestrator on --link-port, creates a flat creative world called "mcmcp-sp-check"
and leaves it; the save stays in run/client/saves for you to delete.
"""

import argparse
import importlib.util
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))

# The orchestrator driver is the companion check's: one stdio MCP client, kept in one place.
_spec = importlib.util.spec_from_file_location("companion_check", os.path.join(HERE, "companion-check.py"))
companion_check = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(companion_check)
check = companion_check.check


def endpoints(orch):
    state, _, _ = orch.call("mcmcp_instances")
    return state.get("connected", [])


def wait_for(orch, wanted, seconds, what):
    deadline = time.time() + seconds
    found = None
    while time.time() < deadline:
        found = wanted(endpoints(orch))
        if found:
            return found
        time.sleep(1)
    check(what, False, endpoints(orch))
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("state_dir")
    parser.add_argument("--link-port", type=int, default=26580)
    parser.add_argument("--quit", action="store_true", help="quit the dev client when done")
    args = parser.parse_args()

    orch = companion_check.Orchestrator(args.state_dir, args.link_port)
    try:
        client = wait_for(orch, lambda es: next((e for e in es if e.get("side") == "client"), None), 180,
                          "a client endpoint connects")
        if not client:
            return 1
        check("a client endpoint connects", True)
        check("no server endpoint before a world is open",
              not any(e.get("side") == "server" for e in endpoints(orch)), endpoints(orch))

        _, text, error = orch.call("client_world_create", {"instance": client["instance"], "name": "mcmcp-sp-check",
                                                           "worldType": "flat", "gameMode": "creative"})
        check("the world is created", not error, text)
        # client_wait answers when its ticks run out whether or not the world is in, so ask until it is.
        loaded = False
        for _ in range(60):
            state, text, error = orch.call("client_wait", {"instance": client["instance"], "waitFor": "worldLoaded",
                                                           "ticks": 200})
            if error or (state.get("conditionMet") and state.get("playerPresent")):
                loaded = not error
                break
        check("the world loads", loaded, text)

        server = wait_for(orch, lambda es: next((e for e in es if e.get("side") == "server"), None), 60,
                          "the world's server endpoint appears")
        if server:
            check("the world's server endpoint appears", True)
            check("it is served over the link as the singleplayer world's", server.get("via") == "singleplayer", server)
            check("it opened no port of its own", not server.get("httpEndpoint"), server)
            check("it belongs to the same game as the client", server.get("game") == client.get("game"), server)
            info, text, error = orch.call("server_world_info", {"instance": server["instance"]})
            check("a server tool answers through it", not error and bool(info), text)
            players, text, error = orch.call("server_list_players", {"instance": server["instance"]})
            check("it sees the player in the world", not error and "McmcpDev" in (text + str(players)), text)

        _, text, error = orch.call("client_world_leave", {"instance": client["instance"]})
        check("the world is left", not error, text)
        gone = wait_for(orch, lambda es: not any(e.get("side") == "server" for e in es), 60,
                        "the server endpoint goes with the world")
        if gone:
            check("the server endpoint goes with the world", True)
        if args.quit:
            orch.call("client_quit", {"instance": client["instance"]})
    finally:
        orch.stop()

    failures = companion_check.failures
    print("\n" + ("ALL PASSED" if not failures else "%d FAILED: %s" % (len(failures), ", ".join(failures))))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
