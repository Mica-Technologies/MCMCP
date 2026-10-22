package com.micatechnologies.minecraft.mcmcp.game;

import javax.annotation.Nullable;

/**
 * Who a game-thread task runs for, so the server's task pump can take turns between callers and say
 * who is spending its tick.
 *
 * <p>The key is the chunk governor's hold caller: one per agent on the endpoint, one per player
 * through the companion, one per background job. The label is for people to read.
 */
public final class TaskCaller {

    /** MCMCP's own housekeeping: releases, notices. */
    public static final TaskCaller MCMCP = new TaskCaller("mcmcp", "MCMCP");

    public final String key;
    public final String label;

    public TaskCaller(String key, @Nullable String label) {
        this.key = key;
        this.label = label == null || label.isEmpty() ? key : label;
    }
}
