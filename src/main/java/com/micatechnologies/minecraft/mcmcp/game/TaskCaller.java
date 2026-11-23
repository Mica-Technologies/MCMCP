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
    /**
     * What the task is part of, for a person reading a stall report: the tool and a short form of its
     * arguments. Null for MCMCP's own housekeeping.
     */
    @Nullable
    public final String what;

    public TaskCaller(String key, @Nullable String label) {
        this(key, label, false, null);
    }

    private TaskCaller(String key, @Nullable String label, boolean followUp, @Nullable String what) {
        this.key = key;
        this.label = label == null || label.isEmpty() ? key : label;
        this.followUp = followUp;
        this.what = what;
    }

    /** The same caller, for a task that finishes what one of its earlier tasks started. */
    public TaskCaller asFollowUp() {
        return followUp ? this : new TaskCaller(key, label, true, what);
    }

    /** The same caller, with what its task is part of: the tool call, as a stall report should name it. */
    public TaskCaller describing(@Nullable String what) {
        return new TaskCaller(key, label, followUp, what);
    }
}
