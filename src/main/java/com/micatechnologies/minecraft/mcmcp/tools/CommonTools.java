package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.McmcpIdentity;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.game.McmcpProcess;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.protocol.McpProtocol;
import java.io.File;
import java.util.List;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

/**
 * Tools available on both endpoints, plus the registration entry point for the server-side set.
 *
 * <p>What lands here rather than in a side-specific class is anything that reads MCMCP's own state
 * or the game installation, as opposed to the running world: what the endpoint can do, which mods
 * are loaded, what the log says. All of it works identically on a client and a dedicated server.
 */
public final class CommonTools {

    private CommonTools() {
    }

    public static void register() {
        registerEndpointInfo();
        registerModList();
        registerReadLog();
        RegistryDumpTools.register();

        ServerWorldTools.register();
        ServerBuildTools.register();
        ServerPlayerTools.register();
        ServerCommandTools.register();
        ServerLifecycleTools.register();
    }

    /**
     * Reports what this endpoint is and what it is permitted to do.
     *
     * <p>The most valuable tool in the set for a model's first call, and the reason the config's
     * permission switches disable tools with an error rather than by hiding them: a model that can
     * read the permission state up front stops planning around capabilities it does not have,
     * instead of discovering each one through a failed call.
     */
    private static void registerEndpointInfo() {
        McpRegistry.registerTool(McpTool.named("mcmcp_endpoint_info")
            .title("Endpoint info")
            .description("Describe this MCMCP endpoint: which game instance it belongs to, which side "
                + "of the game it runs on, which MCP protocol versions it speaks, and which capability "
                + "groups the operator has enabled or disabled. Call this first — several tool "
                + "families can be turned off in configuration, and this reports which. The instance "
                + "id and name identify which running game you are attached to, and the process id "
                + "and start time identify which running *process*, which is what tells two games "
                + "launched from one directory apart.")
            .schema(JsonSchema.noArguments())
            .readOnly()
            .closedWorld()
            .offGameThread()
            .handler(context -> {
                JsonObject info = new JsonObject();
                info.addProperty("mod", McmcpConstants.MOD_NAME);
                info.addProperty("modVersion", McmcpConstants.MOD_VERSION);
                info.addProperty("minecraftVersion", "1.12.2");
                info.addProperty("side", context.getSide().id());
                info.addProperty("gameAvailable", context.getGameThread().isAvailable());

                // Which game this is. Reported even on a direct HTTP connection, where the caller
                // already knows, because the alternative is a model that can only tell instances
                // apart when it happens to have come through an orchestrator. The secret is not
                // here and must never be: this result goes into a model's context.
                McmcpIdentity identity = McmcpConfig.identity();
                JsonObject instance = new JsonObject();
                instance.addProperty("id", identity.getInstanceId());
                instance.addProperty("name", identity.getInstanceName());
                info.add("instance", instance);

                // Which *process* this is, which the instance id deliberately does not say: the id
                // lives in the config file, so two games launched from one directory share it. When
                // a stale game is still holding the port, these three fields are the only thing in
                // MCMCP's whole surface that tells the two apart -- and 'startedAt' is what makes
                // "this endpoint predates my last build" answerable without a process listing.
                info.add("process", McmcpProcess.toJson());

                JsonArray protocols = new JsonArray();
                for (String version : McpProtocol.supportedVersions()) {
                    protocols.add(version);
                }
                info.add("supportedProtocolVersions", protocols);
                info.addProperty("negotiatedProtocolVersion", context.getSession().getProtocolVersion());

                JsonObject permissions = new JsonObject();
                permissions.addProperty("commands", McmcpConfig.isAllowCommands());
                permissions.addProperty("playerControl", McmcpConfig.isAllowPlayerControl());
                permissions.addProperty("inventoryChanges", McmcpConfig.isAllowInventoryChanges());
                permissions.addProperty("worldEdits", McmcpConfig.isAllowWorldEdits());
                permissions.addProperty("screenshots", McmcpConfig.isAllowScreenshots());
                permissions.addProperty("logAccess", McmcpConfig.isAllowLogAccess());
                permissions.addProperty("chat", McmcpConfig.isAllowChat());
                permissions.addProperty("processControl", McmcpConfig.isAllowProcessControl());
                info.add("permissions", permissions);

                JsonObject limits = new JsonObject();
                limits.addProperty("maxScanRadius", McmcpConfig.getMaxScanRadius());
                limits.addProperty("maxInputTicks", McmcpConfig.getMaxInputTicks());
                limits.addProperty("maxLogLines", McmcpConfig.getMaxLogLines());
                info.add("limits", limits);

                return ToolResult.structured(info);
            })
            .build());
    }

