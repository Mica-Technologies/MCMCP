package com.micatechnologies.minecraft.mcmcp;

import com.micatechnologies.minecraft.mcmcp.command.CommandMcmcp;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.prompts.CommonPrompts;
import com.micatechnologies.minecraft.mcmcp.resources.CommonResources;
import com.micatechnologies.minecraft.mcmcp.tools.CommonTools;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * MCMCP — a Model Context Protocol server embedded in Minecraft.
 *
 * <p>MCMCP exposes a running game as an MCP server, so any MCP client (an editor, an agent
 * framework, a CLI) can inspect and act on it through the same protocol it uses for everything
 * else. It runs two independent endpoints:
 *
 * <ul>
 *   <li><b>Client endpoint</b> — inside a player's game client, controlling that player. This is
 *       the one that works everywhere: it needs nothing installed on the server, no operator
 *       rights, and no inbound port, so it works on any server the player can join. It can do
 *       exactly what the player could do by hand, and nothing more.</li>
 *   <li><b>Server endpoint</b> — inside a dedicated or integrated server. Authoritative world state
 *       for every player, commands with server authority, no camera and no input. Off by default;
 *       it is a much larger grant and belongs to whoever operates the server.</li>
 * </ul>
 *
 * <p>The two are not alternatives. A singleplayer world with both running gives a model a camera
 * and player control on one port and authoritative world queries on another.
 *
 * <h2>Lifecycle ordering</h2>
 *
 * Registration happens in {@code preInit}/{@code init}; endpoints bind later — the client endpoint
 * in {@code postInit}, the server endpoint on {@code FMLServerStartingEvent}. Binding earlier would
 * accept calls while registries are still being populated, and the first tool call would observe a
 * half-constructed game.
 */
@Mod(modid = McmcpConstants.MOD_NAMESPACE,
     version = McmcpConstants.MOD_VERSION,
     name = McmcpConstants.MOD_NAME,
     acceptedMinecraftVersions = "[1.12.2]",
     // No dependencies. MCMCP integrates with other mods by letting them register tools against it,
     // never by requiring them — the mod has to be droppable into any 1.12.2 pack.
     dependencies = "")
public class Mcmcp {

    public static final Logger LOGGER = LogManager.getLogger(McmcpConstants.MOD_NAMESPACE);

    @SidedProxy(clientSide = "com.micatechnologies.minecraft.mcmcp.McmcpClientProxy",
                serverSide = "com.micatechnologies.minecraft.mcmcp.McmcpCommonProxy")
    public static McmcpProxy proxy;

    @Mod.Instance(McmcpConstants.MOD_NAMESPACE)
    public static Mcmcp instance;

    @Nullable
    private static volatile McpEndpoint serverEndpoint;

    /** Ticks between session sweeps; 600 ticks is 30 seconds at a healthy tick rate. */
    private static final int SWEEP_INTERVAL_TICKS = 600;

    private int ticksSinceSweep;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        McmcpConfig.init(event.getSuggestedConfigurationFile());
        CommonTools.register();
        CommonResources.register();
        CommonPrompts.register();
        proxy.preInit(event);
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info(McmcpConstants.MOD_NAME + " " + McmcpConstants.MOD_VERSION + " loaded.");
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
        proxy.registerSideSpecific();
    }

    /**
     * Starts the client endpoint.
     *
     * <p>{@code postInit} is the earliest point where every mod's registration has run, so the tool
     * catalogue a connecting client sees is complete. Any mod that registers tools later still
     * works — the registry emits {@code notifications/tools/list_changed} — but starting here means
     * the common case needs no such notification.
     */
    @EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.startClientEndpoint();
        // Endpoints run on daemon threads, so they cannot keep the JVM alive; this hook exists to
        // release the ports and close sessions cleanly on a normal quit. The client has no Forge
        // shutdown event to hang this on.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdownEndpoints();
            }
        }, "MCMCP-shutdown"));
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandMcmcp());

        if (!McmcpConfig.isServerEndpointEnabled()) {
            return;
        }
        serverEndpoint = McpEndpoint.startOrLog(
            McmcpSide.SERVER,
            McmcpConfig.settingsFor(McmcpSide.SERVER),
            new ServerThreadBridge());
    }

    /**
     * Stops the server endpoint when the world does.
     *
     * <p>{@code FMLServerStoppingEvent} rather than {@code Stopped}: on a client, "server stopping"
     * is the player leaving a singleplayer world, and the endpoint's tools reference that world.
     * Holding the port open past that point leaves a live MCP endpoint answering questions about a
     * world that no longer exists.
     */
    @EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        McpEndpoint endpoint = serverEndpoint;
        if (endpoint != null) {
            endpoint.stop();
            serverEndpoint = null;
        }
    }

    /**
     * Drives session expiry off the server tick.
     *
     * <p>A dedicated timer thread would work too, but the tick is already running and sweeping is
     * cheap — and tying it to the tick means a paused or stalled server does not silently expire
     * sessions that are perfectly healthy.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++ticksSinceSweep < SWEEP_INTERVAL_TICKS) {
            return;
        }
        ticksSinceSweep = 0;
        for (McpEndpoint endpoint : allEndpoints()) {
            endpoint.tick();
        }
    }

    // ------------------------------------------------------------------
    // Endpoint access
    // ------------------------------------------------------------------

    @Nullable
    public static McpEndpoint getServerEndpoint() {
        return serverEndpoint;
    }

    @Nullable
    public static McpEndpoint getClientEndpoint() {
        return proxy == null ? null : proxy.getClientEndpoint();
    }

    /** Every endpoint currently running in this process; zero, one or two entries. */
    public static List<McpEndpoint> allEndpoints() {
        List<McpEndpoint> endpoints = new ArrayList<>(2);
        McpEndpoint client = getClientEndpoint();
        if (client != null) {
            endpoints.add(client);
        }
        McpEndpoint server = serverEndpoint;
        if (server != null) {
            endpoints.add(server);
        }
        return endpoints;
    }

    /** Stops every endpoint. Backs {@code /mcmcp stop} and the JVM shutdown hook. */
    public static void shutdownEndpoints() {
        McpEndpoint server = serverEndpoint;
        if (server != null) {
            server.stop();
            serverEndpoint = null;
        }
        if (proxy != null) {
            proxy.stopClientEndpoint();
        }
    }

    /**
     * Re-reads the config and restarts every endpoint that should be running.
     *
     * <p>A full stop and start rather than mutating live settings: the bind address, port and worker
     * pool are fixed at socket-creation time, so "reload" that did not rebind would silently apply
     * only half the file.
     */
    public static void restartEndpoints() {
        shutdownEndpoints();
        McmcpConfig.reload();
        if (proxy != null) {
            proxy.startClientEndpoint();
        }
        if (McmcpConfig.isServerEndpointEnabled() && ServerThreadBridge.server() != null) {
            serverEndpoint = McpEndpoint.startOrLog(
                McmcpSide.SERVER,
                McmcpConfig.settingsFor(McmcpSide.SERVER),
                new ServerThreadBridge());
        }
    }
}
