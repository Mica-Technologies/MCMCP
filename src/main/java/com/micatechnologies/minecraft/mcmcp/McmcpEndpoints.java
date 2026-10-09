package com.micatechnologies.minecraft.mcmcp;

import com.micatechnologies.minecraft.mcmcp.game.GameThreadBridge;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.game.McmcpProcess;
import com.micatechnologies.minecraft.mcmcp.mcp.RequestJournal;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.link.LinkSettings;
import com.micatechnologies.minecraft.mcmcp.protocol.EndpointAddress;
import com.micatechnologies.minecraft.mcmcp.protocol.McpDispatcher;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpointSettings;
import com.micatechnologies.minecraft.mcmcp.transport.ReverseTransport;
import java.io.File;
import java.io.IOException;
import javax.annotation.Nullable;
import net.minecraftforge.fml.common.Loader;

/**
 * Builds a fully wired endpoint — HTTP transport, orchestrator link, dispatcher — for either side.
 *
 * <p>One place rather than two. The client proxy and the server startup handler were each
 * constructing an endpoint, and each acquiring a second transport separately is how the two sides
 * drift until only one of them can be orchestrated. Both now call {@link #start}, and anything added
 * to the wiring reaches both by construction.
 *
 * <p>Nothing here names a type from {@code client/}: this is common code, and a dedicated server
 * that class-loads a client type dies at startup.
 */
public final class McmcpEndpoints {

    /** One journal per side for the life of the process, so a restarted endpoint keeps writing the same run. */
    private static final java.util.Map<McmcpSide, RequestJournal> JOURNALS = new java.util.EnumMap<>(McmcpSide.class);

    private McmcpEndpoints() {
    }

    /**
     * The request journal for {@code side}, opened on first use.
     *
     * <p>Process-wide rather than per endpoint: an endpoint restarts with {@code /mcmcp restart} and
     * a singleplayer server endpoint comes and goes with each world, but a crash is a property of the
     * process, and the open line that {@code previousRun} looks for should mark the process starting.
     */
    public static synchronized RequestJournal journal(McmcpSide side) {
        RequestJournal journal = JOURNALS.get(side);
        if (journal == null) {
            File directory = new File(McmcpPaths.gameDirectory(), "mcmcp/journal");
            journal = new RequestJournal(directory, side.id(), new RequestJournal.Settings() {
                @Override
                public boolean enabled() {
                    return McmcpConfig.isJournalEnabled();
                }

                @Override
                public long maxFileBytes() {
                    return McmcpConfig.getJournalMaxFileBytes();
                }

                @Override
                public int files() {
                    return McmcpConfig.getJournalFiles();
                }
            });
            final File file = journal.getFile();
            journal.setOnFailure(() -> Mcmcp.LOGGER.warn("MCMCP stopped writing its request journal at "
                + file + " after repeated write failures. Tool calls are no longer being recorded."));
            journal.open(McmcpProcess.pid(), McmcpConstants.MOD_VERSION, System.currentTimeMillis());
            JOURNALS.put(side, journal);
        }
        return journal;
    }

    /**
     * Writes one side's clean-exit line, if its journal is open. The server side calls this when the
     * server stops: on a dedicated server that is the process ending, and in singleplayer it is the
     * world closing, after which the next world's first call opens a new run.
     */
    public static synchronized void closeJournal(McmcpSide side) {
        RequestJournal journal = JOURNALS.get(side);
        if (journal != null) {
            journal.close(System.currentTimeMillis());
        }
    }

    /** Writes each journal's clean-exit line. Called from the JVM shutdown hook. */
    public static synchronized void closeJournals() {
        for (RequestJournal journal : JOURNALS.values()) {
            journal.close(System.currentTimeMillis());
        }
    }

    /**
     * Builds and starts one endpoint, logging rather than throwing if it cannot come up at all.
     *
     * @return the running endpoint, or null if every transport failed
     */
    @Nullable
    public static McpEndpoint start(McmcpSide side, GameThreadBridge gameThread) {
        McpEndpointSettings settings = McmcpConfig.settingsFor(side);
        McpEndpoint endpoint = new McpEndpoint(side, settings, gameThread);
        endpoint.getDispatcher().setJournal(journal(side));
        return start(endpoint, null);
    }

