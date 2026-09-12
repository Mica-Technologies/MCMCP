package com.micatechnologies.minecraft.mcmcp.game;

import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Identity of the JVM this endpoint is running inside: process id and start time.
 *
 * <h2>Why an endpoint needs to be able to say which process it is</h2>
 *
 * MCMCP's ports are fixed by configuration, and the game that wins the bind keeps it. A
 * {@code runClient} whose Gradle task was stopped leaves the <em>game</em> running and still holding
 * {@code clientPort}; the next launch cannot bind, and every call keeps reaching the previous build.
 * Nothing errors — the old endpoint answers perfectly, from a jar that is no longer the one being
 * worked on.
 *
 * <p>Everything MCMCP reported before this class existed was identical between those two processes:
 * same instance id, same game directory, same mod version. The pid is the one fact that tells them
 * apart, and the start time is what makes "this endpoint predates my last build" a question a caller
 * can answer for itself rather than by reasoning about what it remembers launching.
 *
 * <h2>Java 8</h2>
 *
 * {@code ProcessHandle.current().pid()} is Java 9. The mod compiles and runs against Java 8, so the
 * pid comes from {@link RuntimeMXBean#getName()}, which every mainstream JVM formats as
 * {@code <pid>@<host>}. That is a convention rather than a guarantee, so a name that does not parse
 * yields {@link #UNKNOWN_PID} rather than a wrong number — a caller can tell "no pid available" from
 * "pid 0", and neither is ever silently something else's process.
 *
 * <p>No Minecraft class is named here, deliberately: the link handshake is built on both sides and
 * unit-tested without a game, and this is one of its inputs.
 *
 * @author Mica Technologies
 */
public final class McmcpProcess {

    /** Reported when the JVM name could not be parsed into a process id. */
    public static final long UNKNOWN_PID = -1L;

    private static final String ISO_8601_UTC = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    private McmcpProcess() {
    }

    /**
     * This game's operating-system process id.
     *
     * @return the pid, or {@link #UNKNOWN_PID} if this JVM does not expose one in a parsable form
     */
    public static long pid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int at = name == null ? -1 : name.indexOf('@');
            if (at <= 0) {
                return UNKNOWN_PID;
            }
            return Long.parseLong(name.substring(0, at));
        }
        catch (RuntimeException notParsable) {
            // Includes the SecurityManager case as well as a name in some shape this does not know.
            // A missing pid is a smaller problem than a made-up one.
            return UNKNOWN_PID;
        }
    }

    /** When this JVM started, in epoch milliseconds. */
    public static long startedAtMillis() {
        return ManagementFactory.getRuntimeMXBean().getStartTime();
    }

    /** How long this JVM has been up, in whole seconds. */
    public static long uptimeSeconds() {
        return ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;
    }

    /**
     * When this JVM started, as an ISO-8601 UTC timestamp.
     *
     * <p>A string rather than the epoch millisecond count because this is read by models and by
     * people, and both compare {@code 2026-09-11T08:14:02Z} against a build time far more reliably
     * than they compare two thirteen-digit numbers. UTC for the same reason: an instance reporting a
     * local time is a timestamp that cannot be compared with one from a machine elsewhere.
     *
     * @return the start time formatted as {@code yyyy-MM-ddTHH:mm:ssZ}
     */
    public static String startedAtIso() {
        return formatIso(startedAtMillis());
    }

    /**
     * Formats an epoch millisecond value as an ISO-8601 UTC timestamp.
     *
     * @param epochMillis the instant to format
     *
     * @return the formatted timestamp
     */
    public static String formatIso(long epochMillis) {
        // SimpleDateFormat rather than java.time: the mod targets Java 8 but runs under whatever the
        // pack's launcher provides, and this is the formatting that behaves identically on all of
        // them. It is not thread-safe, hence a fresh one per call — this is called a handful of
        // times per session, not per tick.
        SimpleDateFormat format = new SimpleDateFormat(ISO_8601_UTC, Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(epochMillis));
    }

    /**
     * The process identity as a JSON object, for embedding in a tool result.
     *
     * <p>One shape in every tool that reports it, so a caller comparing two endpoints is never
     * comparing two different spellings of the same three facts.
     *
     * @return {@code {"pid": …, "startedAt": …, "uptimeSeconds": …}}
     */
    public static JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("pid", pid());
        json.addProperty("startedAt", startedAtIso());
        json.addProperty("uptimeSeconds", uptimeSeconds());
        return json;
    }
}
