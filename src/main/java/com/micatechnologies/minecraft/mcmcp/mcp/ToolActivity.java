package com.micatechnologies.minecraft.mcmcp.mcp;

import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;

/**
 * When a tool was last called on each side.
 *
 * <p>Something in client code needs to know an agent is driving — the client holds its frame rate
 * up while one is (see {@code ClientKeepAwake}) — and common code must not name a client type. So
 * the dispatcher records the fact here and the client reads it, rather than the dispatcher calling
 * into the client.
 */
public final class ToolActivity {

    private static volatile long lastClientCallMillis;
    private static volatile long lastServerCallMillis;

    private ToolActivity() {
    }

    public static void noteCall(McmcpSide side) {
        long now = System.currentTimeMillis();
        if (side.isClient()) {
            lastClientCallMillis = now;
        }
        else {
            lastServerCallMillis = now;
        }
    }

    /** {@link System#currentTimeMillis()} of the last tool call on {@code side}, or 0 if none yet. */
    public static long lastCallMillis(McmcpSide side) {
        return side.isClient() ? lastClientCallMillis : lastServerCallMillis;
    }
}
