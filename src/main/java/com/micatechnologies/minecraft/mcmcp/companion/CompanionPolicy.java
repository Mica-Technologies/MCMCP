package com.micatechnologies.minecraft.mcmcp.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.mcp.CallFilter;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.Principal;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * What a companion caller may do: which class each tool belongs to, which classes a player has, and
 * which tool arguments must name the caller.
 *
 * <h2>An allowlist, not a blocklist</h2>
 *
 * A tool is served over the companion only if it is named in {@link #CLASSES}. A tool added to MCMCP
 * later is therefore not reachable from a live server until someone decides which class it belongs
 * in. Stopping the server, saving the world, profilers and samplers, log readers, storage expiry and
 * server-wide broadcasts are in no class and are never served.
 *
 * <h2>The caller is the connection</h2>
 *
 * A tool argument that names a player ({@code asPlayer}, {@code player}) is checked against the
 * caller. Naming someone else needs the {@code others} class; leaving {@code asPlayer} out of a
 * command, which on the endpoint means console authority, runs it as the caller unless they hold
 * {@code command.console}.
 *
 * <p>No Minecraft class is named here, so every rule is unit-tested.
 */
public final class CompanionPolicy implements CallFilter {

    /** The permission node for a class: {@code mcmcp.companion.<class>}. */
    public static final String NODE_PREFIX = "mcmcp.companion.";

    /** Every class a player can be granted, in the order they are checked and listed. */
    public static final Set<String> ALL_CLASSES = Collections.unmodifiableSet(new LinkedHashSet<>(
        java.util.Arrays.asList(Principal.CLASS_READ, Principal.CLASS_WRITE, Principal.CLASS_COMMAND,
            Principal.CLASS_LOAD, Principal.CLASS_OTHERS, Principal.CLASS_CONSOLE)));

    /** Tool name to the class that unlocks it. Anything absent is never served. */
    static final Map<String, String> CLASSES = new HashMap<>();

    /** Tool name to the argument in it that names a player. */
    static final Map<String, String> PLAYER_ARGUMENTS = new HashMap<>();

    static {
        for (String read : new String[] {"server_get_block", "server_get_blocks", "server_find_blocks",
            "server_nearby_entities", "server_world_info", "server_list_players", "server_player_state",
            "server_player_inventory", "server_tick_stats", "server_changes_since", "mcmcp_endpoint_info", "game_list_mods",
            "game_health"}) {
            CLASSES.put(read, Principal.CLASS_READ);
        }
        for (String write : new String[] {"server_set_block", "server_set_blocks", "server_undo",
            "server_teleport_player"}) {
            CLASSES.put(write, Principal.CLASS_WRITE);
        }
        for (String command : new String[] {"server_run_command", "server_run_commands", "server_tell_player"}) {
            CLASSES.put(command, Principal.CLASS_COMMAND);
        }
        for (String load : new String[] {"server_keep_loaded", "server_release_loaded"}) {
            CLASSES.put(load, Principal.CLASS_LOAD);
        }
        PLAYER_ARGUMENTS.put("server_run_command", "asPlayer");
        PLAYER_ARGUMENTS.put("server_run_commands", "asPlayer");
        PLAYER_ARGUMENTS.put("server_player_state", "player");
        PLAYER_ARGUMENTS.put("server_player_inventory", "player");
        PLAYER_ARGUMENTS.put("server_teleport_player", "player");
        PLAYER_ARGUMENTS.put("server_tell_player", "player");
    }

    /** The class a tool belongs to, or null when the companion never serves it. */
    @Nullable
    public static String classOf(String toolName) {
        return CLASSES.get(toolName);
    }

    /**
     * The classes a player holds: every class whose node they have. Empty when the companion is off
     * or they are not on the allowlist — both keys are needed, always.
     */
    public static Set<String> grants(boolean enabled, boolean allowlisted, Predicate<String> hasNode) {
        Set<String> granted = new LinkedHashSet<>();
        if (!enabled || !allowlisted) {
            return granted;
        }
        for (String callClass : ALL_CLASSES) {
            if (hasNode.test(NODE_PREFIX + callClass)) {
                granted.add(callClass);
            }
        }
        return granted;
    }

    @Override
    public boolean lists(McpSession session, McpTool tool) {
        Principal principal = session.getPrincipal();
        String callClass = classOf(tool.getName());
        return callClass != null && principal.has(callClass);
    }

    @Override
    @Nullable
    public String admit(McpSession session, McpTool tool, JsonObject arguments) {
        Principal principal = session.getPrincipal();
        String callClass = classOf(tool.getName());
        if (callClass == null) {
            return "'" + tool.getName() + "' is not available through the MCMCP companion.";
        }
        if (!principal.has(callClass)) {
            return "Your companion access on this server does not include '" + callClass + "' calls, "
                + "which '" + tool.getName() + "' is. An operator can grant the " + NODE_PREFIX + callClass
                + " permission node.";
        }
        JsonElement load = arguments.get("load");
        if (load != null && load.isJsonPrimitive() && load.getAsJsonPrimitive().isBoolean() && load.getAsBoolean()
            && !principal.has(Principal.CLASS_LOAD)) {
            return "load: true needs '" + Principal.CLASS_LOAD + "' access, which yours does not include. An "
                + "operator can grant the " + NODE_PREFIX + Principal.CLASS_LOAD + " permission node; or leave "
                + "load out to read and write only chunks that are already loaded.";
        }
        return pinPlayer(principal, tool.getName(), arguments);
    }

    @Override
    public boolean servesResourcesAndPrompts(McpSession session) {
        // Resources include the game log and prompts read world state into text; neither has a class.
        return false;
    }

    /**
     * Makes a player-naming argument name the caller, or refuses when it names someone else and the
     * caller may not.
     */
    @Nullable
    static String pinPlayer(Principal principal, String toolName, JsonObject arguments) {
        String argument = PLAYER_ARGUMENTS.get(toolName);
        if (argument == null) {
            return null;
        }
        String self = principal.getPlayerName();
        JsonElement given = arguments.get(argument);
        boolean named = given != null && given.isJsonPrimitive() && !given.getAsString().trim().isEmpty();
        if (!named) {
            boolean consoleDefault = "server_run_command".equals(toolName) || "server_run_commands".equals(toolName);
            if (consoleDefault && principal.has(Principal.CLASS_CONSOLE)) {
                return null;
            }
            // Absent: the caller is the player meant. For a command that is also what keeps it from
            // running with the console's authority.
            arguments.addProperty(argument, self);
            return null;
        }
        String name = given.getAsString().trim();
        if (self != null && name.toLowerCase(Locale.ROOT).equals(self.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (principal.has(Principal.CLASS_OTHERS)) {
            return null;
        }
        return "'" + argument + "' may only name you (" + self + ") through the MCMCP companion. Naming "
            + "another player needs the " + NODE_PREFIX + Principal.CLASS_OTHERS + " permission node.";
    }
}
