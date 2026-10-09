package com.micatechnologies.minecraft.mcmcp.chunkload;

/**
 * Whether the server can afford for MCMCP to load chunks, from its tick time and its memory.
 *
 * <ul>
 *   <li>{@link State#OK}: load at the configured pace.</li>
 *   <li>{@link State#THROTTLED}: mean tick time is over the throttle threshold; load slowly.</li>
 *   <li>{@link State#PAUSED}: the server is below 20 TPS, or its heap is nearly full after garbage
 *       collection; load nothing new. Held for long enough, MCMCP gives back what it holds.</li>
 * </ul>
 *
 * <p>Getting worse is immediate; getting better waits out a hysteresis window, so a server hovering
 * at the threshold does not flap between loading and not every second.
 *
 * <p>Pure: fed numbers, it returns a state.
 */
public final class ServerHealth {

    public enum State { OK, THROTTLED, PAUSED }

    /** How long conditions must stay better before the state improves. */
    public static final long RECOVERY_MILLIS = 10_000L;

    private State state = State.OK;
    private long betterSinceMillis = -1L;
    private long pausedSinceMillis = -1L;
    private double lastMspt;
    private double lastHeapPercent;

    /**
     * Takes one reading.
     *
     * @return the state after it
     */
    public State update(double meanMspt, double heapPercent, long nowMillis, double throttleMspt,
                        double pauseMspt, double pauseHeapPercent) {
        lastMspt = meanMspt;
        lastHeapPercent = heapPercent;
        State measured = meanMspt > pauseMspt || heapPercent > pauseHeapPercent ? State.PAUSED
            : meanMspt > throttleMspt ? State.THROTTLED : State.OK;
        if (measured.ordinal() >= state.ordinal()) {
            // Worse, or the same: take it at once.
            if (measured == State.PAUSED && state != State.PAUSED) {
                pausedSinceMillis = nowMillis;
            }
            state = measured;
            betterSinceMillis = -1L;
            return state;
        }
        if (betterSinceMillis < 0L) {
            betterSinceMillis = nowMillis;
        }
        if (nowMillis - betterSinceMillis >= RECOVERY_MILLIS) {
            state = measured;
            betterSinceMillis = -1L;
            if (state != State.PAUSED) {
                pausedSinceMillis = -1L;
            }
        }
        return state;
    }

    public State state() {
        return state;
    }

    /** How long the server has been paused, or 0 when it is not. */
    public long pausedForMillis(long nowMillis) {
        return state == State.PAUSED && pausedSinceMillis >= 0L ? nowMillis - pausedSinceMillis : 0L;
    }

    /** The earliest the state could next improve, in milliseconds from now. */
    public long millisUntilRecovery(long nowMillis) {
        if (state == State.OK) {
            return 0L;
        }
        return betterSinceMillis < 0L ? RECOVERY_MILLIS : Math.max(0L, RECOVERY_MILLIS - (nowMillis - betterSinceMillis));
    }

    public double lastMspt() {
        return lastMspt;
    }

    public double lastHeapPercent() {
        return lastHeapPercent;
    }

    /** How many loads the tick may make in this state, given the configured pace. */
    public static int perTick(State state, int configured) {
        switch (state) {
            case OK:
                return configured;
            case THROTTLED:
                return Math.min(configured, 2);
            default:
                return 0;
        }
    }
}
