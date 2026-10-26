package com.micatechnologies.minecraft.mcmcp.perf;

/**
 * Whether a dimension's entity update actually ran lately, from what was seen tick by tick (#140).
 *
 * <p>Inferring it from vanilla's idle rule (no player and no forced chunk for 300 ticks) is wrong in
 * the misleading direction whenever another mod is in the way: a pauser that cancels
 * {@code WorldServer.updateEntities} at its head also stops the idle count from moving, so a world
 * where nothing at all ticks reads as ticking. So each sampled tick is classified by its effects, and
 * the verdict is taken over the last few of them.
 *
 * <p>No Minecraft class: the Forge half that watches the ticks is {@code tools.EntityTickObserver}.
 * One instance per world, written and read on that world's server thread.
 */
public final class EntityTickEvidence {

    /** What one tick showed. */
    public enum Tick {
        /** An entity the update would have ticked did tick, or the update ran past vanilla's idle check. */
        RAN,
        /** The update ran and returned at vanilla's idle cutoff: no player and no forced chunk. */
        IDLE,
        /** The update would have ticked something it was given, and nothing moved: it did not run. */
        SKIPPED,
        /** Nothing seen either way: no entity to watch and no idle count that had to move. */
        UNKNOWN
    }

    /** Why entities are not ticking, or that they are. */
    public enum Verdict {
        TICKING("ticking"),
        /** Vanilla's own rule: no player and no forced chunk for 300 ticks. */
        IDLE_CUTOFF("idleCutoff"),
        /** The update did not run, though vanilla would have run it: another mod cancelled it. */
        SUPPRESSED("suppressed"),
        /** Nothing observed yet; the caller falls back to vanilla's rule. */
        UNOBSERVED("unobserved");

        public final String reason;

        Verdict(String reason) {
            this.reason = reason;
        }
    }

    /** How many sampled ticks the verdict looks back over. */
    public static final int WINDOW = 8;

    private final Tick[] ring = new Tick[WINDOW];
    private int next;
    private int size;

    public void record(Tick tick) {
        ring[next] = tick;
        next = (next + 1) % WINDOW;
        if (size < WINDOW) {
            size++;
        }
    }

    /**
     * The verdict over the window.
     *
     * <p>Any tick that ran means ticking: a lag limiter that skips some ticks still ticks its
     * entities, and saying they are stopped would be the opposite mistake. Otherwise the newest tick
     * that showed anything decides between vanilla's cutoff and suppression, so a world a player has
     * just left reads as idle, not as cancelled.
     */
    public Verdict verdict() {
        Tick newestKnown = null;
        for (int i = 1; i <= size; i++) {
            Tick tick = ring[Math.floorMod(next - i, WINDOW)];
            if (tick == Tick.RAN) {
                return Verdict.TICKING;
            }
            if (newestKnown == null && tick != Tick.UNKNOWN) {
                newestKnown = tick;
            }
        }
        if (newestKnown == Tick.IDLE) {
            return Verdict.IDLE_CUTOFF;
        }
        if (newestKnown == Tick.SKIPPED) {
            return Verdict.SUPPRESSED;
        }
        return Verdict.UNOBSERVED;
    }

    /**
     * Classifies one tick.
     *
     * @param watched        entities that vanilla would have ticked, seen before and after the tick
     * @param anyAdvanced    whether any of them did tick
     * @param idleBefore     vanilla's idle count before the tick, or -1 if it cannot be read
     * @param idleAfter      the same after the tick
     * @param idleRuleHeld   no player and no forced chunk, so vanilla must move the idle count
     * @param cutoff         the idle count at which vanilla returns early
     */
    public static Tick classify(int watched, boolean anyAdvanced, int idleBefore, int idleAfter,
                                boolean idleRuleHeld, int cutoff) {
        if (anyAdvanced) {
            return Tick.RAN;
        }
        boolean idleReadable = idleBefore >= 0 && idleAfter >= 0;
        if (idleReadable && idleAfter != idleBefore) {
            // The head of the update ran: it counts up while the world is empty, and resets once it
            // is not. Counting up from the cutoff is the early return.
            return idleRuleHeld && idleBefore >= cutoff ? Tick.IDLE : Tick.RAN;
        }
        if (idleReadable && idleRuleHeld) {
            // Vanilla moves the count on every call while the world is empty; still means no call.
            return Tick.SKIPPED;
        }
        if (watched > 0) {
            return Tick.SKIPPED;
        }
        return Tick.UNKNOWN;
    }
}
