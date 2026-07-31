package com.micatechnologies.minecraft.mcmcp.transport;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.game.GameThreadBridge;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpc;
import com.micatechnologies.minecraft.mcmcp.protocol.McpDispatcher;
import com.micatechnologies.minecraft.mcmcp.protocol.McpProtocol;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSessionManager;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * One complete MCP server: transport, dispatcher, sessions, and the wiring that pushes catalogue
 * changes out to connected clients.
 *
 * <p>MCMCP runs up to two of these concurrently and they are fully independent — separate ports,
 * separate sessions, separate tool visibility:
 *
 * <ul>
 *   <li>The <b>client</b> endpoint lives in a player's game client. It is the one that matters,
 *       because it works on any server the player can join. No operator rights, no server-side
 *       install, no inbound port on the server: the model drives that one player exactly as the
 *       player themselves would, which is also what keeps it honest — it cannot do anything the
 *       player could not do by hand.</li>
 *   <li>The <b>server</b> endpoint lives in a dedicated server (or the integrated server behind a
 *       singleplayer world) and sees authoritative world state for every player. More powerful, but
 *       it requires the server operator to install and expose it.</li>
 * </ul>
 *
 * <p>Both can run at once — a singleplayer world with the client endpoint controlling the player and
 * the server endpoint inspecting the world is the most capable configuration there is.
 */
public class McpEndpoint implements McpRegistry.ChangeListener {

    private final McmcpSide side;
    private final McpEndpointSettings settings;
    private final McpSessionManager sessions;
    private final McpDispatcher dispatcher;
    private final HttpMcpTransport transport;

    private volatile boolean started;

    public McpEndpoint(McmcpSide side, McpEndpointSettings settings, GameThreadBridge gameThread) {
        this.side = side;
        this.settings = settings;
        this.sessions = new McpSessionManager(settings.getSessionIdleTimeoutMillis(), settings.getMaxSessions());
        this.dispatcher = new McpDispatcher(side, gameThread, settings.getGameThreadTimeoutMillis(),
            buildInstructions(side));
        this.transport = new HttpMcpTransport(settings, dispatcher, sessions);
    }

    public McmcpSide getSide() {
        return side;
    }

    public McpEndpointSettings getSettings() {
        return settings;
    }

    public McpSessionManager getSessions() {
        return sessions;
    }

    public boolean isRunning() {
        return started && transport.isRunning();
    }

    /**
     * Binds the port and registers for catalogue-change notifications.
     *
     * @throws IOException if the port cannot be bound
     */
    public synchronized void start() throws IOException {
        if (started) {
            return;
        }
        transport.start();
        McpRegistry.addChangeListener(this);
        started = true;
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        McpRegistry.removeChangeListener(this);
        transport.stop();
        started = false;
    }

    /** Drops sessions that have gone quiet. Driven from a server tick handler, not a timer thread. */
    public void tick() {
        sessions.sweepExpired();
    }

    // ------------------------------------------------------------------
    // Registry change fan-out
    // ------------------------------------------------------------------

    @Override
    public void onToolsChanged() {
        broadcast(McpProtocol.NOTIFICATION_TOOLS_LIST_CHANGED);
    }

    @Override
    public void onResourcesChanged() {
        broadcast(McpProtocol.NOTIFICATION_RESOURCES_LIST_CHANGED);
    }

    @Override
    public void onPromptsChanged() {
        broadcast(McpProtocol.NOTIFICATION_PROMPTS_LIST_CHANGED);
    }

    @Override
    public void onResourceUpdated(String uri) {
        if (!started) {
            return;
        }
        // Only to sessions that actually subscribed. Broadcasting resource updates to everyone
        // would turn one player's inventory change into traffic on every connected client's stream.
        sessions.broadcastToSubscribers(uri, McpDispatcher.resourceUpdatedNotification(uri));
    }

