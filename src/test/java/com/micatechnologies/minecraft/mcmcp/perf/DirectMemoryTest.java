package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** The direct-buffer ceiling, which has no public API and so is read from the JVM's arguments. */
class DirectMemoryTest {

    @Test
    void theFlagIsReadInEverySizeUnitTheJvmAccepts() {
        assertEquals(Long.valueOf(1048576L), DirectMemory.parseSize("1048576"));
        assertEquals(Long.valueOf(512L << 10), DirectMemory.parseSize("512k"));
        assertEquals(Long.valueOf(512L << 20), DirectMemory.parseSize("512M"));
        assertEquals(Long.valueOf(2L << 30), DirectMemory.parseSize("2g"));
    }

    @Test
    void theLastOccurrenceOfTheFlagWinsAsItDoesForTheJvm() {
        assertEquals(Long.valueOf(1L << 30), DirectMemory.parseMaxDirectMemoryFlag(Arrays.asList(
            "-Xmx4G", "-XX:MaxDirectMemorySize=256m", "-XX:MaxDirectMemorySize=1g")));
    }

    @Test
    void noFlagMeansTheDefaultIsInEffectNotZero() {
        assertNull(DirectMemory.parseMaxDirectMemoryFlag(Collections.singletonList("-Xmx4G")));
        assertNull(DirectMemory.parseMaxDirectMemoryFlag(Collections.singletonList(
            "-XX:MaxDirectMemorySize=lots")));
    }

    @Test
    void theReportSaysHowMuchIsUsedAndWhereTheCeilingCameFrom() {
        JsonObject json = DirectMemory.toJson();
        assertTrue(json.has("directUsedMb"), json.toString());
        assertTrue(json.has("directMaxMb"), json.toString());
        assertTrue(json.has("directMaxFrom"), json.toString());
    }
}