    /**
     * Lists loaded mods.
     *
     * <p>Not trivia. On a modded instance, "can I craft this", "why is this block here" and "what
     * does this item do" all depend on which mods are present, and a model that knows the pack can
     * reason about content it has never seen a tool for.
     */
    private static void registerModList() {
        McpRegistry.registerTool(McpTool.named("game_list_mods")
            .title("List loaded mods")
            .description("List every mod loaded in this game instance, with id, name and version. Use "
                + "this to understand what content and mechanics exist in the current modpack.")
            .schema(JsonSchema.noArguments())
            .readOnly()
            .closedWorld()
            .offGameThread()
            .handler(context -> {
                JsonArray mods = new JsonArray();
                for (ModContainer container : Loader.instance().getModList()) {
                    JsonObject mod = new JsonObject();
                    mod.addProperty("id", container.getModId());
                    mod.addProperty("name", container.getName());
                    mod.addProperty("version", container.getDisplayVersion());
                    mods.add(mod);
                }
                JsonObject result = new JsonObject();
                result.addProperty("count", mods.size());
                result.add("mods", mods);
                return ToolResult.structured(result);
            })
            .build());
    }

    /**
     * Tails the game log.
     *
     * <p>The single most useful debugging tool in the set. A crash, a mod's error message, a failed
     * command and a mixin that did not apply all end up here and nowhere a model could otherwise
     * see them.
     */
    private static void registerReadLog() {
        McpRegistry.registerTool(McpTool.named("game_read_log")
            .title("Read game log")
            .description("Read the tail of the game's log file (logs/latest.log). Use this to diagnose "
                + "errors, confirm that an action had an effect, or read output that never reached the "
                + "chat window. Supports a case-insensitive substring filter, which searches further "
                + "back through the file than an unfiltered read.")
            .schema(JsonSchema.object()
                .integer("lines", "How many log lines to return, newest last.", 1, 5000)
                .string("filter", "Case-insensitive substring; only matching lines are returned.")
                .string("file", "Log file name inside the logs/ directory. Defaults to latest.log. "
                    + "Path separators are rejected.")
                .build())
            .readOnly()
            .closedWorld()
            .offGameThread()
            .handler(context -> {
                if (!McmcpConfig.isAllowLogAccess()) {
                    return ToolResult.error("Log access is disabled by permissions.allowLogAccess in the "
                        + "MCMCP config.");
                }

                int lineCount = context.getBoundedInt("lines", 100, 1, McmcpConfig.getMaxLogLines());
                String filter = context.getString("filter", null);
                String fileName = context.getString("file", "latest.log");

                // Reject any path syntax outright rather than trying to sanitise it. This tool takes
                // a caller-supplied file name and reads it off disk; "logs/../../.ssh/id_rsa" is the
                // obvious attack and a name-only rule is the version of the check that cannot be
                // subtly wrong.
                if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
                    return ToolResult.error("The 'file' argument must be a plain file name inside the "
                        + "logs directory, with no path separators.");
                }

                File logFile = new File(McmcpPaths.logsDirectory(), fileName);
                if (!logFile.isFile()) {
                    return ToolResult.error("No log file named '" + fileName + "' exists in "
                        + McmcpPaths.logsDirectory().getAbsolutePath());
                }

                List<String> lines = McmcpPaths.tail(logFile, lineCount, filter);

                // Metadata only. The lines themselves are the text block and nothing else, because
                // repeating them here made every log read cost exactly twice what it needed to —
                // measured at 2.0x for 50, 100 and 300 lines alike — for a JSON array whose every
                // element was a string already present, verbatim, a few hundred bytes earlier.
                JsonObject result = new JsonObject();
                result.addProperty("file", logFile.getAbsolutePath());
                result.addProperty("returnedLines", lines.size());
                if (filter != null && !filter.isEmpty()) {
                    result.addProperty("filter", filter);
                }

                // Text form rather than the JSON ToolResult.structured would produce: a log excerpt
                // is read as text, and JSON-escaping every line makes it materially harder to read
                // for no gain.
                return ToolResult.text(String.join("\n", lines)).withStructured(result);
            })
            .build());
    }
}
