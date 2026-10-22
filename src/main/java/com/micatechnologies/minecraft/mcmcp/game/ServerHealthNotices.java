package com.micatechnologies.minecraft.mcmcp.game;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolActivity;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpc;
import com.micatechnologies.minecraft.mcmcp.protocol.McpProtocol;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import javax.annotation.Nullable;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.server.SPacketChat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Tells operators in chat, and every MCP session on the server endpoint, when the server's health
 * changes (#133): throttled in yellow, paused or stalled in red, and back to OK in green, at most once
 * per level per {@code health.alertIntervalSeconds} ({@link HealthAlerts} decides when).
 *
 * <h2>Reaching ops while the server thread is stuck</h2>
 *
 * Chat normally goes out from the server thread, and during a stall that thread is the one thing that
 * cannot send it. So the server thread keeps a list of the operators' connections, refreshed once a
 * second while it ticks, and a delivery thread of this class's own sends to those: a connection's
 * {@code sendPacket} hands the packet to netty's event loop and never waits for the server thread.
 * Best effort: an operator who joined during the stall is not on the list yet. The list is read and
 * nothing else; no world state is touched off the server thread, and the watchdog never waits on any
 * of this.
 *
 * <p>The MCP notification is {@code notifications/message} with logger {@code mcmcp.health}, as the
 * emergency stop sends {@code mcmcp.estop}: {@code {"message": ..., "health": {...}}}, the latter as
 * {@code game_health}'s {@code serverHealth}.
 */
public final class ServerHealthNotices {

    private static final HealthAlerts ALERTS = new HealthAlerts();

    private static final ExecutorService DELIVERY = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "MCMCP-health-notices");
        thread.setDaemon(true);
        return thread;
    });

    /** The operators' connections as of the last tick that refreshed them. Written by the server thread. */
    private static volatile List<NetHandlerPlayServer> operators = Collections.emptyList();

    private static boolean registered;
    private static int ticks;

    private ServerHealthNotices() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerLoad.addListener((now, before) -> poll(System.currentTimeMillis()));
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    public static void serverStopping() {
        ALERTS.reset();
        operators = Collections.emptyList();
    }

    /**
     * Announces the current level if {@link HealthAlerts} says it is time. Any thread, never blocks: the
     * watchdog calls it every second, the server thread on each change and once a second.
     */
    public static void poll(long nowMillis) {
        if (ServerThreadBridge.server() == null) {
            return;
        }
        ServerLoad.Level level = ALERTS.poll(ServerLoad.level(), nowMillis,
            McmcpConfig.getHealthAlertIntervalSeconds() * 1000L);
        if (level == null) {
            return;
        }
        final String text = HealthAlerts.message(level, ServerLoad.meanMspt(), ServerLoad.realTps(), ServerLoad.heapPercent(),
            ServerLoad.stalledForMillis() / 1000L, topCaller(nowMillis),
            level == ServerLoad.Level.STALLED ? ServerLoad.lastStallDump() : null);
        final JsonObject health = ServerLoad.toJson();
        try {
            DELIVERY.execute(() -> deliver(level, text, health));
        }
        catch (RejectedExecutionException e) {
            // Shutting down: nobody left to tell.
        }
    }

    /** Delivery thread. */
    private static void deliver(ServerLoad.Level level, String text, JsonObject health) {
        JsonObject data = new JsonObject();
        data.addProperty("message", text);
        data.add("health", health);
        JsonObject params = new JsonObject();
        params.addProperty("level", level == ServerLoad.Level.OK ? "notice"
            : level == ServerLoad.Level.THROTTLED ? "warning" : level == ServerLoad.Level.PAUSED ? "error" : "critical");
        params.addProperty("logger", "mcmcp.health");
        params.add("data", data);
        JsonObject notification = JsonRpc.notification(McpProtocol.NOTIFICATION_MESSAGE, params);
        for (McpEndpoint endpoint : Mcmcp.allEndpoints()) {
            if (!endpoint.getSide().isClient()) {
                try {
                    endpoint.getSessions().broadcast(notification);
                }
                catch (RuntimeException e) {
                    Mcmcp.LOGGER.debug("MCMCP could not tell sessions of a health change", e);
                }
            }
        }
        if (!McmcpConfig.isHealthNotifyOps()) {
            return;
        }
        TextFormatting colour = level == ServerLoad.Level.OK ? TextFormatting.GREEN
            : level == ServerLoad.Level.THROTTLED ? TextFormatting.YELLOW : TextFormatting.RED;
        TextComponentString line = new TextComponentString(text);
        line.getStyle().setColor(colour);
        for (NetHandlerPlayServer connection : operators) {
            try {
                connection.sendPacket(new SPacketChat(line));
            }
            catch (Throwable t) {
                // A connection closing as this ran; the others still get it.
            }
        }
    }

    /**
     * Who is spending MCMCP's share of the tick, from the task pump, as "who (7.2 ms/tick)"; failing
     * that — during a stall nothing is spent — the server endpoint's longest-running call, as
     * "who: tool (N s)"; or null when nothing runs.
     */
    @Nullable
    private static String topCaller(long nowMillis) {
        String spending = ServerTaskPump.topCaller();
        if (spending != null && ServerLoad.level() != ServerLoad.Level.STALLED) {
            return spending;
        }
        ToolActivity.RunningCall oldest = null;
        for (ToolActivity.RunningCall call : ToolActivity.running()) {
            if (call.getSide() == McmcpSide.SERVER && (oldest == null || call.getStartedMillis() < oldest.getStartedMillis())) {
                oldest = call;
            }
        }
        if (oldest == null) {
            return null;
        }
        return (oldest.getWho() == null ? "?" : oldest.getWho()) + ": " + oldest.getTool() + " ("
            + Math.max(0L, (nowMillis - oldest.getStartedMillis()) / 1000L) + " s)";
    }

    /** On Forge's bus. */
    public static final class Events {

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || ++ticks % 20 != 0) {
                return;
            }
            MinecraftServer server = ServerThreadBridge.server();
            if (server == null) {
                return;
            }
            // Operators on a dedicated server; everyone in singleplayer, as EndpointNotices does.
            List<NetHandlerPlayServer> found = new ArrayList<>();
            for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                if (player.connection != null && (!server.isDedicatedServer()
                    || server.getPlayerList().canSendCommands(player.getGameProfile()))) {
                    found.add(player.connection);
                }
            }
            operators = Collections.unmodifiableList(found);
            poll(System.currentTimeMillis());
        }
    }
}
