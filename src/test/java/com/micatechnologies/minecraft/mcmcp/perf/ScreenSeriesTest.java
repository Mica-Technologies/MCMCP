package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class ScreenSeriesTest {

    @Test
    void aOneFrameDipInsideASlowDriftIsFoundAsAStep() {
        // A camera climbing a lift hill brightens steadily; one frame dips by 12.
        long[] ms = {0, 16, 33, 50, 66, 83};
        double[] lum = {89.4, 90.5, 91.6, 79.7, 93.8, 95.0};
        JsonObject json = ScreenSeries.summarise(ms, lum, null, 8.0D, false);
        assertEquals(14.1, json.getAsJsonObject("maxStep").get("delta").getAsDouble(), 1e-9);
        assertEquals(66L, json.getAsJsonObject("maxStep").get("ms").getAsLong());
        assertEquals(2, json.get("steps").getAsInt());
        assertEquals(50L, json.getAsJsonArray("firstSteps").get(0).getAsJsonObject().get("ms").getAsLong());
    }

    @Test
    void aSteadyScreenSaysSoWithoutAnyReads() {
        long[] ms = {0, 16, 33};
        double[] lum = {90.0, 90.2, 90.1};
        JsonObject json = ScreenSeries.summarise(ms, lum, null, 8.0D, false);
        assertEquals(0, json.get("steps").getAsInt());
        assertFalse(json.has("firstSteps"));
        assertFalse(json.has("samples"));
    }

    @Test
    void readsComeBackAsParallelArraysWhenAskedFor() {
        long[] ms = {0, 16};
        double[] lum = {10.04, 20.06};
        double[][] rgb = {{1, 2}, {3, 4}, {5, 6}};
        JsonObject json = ScreenSeries.summarise(ms, lum, rgb, 8.0D, true);
        JsonObject samples = json.getAsJsonObject("samples");
        assertEquals("[0,16]", samples.get("ms").toString());
        assertEquals("[10.0,20.1]", samples.get("lum").toString());
        assertTrue(samples.has("b"));
    }
}
