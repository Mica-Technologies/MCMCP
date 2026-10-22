package com.micatechnologies.minecraft.mcmcp.tools;

/**
 * Whether a write fits its caller's writes-per-minute budget now, and how long a paced caller waits
 * between looks when it does not (#131). No Minecraft class, so unit-tested on its own.
 *
 * <p>An interactive call over the budget is refused with {@code busy.retryAfterMs}: someone is waiting
 * on it and can decide what to do. A job part has nobody waiting, so it is paced instead: it waits for
 * the budget, and a part larger than the whole budget goes through once the caller has written nothing
 * for a minute, rather than waiting for room that can never come.
 */
final class WritePacing {

    /** The longest single wait between looks, so a pause or cancel lands within it. */
    static final long MAX_WAIT_STEP_MILLIS = 1000L;

    private WritePacing() {
    }

    /**
     * @param used      cells the caller wrote in the last minute
     * @param cells     cells this write would add
     * @param perMinute the caller's budget
     * @param paced     whether the caller waits for the budget (a job) rather than being refused
     */
    static boolean fits(long used, long cells, long perMinute, boolean paced) {
        if (used + cells <= perMinute) {
            return true;
        }
        return paced && used <= 0L && cells > perMinute;
    }

    /**
     * How long a paced caller sleeps before looking again: until room is expected, but never longer
     * than one step and never less than a few milliseconds, so it neither spins nor oversleeps a cancel.
     */
    static long waitStep(long millisUntilRoom) {
        return Math.max(10L, Math.min(MAX_WAIT_STEP_MILLIS, millisUntilRoom));
    }
}
