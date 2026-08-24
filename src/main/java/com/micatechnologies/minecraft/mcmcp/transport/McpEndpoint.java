package com.micatechnologies.minecraft.mcmcp.transport;

import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /**
     * Every way traffic can reach this endpoint.
     *
     * <p>Copy-on-write because {@link #describe()} and {@link #tick()} read it from other threads
     * while {@link #addTransport} is only ever called during startup — the read-heavy, write-once
     * shape this collection exists for.
     */
    private final List<McpTransport> transports = new CopyOnWriteArrayList<>();

    private volatile boolean started;

    public McpEndpoint(McmcpSide side, McpEndpointSettings settings, GameThreadBridge gameThread) {
        this.side = side;
        this.settings = settings;
        this.sessions = new McpSessionManager(settings.getSessionIdleTimeoutMillis(), settings.getMaxSessions());
        this.dispatcher = new McpDispatcher(side, gameThread, settings.getGameThreadTimeoutMillis(),
            buildInstructions(side));
        this.transports.add(new HttpMcpTransport(settings, dispatcher, sessions));
    }

    /**
     * Adds another way in, before {@link #start()}.
     *
     * <p>Adding after start would leave a transport that never starts, which presents as an
     * orchestrator that cannot see an instance whose log says MCMCP came up fine.
     */
    public void addTransport(McpTransport transport) {
        if (started) {
            throw new IllegalStateException("Transports must be added before the endpoint starts");
        }
        transports.add(transport);
    }

    public McpDispatcher getDispatcher() {
        return dispatcher;
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
        if (!started) {
            return false;
        }
        for (McpTransport transport : transports) {
            if (transport.isRunning()) {
                return true;
            }
        }
        return false;
    }

    /**
     * This endpoint's HTTP URL, but only if the HTTP transport actually bound.
     *
     * <p>Sent to an orchestrator in the handshake so the app can offer a human the direct URL for
     * the times when talking to one instance without the orchestrator in the way is the fastest
     * route to an answer. Null when the port was taken — which is exactly the case the orchestrator
     * exists to rescue, and advertising a URL that nothing is listening on would send someone
     * debugging in the wrong direction.
     */
    @Nullable
    public String httpUrlIfRunning() {
        for (McpTransport transport : transports) {
            if (transport instanceof HttpMcpTransport && transport.isRunning()) {
                return transport.describeTarget();
            }
        }
        return null;
    }

    /**
     * Every transport this endpoint has, running or not, for {@code /mcmcp status}.
     *
     * <p>Not just the running ones. A transport that failed to come up is the single most useful
     * line in a status report — an HTTP port that is not listening is what somebody is trying to
     * diagnose, and omitting it leaves them looking at a status that seems fine.
     */
    public List<McpTransport> transports() {
        return Collections.unmodifiableList(new ArrayList<>(transports));
    }

    /**
     * Starts every transport, and registers for catalogue-change notifications.
     *
     * <p>A transport that fails to start is logged and skipped rather than aborting the endpoint.
     * The case this is for is the one that prompted the whole orchestrator: a second game client
     * cannot bind 25585 because the first one has it. Before, that instance had no MCP at all and
     * said so only in a log line. Now it keeps whatever else came up — in practice the orchestrator
     * link, which needs no port and so cannot collide.
     *
     * @throws IOException if <em>every</em> transport failed, which is the only case where the
     *                     endpoint genuinely has no way in
     */
    public synchronized void start() throws IOException {
        if (started) {
            return;
        }

        IOException firstFailure = null;
        int running = 0;
        for (McpTransport transport : transports) {
            try {
                transport.start();
                running++;
            }
            catch (IOException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
                Mcmcp.LOGGER.error("MCMCP " + side.id() + " endpoint could not start its "
                    + transport.describeKind() + " transport on " + transport.describeTarget()
                    + ": " + e.getMessage());
            }
        }

        if (running == 0) {
            throw firstFailure == null
                ? new IOException("No transports are configured for the " + side.id() + " endpoint")
                : firstFailure;
        }

        McpRegistry.addChangeListener(this);
        started = true;
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        McpRegistry.removeChangeListener(this);
        for (McpTransport transport : transports) {
            transport.stop();
        }
        started = false;
    }

    /**
     * Drops sessions that have gone quiet.
     *
     * <p>Driven from {@code Mcmcp}'s housekeeping scheduler rather than a game tick. It used to run
     * off {@code ServerTickEvent}, which meant a client sitting at the main menu — no world open, so
     * no integrated server, so no server tick — never swept at all. That was survivable while the
     * only way in was an HTTP port nobody connects to from the main menu, and stopped being
     * survivable once an orchestrator link is up from the moment the game loads.
     */
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

        // Per-transport rather than one aggregate flag: "running" being true while the HTTP port is
        // dead is exactly the state someone debugging a missing endpoint needs to be able to see.
        JsonArray transportStates = new JsonArray();
        for (McpTransport transport : transports) {
            JsonObject state = new JsonObject();
            state.addProperty("kind", transport.describeKind());
            state.addProperty("target", transport.describeTarget());
            state.addProperty("running", transport.isRunning());
            transportStates.add(state);
        }
        status.add("transports", transportStates);
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
}
