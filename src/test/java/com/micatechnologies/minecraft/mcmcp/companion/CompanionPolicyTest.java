package com.micatechnologies.minecraft.mcmcp.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.Capability;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.Principal;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Who may call what through the companion, and as whom. */
class CompanionPolicyTest {

    private final CompanionPolicy policy = new CompanionPolicy();

    private static McpTool tool(String name) {
        return McpTool.named(name).description("test").schema(JsonSchema.noArguments())
            .handler(context -> ToolResult.text("ok")).build();
    }

    private static McpSession session(String... classes) {
        McpSession session = new McpSession("s", 0L);
        session.setPrincipal(Principal.companion("d04d5aaa-c6c9-386f-97c8-ff8571aa906a", "Builder",
            new HashSet<>(Arrays.asList(classes))));
        return session;
    }

    @Test
    void bothKeysAreNeededAndANodeGrantsOnlyItsClass() {
        Set<String> granted = CompanionPolicy.grants(true, true,
            node -> node.equals("mcmcp.companion.read") || node.equals("mcmcp.companion.write"));
        assertEquals(new HashSet<>(Arrays.asList("read", "write")), granted);
        assertTrue(CompanionPolicy.grants(true, false, node -> true).isEmpty(), "an op not on the allowlist gets nothing");
        assertTrue(CompanionPolicy.grants(false, true, node -> true).isEmpty(), "a disabled companion grants nothing");
    }

    @Test
    void aToolInNoClassIsNeitherListedNorRunEvenWithEveryGrant() {
        McpSession everything = session("read", "write", "command", "others", "command.console");
        for (String name : new String[] {"server_stop", "server_save_world", "game_read_log", "game_storage",
            "server_broadcast", "game_cpu_sample", "server_profile_ticking", "a_tool_added_next_year"}) {
            assertFalse(policy.lists(everything, tool(name)), name);
            assertNotNull(policy.admit(everything, tool(name), new JsonObject()), name);
        }
        assertFalse(policy.servesResourcesAndPrompts(everything));
    }

    @Test
    void aCallerSeesAndRunsOnlyTheClassesTheyHold() {
        McpSession reader = session("read");
        assertTrue(policy.lists(reader, tool("server_get_blocks")));
        assertFalse(policy.lists(reader, tool("server_set_blocks")));
        String refusal = policy.admit(reader, tool("server_set_blocks"), new JsonObject());
        assertNotNull(refusal);
        assertTrue(refusal.contains("mcmcp.companion.write"), refusal);
        assertNull(policy.admit(reader, tool("server_get_blocks"), new JsonObject()));
    }

    @Test
    void aCommandWithNoPlayerNamedRunsAsTheCallerNotTheConsole() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("command", "fill 0 0 0 1 1 1 stone");
        assertNull(policy.admit(session("command"), tool("server_run_command"), arguments));
        assertEquals("Builder", arguments.get("asPlayer").getAsString());

        JsonObject console = new JsonObject();
        assertNull(policy.admit(session("command", "command.console"), tool("server_run_command"), console));
        assertFalse(console.has("asPlayer"), "console authority is left to whoever holds it");
    }

    @Test
    void namingAnotherPlayerNeedsTheOthersClass() {
        JsonObject other = new JsonObject();
        other.addProperty("player", "SomeoneElse");
        String refusal = policy.admit(session("read"), tool("server_player_inventory"), other);
        assertNotNull(refusal);
        assertTrue(refusal.contains("mcmcp.companion.others"), refusal);

        JsonObject self = new JsonObject();
        self.addProperty("player", "builder");
        assertNull(policy.admit(session("read"), tool("server_player_inventory"), self), "case does not matter");

        JsonObject allowed = new JsonObject();
        allowed.addProperty("asPlayer", "SomeoneElse");
        assertNull(policy.admit(session("command", "others"), tool("server_run_command"), allowed));
        assertEquals("SomeoneElse", allowed.get("asPlayer").getAsString());
    }

    @Test
    void capabilitiesFollowTheGrantsNotTheEndpointSwitches() {
        Principal writer = Principal.companion("id", "Builder", Collections.singleton("write"));
        assertTrue(writer.allows(Capability.WORLD_EDITS));
        assertFalse(writer.allows(Capability.COMMANDS));
        Principal everything = Principal.companion("id", "Builder",
            new HashSet<>(Arrays.asList("read", "write", "command", "others", "command.console")));
        assertFalse(everything.allows(Capability.PROCESS_CONTROL), "stopping the server is never granted");
        assertFalse(everything.allows(Capability.LOG_ACCESS));
    }

    @Test
    void grantsChangeInPlaceSoEverySessionSeesThemAtOnce() {
        Principal principal = Principal.companion("id", "Builder", Collections.singleton("read"));
        McpSession a = new McpSession("a", 0L);
        McpSession b = new McpSession("b", 0L);
        a.setPrincipal(principal);
        b.setPrincipal(principal);
        assertTrue(principal.setClasses(Collections.emptySet()));
        assertFalse(policy.lists(a, tool("server_get_blocks")));
        assertFalse(policy.lists(b, tool("server_get_blocks")));
        assertFalse(principal.setClasses(Collections.emptySet()), "no change is reported as none");
    }

    @Test
    void theJournalNamesACompanionCallerAndOmitsTheEndpoint() {
        assertNull(Principal.ENDPOINT.toJournal());
        JsonObject line = Principal.companion("uuid-1", "Builder", Collections.singleton("read")).toJournal();
        assertEquals("Builder", line.get("player").getAsString());
        assertEquals("companion", line.get("via").getAsString());
    }
}
