package com.micatechnologies.minecraft.mcmcp.companion.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.McmcpEndpoints;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionAccess;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionHandshake;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionProtocol;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionProtocolException;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionReassembler;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpc;
import com.micatechnologies.minecraft.mcmcp.protocol.McpDispatcher;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.PacketBuffer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLEventChannel;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import net.minecraftforge.server.permission.PermissionAPI;

/**
 * The server half of the companion: MCMCP on a server reached through its players' game
 * connections instead of a port.
 *
 * <h2>What it serves</h2>
 *
 * The same thing the server endpoint does, through the same dispatcher class: MCP, with the
 * server-side tool catalogue. A player's client forwards JSON-RPC here unchanged and gets the
 * replies back, so the tools, their schemas and their descriptions all come from this server's
 * build. Nothing listens: no port, no orchestrator link.
 *
 * <h2>Who it serves</h2>
 *
 * Nobody, until an operator says so. A player must be on the companion allowlist <em>and</em> hold
 * the companion permission node, which operators do by default; and the companion must be enabled.
 * Identity comes only from the connection a frame arrived on.
 *
 * <h2>Threads</h2>
 *
 * Frames arrive on the netty thread and are only reassembled there. Hello is decided on the server
 * thread, where the permission check is safe. MCP messages run on a small worker pool, never the
 * netty thread or the server thread: a tool waits on the server thread and must not be waiting from
 * it. Replies go out from the server thread, a budget per tick.
 */
public final class CompanionServer {

    /** Granted to operators by default. Phase-in: finer per-class nodes join it. */
    public static final String NODE_USE = "mcmcp.companion.use";

    private static final AtomicInteger WORKER_COUNT = new AtomicInteger();

    @Nullable
    private static FMLEventChannel channel;

    @Nullable
    private static volatile McpDispatcher dispatcher;

    @Nullable
    private static volatile ExecutorService workers;

    private static final Map<UUID, CompanionPeer> PEERS = new ConcurrentHashMap<>();

    private CompanionServer() {
    }

