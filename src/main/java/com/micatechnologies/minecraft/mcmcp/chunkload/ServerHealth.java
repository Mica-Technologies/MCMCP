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
        return update(meanMspt, heapPercent, -1.0D, nowMillis, throttleMspt, pauseMspt, pauseHeapPercent, 0.0D, 0.0D);
    }

    /**
     * Takes one reading that includes the real TPS: ticks finished a second by the wall clock. A low one
     * counts like a long mean tick, since the mean tick time leaves out the time between ticks and so
     * cannot see a server thread that stops for seconds at a time between quick ticks.
     *
     * @param realTps          ticks a second over the last few seconds, or negative when not known
     * @param throttleBelowTps throttled below this; 0 for never
     * @param pauseBelowTps    paused below this; 0 for never
     * @return the state after it
     */
    public State update(double meanMspt, double heapPercent, double realTps, long nowMillis, double throttleMspt,
                        double pauseMspt, double pauseHeapPercent, double throttleBelowTps, double pauseBelowTps) {
        lastMspt = meanMspt;
        lastHeapPercent = heapPercent;
        boolean known = realTps >= 0.0D;
        State measured = meanMspt > pauseMspt || heapPercent > pauseHeapPercent || known && realTps < pauseBelowTps
            ? State.PAUSED
            : meanMspt > throttleMspt || known && realTps < throttleBelowTps ? State.THROTTLED : State.OK;
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
