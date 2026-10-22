package com.micatechnologies.minecraft.mcmcp.game;

import java.util.EnumMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * When to tell people the server's health changed, and what to say (#133).
 *
 * <p>The rule: announce the level the server is at whenever it is not the one last announced, but
 * never the same level twice within the interval. Asked again later — the watchdog asks every second —
 * a level held back by the interval is announced once the interval is over, if the server is still at
 * it. So a server hovering at a threshold costs one line per level per interval, and ops are never left
 * believing an old announcement after the server settles somewhere else. Nothing is said while the
 * server stays healthy: OK is announced only after trouble was.
 *
 * <p>Pure: fed levels and times, it answers.
 */
public final class HealthAlerts {

    private final Map<ServerLoad.Level, Long> lastSent = new EnumMap<>(ServerLoad.Level.class);
    private ServerLoad.Level announced = ServerLoad.Level.OK;

    /**
     * @return the level to announce now, or null for nothing
     */
    @Nullable
    public synchronized ServerLoad.Level poll(ServerLoad.Level current, long nowMillis, long intervalMillis) {
        if (current == announced) {
            return null;
        }
        Long last = lastSent.get(current);
        if (last != null && nowMillis - last < intervalMillis) {
            return null;
        }
        lastSent.put(current, nowMillis);
        announced = current;
        return current;
    }

    /** The server stopped: the next one starts with nothing announced. */
    public synchronized void reset() {
        lastSent.clear();
        announced = ServerLoad.Level.OK;
    }

    /**
     * The chat line for {@code level}, without colour.
     *
     * @param topCaller the MCMCP call that has run longest, as "who: tool (N s)", or null
     * @param dumpPath  where the stall's stacks went, or null
     */
    public static String message(ServerLoad.Level level, double mspt, double heapPercent, long stalledSeconds,
                                 @Nullable String topCaller, @Nullable String dumpPath) {
        String numbers = "mean tick " + ServerLoad.round1(mspt) + " ms (" + ServerLoad.round1(ServerLoad.tps(mspt))
            + " TPS), heap " + Math.round(heapPercent) + "%";
        StringBuilder text = new StringBuilder("[MCMCP] ");
        switch (level) {
            case OK:
                return text.append("Server health is back to OK: ").append(numbers).append('.').toString();
            case THROTTLED:
                text.append("Server is throttled: ").append(numbers)
                    .append(". MCMCP loads chunks slowly and holds back background jobs.");
                break;
            case PAUSED:
                text.append("Server is paused: ").append(numbers)
                    .append(". MCMCP refuses calls that change the world and holds back jobs until it recovers.");
                break;
            default:
                text.append("Server thread stalled: no tick in ").append(stalledSeconds).append(" s.");
                if (dumpPath != null) {
                    text.append(" Stacks are in ").append(dumpPath).append('.');
                }
                text.append(" MCMCP refuses calls that change the world.");
                break;
        }
        if (topCaller != null) {
            text.append(" Longest MCMCP call: ").append(topCaller).append('.');
        }
        return text.append(" /mcmcp estop stops every MCMCP change.").toString();
    }
}