    /**
     * Starts an endpoint built around a dispatcher of its own: the companion's virtual server
     * endpoint, whose dispatcher forwards to the server the player is on. It gets the same HTTP
     * transport, orchestrator link and address as any other server endpoint, so it appears to an
     * orchestrator as this game's {@code .server}, exactly as a singleplayer world's would.
     *
     * @param via how the endpoint's tools are reached, told to the orchestrator ({@code "companion"})
     */
    @Nullable
    public static McpEndpoint startVirtual(McpDispatcher dispatcher, String via) {
        McpEndpointSettings settings = McmcpConfig.settingsFor(dispatcher.getSide());
        return start(new McpEndpoint(dispatcher.getSide(), settings, dispatcher), via);
    }

    @Nullable
    private static McpEndpoint start(final McpEndpoint endpoint, @Nullable String via) {
        McmcpSide side = endpoint.getSide();
        final ReverseTransport link = McmcpConfig.isOrchestratorLinkEnabled()
            ? attachOrchestratorLink(side, endpoint, via) : null;
        // Always set, link or not: a direct endpoint accepts 'instance' naming itself either way,
        // and only the steering note depends on the link being up.
        endpoint.getDispatcher().setAddress(new EndpointAddress(
            McmcpConfig.identity().getInstanceId(),
            side.id(),
            () -> link == null ? null : link.getAssignedName(),
            () -> link != null && link.isRunning()));

        try {
            endpoint.start();
            return endpoint;
        }
        catch (IOException e) {
            // A failed start must never take the game down with it. With the orchestrator link
            // enabled this is now genuinely rare — the link needs no port, so reaching here means
            // both the port was taken and the link was turned off.
            Mcmcp.LOGGER.error("MCMCP could not start the " + side.id() + " endpoint: " + e.getMessage()
                + ". The game will run without it. If the port is already in use — a second game "
                + "instance, or a previous one still exiting — either change it in the MCMCP config "
                + "and use '/mcmcp restart', or enable the orchestrator link, which needs no port.");
            return null;
        }
    }

    @Nullable
    private static ReverseTransport attachOrchestratorLink(McmcpSide side, final McpEndpoint endpoint,
                                                           @Nullable final String via) {
        LinkSettings linkSettings = McmcpConfig.linkSettings();
        McmcpIdentity identity = McmcpConfig.identity();

        if (!identity.hasSecret()) {
            // Should be impossible: the config generates one on load. Refusing to dial without it is
            // still the right answer, because an orchestrator would have to either reject the
            // handshake or accept an unauthenticated instance, and the second is worse.
            Mcmcp.LOGGER.error("MCMCP has no instance secret, so the orchestrator link cannot start. "
                + "Clear identity.instanceSecret in the MCMCP config and restart to regenerate it.");
            return null;
        }

        ReverseTransport link = new ReverseTransport(
            linkSettings,
            identity,
            side,
            endpoint.getDispatcher(),
            endpoint.getSessions(),
            new ReverseTransport.HelloDetails() {

                @Override
                @Nullable
                public String getGameDirectory() {
                    File directory = McmcpPaths.gameDirectory();
                    return directory == null ? null : directory.getAbsolutePath();
                }

                @Override
                public String getModVersion() {
                    return McmcpConstants.MOD_VERSION;
                }

                @Override
                public String getMinecraftVersion() {
                    return Loader.MC_VERSION;
                }

                @Override
                @Nullable
                public String getEndpointUrl() {
                    // Resolved per handshake rather than captured once: on a reconnect after
                    // '/mcmcp restart' the HTTP transport may have bound a port it could not get
                    // the first time, and the stale answer would be worse than none.
                    return endpoint.httpUrlIfRunning();
                }

                @Override
                @Nullable
                public String getVia() {
                    return via;
                }
            });
        endpoint.addTransport(link);
        return link;
    }
}
