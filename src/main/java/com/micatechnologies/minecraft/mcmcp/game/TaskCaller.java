package com.micatechnologies.minecraft.mcmcp.game;

import javax.annotation.Nullable;

/**
 * Who a game-thread task runs for, so the server's task pump can take turns between callers and say
 * who is spending its tick.
 *
 * <p>The key is the chunk governor's hold caller: one per agent on the endpoint, one per player
 * through the companion, one per background job. The label is for people to read.
 *
 * <p>A follow-up finishes a call whose earlier task already changed the world, such as saving its
 * undo point: the pump runs it before anyone's turn ({@link FairDrain#offerFirst}).
 */
public final class TaskCaller {

    /** MCMCP's own housekeeping: releases, notices. */
    public static final TaskCaller MCMCP = new TaskCaller("mcmcp", "MCMCP");

    public final String key;
    public final String label;
    public final boolean followUp;

    public TaskCaller(String key, @Nullable String label) {
        this(key, label, false);
    }

    private TaskCaller(String key, @Nullable String label, boolean followUp) {
        this.key = key;
        this.label = label == null || label.isEmpty() ? key : label;
        this.followUp = followUp;
    }

    /** The same caller, for a task that finishes what one of its earlier tasks started. */
    public TaskCaller asFollowUp() {
        return followUp ? this : new TaskCaller(key, label, true);
    }
}
