package com.micatechnologies.minecraft.mcmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import org.junit.jupiter.api.Test;

/** Letting go is allowed through the emergency stop; taking hold is not. */
class ReleaseDuringStopTest {

    private static JsonObject args(String json) {
        return Json.parse(json).getAsJsonObject();
    }

    @Test
    void releasingKeysOrOnesOwnInputLockPassesTheStop() {
        assertTrue(McpDispatcher.isRelease("client_key", args("{\"release\":true}")));
        assertTrue(McpDispatcher.isRelease("client_input_lock", args("{\"locked\":false}")));
    }

    @Test
    void pressingLockingOrForcingAnothersLockDoesNot() {
        assertFalse(McpDispatcher.isRelease("client_key", args("{\"key\":\"forward\",\"ticks\":20}")));
        assertFalse(McpDispatcher.isRelease("client_key", args("{\"release\":false}")));
        assertFalse(McpDispatcher.isRelease("client_input_lock", args("{\"locked\":true}")));
        assertFalse(McpDispatcher.isRelease("client_input_lock", args("{}")));
        assertFalse(McpDispatcher.isRelease("client_input_lock", args("{\"locked\":false,\"force\":true}")));
        assertFalse(McpDispatcher.isRelease("client_input_lock", args("{\"op\":\"wait\",\"locked\":false}")));
        assertFalse(McpDispatcher.isRelease("client_move", args("{\"release\":true}")));
    }
}
