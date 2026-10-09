package com.micatechnologies.minecraft.mcmcp.companion.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.McmcpEndpoints;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionAccess;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionHandshake;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionPolicy;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionProtocol;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionProtocolException;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionReassembler;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.mcp.Principal;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpc;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpcException;
import com.micatechnologies.minecraft.mcmcp.protocol.McpDispatcher;
import com.micatechnologies.minecraft.mcmcp.protocol.McpProtocol;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
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
 * server-side tool catalogue, filtered per player by {@link CompanionPolicy}. A player's client
 * forwards JSON-RPC here unchanged and gets the replies back. Nothing listens: no port, no link.
 *
 * <h2>Who it serves</h2>
 *
 * A player on the companion allowlist, for the classes of call whose {@code mcmcp.companion.*}
 * permission nodes they hold, while the companion is enabled. Identity comes only from the connection
 * a frame arrived on. Grants are checked at hello and again every few seconds, so allowing, revoking,
 * turning the companion off or a permission changing hands takes effect without anyone rejoining.
 *
 * <h2>Threads</h2>
 *
 * Frames arrive on the netty thread and are only reassembled there. Access is decided on the server
 * thread, where the permission check is safe. MCP messages run on a small worker pool, never the
 * netty thread or the server thread: a tool waits on the server thread and must not be waiting from
 * it. Replies go out from the server thread, a budget per tick.
 */
public final class CompanionServer {

    /** Ticks between re-checks of every connected player's grants: five seconds. */
    private static final int REGRANT_TICKS = 100;

    /** At most one operator notice per player per this long. */
    private static final long NOTICE_INTERVAL_MILLIS = 10_000L;

    private static final AtomicInteger WORKER_COUNT = new AtomicInteger();

    @Nullable
    private static FMLEventChannel channel;

    @Nullable
    private static volatile McpDispatcher dispatcher;

    @Nullable
    private static volatile ExecutorService workers;

    private static final Map<UUID, CompanionPeer> PEERS = new ConcurrentHashMap<>();

    private static int ticks;

    private CompanionServer() {
    }

