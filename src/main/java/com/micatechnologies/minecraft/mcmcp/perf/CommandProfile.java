package com.micatechnologies.minecraft.mcmcp.perf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongUnaryOperator;
import javax.annotation.Nullable;

/**
 * What {@code server_run_command}'s and {@code server_run_commands}' {@code profile} measures: each
 * command's own time, which ticks the commands ran in, and how long those ticks took.
 *
 * <p>The two figures answer different questions, which is why both are kept. A command whose
 * {@code commandMs} is most of its tick's {@code tickMs} is the slow thing; a fast command in a slow
 * tick caused work that ran after it returned, and the sample's {@code scope: "tick"} is what
 * names that work.
 *
 * <p>Imports no Minecraft class. {@link #record} is called on the game thread and everything else
 * after the game thread's result has come back, so it needs no locking of its own.
 */
public final class CommandProfile {

    /** The {@code profile} argument, parsed and clamped to the bounds the schema publishes. */
    public static final class Options {
        public final int intervalMillis;
        public final int top;
        public final double minPercent;
        public final int maxLines;
        public final boolean wholeTick;

        Options(int intervalMillis, int top, double minPercent, int maxLines, boolean wholeTick) {
            this.intervalMillis = intervalMillis;
            this.top = top;
            this.minPercent = minPercent;
            this.maxLines = maxLines;
            this.wholeTick = wholeTick;
        }
    }

    /**
     * Reads the {@code profile} argument: null when it is absent, null or false, so a call without it
     * pays nothing. {@code true}, an object, or an object sent as a string all switch it on, because
     * the argument is usually written by a model.
     */
    @Nullable
    public static Options parse(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject object = null;
        if (element.isJsonObject()) {
            object = element.getAsJsonObject();
        } else if (element.isJsonPrimitive()) {
            String text = element.getAsJsonPrimitive().getAsString().trim();
            if (text.startsWith("{")) {
                try {
                    JsonElement parsed = new JsonParser().parse(text);
                    object = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
                } catch (RuntimeException e) {
                    object = null;
                }
            } else if (!"true".equalsIgnoreCase(text)) {
                return null;
            }
        } else {
            return null;
        }
        String scope = Json.getString(object, "scope", "commands");
        return new Options(
            clamp(Json.getInt(object, "interval_ms", 2), 1, 20),
            clamp(Json.getInt(object, "top", 15), 1, 50),
            Math.max(0.1D, Math.min(50.0D, Json.getDouble(object, "min_percent", 2.0D))),
            clamp(Json.getInt(object, "max_lines", 40), 10, 300),
            "tick".equalsIgnoreCase(scope));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** One command's measurement. */
    private static final class Ran {
        final int index;
        final String command;
        final long tick;
        final long nanos;

        Ran(int index, String command, long tick, long nanos) {
            this.index = index;
            this.command = command;
            this.tick = tick;
            this.nanos = nanos;
        }
    }

    private final List<Ran> ran = new ArrayList<Ran>();

    /**
     * Records one command.
     *
     * @param tick  the tick it ran in: the clock's completed count, read during that tick
     * @param nanos wall time inside the command
     */
    public void record(int index, String command, long tick, long nanos) {
        ran.add(new Ran(index, command, tick, nanos));
    }

    public boolean isEmpty() {
        return ran.isEmpty();
    }

    /** The last tick any command ran in, which is the one to wait for; -1 when none ran. */
    public long lastTick() {
        long last = -1L;
        for (Ran each : ran) {
            last = Math.max(last, each.tick);
        }
        return last;
    }

    /**
     * Adds {@code tickMs}, {@code commandMs} and, for more than one command, {@code ticks},
     * {@code tickMsEach} and {@code slowestCommands} to {@code into}.
     *
     * @param durationOf a tick's duration in nanoseconds by its index, or -1 when it is not known
     */
    public void addTo(JsonObject into, LongUnaryOperator durationOf, int top) {
        // Commands per tick, in the order the ticks ran.
        Map<Long, long[]> byTick = new LinkedHashMap<Long, long[]>();
        long commandNanos = 0L;
        for (Ran each : ran) {
            long[] entry = byTick.get(each.tick);
            if (entry == null) {
                entry = new long[2];
                byTick.put(each.tick, entry);
            }
            entry[0]++;
            entry[1] += each.nanos;
            commandNanos += each.nanos;
        }

        long worstTick = -1L;
        int unknown = 0;
        JsonArray each = new JsonArray();
        for (Map.Entry<Long, long[]> tick : byTick.entrySet()) {
            long took = durationOf.applyAsLong(tick.getKey());
            if (took < 0L) {
                unknown++;
            } else {
                worstTick = Math.max(worstTick, took);
            }
            if (each.size() < 50) {
                JsonObject row = new JsonObject();
                if (took >= 0L) {
                    row.addProperty("tickMs", DurationWindow.millis(took));
                }
                row.addProperty("commands", tick.getValue()[0]);
                row.addProperty("commandMs", DurationWindow.millis(tick.getValue()[1]));
                each.add(row);
            }
        }

        if (worstTick >= 0L) {
            into.addProperty("tickMs", DurationWindow.millis(worstTick));
        }
        if (unknown > 0) {
            into.addProperty("tickMsUnknown", unknown == byTick.size() && unknown == 1
                ? "The tick did not finish in time to be measured."
                : unknown + " of " + byTick.size() + " tick(s) did not finish in time to be measured.");
        }
        into.addProperty("commandMs", DurationWindow.millis(commandNanos));
        if (ran.size() > 1) {
            into.addProperty("ticks", byTick.size());
            if (byTick.size() > 1) {
                // tickMs above is the worst of these.
                into.add("tickMsEach", each);
            }
            List<Ran> slowest = new ArrayList<Ran>(ran);
            Collections.sort(slowest, (a, b) -> Long.compare(b.nanos, a.nanos));
            JsonArray rows = new JsonArray();
            for (int i = 0; i < Math.min(top, slowest.size()); i++) {
                Ran command = slowest.get(i);
                JsonObject row = new JsonObject();
                row.addProperty("i", command.index);
                row.addProperty("commandMs", DurationWindow.millis(command.nanos));
                row.addProperty("command", command.command.length() > 120
                    ? command.command.substring(0, 117) + "..." : command.command);
                rows.add(row);
            }
            into.add("slowestCommands", rows);
        }
    }
}
