package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.client.ClientDeferredTasks;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.WorldSummary;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Singleplayer world management: list, create, load and leave.
 *
 * <h2>Why not just click the menus</h2>
 *
 * {@link ClientGuiTools} can drive the main menu, and for a one-off that is fine. It is the wrong
 * foundation for anything repeatable. Creating a world by clicking means: click Singleplayer, wait
 * for a screen, click Create New World, type a name, click More World Options, click World Type the
 * right number of times to land on Superflat, click Allow Cheats, click Create — eight screens deep,
 * every step dependent on the last having finished rendering, and every label subject to the
 * language the client happens to be set to.
 *
 * <p>These tools call {@code launchIntegratedServer} directly, which is what those screens do once
 * the clicking is over. A world is then one call with named arguments, it does not care about the
 * client's language, and it cannot half-succeed and leave the client stranded on an intermediate
 * screen.
 *
 * <h2>This is the one place MCMCP writes to disk on its own</h2>
 *
 * Creating a world creates a save directory. That is real, persistent, and not undone by leaving the
 * world, so {@code client_world_create} is marked destructive and refuses to overwrite: a name that
 * collides with an existing save gets a suffix rather than replacing what is there. Deliberately
 * there is no tool to <em>delete</em> a world — the failure mode of getting that wrong is somebody's
 * survival save, and the recovery is nothing.
 */
@SideOnly(Side.CLIENT)
public final class ClientWorldTools {

    /**
     * How long to wait for a queued world-leave to finish. Generous because leaving saves every
     * chunk and shuts the integrated server down, which on a large world is not instant.
     */
    private static final long WORLD_LEAVE_TIMEOUT_MILLIS = 180_000L;

    /** How long to wait for the integrated server to stop before loading the main menu. */
    private static final long SERVER_STOP_WAIT_MILLIS = 120_000L;

    /** How often to re-check whether the integrated server has stopped. */
    private static final long SERVER_STOP_POLL_MILLIS = 10L;

    private ClientWorldTools() {
    }

    public static void register() {
        // Eager, so the event-bus subscription happens on the thread that sets tools up rather
        // than lazily from whichever MCP handler thread first leaves a world.
        ClientDeferredTasks.register();
        registerWorldList();
        registerWorldCreate();
        registerWorldLoad();
        registerWorldLeave();
    }

    private static ISaveFormat saveLoader() {
        return Minecraft.getMinecraft().getSaveLoader();
    }

