package com.micatechnologies.minecraft.mcmcp.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
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
 * in. Stopping the server, saving the world, the heap histogram, changing log levels, storage expiry and
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
            Principal.CLASS_LOAD, Principal.CLASS_USE, Principal.CLASS_PROFILE, Principal.CLASS_LOGS,
            Principal.CLASS_OTHERS,
            Principal.CLASS_CONSOLE)));

    /** Tool name to the class that unlocks it. Anything absent is never served. */
    static final Map<String, String> CLASSES = new HashMap<>();

    /** Tool name to the argument in it that names a player. */
    static final Map<String, String> PLAYER_ARGUMENTS = new HashMap<>();

    static {
        for (String read : new String[] {"server_get_block", "server_get_blocks", "server_find_blocks",
            "server_nearby_entities", "server_world_info", "server_list_players", "server_player_state",
            "server_player_inventory", "server_tick_stats", "server_changes_since", "server_watch_blocks", "server_wait_tick", "server_get_tile_entities", "server_wire_connections", "server_get_biomes", "server_count_blocks", "mcmcp_endpoint_info", "game_list_mods",
            "game_health", "game_block_states", "server_run_test", "server_job_submit", "server_job_status", "server_job_list", "server_job_cancel",
            "server_job_pause", "server_job_resume", "server_estop", "server_get_entity_nbt", "server_verify", "server_check_enclosure", "server_plan_view",
            "server_check_walk", "server_refresh_chunks", "game_events_wait"}) {
            CLASSES.put(read, Principal.CLASS_READ);
        }
        for (String write : new String[] {"server_set_block", "server_set_blocks", "server_undo", "server_relight", "server_apply_ops",
            "server_teleport_player", "server_remove_entities", "server_chisel_block", "server_set_entity_nbt",
            "server_target", "server_repair", "server_place_doors", "server_place_railing",
            "server_build", "server_build_stair", "server_reload_tile_entities", "server_copy_structure"}) {
            CLASSES.put(write, Principal.CLASS_WRITE);
        }
        for (String command : new String[] {"server_run_command", "server_run_commands", "server_tell_player"}) {
            CLASSES.put(command, Principal.CLASS_COMMAND);
        }
        for (String load : new String[] {"server_keep_loaded", "server_release_loaded", "server_pregen_chunks"}) {
            CLASSES.put(load, Principal.CLASS_LOAD);
        }
        for (String use : new String[] {"server_use_item_on_block", "server_use_item_on_blocks", "server_activate_block",
            "server_player_lease"}) {
            CLASSES.put(use, Principal.CLASS_USE);
        }
        // What is eating a live server's tick could only be answered with Spark, uploading the profile
        // to a third-party service and decoding its protobuf by hand (#100).
        for (String profile : new String[] {"game_cpu_sample", "server_profile_ticking", "server_profile_sections",
            "server_census"}) {
            CLASSES.put(profile, Principal.CLASS_PROFILE);
        }
        // Why the server dropped a connection is written in its log and nowhere else (#195). Reading
        // only: raising a logger's level is not served.
        CLASSES.put("game_read_log", Principal.CLASS_LOGS);
        PLAYER_ARGUMENTS.put("server_run_command", "asPlayer");
        PLAYER_ARGUMENTS.put("server_run_commands", "asPlayer");
        PLAYER_ARGUMENTS.put("server_player_state", "player");
        PLAYER_ARGUMENTS.put("server_player_inventory", "player");
        PLAYER_ARGUMENTS.put("server_teleport_player", "player");
        PLAYER_ARGUMENTS.put("server_tell_player", "player");
        PLAYER_ARGUMENTS.put("server_use_item_on_block", "player");
        PLAYER_ARGUMENTS.put("server_use_item_on_blocks", "player");
        PLAYER_ARGUMENTS.put("server_activate_block", "player");
        PLAYER_ARGUMENTS.put("server_player_lease", "player");
        PLAYER_ARGUMENTS.put("server_refresh_chunks", "player");
    }

    /** The config key of the cap on one player's running calls, as a refusal's {@code busy.limit} names it. */
    public static final String LIMIT_CONCURRENT_CALLS = "companion.maxConcurrentCalls";

    /**
     * Short calls that put something back or stop something: a player's return teleport, their lease,
     * a chunk hold's release, the emergency stop, a job's cancel or pause. They are counted apart from
     * the calls that do the work, with an allowance of the same size, so a player whose long calls
     * fill {@code companion.maxConcurrentCalls} can still be put back where they were (#194): a
     * screenshot's return teleport was refused by the cap, and left the player at the camera.
     */
    static final Set<String> CONTROL_CALLS = Collections.unmodifiableSet(new java.util.HashSet<>(
        java.util.Arrays.asList("server_teleport_player", "server_player_lease", "server_release_loaded",
            "server_estop", "server_job_cancel", "server_job_pause")));

    /** Whether a tool's calls are counted under the control allowance, not with the working calls. */
    public static boolean isControlCall(@Nullable String toolName) {
        return toolName != null && CONTROL_CALLS.contains(toolName);
    }

    /**
     * Reads of one place or one number that load nothing: a block, a few tile entities by position, a
     * player's state, the tick rate, a job's status. They are counted apart, with an allowance of the
     * same size, so a monitoring loop polling one signal stays responsive while a player's working
     * calls fill {@code companion.maxConcurrentCalls} (#210): with several agents busy, a poll of one
     * tile entity got a sample every 12 s, each refusal costing a retry sleep.
     */
    static final Set<String> PROBE_CALLS = Collections.unmodifiableSet(new java.util.HashSet<>(
        java.util.Arrays.asList("server_get_block", "server_get_tile_entities", "server_player_state",
            "server_tick_stats", "server_job_status", "server_job_list", "server_list_players",
            "mcmcp_endpoint_info")));

    /** The most exact positions a {@code server_get_tile_entities} probe may name. */
    public static final int MAX_PROBE_POSITIONS = 4;

    /**
     * Whether a call is a probe, counted under the probe allowance: a {@link #PROBE_CALLS} tool that
     * loads no chunk, and for {@code server_get_tile_entities} only a read of at most
     * {@link #MAX_PROBE_POSITIONS} exact positions, never a box.
     */
    public static boolean isProbeCall(@Nullable String toolName, @Nullable JsonObject arguments) {
        if (toolName == null || !PROBE_CALLS.contains(toolName)) {
            return false;
        }
        if (arguments != null && Json.getBoolean(arguments, "load", false)) {
            return false;
        }
        if ("server_get_tile_entities".equals(toolName)) {
            JsonArray positions = arguments == null ? null : Json.getArray(arguments, "positions");
            return positions != null && positions.size() > 0 && positions.size() <= MAX_PROBE_POSITIONS;
        }
        return true;
    }

    /**
     * Calls that wait on the world and may sit for minutes doing nothing: an event subscription's
     * long-poll (#201). They run on their own threads under their own small cap, so a player's open
     * subscription neither takes one of the four workers every player shares nor counts against
     * {@code companion.maxConcurrentCalls}.
     */
    static final Set<String> WAIT_CALLS = Collections.singleton("game_events_wait");

    /** How many waits one player may hold open at once, every agent of theirs counted together. */
    public static final int MAX_WAITS_PER_PLAYER = 2;

    public static boolean isWaitCall(@Nullable String toolName) {
        return toolName != null && WAIT_CALLS.contains(toolName);
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
        return admit(session.getPrincipal(), tool.getName(), arguments);
    }

    /**
     * The whole admission of one call by a companion caller: its class, {@code load}, and whom it may
     * name. Every way a companion caller can make a tool run goes through this, not only the
     * dispatcher: a step of {@code server_run_test} and a job's part once checked the class alone, so
     * a command step with no {@code asPlayer} ran with the console's authority, and a step could name
     * another player or load chunks, without the classes that allow those.
     *
     * @return the refusal, or null when the call may run; {@code arguments} may have the caller's own
     *         name added to it
     */
    @Nullable
    public static String admit(Principal principal, String toolName, JsonObject arguments) {
        String callClass = classOf(toolName);
        if (callClass == null) {
            return "'" + toolName + "' is not available through the MCMCP companion.";
        }
        if (!principal.has(callClass)) {
            return "Your companion access on this server does not include '" + callClass + "' calls, "
                + "which '" + toolName + "' is. An operator can grant the " + NODE_PREFIX + callClass
                + " permission node.";
        }
        // Read as the tools read it: they take the string "true" for the boolean, so a check for a JSON
        // boolean alone let that spelling load chunks without the class.
        if (com.micatechnologies.minecraft.mcmcp.json.Json.getBoolean(arguments, "load", false)
            && !principal.has(Principal.CLASS_LOAD)) {
            return "load: true needs '" + Principal.CLASS_LOAD + "' access, which yours does not include. An "
                + "operator can grant the " + NODE_PREFIX + Principal.CLASS_LOAD + " permission node; or leave "
                + "load out to read and write only chunks that are already loaded.";
        }
        return pinPlayer(principal, toolName, arguments);
    }

    /** Whether {@code toolName} takes an argument naming the player it acts as or on. */
    public static boolean namesPlayer(String toolName) {
        return PLAYER_ARGUMENTS.containsKey(toolName);
    }

    /**
     * What to tell a companion caller whose command ran as their own player because it named nobody:
     * why, and what would make it run as the console (#217). Null for anyone else. Agents sharing one
     * player took "ran as the player" for a fault of the tool and sent world edits through it all day.
     */
    @Nullable
    public static String ranAsSelfNote(Principal principal, @Nullable String asPlayer) {
        if (!principal.isCompanion() || principal.has(Principal.CLASS_CONSOLE) || asPlayer == null
            || principal.getPlayerName() == null || !asPlayer.equalsIgnoreCase(principal.getPlayerName())) {
            return null;
        }
        return "Through the companion a command runs as you, the connected player, unless an operator grants "
            + NODE_PREFIX + Principal.CLASS_CONSOLE + "; with that node a command with no asPlayer runs as the "
            + "server console.";
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
