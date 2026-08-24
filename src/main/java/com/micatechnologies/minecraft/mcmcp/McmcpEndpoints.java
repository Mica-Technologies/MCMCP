package com.micatechnologies.minecraft.mcmcp;

import com.micatechnologies.minecraft.mcmcp.game.GameThreadBridge;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.link.LinkSettings;
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

    private McmcpEndpoints() {
    }

    /**
     * Builds and starts one endpoint, logging rather than throwing if it cannot come up at all.
     *
     * @return the running endpoint, or null if every transport failed
     */
    @Nullable
    public static McpEndpoint start(McmcpSide side, GameThreadBridge gameThread) {
        McpEndpointSettings settings = McmcpConfig.settingsFor(side);
        final McpEndpoint endpoint = new McpEndpoint(side, settings, gameThread);

        if (McmcpConfig.isOrchestratorLinkEnabled()) {
            attachOrchestratorLink(side, endpoint);
        }

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

    private static void attachOrchestratorLink(McmcpSide side, final McpEndpoint endpoint) {
        LinkSettings linkSettings = McmcpConfig.linkSettings();
        McmcpIdentity identity = McmcpConfig.identity();

        if (!identity.hasSecret()) {
            // Should be impossible: the config generates one on load. Refusing to dial without it is
            // still the right answer, because an orchestrator would have to either reject the
            // handshake or accept an unauthenticated instance, and the second is worse.
            Mcmcp.LOGGER.error("MCMCP has no instance secret, so the orchestrator link cannot start. "
                + "Clear identity.instanceSecret in the MCMCP config and restart to regenerate it.");
            return;
        }

        endpoint.addTransport(new ReverseTransport(
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
            }));
    }
}
