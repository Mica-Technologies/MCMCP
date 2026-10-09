package com.micatechnologies.minecraft.mcmcp.client.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.McmcpEndpoints;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionFrames;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionHandshake;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionHandshake.State;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionProtocol;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionProtocolException;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionReassembler;
import com.micatechnologies.minecraft.mcmcp.companion.server.CompanionServer;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;

/**
 * The client half of the companion: notices that the server the player joined has MCMCP's companion,
 * asks to use it, and while it may, presents the server's tools as this game's server endpoint.
 *
 * <h2>Finding out</h2>
 *
 * The server's channel list arrives with the login handshake, before the player is in the world, so
 * whether it has {@code mcmcp:companion} is known without sending anything. A server without it is
 * never sent a byte. One with it gets a hello, and its welcome says whether this player may use it.
 *
 * <h2>What the agent sees</h2>
 *
 * While the companion is available, a virtual server endpoint runs in this game — the same HTTP port
 * and orchestrator link a singleplayer world's server endpoint would have — whose every message is
 * forwarded to the server. The orchestrator shows it as this game's {@code .server}. It goes away
 * when the player leaves the server.
 */
public final class ClientCompanion {

    private static final AtomicInteger THREADS = new AtomicInteger();

    /** Endpoint starts and stops happen off the netty thread, one at a time, in order. */
    private static final ExecutorService LIFECYCLE = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MCMCP-companion-client-" + THREADS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private static volatile boolean serverHasChannel;

    /**
     * Set at connection, cleared once the hello is sent. Sent from the first client tick with a
     * player in the world, not from the connected event: that fires during the login handshake, and
     * a custom payload sent then reaches a server that has no play handler for the player yet and
     * drops it without a word.
     */
    private static volatile boolean helloPending;
    private static volatile State state = State.ABSENT;
    private static volatile String detail = "Not connected to a multiplayer server.";
    @Nullable
    private static volatile String serverModVersion;

    /** Fed from the netty thread only. Replaced per connection. */
    private static CompanionReassembler reassembler = newReassembler();
    private static final Object SEND_LOCK = new Object();
    private static int nextStream;

    @Nullable
    private static volatile CompanionForwarder forwarder;
    @Nullable
    private static volatile McpEndpoint endpoint;

    private ClientCompanion() {
    }

    public static void register() {
        CompanionServer.channel().register(new PacketHandler());
        MinecraftForge.EVENT_BUS.register(new ConnectionWatcher());
    }

    @Nullable
    public static McpEndpoint endpoint() {
        return endpoint;
    }

    /** For {@code mcmcp_endpoint_info} and {@code client_connection_info}. */
    public static JsonObject status() {
        JsonObject json = new JsonObject();
        json.addProperty("state", state.name().toLowerCase(java.util.Locale.ROOT));
        json.addProperty("detail", detail);
        if (serverModVersion != null) {
            json.addProperty("serverModVersion", serverModVersion);
        }
        McpEndpoint running = endpoint;
        if (running != null) {
            String url = running.httpUrlIfRunning();
            if (url != null) {
                json.addProperty("httpEndpoint", url);
            }
        }
        return json;
    }

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    private static void connected(boolean local) {
        reassembler = newReassembler();
        if (local) {
            set(State.ABSENT, "Playing singleplayer: the world's own server endpoint serves its tools.", null);
            return;
        }
        if (!serverHasChannel) {
            set(State.ABSENT, "This server does not run MCMCP's companion.", null);
            return;
        }
        set(State.NEGOTIATING, "Asking the server for its companion; waiting for the answer.", null);
        helloPending = true;
    }

    private static void sendHelloIfDue() {
        if (!helloPending || net.minecraft.client.Minecraft.getMinecraft().player == null
            || net.minecraft.client.Minecraft.getMinecraft().getConnection() == null) {
            return;
        }
        helloPending = false;
        send(CompanionProtocol.TYPE_HELLO, CompanionHandshake.hello(McmcpConstants.MOD_VERSION));
    }

    private static void disconnected() {
        serverHasChannel = false;
        helloPending = false;
        set(State.ABSENT, "Not connected to a multiplayer server.", null);
        LIFECYCLE.execute(ClientCompanion::stopEndpoint);
    }

    private static void welcomed(@Nullable JsonObject welcome) {
        State answer = CompanionHandshake.stateOf(welcome);
        String version = welcome == null ? null : Json.getString(welcome, "modVersion");
        switch (answer) {
            case AVAILABLE:
                set(State.AVAILABLE, "The server's tools are available as this game's server endpoint.", version);
                LIFECYCLE.execute(ClientCompanion::startEndpoint);
                break;
            case INCOMPATIBLE:
                set(State.INCOMPATIBLE, "The server's MCMCP (" + version + ") speaks companion protocol "
                    + CompanionHandshake.protocolOf(welcome) + "; this client speaks "
                    + CompanionProtocol.VERSION + ". Update whichever MCMCP is older.", version);
                break;
            default:
                set(State.UNAUTHORISED, Json.getString(welcome, "reason", "The server refused."), version);
                break;
        }
        Mcmcp.LOGGER.info("MCMCP companion: " + detail);
    }

    private static void set(State newState, String newDetail, @Nullable String version) {
        state = newState;
        detail = newDetail;
        serverModVersion = version;
    }