    /** Registers the channel. Both sides: the client half listens on the same channel. */
    public static void preInit() {
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CompanionProtocol.CHANNEL);
        channel.register(new PacketHandler());
        MinecraftForge.EVENT_BUS.register(new Lifecycle());
    }

    /** Permission nodes can only be registered from init on. */
    public static void init() {
        PermissionAPI.registerNode(NODE_USE, DefaultPermissionLevel.OP,
            "Use this server's MCMCP tools through the companion. The player must also be on "
                + "companion.allowedPlayers in the MCMCP config.");
    }

    public static FMLEventChannel channel() {
        if (channel == null) {
            throw new IllegalStateException("The companion channel is registered in preInit");
        }
        return channel;
    }

    /** Builds the dispatcher and workers. Called when a server starts, whether or not it is enabled. */
    public static synchronized void serverStarting() {
        McpDispatcher built = new McpDispatcher(McmcpSide.SERVER, new ServerThreadBridge(),
            McmcpConfig.settingsFor(McmcpSide.SERVER).getGameThreadTimeoutMillis(),
            McpEndpoint.instructionsFor(McmcpSide.SERVER));
        built.setJournal(McmcpEndpoints.journal(McmcpSide.SERVER));
        dispatcher = built;
        workers = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "MCMCP-companion-" + WORKER_COUNT.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Ends every peer and the workers. */
    public static synchronized void serverStopping() {
        for (CompanionPeer peer : PEERS.values()) {
            peer.close();
        }
        PEERS.clear();
        ExecutorService pool = workers;
        workers = null;
        dispatcher = null;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /** Drops one player's companion state; their running calls are cancelled. Backs revoke. */
    public static void drop(UUID playerId) {
        CompanionPeer peer = PEERS.remove(playerId);
        if (peer != null) {
            peer.close();
        }
    }

    /** Drops every player's companion state. Backs turning the companion off. */
    public static void dropAll() {
        for (UUID id : PEERS.keySet()) {
            drop(id);
        }
    }

    /** A snapshot for status reports: who is connected and how much is queued for them. */
    public static JsonObject status() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", McmcpConfig.isCompanionEnabled());
        json.addProperty("allowedPlayers", McmcpConfig.getCompanionAllowedPlayers().size());
        com.google.gson.JsonArray peers = new com.google.gson.JsonArray();
        for (CompanionPeer peer : PEERS.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("player", peer.playerName);
            entry.addProperty("granted", peer.isGranted());
            entry.addProperty("sessions", peer.sessions().size());
            entry.addProperty("queuedBytes", peer.outbox.queuedBytes());
            peers.add(entry);
        }
        json.add("players", peers);
        return json;
    }

    // ------------------------------------------------------------------
    // Access
    // ------------------------------------------------------------------

    /**
     * Why {@code player} may not use the companion, or null when they may.
     *
     * <p>The reasons a player sees name what to ask an operator for, never who else is allowed.
     */
    @Nullable
    static String refusal(EntityPlayerMP player) {
        if (!McmcpConfig.isCompanionEnabled()) {
            return "The MCMCP companion is turned off on this server.";
        }
        if (!CompanionAccess.isAllowlisted(player.getUniqueID(), player.getName(), McmcpConfig.getCompanionAllowedPlayers())) {
            return "You are not on this server's MCMCP companion allowlist. Ask an operator to run "
                + "/mcmcp companion allow " + player.getName() + ".";
        }
        if (!PermissionAPI.hasPermission(player, NODE_USE)) {
            return "You do not have the " + NODE_USE + " permission on this server.";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Inbound
    // ------------------------------------------------------------------

    private static void onFrame(EntityPlayerMP player, byte[] frame) {
        UUID id = player.getUniqueID();
        CompanionPeer peer = PEERS.computeIfAbsent(id, key -> new CompanionPeer(key, player.getName(),
            McmcpConfig.getCompanionMaxMessageBytes(), McmcpConfig.getCompanionMaxOpenMessages()));
        if (peer.isBroken()) {
            return;
        }
        CompanionReassembler.Message message;
        try {
            message = peer.reassembler.accept(frame);
        }
        catch (CompanionProtocolException e) {
            Mcmcp.LOGGER.warn("MCMCP companion: dropping " + player.getName() + "'s companion session: "
                + e.getMessage());
            peer.markBroken();
            peer.close();
            return;
        }
        if (message == null) {
            return;
        }
        switch (message.type) {
            case CompanionProtocol.TYPE_HELLO:
                onHello(player, peer, message.bytes);
                break;
            case CompanionProtocol.TYPE_MCP:
                if (peer.isGranted()) {
                    onMcp(peer, message.bytes);
                }
                break;
            case CompanionProtocol.TYPE_BYE:
                onBye(peer, message.bytes);
                break;
            default:
                // A newer client's message type this build does not know. Ignored, not fatal.
                break;
        }
    }

    private static void onHello(final EntityPlayerMP player, final CompanionPeer peer, byte[] bytes) {
        final JsonObject hello = CompanionHandshake.parse(bytes);
        MinecraftServer server = ServerThreadBridge.server();
        if (server == null) {
            return;
        }
        // Decided on the server thread: the permission handler may read the player list.
        server.addScheduledTask(() -> {
            byte[] welcome;
            if (CompanionHandshake.protocolOf(hello) != CompanionProtocol.VERSION) {
                welcome = CompanionHandshake.refused(McmcpConstants.MOD_VERSION, "This server's MCMCP speaks "
                    + "companion protocol " + CompanionProtocol.VERSION + " and your client speaks "
                    + CompanionHandshake.protocolOf(hello) + ". Update whichever MCMCP is older.");
            }
            else {
                String refusal = dispatcher == null ? "The server is not ready." : refusal(player);
                if (refusal == null) {
                    peer.grant();
                    welcome = CompanionHandshake.granted(McmcpConstants.MOD_VERSION,
                        Collections.singletonList("all"));
                    Mcmcp.LOGGER.info("MCMCP companion: " + player.getName() + " connected ("
                        + Json.getString(hello, "modVersion", "unknown version") + ")");
                }
                else {
                    welcome = CompanionHandshake.refused(McmcpConstants.MOD_VERSION, refusal);
                    Mcmcp.LOGGER.info("MCMCP companion: refused " + player.getName() + ": " + refusal);
                }
            }
            send(peer, CompanionProtocol.TYPE_WELCOME, welcome);
        });
    }

    private static void onMcp(final CompanionPeer peer, byte[] bytes) {
        final McpDispatcher handler = dispatcher;
        ExecutorService pool = workers;
        if (handler == null || pool == null) {
            return;
        }
        JsonElement parsed;
        try {
            parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        }
        catch (RuntimeException e) {
            return;
        }
        if (parsed == null || !parsed.isJsonObject()) {
            return;
        }
        JsonObject envelope = parsed.getAsJsonObject();
        final String key = Json.getString(envelope, CompanionProtocol.FIELD_SESSION);
        final JsonObject message = Json.getObject(envelope, CompanionProtocol.FIELD_MESSAGE);
        if (key == null || message == null) {
            return;
        }
        final McpSession session = peer.session(key, McmcpConfig.getCompanionMaxSessionsPerPlayer());
        session.touch(System.currentTimeMillis());
        try {
            pool.execute(() -> {
                JsonObject response = handler.dispatch(session, message);
                if (response != null) {
                    reply(peer, key, response, session);
                }
            });
        }
        catch (RejectedExecutionException e) {
            // The server is stopping.
        }
    }

    private static void onBye(CompanionPeer peer, byte[] bytes) {
        JsonObject bye = CompanionHandshake.parse(bytes);
        String key = bye == null ? null : Json.getString(bye, CompanionProtocol.FIELD_SESSION);
        if (key != null) {
            peer.closeSession(key);
        }
        else {
            drop(peer.playerId);
        }
    }

    // ------------------------------------------------------------------
    // Outbound
    // ------------------------------------------------------------------

    /**
     * Queues a reply. One too large for the player's queue becomes a tool error, so the caller learns
     * why rather than waiting for a reply that will never come.
     */
    private static void reply(CompanionPeer peer, String key, JsonObject response, McpSession session) {
        byte[] bytes = CompanionHandshake.bytes(CompanionPeer.envelope(key, response));
        if (peer.outbox.offer(CompanionProtocol.TYPE_MCP, bytes, McmcpConfig.getCompanionMaxQueuedBytes())) {
            return;
        }
        JsonElement id = JsonRpc.getId(response);
        if (id == null) {
            return;
        }
        JsonObject refused = JsonRpc.result(id, ToolResult.error("The reply was " + bytes.length
                + " bytes, and this player's companion queue is full (companion.maxQueuedMB). Ask for "
                + "less at once, or wait for earlier replies to arrive.")
            .toJson(session.getProtocolVersion()));
        peer.outbox.offer(CompanionProtocol.TYPE_MCP,
            CompanionHandshake.bytes(CompanionPeer.envelope(key, refused)), Long.MAX_VALUE);
    }

    private static void send(CompanionPeer peer, byte type, byte[] message) {
        peer.outbox.offer(type, message, Long.MAX_VALUE);
    }

    /** Moves session notifications into the outbox, then sends a budget's worth to each player. */
    private static void flush(MinecraftServer server) {
        int budget = McmcpConfig.getCompanionSendBudgetBytesPerTick();
        for (CompanionPeer peer : PEERS.values()) {
            for (Map.Entry<String, McpSession> entry : peer.sessionEntries()) {
                JsonObject notification;
                try {
                    while ((notification = entry.getValue().pollOutbound(0L)) != null) {
                        peer.outbox.offer(CompanionProtocol.TYPE_MCP, CompanionHandshake.bytes(
                            CompanionPeer.envelope(entry.getKey(), notification)),
                            McmcpConfig.getCompanionMaxQueuedBytes());
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (peer.outbox.queuedBytes() == 0L) {
                continue;
            }
            final EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(peer.playerId);
            if (player == null || player.connection == null) {
                continue;
            }
            // A connection that cannot take more gets nothing this tick; the queue waits instead of
            // netty's buffer growing.
            if (!player.connection.netManager.channel().isWritable()) {
                continue;
            }
            peer.outbox.drain(budget, frame -> {
                ByteBuf buffer = Unpooled.wrappedBuffer(frame);
                channel().sendTo(new FMLProxyPacket(new PacketBuffer(buffer), CompanionProtocol.CHANNEL), player);
            });
        }
    }

    // ------------------------------------------------------------------
    // Event handlers
    // ------------------------------------------------------------------

    /** On the channel's own bus. */
    public static final class PacketHandler {

        /**
         * Never lets anything escape: an exception thrown from a custom-payload handler makes FML
         * terminate the player's whole connection. A companion fault must cost the companion, not
         * the player's session on the server.
         */
        @SubscribeEvent
        public void onServerPacket(FMLNetworkEvent.ServerCustomPacketEvent event) {
            EntityPlayerMP player = null;
            try {
                player = ((NetHandlerPlayServer) event.getHandler()).player;
                ByteBuf payload = event.getPacket().payload();
                byte[] frame = new byte[payload.readableBytes()];
                payload.readBytes(frame);
                onFrame(player, frame);
            }
            catch (Throwable t) {
                Mcmcp.LOGGER.error("MCMCP companion: failed handling a message"
                    + (player == null ? "" : " from " + player.getName()) + "; dropping their companion session", t);
                if (player != null) {
                    drop(player.getUniqueID());
                }
            }
        }
    }

    /** On Forge's event bus. */
    public static final class Lifecycle {

        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            drop(event.player.getUniqueID());
        }

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || PEERS.isEmpty()) {
                return;
            }
            MinecraftServer server = ServerThreadBridge.server();
            if (server != null) {
                flush(server);
            }
        }
    }
}
