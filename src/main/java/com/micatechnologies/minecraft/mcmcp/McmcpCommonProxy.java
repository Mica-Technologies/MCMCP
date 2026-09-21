package com.micatechnologies.minecraft.mcmcp;

import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import javax.annotation.Nullable;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * Server-side proxy: everything client-specific is a no-op.
 *
 * <p>A dedicated server has no client endpoint, no framebuffer to screenshot and no keyboard to
 * drive. Its MCP surface is the server endpoint, which common code starts on
 * {@code FMLServerStartingEvent} — nothing on this path needs a proxy at all.
 */
public class McmcpCommonProxy implements McmcpProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
    }

    @Override
    public void init(FMLInitializationEvent event) {
    }

    @Override
    public void registerSideSpecific() {
        // Server-only tools register from CommonTools, which is loaded on both sides and filters by
        // McmcpSide. There is nothing here that could not be named from common code.
    }

    @Override
    public void startClientEndpoint() {
    }

    @Override
    public void stopClientEndpoint() {
    }

    @Override
    @Nullable
    public McpEndpoint getClientEndpoint() {
        return null;
    }

    @Override
    @Nullable
    public String creativeTabLabel(net.minecraft.creativetab.CreativeTabs tab) {
        return null;
    }

    @Override
    @Nullable
    public com.google.gson.JsonObject describeTileEntityRendering(net.minecraft.tileentity.TileEntity tileEntity) {
        return null;
    }
}