    private void broadcast(String notificationMethod) {
        if (!started) {
            return;
        }
        sessions.broadcast(JsonRpc.notification(notificationMethod, null));
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    /** A status snapshot for the {@code /mcmcp status} command and the log. */
    public JsonObject describe() {
        JsonObject status = new JsonObject();
        status.addProperty("side", side.id());
        status.addProperty("running", isRunning());
        status.addProperty("url", settings.describeUrl());
        status.addProperty("authRequired", settings.isRequireAuth());
        status.addProperty("sessions", sessions.count());
        status.add("protocolVersions", Json.arrayOfStrings(McpProtocol.supportedVersions()));
        status.addProperty("tools", McpRegistry.tools(side).size());
        status.addProperty("resources", McpRegistry.resources(side).size());
        status.addProperty("prompts", McpRegistry.prompts(side).size());
        return status;
    }

    /**
     * The {@code instructions} string returned from {@code initialize}.
     *
     * <p>This is the only text a model sees before it has read any tool descriptions, so it covers
     * the three things that are not deducible from the tool list and that models reliably get wrong
     * on a first attempt: which side they are attached to and therefore what is reachable, the fact
     * that acting takes game-time rather than completing instantly, and that block coordinates are
     * integers while entity positions are not.
     */
    private static String buildInstructions(McmcpSide side) {
        StringBuilder text = new StringBuilder();
        text.append("You are connected to a running Minecraft 1.12.2 game through MCMCP.\n\n");

        if (side.isClient()) {
            text.append("This is the CLIENT endpoint. You are attached to one player's game client and act ")
                .append("as that player. You see what they see and are subject to every rule they are: ")
                .append("reach distance, inventory contents, permissions on whatever server they are ")
                .append("connected to. You cannot read world state the player cannot see, and commands you ")
                .append("send are executed by the server with that player's permission level.\n\n");
            text.append("Screenshot and input tools are available here. Screenshots return a file path by ")
                .append("default rather than inline image data; request the image explicitly when you need ")
                .append("to look at it.\n\n");
        }
        else {
            text.append("This is the SERVER endpoint. You see authoritative world state for every loaded ")
                .append("dimension and every connected player, and commands run with server authority. ")
                .append("There is no camera and no player input on this side — no screenshots, no ")
                .append("keystrokes. Attach to a client endpoint for those.\n\n");
        }

        text.append("Conventions used by every tool here:\n")
            .append("- Block positions are integers (x, y, z). Entity and player positions are doubles and ")
            .append("are not the same thing as the block they stand in.\n")
            .append("- Y is vertical. The world runs from y=0 to y=255 in 1.12.2.\n")
            .append("- Yaw is degrees clockwise from south; pitch is degrees down from horizontal, in ")
            .append("[-90, 90].\n")
            .append("- Actions take game time. Movement and interaction tools apply input for a number of ")
            .append("ticks (20 ticks = 1 second) and return once that input has been applied, not once the ")
            .append("world has finished reacting to it. Re-read state afterwards rather than assuming.\n")
            .append("- Anything unavailable in the current configuration comes back as a tool error ")
            .append("explaining which setting disabled it, not as a protocol failure.\n");
        return text.toString();
    }

    @Nullable
    public static McpEndpoint startOrLog(McmcpSide side, McpEndpointSettings settings, GameThreadBridge bridge) {
        McpEndpoint endpoint = new McpEndpoint(side, settings, bridge);
        try {
            endpoint.start();
            return endpoint;
        }
        catch (IOException e) {
            // A failed bind must never take the game down with it. The overwhelmingly common cause
            // is a port already in use — a second dev launch, or a previous instance still exiting —
            // and the right outcome is a game that runs with MCMCP disabled plus a log line saying
            // exactly what to change.
            Mcmcp.LOGGER.error("MCMCP could not start the " + side.id() + " endpoint on "
                + settings.describeUrl() + ": " + e.getMessage()
                + ". The game will run without it; change the port in the MCMCP config and use "
                + "'/mcmcp restart' to try again.");
            return null;
        }
    }
}
