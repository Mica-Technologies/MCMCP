package com.micatechnologies.minecraft.mcmcp.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.mcp.McpContent;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpResource;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

/**
 * MCP resources exposed by both endpoints, plus the server-side ones.
 *
 * <h2>Resources versus tools</h2>
 *
 * The distinction is about who decides and who pays. A tool call is a turn the model spends: it
 * chooses to call it, waits for it, and the result enters the conversation. A resource is something
 * the <em>client</em> can attach, cache and re-read on its own, without the model spending anything.
 *
 * <p>So the rule applied here is: state a model will want as background goes in as a resource, and
 * anything that takes a parameter or has an effect stays a tool. "The mod list" and "the current
 * player roster" are resources — a client can pin them into context once and refresh on change.
 * "Find me diamond ore within 32 blocks" is a tool, because it is a question, not a fact.
 */
public final class CommonResources {

    private CommonResources() {
    }

    public static void register() {
        registerModList();
        registerLogTail();
        registerServerStatus();
        registerPlayerRoster();
    }

    private static void registerModList() {
        McpRegistry.registerResource(McpResource
            .at(McmcpConstants.RESOURCE_SCHEME + "://game/mods")
            .name("loaded-mods")
            .title("Loaded mods")
            .description("Every mod loaded in this game instance, with id, name and version. Determines "
                + "what content and mechanics exist in this world.")
            .mimeType("application/json")
            .reader((context, uri) -> {
                JsonArray mods = new JsonArray();
                for (ModContainer container : Loader.instance().getModList()) {
                    JsonObject mod = new JsonObject();
                    mod.addProperty("id", container.getModId());
                    mod.addProperty("name", container.getName());
                    mod.addProperty("version", container.getDisplayVersion());
                    mods.add(mod);
                }
                JsonObject payload = new JsonObject();
                payload.addProperty("count", mods.size());
                payload.add("mods", mods);
                return Collections.singletonList(
                    McpContent.textResource(uri, "application/json", Json.writePretty(payload)));
            })
            .build());
    }

    /**
     * The end of the game log, as plain text.
     *
     * <p>A resource rather than only a tool because it is the thing a developer wants pinned open
     * while working — a client can re-read it whenever it likes without the model spending a turn on
     * it. The tool version still exists for filtered and deeper reads.
     */
    private static void registerLogTail() {
        McpRegistry.registerResource(McpResource
            .at(McmcpConstants.RESOURCE_SCHEME + "://game/log/latest")
            .name("latest-log")
            .title("Latest game log")
            .description("The last 200 lines of logs/latest.log. Use the game_read_log tool for a "
                + "longer window or a filtered search.")
            .mimeType("text/plain")
            .reader((context, uri) -> {
                if (!McmcpConfig.isAllowLogAccess()) {
                    return Collections.singletonList(McpContent.textResource(uri, "text/plain",
                        "Log access is disabled by permissions.allowLogAccess in the MCMCP config."));
                }
                List<String> lines = McmcpPaths.tail(McmcpPaths.latestLog(), 200, null);
                return Collections.singletonList(
                    McpContent.textResource(uri, "text/plain", String.join("\n", lines)));
            })
            .build());
    }

    private static void registerServerStatus() {
        McpRegistry.registerResource(McpResource
            .at(McmcpConstants.RESOURCE_SCHEME + "://server/status")
            .name("server-status")
            .title("Server status")
            .description("Server identity, tick performance and the state of every loaded dimension.")
            .mimeType("application/json")
            .serverOnly()
            .reader((context, uri) -> {
                JsonObject payload = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        MinecraftServer server = ServerThreadBridge.server();
                        if (server == null) {
                            throw new IllegalStateException("No Minecraft server is running");
                        }
                        JsonObject json = new JsonObject();
                        json.addProperty("motd", server.getMOTD());
                        json.addProperty("dedicated", server.isDedicatedServer());
                        json.addProperty("players", server.getCurrentPlayerCount());
                        json.addProperty("maxPlayers", server.getMaxPlayers());

                        long total = 0L;
                        for (long sample : server.tickTimeArray) {
                            total += sample;
                        }
                        double meanTickMillis =
                            (total / (double) server.tickTimeArray.length) / 1_000_000.0D;
                        json.addProperty("meanTickMillis",
                            Math.round(meanTickMillis * 100.0D) / 100.0D);

                        JsonArray dimensions = new JsonArray();
                        for (WorldServer world : server.worlds) {
                            if (world != null) {
                                dimensions.add(GameJson.world(world));
                            }
                        }
                        json.add("dimensions", dimensions);
                        return json;
                    }
                });
                return Collections.singletonList(
                    McpContent.textResource(uri, "application/json", Json.writePretty(payload)));
            })
            .build());
    }

    private static void registerPlayerRoster() {
        McpRegistry.registerResource(McpResource
            .at(McmcpConstants.RESOURCE_SCHEME + "://server/players")
            .name("player-roster")
            .title("Connected players")
            .description("Every connected player with position, dimension and health. Names from here "
                + "are what the player tools accept.")
            .mimeType("application/json")
            .serverOnly()
            .reader((context, uri) -> {
                JsonObject payload = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        MinecraftServer server = ServerThreadBridge.server();
                        if (server == null) {
                            throw new IllegalStateException("No Minecraft server is running");
                        }
                        JsonArray players = new JsonArray();
                        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                            players.add(GameJson.player(player));
                        }
                        JsonObject json = new JsonObject();
                        json.addProperty("count", players.size());
                        json.add("players", players);
                        return json;
                    }
                });
                return Collections.singletonList(
                    McpContent.textResource(uri, "application/json", Json.writePretty(payload)));
            })
            .build());
    }

    /** Helper for readers that build a single JSON document. */
    static List<JsonObject> singleJson(String uri, JsonObject payload) {
        List<JsonObject> contents = new ArrayList<>(1);
        contents.add(McpContent.textResource(uri, "application/json", Json.writePretty(payload)));
        return contents;
    }
}
