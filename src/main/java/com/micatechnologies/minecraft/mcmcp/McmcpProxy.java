package com.micatechnologies.minecraft.mcmcp;

import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import javax.annotation.Nullable;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * The client/server split for everything MCMCP does that cannot exist on both sides.
 *
 * <p>Two things sit behind this interface, and both would crash a dedicated server if named from
 * common code:
 *
 * <ul>
 *   <li>The <b>client endpoint</b> itself. It needs {@code ClientThreadBridge}, which references
 *       {@code Minecraft}; class-loading that server-side trips Forge's {@code SideTransformer}
 *       with "Attempted to load class ... for invalid side SERVER".</li>
 *   <li>The <b>client tool set</b> — screenshots, synthetic input, GUI inspection. Same reason.</li>
 * </ul>
 *
 * <p>The server proxy implements every method as a no-op rather than throwing. A dedicated server
 * calling {@code startClientEndpoint} is not an error condition to be surfaced; it is the normal
 * result of shared startup code running on the side that has no client.
 */
public interface McmcpProxy {

    void preInit(FMLPreInitializationEvent event);

    void init(FMLInitializationEvent event);

    /**
     * Registers the tools, resources and prompts that only exist on this side.
     *
     * <p>Separate from {@code preInit} because registration must happen after
     * {@link com.micatechnologies.minecraft.mcmcp.tools.CommonTools} has registered the shared
     * catalogue — tool names are unique registry-wide, and ordering keeps a collision reported
     * against the mod that introduced it.
     */
    void registerSideSpecific();

    /**
     * Starts the client endpoint, if this side has one and the config enables it.
     *
     * <p>Called once the client is far enough along to serve requests. Not in {@code preInit}: an
     * endpoint that binds during mod construction accepts calls while registries are still being
     * populated, and the first tool call would see a half-built world.
     */
    void startClientEndpoint();

    void stopClientEndpoint();

    /** The running client endpoint, or null on a server or when it is disabled or failed to bind. */
    @Nullable
    McpEndpoint getClientEndpoint();
}
