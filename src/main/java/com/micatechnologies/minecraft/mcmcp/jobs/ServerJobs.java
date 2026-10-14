package com.micatechnologies.minecraft.mcmcp.jobs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.companion.server.CompanionServer;
import com.micatechnologies.minecraft.mcmcp.game.Estop;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpc;
import com.micatechnologies.minecraft.mcmcp.protocol.McpProtocol;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

/**
 * The server's half of jobs and the emergency stop: the world folder they are kept in, the
 * notifications that carry them to clients and the orchestrator, and the chat line a stop is
 * announced with.
 *
 * <p>Job and stop changes go out as MCP log messages ({@code mcmcp.jobs}, {@code mcmcp.estop}) to every
 * session on the server endpoint, and a job's also to its owner's companion sessions, so a script or
 * the orchestrator sees them without polling.
 */
public final class ServerJobs {

    private static final String ESTOP_FILE = "mcmcp-estop.json";

    private static boolean registered;
    @Nullable
    private static volatile File worldFolder;

    private ServerJobs() {
    }

    public static synchronized void serverStarting(MinecraftServer server) {
        File folder = server.getEntityWorld().getSaveHandler().getWorldDirectory();
        worldFolder = folder;
        if (!registered) {
            registered = true;
            JobManager.addListener(ServerJobs::announce);
            Estop.addListener(ServerJobs::estopChanged);
        }
        JobManager.serverStarting(folder, new ServerThreadBridge(), McmcpConfig.getGameThreadTimeoutMillis());
        Estop.Stop saved = readEstop(folder);
        if (saved != null) {
            Estop.restore(saved);
            Mcmcp.LOGGER.warn("MCMCP is still emergency-stopped from before the restart: " + saved.by + " ("
                + saved.reason + "). /mcmcp estop reset lifts it.");
        }
    }

    public static synchronized void serverStopping() {
        JobManager.serverStopping();
        Estop.clearLocal();
        worldFolder = null;
    }

    private static void announce(Job job) {
        JsonObject data = new JsonObject();
        JsonObject status = job.toJson(false);
        data.addProperty("message", "Job " + job.id + (job.label == null ? "" : " (" + job.label + ")") + ": "
            + job.state().id() + ", " + status.get("percent").getAsString() + "%");
        data.add("job", status);
        JsonObject notification = notification("mcmcp.jobs", "info", data);
        for (McpEndpoint endpoint : Mcmcp.allEndpoints()) {
            if (!endpoint.getSide().isClient()) {
                endpoint.getSessions().broadcast(notification);
            }
        }
        if (job.ownerKey.startsWith("player:")) {
            try {
                CompanionServer.notifyPlayer(UUID.fromString(job.ownerKey.substring("player:".length())), notification);
            }
            catch (IllegalArgumentException e) {
                // Not a player key after all.
            }
        }
    }

    private static void estopChanged(@Nullable Estop.Stop now, @Nullable Estop.Stop before) {
        boolean local = now != null ? now.source == Estop.Source.LOCAL : before != null && before.source == Estop.Source.LOCAL;
        if (!local) {
            return;
        }
        File folder = worldFolder;
        if (folder != null) {
            writeEstop(folder, now);
        }
        CompanionServer.broadcastEstop(now);
        JsonObject data = new JsonObject();
        data.addProperty("message", now == null ? "MCMCP emergency stop lifted." : Estop.refusal());
        data.addProperty("active", now != null);
        if (now != null) {
            data.add("estop", now.toJson());
        }
        JsonObject notification = notification("mcmcp.estop", now == null ? "notice" : "alert", data);
        for (McpEndpoint endpoint : Mcmcp.allEndpoints()) {
            endpoint.getSessions().broadcast(notification);
        }
        final MinecraftServer server = ServerThreadBridge.server();
        if (server != null) {
            final String line = now == null ? "MCMCP emergency stop lifted. Paused jobs stay paused until resumed."
                : "MCMCP EMERGENCY STOP by " + now.by + ": " + now.reason;
            server.addScheduledTask(() -> server.getPlayerList().sendMessage(new TextComponentString(
                (now == null ? TextFormatting.GREEN : TextFormatting.RED) + "[MCMCP] " + line)));
        }
    }

    private static JsonObject notification(String logger, String level, JsonObject data) {
        JsonObject params = new JsonObject();
        params.addProperty("level", level);
        params.addProperty("logger", logger);
        params.add("data", data);
        return JsonRpc.notification(McpProtocol.NOTIFICATION_MESSAGE, params);
    }

    @Nullable
    private static Estop.Stop readEstop(File folder) {
        File file = new File(folder, ESTOP_FILE);
        if (!file.isFile()) {
            return null;
        }
        try {
            JsonElement parsed = Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            return parsed != null && parsed.isJsonObject() && parsed.getAsJsonObject().has("by")
                ? Estop.Stop.fromJson(parsed.getAsJsonObject(), Estop.Source.LOCAL) : null;
        }
        catch (Exception e) {
            Mcmcp.LOGGER.warn("MCMCP could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static void writeEstop(File folder, @Nullable Estop.Stop stop) {
        File file = new File(folder, ESTOP_FILE);
        try {
            if (stop == null) {
                Files.deleteIfExists(file.toPath());
            }
            else {
                Files.write(file.toPath(), Json.write(stop.toJson()).getBytes(StandardCharsets.UTF_8));
            }
        }
        catch (Exception e) {
            Mcmcp.LOGGER.warn("MCMCP could not save the emergency stop to " + file + ": " + e.getMessage());
        }
    }
}
