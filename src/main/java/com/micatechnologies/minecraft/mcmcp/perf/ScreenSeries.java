package com.micatechnologies.minecraft.mcmcp.perf;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javax.annotation.Nullable;

/**
 * What {@code client_screen_stats} says about a run of brightness reads (#197).
 *
 * <p>A flicker is a change between <em>consecutive</em> frames. Comparing each read with the first
 * (which {@code changed} and {@code maxDeltaFromFirst} do) hides a one-frame dip inside the slow drift
 * of a moving camera, so the summary also gives the largest frame-to-frame step and the steps over
 * the threshold. That is the flicker answer in a dozen tokens; the reads themselves are returned only
 * when asked for, as parallel arrays rather than an object per read.
 */
public final class ScreenSeries {

    /** How many steps over the threshold are listed; the rest are only counted. */
    static final int LISTED_STEPS = 5;

    private ScreenSeries() {
    }

    /**
     * @param ms        each read's time since the first, in milliseconds
     * @param lum       each read's mean luminance
     * @param rgb       null, or each read's mean red, green and blue as {@code rgb[channel][read]}
     * @param withReads whether to include the reads themselves
     */
    public static JsonObject summarise(long[] ms, double[] lum, @Nullable double[][] rgb, double threshold,
        boolean withReads) {
        int n = lum.length;
        double first = lum[0];
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double sum = 0.0D;
        double maxDelta = 0.0D;
        int changed = 0;
        double maxStep = 0.0D;
        int maxStepAt = -1;
        int steps = 0;
        JsonArray listed = new JsonArray();
        for (int i = 0; i < n; i++) {
            min = Math.min(min, lum[i]);
            max = Math.max(max, lum[i]);
            sum += lum[i];
            double delta = Math.abs(lum[i] - first);
            maxDelta = Math.max(maxDelta, delta);
            if (delta > threshold) {
                changed++;
            }
            if (i == 0) {
                continue;
            }
            double step = Math.abs(lum[i] - lum[i - 1]);
            if (step > maxStep) {
                maxStep = step;
                maxStepAt = i;
            }
            if (step > threshold) {
                steps++;
                if (listed.size() < LISTED_STEPS) {
                    JsonObject item = new JsonObject();
                    item.addProperty("ms", ms[i]);
                    item.addProperty("from", round1(lum[i - 1]));
                    item.addProperty("to", round1(lum[i]));
                    listed.add(item);
                }
            }
        }

        JsonObject json = new JsonObject();
        json.addProperty("reads", n);
        json.addProperty("spanMs", ms[n - 1] - ms[0]);
        json.addProperty("minLum", round1(min));
        json.addProperty("maxLum", round1(max));
        json.addProperty("meanLum", round1(sum / n));
        json.addProperty("maxDeltaFromFirst", round1(maxDelta));
        json.addProperty("changed", changed);
        JsonObject biggest = new JsonObject();
        biggest.addProperty("delta", round1(maxStep));
        if (maxStepAt > 0) {
            biggest.addProperty("ms", ms[maxStepAt]);
            biggest.addProperty("from", round1(lum[maxStepAt - 1]));
            biggest.addProperty("to", round1(lum[maxStepAt]));
        }
        json.add("maxStep", biggest);
        json.addProperty("steps", steps);
        if (listed.size() > 0) {
            json.add("firstSteps", listed);
        }
        if (withReads) {
            JsonObject samples = new JsonObject();
            samples.add("ms", longs(ms));
            samples.add("lum", rounded(lum));
            if (rgb != null) {
                samples.add("r", rounded(rgb[0]));
                samples.add("g", rounded(rgb[1]));
                samples.add("b", rounded(rgb[2]));
            }
            json.add("samples", samples);
        }
        return json;
    }

    private static JsonArray longs(long[] values) {
        JsonArray array = new JsonArray();
        for (long value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonArray rounded(double[] values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            array.add(round1(value));
        }
        return array;
    }

    static double round1(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }
}
