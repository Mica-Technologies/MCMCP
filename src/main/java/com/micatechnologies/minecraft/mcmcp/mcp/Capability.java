package com.micatechnologies.minecraft.mcmcp.mcp;

/**
 * A kind of power a tool exercises, checked through {@link ToolContext#refusal}.
 *
 * <p>On an endpoint each maps to a {@code permissions.*} switch in the config, as it always has. For a
 * companion caller each maps to a class of call the player has been granted, and the endpoint's
 * switches do not apply: a dedicated server leaves them off, and the companion is governed by its
 * allowlist and permission nodes instead.
 */
public enum Capability {

    /** Writing blocks, restoring undo points, moving players. */
    WORLD_EDITS,

    /** Running commands. */
    COMMANDS,

    /** Sending chat or messages to players. */
    CHAT,

    /** Using an item on a block, or activating one, as a player and from any distance. */
    ITEM_USE,

    /** Stopping the server or the game. Never granted to a companion caller. */
    PROCESS_CONTROL,

    /** Reading the game's log files. Never granted to a companion caller. */
    LOG_ACCESS
}
