package com.micatechnologies.minecraft.mcmcp.prompts;

import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.mcp.McpPrompt;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompts available on both endpoints.
 *
 * <h2>What a prompt is for here</h2>
 *
 * A prompt is user-initiated: someone picks it out of their client's UI, rather than the model
 * deciding to call it. That makes it the right home for the multi-step workflows that a model would
 * otherwise have to discover by trial — and it means the prompt can arrive pre-loaded with the state
 * the task needs, saving several tool calls before any actual work starts.
 *
 * <p>These deliberately do not tell the model what conclusion to reach. They establish the task, the
 * constraints that are not obvious from the tool list, and the relevant state — and then stop.
 */
public final class CommonPrompts {

    private CommonPrompts() {
    }

    public static void register() {
        registerDiagnoseErrors();
        registerBuildStructure();
    }

    /**
     * Pulls recent errors out of the log and asks for a diagnosis.
     *
     * <p>The one workflow that is genuinely tedious by hand: a Minecraft error is usually one stack
     * trace buried in thousands of lines of mod chatter, and finding it means several filtered log
     * reads. Doing that work up front, at prompt-expansion time, is exactly what prompts are for.
     */
    private static void registerDiagnoseErrors() {
        McpRegistry.registerPrompt(McpPrompt.named("diagnose_errors")
            .title("Diagnose recent errors")
            .description("Collect recent errors and warnings from the game log and investigate what "
                + "caused them.")
            .argument("filter", "Extra substring to search for, e.g. a mod id or a class name. "
                + "Optional.", false)
            .generator(context -> {
                List<String> interesting = new ArrayList<>();
                if (McmcpConfig.isAllowLogAccess()) {
                    // Two passes: the explicit error levels, then anything the caller named. Log4j
                    // writes the level into the line prefix, so a substring match on the bracketed
                    // form is both cheap and accurate enough.
                    interesting.addAll(McmcpPaths.tail(McmcpPaths.latestLog(), 60, "/ERROR]"));
                    interesting.addAll(McmcpPaths.tail(McmcpPaths.latestLog(), 40, "/WARN]"));
                    String extra = context.getString("filter", null);
                    if (extra != null && !extra.isEmpty()) {
                        interesting.addAll(McmcpPaths.tail(McmcpPaths.latestLog(), 60, extra));
                    }
                }

                StringBuilder text = new StringBuilder();
                text.append("Investigate what is going wrong in this Minecraft instance.\n\n");
                text.append("Endpoint: ").append(context.getSide().id()).append('\n');
                text.append("Log access: ")
                    .append(McmcpConfig.isAllowLogAccess() ? "enabled" : "DISABLED in config")
                    .append("\n\n");

                if (interesting.isEmpty()) {
                    text.append("No ERROR or WARN lines were found in the recent log. Use the "
                        + "game_read_log tool to look further back, or with a different filter, "
                        + "before concluding that nothing is wrong.\n");
                }
                else {
                    text.append("Recent error and warning lines from logs/latest.log:\n\n```\n");
                    for (String line : interesting) {
                        text.append(line).append('\n');
                    }
                    text.append("```\n\n");
                    text.append("These lines are the tail of a filtered search, so they are not "
                        + "necessarily in order and a stack trace may be cut off. Use game_read_log "
                        + "with a targeted filter to read the full context around anything that "
                        + "matters, then explain the cause and what would fix it.\n");
                }
                return one(McpPrompt.userMessage(text.toString()));
            })
            .build());
    }

    /**
     * Sets up a build task.
     *
     * <p>Encodes the survey-then-build loop that the block tools are designed around, because a model
     * that has not been told it will otherwise place blocks into terrain it never looked at.
     */
    private static void registerBuildStructure() {
        McpRegistry.registerPrompt(McpPrompt.named("build_structure")
            .title("Build a structure")
            .description("Survey an area and build something in it, using the bulk block tools.")
            .argument("description", "What to build, e.g. 'a 9x9 stone tower with a spiral staircase'.",
                true)
            .argument("location", "Where to build it: coordinates, or a description like 'where the "
                + "player is standing'.", false)
            .generator(context -> {
                String what = context.getString("description", "a structure");
                String where = context.getString("location", null);

                StringBuilder text = new StringBuilder();
                text.append("Build ").append(what).append(" in this Minecraft world.\n\n");
                if (where != null && !where.isEmpty()) {
                    text.append("Location: ").append(where).append("\n\n");
                }

                text.append("Work in this order:\n\n");
                text.append("1. Establish where you are. On the server endpoint use "
                    + "server_list_players and server_player_state; on the client endpoint use "
                    + "client_player_state.\n");
                text.append("2. Survey before you build. Use server_get_blocks over the region you "
                    + "intend to occupy, plus a margin. Read what is already there — terrain, water, "
                    + "existing structures — and do not assume empty space.\n");
                text.append("3. Plan the volume in terms of cuboids. server_set_blocks in 'fill' mode "
                    + "is one call per cuboid; use 'list' mode for the detail that does not fit a "
                    + "box.\n");
                text.append("4. Use replaceOnly='minecraft:air' when adding to an area that already "
                    + "has something in it, so you extend rather than destroy.\n");
                text.append("5. Survey again afterwards and confirm the result matches the plan.\n\n");

                text.append("Constraints worth knowing before you start:\n");
                text.append("- Bulk operations are capped at ")
                    .append(McmcpConfig.getMaxBlockVolume())
                    .append(" blocks per call. Split anything larger across several calls.\n");
                text.append("- Direct block writes need permissions.allowWorldEdits, currently ")
                    .append(McmcpConfig.isAllowWorldEdits() ? "ENABLED" : "DISABLED")
                    .append(". ");
                if (!McmcpConfig.isAllowWorldEdits()) {
                    text.append("With it disabled you cannot write blocks directly — build through "
                        + "commands or player actions, or ask the operator to enable it.");
                }
                text.append('\n');
                text.append("- Direct writes bypass other mods' block-place hooks and any claim or "
                    + "protection system. On a shared world, confirm you are allowed to build where "
                    + "you are building.\n");
                text.append("- The world is 0 to 255 in Y. Writes outside that range are rejected.\n");

                return one(McpPrompt.userMessage(text.toString()));
            })
            .build());
    }

    /** Wraps a single message as a prompt's message list. Public so client-side prompts can reuse it. */
    public static List<com.google.gson.JsonObject> one(com.google.gson.JsonObject message) {
        List<com.google.gson.JsonObject> messages = new ArrayList<>(1);
        messages.add(message);
        return messages;
    }
}
