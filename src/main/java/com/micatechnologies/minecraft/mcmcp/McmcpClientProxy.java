package com.micatechnologies.minecraft.mcmcp;

import com.micatechnologies.minecraft.mcmcp.client.ClientThreadBridge;
import com.micatechnologies.minecraft.mcmcp.client.tools.ClientDebugTools;
import com.micatechnologies.minecraft.mcmcp.client.tools.ClientGuiTools;
import com.micatechnologies.minecraft.mcmcp.client.tools.ClientInputTools;
import com.micatechnologies.minecraft.mcmcp.client.tools.ClientPrompts;
import com.micatechnologies.minecraft.mcmcp.client.tools.ClientResources;
import com.micatechnologies.minecraft.mcmcp.client.tools.ClientStateTools;
import com.micatechnologies.minecraft.mcmcp.client.tools.ClientSyncTools;
import com.micatechnologies.minecraft.mcmcp.client.tools.ClientWorldTools;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import javax.annotation.Nullable;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Client-side proxy: owns the client MCP endpoint and registers the client-only tool set.
 *
 * <p>The client endpoint is MCMCP's primary mode. A model attached here drives one player on
 * whatever server that player is connected to — no server-side install, no operator rights, no
 * inbound port on the server. It also gets the things only a client has: a framebuffer to
 * screenshot, an input pipeline to drive, and a GUI stack to inspect.
 */
@SideOnly(Side.CLIENT)
public class McmcpClientProxy implements McmcpProxy {

    @Nullable
    private McpEndpoint clientEndpoint;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
    }

    @Override
    public void init(FMLInitializationEvent event) {
    }

    @Override
    public void registerSideSpecific() {
        ClientStateTools.register();
        ClientDebugTools.register();
        ClientInputTools.register();
        ClientGuiTools.register();
        ClientSyncTools.register();
        ClientWorldTools.register();
        ClientResources.register();
        ClientPrompts.register();
    }

    @Override
    public void startClientEndpoint() {
        if (clientEndpoint != null) {
            return;
        }
        if (!McmcpConfig.isClientEndpointEnabled()) {
            Mcmcp.LOGGER.info("MCMCP client endpoint is disabled in the config; not starting it.");
            return;
        }
        clientEndpoint = McpEndpoint.startOrLog(
            McmcpSide.CLIENT,
            McmcpConfig.settingsFor(McmcpSide.CLIENT),
            new ClientThreadBridge());
    }

    @Override
    public void stopClientEndpoint() {
        if (clientEndpoint != null) {
            clientEndpoint.stop();
            clientEndpoint = null;
        }
    }

    @Override
    @Nullable
    public McpEndpoint getClientEndpoint() {
        return clientEndpoint;
    }
}