    private static JsonObject describeSummary(WorldSummary summary) {
        JsonObject json = new JsonObject();
        json.addProperty("folderName", summary.getFileName());
        json.addProperty("displayName", summary.getDisplayName());
        json.addProperty("lastPlayed", summary.getLastTimePlayed());
        json.addProperty("gameMode", summary.getEnumGameType().getName());
        json.addProperty("hardcore", summary.isHardcoreModeEnabled());
        json.addProperty("cheats", summary.getCheatsEnabled());
        json.addProperty("requiresConversion", summary.requiresConversion());
        return json;
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    private static void registerWorldList() {
        McpRegistry.registerTool(McpTool.named("client_world_list")
            .title("List singleplayer worlds")
            .description("List the singleplayer worlds saved on this client, newest first.\n\n"
                + "The 'folderName' of each is what client_world_load takes — not the display name, "
                + "which is not unique and may contain characters the folder does not.")
            .schema(JsonSchema.noArguments())
            .clientOnly()
            .readOnly()
            .handler(context -> {
                JsonObject result = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() throws Exception {
                        List<WorldSummary> summaries = saveLoader().getSaveList();
                        java.util.Collections.sort(summaries);

                        JsonArray worlds = new JsonArray();
                        for (WorldSummary summary : summaries) {
                            worlds.add(describeSummary(summary));
                        }

                        JsonObject json = new JsonObject();
                        json.addProperty("count", worlds.size());
                        json.add("worlds", worlds);
                        return json;
                    }
                });
                return ToolResult.structured(result);
            })
            .build());
    }

    // ------------------------------------------------------------------
    // Creating
    // ------------------------------------------------------------------

    private static void registerWorldCreate() {
        McpRegistry.registerTool(McpTool.named("client_world_create")
            .title("Create a singleplayer world")
            .description("Create a new singleplayer world and load into it.\n\n"
                + "This writes a new save directory and is not undone by leaving the world. An "
                + "existing save with the same name is never overwritten — the folder gets a suffix "
                + "instead, and the reply says which folder was actually used.\n\n"
                + "For testing, 'flat' plus creative plus cheats is usually what you want: flat "
                + "ground makes structures easy to place and photograph, and cheats enable the "
                + "commands that make setup quick. Loading takes a few seconds; poll "
                + "client_gui_state or use client_wait until a world is loaded before acting.")
            .schema(JsonSchema.object()
                .string("name", "Display name for the world. Also the basis of the folder name.")
                .enumeration("worldType", "Terrain generator. Defaults to 'default'.",
                    "default", "flat", "largeBiomes", "amplified", "void")
                .enumeration("gameMode", "Starting game mode. Defaults to 'creative'.",
                    "survival", "creative", "adventure", "spectator")
                .string("seed", "World seed. A number is used directly; any other text is hashed, the "
                    + "same as typing it into the seed box. Omit for a random seed.")
                .bool("generateStructures", "Generate villages, temples and so on. Defaults to true.")
                .bool("allowCheats", "Allow commands. Defaults to true, because almost everything a "
                    + "model does to set a world up is a command.")
                .bool("hardcore", "Hardcore mode. Defaults to false. Forces survival and disables "
                    + "cheats.")
                .required("name")
                .build())
            .clientOnly()
            .destructive()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("World control is disabled by "
                        + "permissions.allowPlayerControl in the MCMCP config.");
                }

                final String name = context.requireString("name").trim();
                if (name.isEmpty()) {
                    return ToolResult.error("'name' cannot be empty.");
                }
                final String worldTypeName = context.getString("worldType", "default");
                final String gameModeName = context.getString("gameMode", "creative");
                final String seedText = context.getString("seed", null);
                final boolean generateStructures = context.getBoolean("generateStructures", true);
                final boolean hardcore = context.getBoolean("hardcore", false);
                final boolean allowCheats = context.getBoolean("allowCheats", true);

                final WorldType worldType = parseWorldType(worldTypeName);
                if (worldType == null) {
                    return ToolResult.error("Unknown worldType '" + worldTypeName + "'.");
                }
                final GameType gameType = parseGameType(gameModeName);
                if (gameType == null) {
                    return ToolResult.error("Unknown gameMode '" + gameModeName + "'.");
                }
                final long seed = parseSeed(seedText);

                // Resolving the folder name reads the save list, so it belongs on the game thread —
                // but it is quick, unlike the launch itself.
                final String folderName = context.onGameThread(new Callable<String>() {
                    @Override
                    public String call() {
                        return uncollidingFolderName(name);
                    }
                });

                final WorldSettings settings = new WorldSettings(
                    seed,
                    hardcore ? GameType.SURVIVAL : gameType,
                    generateStructures,
                    hardcore,
                    worldType);
                if (allowCheats && !hardcore) {
                    settings.enableCommands();
                }

                // Fire-and-forget, deliberately. launchIntegratedServer generates spawn chunks and
                // blocks the client thread for well over the few seconds a scheduled task is allowed
                // — waiting on it reports a timeout for a world that is loading perfectly well, which
                // is worse than not waiting at all. The caller waits on the observable outcome
                // instead, which is what client_wait's 'worldLoaded' is for.
                context.getGameThread().runOnGameThread(new Runnable() {
                    @Override
                    public void run() {
                        // Also tears down any world already loaded, so this doubles as "leave and
                        // start fresh" without the caller having to sequence it.
                        Minecraft.getMinecraft().launchIntegratedServer(folderName, name, settings);
                    }
                });

                JsonObject result = new JsonObject();
                result.addProperty("folderName", folderName);
                result.addProperty("displayName", name);
                result.addProperty("worldType", worldType.getName());
                result.addProperty("gameMode", (hardcore ? GameType.SURVIVAL : gameType).getName());
                result.addProperty("seed", seed);
                result.addProperty("generateStructures", generateStructures);
                result.addProperty("hardcore", hardcore);
                result.addProperty("cheats", allowCheats && !hardcore);
                result.addProperty("loadingStarted", true);

                return ToolResult.text("Creating world '" + name + "' in folder '" + folderName
                    + "'. Loading has started; wait for it with client_wait waitFor 'worldLoaded'.")
                    .withStructured(result);
            })
            .build());
    }

    /**
     * Sanitises a display name into a save folder name that does not collide with an existing save.
     *
     * <p>Mirrors what {@code GuiCreateWorld} does, because a folder name that differs from the one
     * the vanilla screen would have produced is a folder the player cannot find later. The collision
     * loop appends a dash rather than a counter for the same reason: that is vanilla's behaviour.
     */
    private static String uncollidingFolderName(String displayName) {
        String folder = displayName.replaceAll("[\\./\"]", "_");
        // Windows reserves these regardless of the platform the save was made on, and a pack that
        // syncs saves between machines would break on one it could not create.
        for (String reserved : new String[]{"CON", "COM", "PRN", "AUX", "CLOCK$", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"}) {
            if (folder.equalsIgnoreCase(reserved)) {
                folder = "_" + folder + "_";
            }
        }
        if (folder.trim().isEmpty()) {
            folder = "World";
        }
        ISaveFormat saveFormat = saveLoader();
        while (saveFormat.getWorldInfo(folder) != null) {
            folder = folder + "-";
        }
        return folder;
    }

    private static WorldType parseWorldType(String name) {
        String needle = name == null ? "default" : name.trim();
        if ("void".equalsIgnoreCase(needle)) {
            // Not a distinct WorldType in 1.12.2; the void preset is flat with empty layers, which
            // callers reach through the generator options rather than the type.
            return WorldType.FLAT;
        }
        for (WorldType type : WorldType.WORLD_TYPES) {
            if (type != null && type.getName().equalsIgnoreCase(needle)) {
                return type;
            }
        }
        if ("largebiomes".equalsIgnoreCase(needle)) {
            return WorldType.LARGE_BIOMES;
        }
        return null;
    }

    private static GameType parseGameType(String name) {
        if (name == null) {
            return GameType.CREATIVE;
        }
        for (GameType type : GameType.values()) {
            if (type != GameType.NOT_SET && type.getName().equalsIgnoreCase(name.trim())) {
                return type;
            }
        }
        return null;
    }

    /**
     * Turns seed text into a seed the same way the vanilla seed box does: a parsable non-zero long is
     * used directly, anything else is hashed, and blank means random.
     */
    private static long parseSeed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new Random().nextLong();
        }
        String trimmed = text.trim();
        try {
            long parsed = Long.parseLong(trimmed);
            if (parsed != 0L) {
                return parsed;
            }
            return new Random().nextLong();
        }
        catch (NumberFormatException notANumber) {
            return trimmed.hashCode();
        }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private static void registerWorldLoad() {
        McpRegistry.registerTool(McpTool.named("client_world_load")
            .title("Load a singleplayer world")
            .description("Load an existing singleplayer world by its folder name, as listed by "
                + "client_world_list.\n\n"
                + "Any world currently loaded is left first. Loading takes a few seconds and the "
                + "client shows a progress screen while it happens, so wait for a world to be present "
                + "before acting — client_wait with waitFor 'worldLoaded' does exactly that.")
            .schema(JsonSchema.object()
                .string("folderName", "Save folder name, from client_world_list.")
                .required("folderName")
                .build())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("World control is disabled by "
                        + "permissions.allowPlayerControl in the MCMCP config.");
                }

                final String folderName = context.requireString("folderName").trim();

                // Validation first, on the game thread but cheaply; the launch itself is scheduled
                // without waiting, for the same reason as in client_world_create.
                final String displayName = context.onGameThread(new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        ISaveFormat saveFormat = saveLoader();
                        if (saveFormat.getWorldInfo(folderName) == null) {
                            throw new IllegalArgumentException("No world with folder name '"
                                + folderName + "'. Call client_world_list to see what exists.");
                        }
                        for (WorldSummary summary : saveFormat.getSaveList()) {
                            if (summary.getFileName().equals(folderName)) {
                                return summary.getDisplayName();
                            }
                        }
                        return folderName;
                    }
                });

                context.getGameThread().runOnGameThread(new Runnable() {
                    @Override
                    public void run() {
                        // Null settings means "load what is on disk" rather than "create"; this is
                        // the same call GuiWorldSelection makes for an existing save.
                        Minecraft.getMinecraft().launchIntegratedServer(folderName, displayName, null);
                    }
                });

                JsonObject result = new JsonObject();
                result.addProperty("folderName", folderName);
                result.addProperty("displayName", displayName);
                result.addProperty("loadingStarted", true);

                return ToolResult.text("Loading world '" + displayName + "'. Wait for it with "
                    + "client_wait waitFor 'worldLoaded'.").withStructured(result);
            })
            .build());
    }

    // ------------------------------------------------------------------
    // Leaving
    // ------------------------------------------------------------------

    private static void registerWorldLeave() {
        McpRegistry.registerTool(McpTool.named("client_world_leave")
            .title("Leave the current world")
            .description("Disconnect from the current world or server and return to the main menu.\n\n"
                + "Singleplayer worlds are saved on the way out, the same as choosing 'Save and Quit "
                + "to Title'. On a multiplayer server this is a normal disconnect.")
            .schema(JsonSchema.noArguments())
            .clientOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowPlayerControl()) {
                    return ToolResult.error("World control is disabled by "
                        + "permissions.allowPlayerControl in the MCMCP config.");
                }

                JsonObject result = leaveLoadedWorld(context);

                return ToolResult.text(result.get("left").getAsBoolean()
                    ? "Left the world; back at the main menu."
                    : "No world was loaded.")
                    .withStructured(result);
            })
            .build());
    }

    /**
     * Leaves whatever world is loaded and returns to the main menu, saving on the way out.
     *
     * <p>Shared with {@code client_quit}, which leaves before shutting the game down. Quitting must
     * use exactly this sequence rather than a shorter one of its own: both hazards below are
     * invisible until the day they hang a client, and having one implementation is what stops a
     * second caller from rediscovering them.
     *
     * <p>Blocks until the leave has finished, so the caller can report truthfully whether the world
     * was saved.
     *
     * @param context the calling tool's context, used to reach the game thread
     *
     * @return {@code {"left": true, "wasSingleplayer": …}}, or {@code {"left": false, "note": …}} if
     *         no world was loaded
     *
     * @throws Exception if the leave did not finish within {@link #WORLD_LEAVE_TIMEOUT_MILLIS}
     */
    static JsonObject leaveLoadedWorld(ToolContext context) throws Exception {
        JsonObject result = context.onGameThread(new Callable<JsonObject>() {
            @Override
            public JsonObject call() {
                Minecraft mc = Minecraft.getMinecraft();
                JsonObject json = new JsonObject();
                if (mc.world == null) {
                    json.addProperty("left", false);
                    json.addProperty("note", "No world was loaded.");
                    return json;
                }

                json.addProperty("left", true);
                json.addProperty("wasSingleplayer", mc.isSingleplayer());
                return json;
            }
        });

        if (result.get("left").getAsBoolean()) {
            // The leave itself must NOT run from the scheduled-task queue. Minecraft drains
            // that queue while holding its lock, and leaving blocks the client thread until
            // Netty finishes closing the channel -- during which Netty fires
            // ClientDisconnectionFromServerEvent, whose handlers commonly call
            // addScheduledTask and block on that same lock. That deadlocks, and the game
            // hangs on disconnect. Running from the client tick puts this in the same
            // context as the vanilla "Save and Quit to Title" button, which is outside the
            // drain. See ClientDeferredTasks.
            ClientDeferredTasks.runNextTick(new Runnable() {
                @Override
                public void run() {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.world == null) {
                        return;
                    }
                    // sendQuittingDisconnectingPacket is what tells an integrated server to
                    // save and shut down. Skipping it and calling loadWorld(null) alone
                    // leaves the server thread running and the save incomplete.
                    mc.world.sendQuittingDisconnectingPacket();

                    // Then wait for the integrated server to actually be down before
                    // loadWorld(null). IntegratedServer.initiateShutdown, which loadWorld
                    // calls, reads:
                    //
                    //     if (isServerRunning())
                    //     Futures.getUnchecked(this.addScheduledTask(...));
                    //
                    // which races: if the server is still "running" when that check runs
                    // but its thread exits before the task is picked up, the task never
                    // executes and getUnchecked parks the client thread forever. Waiting
                    // here makes isServerRunning() false by the time loadWorld looks, so
                    // the guard skips the await entirely and the race cannot happen.
                    //
                    // The wait is bounded, and the server saves on its own thread without
                    // needing this one, so blocking the tick briefly is safe.
                    IntegratedServer server = mc.getIntegratedServer();
                    if (server != null) {
                        long deadline = System.currentTimeMillis() + SERVER_STOP_WAIT_MILLIS;
                        while (server.isServerRunning()
                            && System.currentTimeMillis() < deadline) {
                            try {
                                Thread.sleep(SERVER_STOP_POLL_MILLIS);
                            }
                            catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }

                    mc.loadWorld(null);
                    mc.displayGuiScreen(new GuiMainMenu());
                }
            }).get(WORLD_LEAVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        return result;
    }
}
