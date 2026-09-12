package com.micatechnologies.minecraft.mcmcp.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** What an endpoint is able to say about the process it is running inside. */
class McmcpProcessTest {

    @Test
    void reportsTheProcessIdOfTheJvmItIsRunningIn() {
        // The whole point of the field: it is the only thing that differs between two games launched
        // from one directory, one of which is holding the port and answering for the other.
        assertTrue(McmcpProcess.pid() > 0L,
            "a mainstream JVM names its pid in RuntimeMXBean.getName()");
    }

    @Test
    void formatsStartTimeAsIso8601InUtcSoTwoMachinesCanBeCompared() {
        // A local-time stamp is a stamp that cannot be compared with one from another machine, and
        // this is read straight out of a roster row by people and models alike.
        assertEquals("2026-09-11T08:14:02Z", McmcpProcess.formatIso(1789114442000L));
    }

    @Test
    void reportsAStartTimeInThePastRatherThanASystemClockReading() {
        long now = System.currentTimeMillis();
        assertTrue(McmcpProcess.startedAtMillis() <= now, "the JVM started before this assertion ran");
        assertTrue(McmcpProcess.uptimeSeconds() >= 0L, "uptime cannot be negative");
    }

    @Test
    void reportsTheSameShapeEverywhereItIsEmbedded() {
        // Every tool that carries process identity carries this same object. Two spellings of three
        // facts is how a caller ends up comparing 'pid' against 'processId' and concluding they are
        // different endpoints.
        JsonObject json = McmcpProcess.toJson();

        assertEquals(McmcpProcess.pid(), json.get("pid").getAsLong());
        assertTrue(json.has("startedAt"));
        assertTrue(json.has("uptimeSeconds"));
    }
}