    /** Registers the channel. Both sides: the client half listens on the same channel. */
    public static void preInit() {
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CompanionProtocol.CHANNEL);
        channel.register(new PacketHandler());
        MinecraftForge.EVENT_BUS.register(new Lifecycle());
    }

    /**
     * Registers a permission node per class of call. Permission nodes can only be registered from init
     * on. Console authority is granted to nobody by default: running as oneself already carries an
     * operator's own command rights, and console authority is a deliberate grant through a permission
     * mod.
     */
    public static void init() {
        node(Principal.CLASS_READ, DefaultPermissionLevel.OP, "Read world and player state through the MCMCP companion.");
        node(Principal.CLASS_WRITE, DefaultPermissionLevel.OP,
            "Write blocks, restore undo points and move players through the MCMCP companion.");
        node(Principal.CLASS_COMMAND, DefaultPermissionLevel.OP,
            "Run commands as yourself, and message players, through the MCMCP companion.");
        node(Principal.CLASS_OTHERS, DefaultPermissionLevel.OP,
            "Name players other than yourself in MCMCP companion tools (state, inventory, teleport, asPlayer).");
        node(Principal.CLASS_CONSOLE, DefaultPermissionLevel.NONE,
            "Run commands through the MCMCP companion with the server console's authority.");
    }

    private static void node(String callClass, DefaultPermissionLevel level, String description) {
        PermissionAPI.registerNode(CompanionPolicy.NODE_PREFIX + callClass, level,
            description + " The player must also be on companion.allowedPlayers in the MCMCP config.");
    }

    public static FMLEventChannel channel() {
        if (channel == null) {
            throw new IllegalStateException("The companion channel is registered in preInit");
        }
        return channel;
    }

    /** Builds the dispatcher and workers, and resolves names on the allowlist to UUIDs. */
    public static synchronized void serverStarting(MinecraftServer server) {
        McpDispatcher built = new McpDispatcher(McmcpSide.SERVER, new ServerThreadBridge(),
            McmcpConfig.settingsFor(McmcpSide.SERVER).getGameThreadTimeoutMillis(),
            McpEndpoint.instructionsFor(McmcpSide.SERVER));
        built.setJournal(McmcpEndpoints.journal(McmcpSide.SERVER));
        built.setFilter(new CompanionPolicy());
        dispatcher = built;
        workers = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "MCMCP-companion-" + WORKER_COUNT.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        resolveAllowlist(server);
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

    // ------------------------------------------------------------------
    // Access
    // ------------------------------------------------------------------

    /** The classes {@code player} holds now. Server thread. */
    static Set<String> grantsFor(EntityPlayerMP player) {
        boolean allowlisted = CompanionAccess.isAllowlisted(player.getUniqueID(), player.getName(),
            McmcpConfig.getCompanionAllowedPlayers());
        return CompanionPolicy.grants(McmcpConfig.isCompanionEnabled(), allowlisted,
            node -> PermissionAPI.hasPermission(player, node));
    }

    /**
     * Why {@code player} holds nothing. The reasons name what to ask an operator for, never who else
     * is allowed.
     */
    static String refusalFor(EntityPlayerMP player) {
        if (!McmcpConfig.isCompanionEnabled()) {
            return "The MCMCP companion is turned off on this server.";
        }
        if (!CompanionAccess.isAllowlisted(player.getUniqueID(), player.getName(),
            McmcpConfig.getCompanionAllowedPlayers())) {
            return "You are not on this server's MCMCP companion allowlist. Ask an operator to run "
                + "/mcmcp companion allow " + player.getName() + ".";
        }
        return "You hold none of the " + CompanionPolicy.NODE_PREFIX + "* permission nodes on this server.";
    }

    /**
     * Brings one player's grants up to date and tells their client what changed: a welcome when access
     * is newly granted, a bye when it is gone, a tool-list change when it shrank or grew. Server thread.
     */
    private static void regrant(CompanionPeer peer, @Nullable EntityPlayerMP player, boolean answeringHello) {
        if (peer.isBroken()) {
            return;
        }
        Set<String> granted = player == null ? Collections.emptySet() : grantsFor(player);
        boolean wasGranted = peer.isGranted();
        boolean changed = peer.principal.setClasses(granted);
        if (granted.isEmpty()) {
            String reason = player == null ? "You left the server." : refusalFor(player);
            if (wasGranted) {
                peer.closeSessions();
                send(peer, CompanionProtocol.TYPE_BYE, CompanionHandshake.bye(reason));
                Mcmcp.LOGGER.info("MCMCP companion: " + peer.playerName + " no longer has access: " + reason);
            }
            else if (answeringHello) {
                send(peer, CompanionProtocol.TYPE_WELCOME, CompanionHandshake.refused(McmcpConstants.MOD_VERSION, reason));
                Mcmcp.LOGGER.info("MCMCP companion: refused " + peer.playerName + ": " + reason);
            }
            return;
        }
        if (!wasGranted || answeringHello) {
            send(peer, CompanionProtocol.TYPE_WELCOME, CompanionHandshake.granted(McmcpConstants.MOD_VERSION, granted));
            Mcmcp.LOGGER.info("MCMCP companion: " + peer.playerName + " connected with " + granted);
        }
        else if (changed) {
            for (McpSession session : peer.sessions()) {
                session.enqueue(JsonRpc.notification(McpProtocol.NOTIFICATION_TOOLS_LIST_CHANGED, null));
            }
            Mcmcp.LOGGER.info("MCMCP companion: " + peer.playerName + "'s access is now " + granted);
        }
    }

    /**
     * Re-checks every player who has asked for the companion. Called after the allowlist or the
     * enabled switch changes, so the change is immediate. Server thread.
     */
    public static void accessChanged() {
        MinecraftServer server = ServerThreadBridge.server();
        if (server == null) {
            return;
        }
        for (CompanionPeer peer : PEERS.values()) {
            if (peer.isHelloReceived()) {
                regrant(peer, server.getPlayerList().getPlayerByUUID(peer.playerId), false);
            }
        }
    }

    /**
     * Replaces names on the allowlist with UUIDs from the server's profile cache, so an entry keeps
     * meaning the same player after a name change. Names it cannot resolve stay as they are and still
     * match by name.
     */
    static void resolveAllowlist(MinecraftServer server) {
        List<String> entries = McmcpConfig.getCompanionAllowedPlayers();
        List<String> resolved = new ArrayList<>(entries.size());
        boolean changed = false;
        for (String entry : entries) {
            String value = entry.trim();
            if (value.isEmpty() || looksLikeUuid(value)) {
                resolved.add(value);
                continue;
            }
            GameProfile profile = server.getPlayerProfileCache().getGameProfileForUsername(value);
            if (profile != null && profile.getId() != null) {
                resolved.add(profile.getId().toString());
                changed = true;
                Mcmcp.LOGGER.info("MCMCP companion: allowlist entry '" + value + "' is " + profile.getId());
            }
            else {
                resolved.add(value);
            }
        }
        if (changed) {
            McmcpConfig.setCompanionAllowedPlayers(resolved);
        }
    }

    static boolean looksLikeUuid(String value) {
        try {
            UUID.fromString(value);
            return value.length() == 36;
        }
        catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Operator controls
    // ------------------------------------------------------------------

    /** Drops one player's companion state; their running calls are cancelled. */
    public static void drop(UUID playerId) {
        CompanionPeer peer = PEERS.remove(playerId);
        if (peer != null) {
            peer.close();
        }
    }

    /** A snapshot for status reports: who is connected, with what, and how much is queued for them. */
    public static JsonObject status() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", McmcpConfig.isCompanionEnabled());
        json.addProperty("allowedPlayers", McmcpConfig.getCompanionAllowedPlayers().size());
        JsonArray peers = new JsonArray();
        for (CompanionPeer peer : PEERS.values()) {
            if (!peer.isHelloReceived()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("player", peer.playerName);
            entry.add("classes", Json.arrayOfStrings(peer.principal.getClasses()));
            entry.addProperty("sessions", peer.sessions().size());
            entry.addProperty("runningCalls", peer.runningCalls.get());
            entry.addProperty("queuedBytes", peer.outbox.queuedBytes());
            peers.add(entry);
        }
        json.add("players", peers);
        return json;
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
                onHello(peer, message.bytes);
                break;
            case CompanionProtocol.TYPE_MCP:
                onMcp(peer, message.bytes);
                break;
            case CompanionProtocol.TYPE_BYE:
                onBye(peer, message.bytes);
                break;
            default:
                // A newer client's message type this build does not know. Ignored, not fatal.
                break;
        }
    }

    private static void onHello(final CompanionPeer peer, byte[] bytes) {
        final JsonObject hello = CompanionHandshake.parse(bytes);
        final MinecraftServer server = ServerThreadBridge.server();
        if (server == null) {
            return;
        }
        // Decided on the server thread: the permission handler may read the player list.
        server.addScheduledTask(() -> {
            peer.markHelloReceived();
            if (CompanionHandshake.protocolOf(hello) != CompanionProtocol.VERSION) {
                send(peer, CompanionProtocol.TYPE_WELCOME, CompanionHandshake.refused(McmcpConstants.MOD_VERSION,
                    "This server's MCMCP speaks companion protocol " + CompanionProtocol.VERSION
                        + " and your client speaks " + CompanionHandshake.protocolOf(hello)
                        + ". Update whichever MCMCP is older."));
                return;
            }
            if (dispatcher == null) {
                send(peer, CompanionProtocol.TYPE_WELCOME,
                    CompanionHandshake.refused(McmcpConstants.MOD_VERSION, "The server is not ready."));
                return;
            }
            regrant(peer, server.getPlayerList().getPlayerByUUID(peer.playerId), true);
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
        if (!peer.isGranted()) {
            // Access went between the client sending and this arriving: answer, so nothing waits.
            refuse(peer, key, message, "You no longer have MCMCP companion access on this server.");
            return;
        }
        boolean fresh = peer.existingSession(key) == null;
        final McpSession session = peer.session(key, McmcpConfig.getCompanionMaxSessionsPerPlayer());
        session.touch(System.currentTimeMillis());
        if (fresh && !McpProtocol.METHOD_INITIALIZE.equals(JsonRpc.getMethod(message))) {
            // A client session this server evicted (past maxSessionsPerPlayer) and the client still
            // uses. It initialized long ago; treating the new one as initialized beats refusing every
            // call on it with "not initialized", which the client cannot recover from.
            session.applyInitialize(McpProtocol.LATEST_VERSION, new JsonObject(), new JsonObject());
            session.markInitialized();
        }
        final boolean toolCall = McpProtocol.METHOD_TOOLS_CALL.equals(JsonRpc.getMethod(message));
        if (toolCall && peer.runningCalls.incrementAndGet() > McmcpConfig.getCompanionMaxConcurrentCalls()) {
            peer.runningCalls.decrementAndGet();
            refuse(peer, key, message, "You already have " + McmcpConfig.getCompanionMaxConcurrentCalls()
                + " companion calls running on this server (companion.maxConcurrentCalls). Wait for one to "
                + "finish.");
            return;
        }
        try {
            pool.execute(() -> {
                try {
                    JsonObject response = handler.dispatch(session, message);
                    if (response != null) {
                        reply(peer, key, response, session);
                        if (toolCall) {
                            audit(peer, message, response);
                        }
                    }
                }
                finally {
                    if (toolCall) {
                        peer.runningCalls.decrementAndGet();
                    }
                }
            });
        }
        catch (RejectedExecutionException e) {
            if (toolCall) {
                peer.runningCalls.decrementAndGet();
            }
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

    /** Answers a request that will not be run: a tool error for a tool call, a JSON-RPC error otherwise. */
    private static void refuse(CompanionPeer peer, String key, JsonObject message, String why) {
        JsonElement id = JsonRpc.getId(message);
        if (id == null || !JsonRpc.isRequest(message)) {
            return;
        }
        JsonObject response = McpProtocol.METHOD_TOOLS_CALL.equals(JsonRpc.getMethod(message))
            ? JsonRpc.result(id, ToolResult.error(why).toJson(McpProtocol.LATEST_VERSION))
            : JsonRpc.error(id, JsonRpcException.INTERNAL_ERROR, why);
        peer.outbox.offer(CompanionProtocol.TYPE_MCP,
            CompanionHandshake.bytes(CompanionPeer.envelope(key, response)), Long.MAX_VALUE);
    }

    /**
     * Records a call that changed something: one INFO line in the server log, and a line for the
     * operators in chat (rate-limited). Reads are in the request journal only.
     */
    private static void audit(CompanionPeer peer, JsonObject request, JsonObject response) {
        JsonObject params = Json.getObjectOrEmpty(request, "params");
        String tool = Json.getString(params, "name", "?");
        String callClass = CompanionPolicy.classOf(tool);
        if (callClass == null || Principal.CLASS_READ.equals(callClass)) {
            return;
        }
        if ("server_undo".equals(tool)
            && !"restore".equals(Json.getString(Json.getObjectOrEmpty(params, "arguments"), "op", "list"))) {
            // Listing undo points changes nothing.
            return;
        }
        JsonObject result = Json.getObject(response, "result");
        if (result == null || Json.getBoolean(result, "isError", false)) {
            return;
        }
        JsonObject structured = Json.getObject(result, "structuredContent");
        StringBuilder line = new StringBuilder(tool);
        if (structured != null) {
            for (String field : new String[] {"written", "command", "ranAs", "undoPoint"}) {
                JsonElement value = structured.get(field);
                if (value != null && value.isJsonPrimitive()) {
                    line.append(' ').append(field).append('=').append(value.getAsString());
                }
            }
        }
        Mcmcp.LOGGER.info("MCMCP companion: " + peer.playerName + " " + line);
        peer.noteForOperators(line.toString());
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

    /** Tells online operators what agents changed, at most a line per player per ten seconds. */
    private static void noticeOperators(MinecraftServer server) {
        if (!McmcpConfig.isCompanionNotifyOps()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (CompanionPeer peer : PEERS.values()) {
            String notice = peer.takeNotice(now, NOTICE_INTERVAL_MILLIS);
            if (notice == null) {
                continue;
            }
            TextComponentString text = new TextComponentString("[MCMCP] " + peer.playerName + "'s agent: " + notice);
            text.getStyle().setColor(TextFormatting.GRAY).setItalic(true);
            for (EntityPlayerMP online : server.getPlayerList().getPlayers()) {
                if (server.getPlayerList().canSendCommands(online.getGameProfile())) {
                    online.sendMessage(text);
                }
            }
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
            if (server == null) {
                return;
            }
            if (++ticks % REGRANT_TICKS == 0) {
                accessChanged();
            }
            noticeOperators(server);
            flush(server);
        }
    }
}
