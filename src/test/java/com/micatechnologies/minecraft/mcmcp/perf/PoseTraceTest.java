package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** What a pose trace finds odd. */
class PoseTraceTest {

    @Test
    void aRawYawJumpOf360InOneTickIsFoundThoughWrappingWouldHideIt() {
        PoseTrace trace = new PoseTrace(100, "x", "y", "z", "yaw");
        for (int i = 0; i < 100; i++) {
            // Turning steadily through ±180, which wrapping would turn into a fake 359° step;
            // raw, it is 3° a tick, except one tick where the raw value jumps by a whole turn.
            double yaw = 170.0D + i * 3.0D + (i >= 60 ? 360.0D : 0.0D);
            trace.add(1000 + i, i * 0.5D, 64.0D, 0.0D, yaw);
        }
        JsonObject yaw = trace.summary(30.0D, 0.25D).getAsJsonObject("columns").getAsJsonObject("yaw");
        assertEquals(1, yaw.get("over").getAsInt());
        assertEquals(1060, yaw.get("atTick").getAsLong());
        assertEquals(363.0D, yaw.get("maxStep").getAsDouble(), 1e-9);
    }

    @Test
    void steadyMotionIsNotACorrectionButAKinkEveryFiveTicksIsAndItsPeriodIsNamed() {
        PoseTrace trace = new PoseTrace(200, "x", "y", "z", "yaw");
        double x = 0.0D;
        for (int i = 0; i < 200; i++) {
            x += 0.5D;
            if (i % 5 == 0 && i > 0) {
                x += 0.6D;
            }
            trace.add(i, x, 64.0D, 0.0D, 0.0D);
        }
        JsonObject columns = trace.summary(30.0D, 0.25D).getAsJsonObject("columns");
        JsonObject xs = columns.getAsJsonObject("x");
        assertTrue(xs.get("over").getAsInt() > 30);
        assertEquals(5L, xs.get("everyTicks").getAsLong());
        assertEquals(0, columns.getAsJsonObject("z").get("over").getAsInt());
    }

    @Test
    void theTraceIsKeptInFullForTheCsvAndStopsAtCapacity() {
        PoseTrace trace = new PoseTrace(3, "x", "yaw");
        trace.add(1, 0.123456789D, 10.0D);
        trace.add(2, 1.0D, 20.0D);
        trace.add(3, 2.0D, 30.0D);
        trace.add(4, 3.0D, 40.0D);
        assertTrue(trace.isFull());
        assertEquals("tick,x,yaw\n1,0.1235,10.0\n2,1.0,20.0\n3,2.0,30.0\n", trace.csv());
        assertEquals(3, trace.columnsJson().getAsJsonArray("tick").size());
    }

    @Test
    void aFewScatteredOutliersHaveNoPeriod() {
        assertFalse(PoseTrace.commonInterval(java.util.Arrays.asList(3L, 10L, 40L, 41L)) != null);
        assertEquals(Long.valueOf(5L), PoseTrace.commonInterval(java.util.Arrays.asList(5L, 10L, 15L, 20L)));
    }
}
