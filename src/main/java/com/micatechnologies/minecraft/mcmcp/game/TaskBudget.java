package com.micatechnologies.minecraft.mcmcp.game;

/**
 * How much of each tick MCMCP's task pump may spend, by the server's health (#133).
 *
 * <ul>
 *   <li>OK: {@code health.mcmcpTickBudgetMs} (15 ms by default), with as many tasks as fit.</li>
 *   <li>Jobs held ({@link ServerLoad.Level#THROTTLED}): at most 5 ms.</li>
 *   <li>Writes refused or stalled: one task a tick.</li>
 * </ul>
 *
 * A task that has started is never cut short, so these bound when the pump stops <em>starting</em>
 * tasks; one long slice can still overrun them. At least one task runs every tick there is one.
 *
 * <p>Pure.
 */
public final class TaskBudget {

    /** The most a held-back server lets MCMCP spend a tick, whatever the configured budget. */
    static final int THROTTLED_MS = 5;

    private TaskBudget() {
    }

    public static long budgetNanos(ServerLoad.Level level, int configuredMs) {
        int ms = Math.max(1, configuredMs);
        switch (level) {
            case OK:
                return ms * 1_000_000L;
            case THROTTLED:
                return Math.min(ms, THROTTLED_MS) * 1_000_000L;
            default:
                return 0L;
        }
    }

    /** The tick's budget for people to read: "15 ms", or "one task" when it is a count, not a time. */
    public static String describe(ServerLoad.Level level, int configuredMs) {
        return maxTasks(level) == 1 ? "one task" : budgetNanos(level, configuredMs) / 1_000_000L + " ms";
    }

    public static int maxTasks(ServerLoad.Level level) {
        return level == ServerLoad.Level.OK || level == ServerLoad.Level.THROTTLED ? Integer.MAX_VALUE : 1;
    }
}
