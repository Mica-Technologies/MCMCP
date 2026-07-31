package com.micatechnologies.minecraft.mcmcp.game;

/**
 * Which of the two MCMCP endpoints a piece of work belongs to.
 *
 * <p>This is <em>not</em> Forge's {@code Side}. Forge's logical sides describe where code is
 * running; this describes which MCP endpoint is exposed and therefore what a connected model can
 * reach:
 *
 * <ul>
 *   <li>{@link #CLIENT} — the endpoint hosted inside a player's game client. It controls that one
 *       player, sees exactly what they see, and can take screenshots and drive input. Crucially it
 *       works on <em>any</em> server the player can join, including ones where they have no
 *       operator rights and no ability to open a port. This is the primary mode.</li>
 *   <li>{@link #SERVER} — the endpoint hosted inside a dedicated server (or the integrated server
 *       behind a singleplayer world). It sees every player and the authoritative world state, and
 *       can run commands with server authority, but it has no camera and no input to drive.</li>
 * </ul>
 *
 * <p>Tools declare which sides they support; the registry refuses to expose a client-only tool on a
 * server endpoint rather than letting it fail at call time. That matters because tool discovery is
 * how a model plans: a tool listed but permanently broken is worse than one that was never listed.
 */
public enum McmcpSide {

    CLIENT("client"),
    SERVER("server");

    private final String id;

    McmcpSide(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean isClient() {
        return this == CLIENT;
    }

    public boolean isServer() {
        return this == SERVER;
    }
}
