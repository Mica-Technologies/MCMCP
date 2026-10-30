package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Telling a server paused on purpose from a stalled one (#158). */
class IdlePauseTest {

    private static StackTraceElement frame(String type, String method) {
        return new StackTraceElement(type, method, null, -1);
    }

    @AfterEach
    void clearDeclaration() {
        IdlePause.declare(null);
    }

    @Test
    void aPauserSleepingInTheMainLoopIsAPauseAndIsNamed() {
        StackTraceElement[] stack = {
            frame("java.lang.Thread", "sleep"),
            frame("net.minecraft.server.MinecraftServer", "handler$zza000$ldfx$pauserFreeze"),
            frame("net.minecraft.server.MinecraftServer", "run"),
            frame("java.lang.Thread", "run")};

        String reason = IdlePause.reason(stack);
        assertNotNull(reason);
        assertTrue(reason.contains("pauserFreeze"), reason);
    }

    @Test
    void theMainLoopAsleepOutsideATickIsAPauseEvenUnnamed() {
        StackTraceElement[] stack = {
            frame("java.lang.Thread", "sleep"),
            frame("net.minecraft.server.MinecraftServer", "run"),
            frame("java.lang.Thread", "run")};

        assertEquals("asleep in the main loop, outside any tick", IdlePause.reason(stack));
    }

    @Test
    void aThreadAsleepInsideATickIsNotAPause() {
        StackTraceElement[] stack = {
            frame("java.lang.Thread", "sleep"),
            frame("com.example.SlowMod", "onTick"),
            frame("net.minecraft.server.MinecraftServer", "func_71190_q"),
            frame("net.minecraft.server.dedicated.DedicatedServer", "func_71190_q"),
            frame("net.minecraft.server.MinecraftServer", "func_71217_p"),
            frame("net.minecraft.server.MinecraftServer", "run")};

        assertNull(IdlePause.reason(stack));
    }

    @Test
    void aBusyOrBlockedThreadIsNotAPause() {
        StackTraceElement[] stack = {
            frame("java.lang.Object", "wait"),
            frame("net.minecraft.server.MinecraftServer", "run")};

        assertNull(IdlePause.reason(stack));
        assertNull(IdlePause.reason(new StackTraceElement[0]));
    }

    @Test
    void aDeclaredPauseWinsWhateverTheStack() {
        IdlePause.declare("LDFX pauser");

        assertEquals("paused by LDFX pauser", IdlePause.reason(null));
    }
}