    private static void startEndpoint() {
        stopEndpoint();
        CompanionForwarder created = new CompanionForwarder(bytes -> send(CompanionProtocol.TYPE_MCP, bytes));
        forwarder = created;
        endpoint = McmcpEndpoints.startVirtual(created, "companion");
        if (endpoint == null) {
            Mcmcp.LOGGER.warn("MCMCP companion: the server's tools are available, but the endpoint that "
                + "presents them could not start (see above).");
        }
    }

    private static void stopEndpoint() {
        CompanionForwarder old = forwarder;
        forwarder = null;
        if (old != null) {
            old.close();
        }
        McpEndpoint running = endpoint;
        endpoint = null;
        if (running != null) {
            running.stop();
        }
    }

    // ------------------------------------------------------------------
    // Frames
    // ------------------------------------------------------------------

    /** Splits a message into frames under the client-to-server limit and sends them in order. */
    private static void send(byte type, byte[] message) {
        synchronized (SEND_LOCK) {
            int stream = nextStream;
            nextStream = (nextStream + 1) & 0x7FFFFFFF;
            for (byte[] frame : CompanionFrames.encode(type, stream, message, CompanionProtocol.MAX_C2S_FRAME)) {
                ByteBuf buffer = Unpooled.wrappedBuffer(frame);
                CompanionServer.channel().sendToServer(
                    new FMLProxyPacket(new PacketBuffer(buffer), CompanionProtocol.CHANNEL));
            }
        }
    }

    private static void onFrame(byte[] frame) {
        CompanionReassembler.Message message;
        try {
            message = reassembler.accept(frame);
        }
        catch (CompanionProtocolException e) {
            Mcmcp.LOGGER.warn("MCMCP companion: the server sent something this client cannot read ("
                + e.getMessage() + "); its tools are unavailable until you rejoin.");
            set(State.INCOMPATIBLE, "The server sent a message this client could not read: " + e.getMessage(),
                serverModVersion);
            LIFECYCLE.execute(ClientCompanion::stopEndpoint);
            return;
        }
        if (message == null) {
            return;
        }
        if (message.type == CompanionProtocol.TYPE_WELCOME) {
            welcomed(CompanionHandshake.parse(message.bytes));
        }
        else if (message.type == CompanionProtocol.TYPE_BYE) {
            // The server ended this player's access while they stay connected. A later welcome (an
            // operator allowing them again) brings the tools back without a rejoin.
            JsonObject bye = CompanionHandshake.parse(message.bytes);
            set(State.UNAUTHORISED, Json.getString(bye, "reason", "The server ended companion access."),
                serverModVersion);
            Mcmcp.LOGGER.info("MCMCP companion: " + detail);
            LIFECYCLE.execute(ClientCompanion::stopEndpoint);
        }
        else if (message.type == CompanionProtocol.TYPE_MCP) {
            CompanionForwarder current = forwarder;
            if (current == null) {
                return;
            }
            try {
                JsonElement parsed = Json.parse(new String(message.bytes, StandardCharsets.UTF_8));
                if (parsed != null && parsed.isJsonObject()) {
                    current.deliver(parsed.getAsJsonObject());
                }
            }
            catch (RuntimeException e) {
                Mcmcp.LOGGER.debug("MCMCP companion: unreadable message from the server: " + e.getMessage());
            }
        }
    }

    private static CompanionReassembler newReassembler() {
        // Generous: replies from the server are bounded by its own limits, and it is the peer this
        // client chose to trust with its tools. The limit is against a broken one, not a hostile one.
        return new CompanionReassembler(256 * 1024 * 1024, 64);
    }

    // ------------------------------------------------------------------
    // Event handlers
    // ------------------------------------------------------------------

    /** On the companion channel's bus. */
    public static final class PacketHandler {

        /** Never lets anything escape: FML ends the connection over an exception thrown here. */
        @SubscribeEvent
        public void onClientPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
            try {
                ByteBuf payload = event.getPacket().payload();
                byte[] frame = new byte[payload.readableBytes()];
                payload.readBytes(frame);
                onFrame(frame);
            }
            catch (Throwable t) {
                Mcmcp.LOGGER.error("MCMCP companion: failed handling a message from the server; the "
                    + "server's tools are unavailable until you rejoin", t);
                set(State.INCOMPATIBLE, "This client failed to handle a message from the server: " + t, serverModVersion);
                LIFECYCLE.execute(ClientCompanion::stopEndpoint);
            }
        }
    }

    /** On Forge's bus. The network events arrive on the netty thread, the tick on the client thread. */
    public static final class ConnectionWatcher {

        @SubscribeEvent
        public void onRegistration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
            // The server's channel list, sent with the login handshake, before the connected event.
            if (event.getSide() == net.minecraftforge.fml.relauncher.Side.CLIENT
                && "REGISTER".equals(event.getOperation())
                && event.getRegistrations().contains(CompanionProtocol.CHANNEL)) {
                serverHasChannel = true;
            }
        }

        @SubscribeEvent
        public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
            connected(event.isLocal());
        }

        @SubscribeEvent
        public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            disconnected();
        }

        @SubscribeEvent
        public void onClientTick(net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent event) {
            if (event.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) {
                sendHelloIfDue();
            }
        }
    }
}
