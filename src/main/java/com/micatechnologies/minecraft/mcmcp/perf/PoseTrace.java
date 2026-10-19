package com.micatechnologies.minecraft.mcmcp.perf;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Something's pose sampled once per tick or frame, as columns, and what is odd about it (#109).
 *
 * <p>What a trace is for is the one sample in a thousand: a 360° yaw jump in a single tick, which
 * vanilla interpolates into an on-screen spin, or a position correction every fifth tick. A model
 * cannot find those in 1,000 rows of JSON it has to hold in context, so the summary finds them:
 * per column, the largest step between samples, and the samples where it passes a threshold.
 *
 * <p>Two kinds of step. Angles are judged on their first difference, raw, because the raw value is
 * what interpolation uses: wrapping maps x and x+360 to the same angle and hides exactly the jump
 * that spins the camera, and makes every crossing of ±180 look like a 359.9° step. Positions are
 * judged on their second difference — the change in velocity — since something moving smoothly at
 * 0.5 blocks a tick has large first differences and none of them are interesting; a correction
 * is a kink.
 *
 * <p>Pure: Gson, no Minecraft class, so it is unit-tested directly.
 */
public final class PoseTrace {

    /** Most outliers one column lists; the count says how many there were. */
    static final int LISTED_OUTLIERS = 20;

    private final List<String> names = new ArrayList<>();
    private final Map<String, double[]> columns = new LinkedHashMap<>();
    private final long[] ticks;
    private int size;

    public PoseTrace(int capacity, String... columnNames) {
        ticks = new long[capacity];
        for (String name : columnNames) {
            names.add(name);
            columns.put(name, new double[capacity]);
        }
    }

    public int capacity() {
        return ticks.length;
    }

    public int size() {
        return size;
    }

    public boolean isFull() {
        return size >= ticks.length;
    }

    /** One sample: its tick and a value per column, in the order the columns were named. */
    public void add(long tick, double... values) {
        if (isFull()) {
            return;
        }
        ticks[size] = tick;
        for (int i = 0; i < names.size(); i++) {
            columns.get(names.get(i))[size] = values[i];
        }
        size++;
    }

    /**
     * @param angleThreshold   degrees a step in an angle column must pass to be listed
     * @param positionThreshold blocks per sample² a change of velocity must pass to be listed
     */
    public JsonObject summary(double angleThreshold, double positionThreshold) {
        JsonObject json = new JsonObject();
        json.addProperty("samples", size);
        if (size > 0) {
            json.addProperty("firstTick", ticks[0]);
            json.addProperty("lastTick", ticks[size - 1]);
        }
        JsonObject perColumn = new JsonObject();
        for (String name : names) {
            boolean angle = isAngle(name);
            boolean position = isPosition(name);
            if (!angle && !position) {
                continue;
            }
            double[] values = columns.get(name);
            int order = angle ? 1 : 2;
            double threshold = angle ? angleThreshold : positionThreshold;
            double largest = 0.0D;
            int largestAt = -1;
            int over = 0;
            JsonArray listed = new JsonArray();
            List<Long> overTicks = new ArrayList<>();
            int lastOver = -2;
            double lastStep = 0.0D;
            for (int i = order; i < size; i++) {
                double step = order == 1 ? values[i] - values[i - 1]
                    : values[i] - 2.0D * values[i - 1] + values[i - 2];
                double magnitude = Math.abs(step);
                if (magnitude > largest) {
                    largest = magnitude;
                    largestAt = i;
                }
                // A one-sample jump is two kinks, into it and back out; the second is the same
                // correction, and counting it halves every period found.
                boolean rebound = order == 2 && lastOver == i - 1 && Math.signum(step) == -Math.signum(lastStep);
                if (magnitude > threshold && !rebound) {
                    lastOver = i;
                    lastStep = step;
                    over++;
                    overTicks.add(ticks[i]);
                    if (listed.size() < LISTED_OUTLIERS) {
                        JsonArray item = new JsonArray();
                        item.add(ticks[i]);
                        item.add(round(step));
                        listed.add(item);
                    }
                }
            }
            JsonObject column = new JsonObject();
            column.addProperty(angle ? "maxStep" : "maxVelocityChange", round(largest));
            if (largestAt >= 0) {
                column.addProperty("atTick", ticks[largestAt]);
            }
            column.addProperty("over", over);
            if (over > 0) {
                column.add("outliers", listed);
                Long period = commonInterval(overTicks);
                if (period != null) {
                    column.addProperty("everyTicks", period);
                }
            }
            perColumn.add(name, column);
        }
        json.add("columns", perColumn);
        json.addProperty("outlierFormat", "[tick, step]; angles by raw step, positions by change of velocity");
        return json;
    }

    /** The columns as arrays, rounded, for a caller that asked for them inline. */
    public JsonObject columnsJson() {
        JsonObject json = new JsonObject();
        JsonArray tickArray = new JsonArray();
        for (int i = 0; i < size; i++) {
            tickArray.add(ticks[i]);
        }
        json.add("tick", tickArray);
        for (String name : names) {
            JsonArray array = new JsonArray();
            double[] values = columns.get(name);
            for (int i = 0; i < size; i++) {
                array.add(round(values[i]));
            }
            json.add(name, array);
        }
        return json;
    }

    /** The trace as CSV, one row per sample. */
    public String csv() {
        StringBuilder text = new StringBuilder(size * 64);
        text.append("tick");
        for (String name : names) {
            text.append(',').append(name);
        }
        text.append('\n');
        for (int i = 0; i < size; i++) {
            text.append(ticks[i]);
            for (String name : names) {
                text.append(',').append(round(columns.get(name)[i]));
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** A prev* column is its own column a sample late: in the CSV, but summarising it says nothing new. */
    static boolean isAngle(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return !lower.startsWith("prev") && (lower.contains("yaw") || lower.contains("pitch") || lower.contains("roll"));
    }

    static boolean isPosition(String name) {
        return "x".equals(name) || "y".equals(name) || "z".equals(name);
    }

    /**
     * The interval most of the outliers are apart, when one is: a correction every fifth tick is
     * the server's, and saying so saves reading the list for it.
     */
    static Long commonInterval(List<Long> at) {
        // Three intervals at least: two points apart is a coincidence, not a period.
        if (at.size() < 4) {
            return null;
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (int i = 1; i < at.size(); i++) {
            counts.merge(at.get(i) - at.get(i - 1), 1, Integer::sum);
        }
        Map.Entry<Long, Integer> best = null;
        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            if (best == null || entry.getValue() > best.getValue()) {
                best = entry;
            }
        }
        return best != null && best.getKey() > 0 && best.getValue() * 2 >= at.size() - 1 ? best.getKey() : null;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0D) / 10000.0D;
    }
}
