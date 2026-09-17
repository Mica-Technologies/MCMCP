package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import com.micatechnologies.minecraft.mcmcp.transport.McpTransport;
import com.micatechnologies.minecraft.mcmcp.transport.ReverseTransport;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.Display;

/**
 * Makes the particular MCMCP instance visible wherever a person identifies a running client.
 *
 * <p>Several development clients often use the same game directory and look otherwise identical.
 * The name shown is the one a person would use for the instance: the label typed into the
 * orchestrator if there is one, otherwise the configured {@code instanceName}. Both are
 * operator-facing labels already sent over the link, unlike the instance secret and bearer token.
 */
@SideOnly(Side.CLIENT)
public final class ClientIdentification {

    private static final String PREFIX = "Minecraft 1.12.2 - MCMCP [";

    private static boolean registered;

    private ClientIdentification() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new Events());
        registered = true;
    }

    static String windowTitle(String instanceName) {
        return PREFIX + instanceName + "]";
    }

    /**
     * The name to show: an orchestrator label wins over the configured one, as
     * {@code LinkHandshake.Result#getAssignedName} documents, because somebody chose it on purpose.
     */
    static String displayName(@Nullable String assignedName, String configuredName) {
        return assignedName == null || assignedName.trim().isEmpty() ? configuredName : assignedName.trim();
    }

    /**
     * Reads the label from whichever orchestrator link has one.
     *
     * <p>A label belongs to the game rather than an endpoint, so in singleplayer the client and
     * integrated-server links carry the same one. The last label received is kept across a dropped
     * link: a window that renames itself whenever the orchestrator restarts identifies nothing.
     */
    private static String instanceName() {
        String assigned = null;
        List<McpEndpoint> endpoints = Mcmcp.allEndpoints();
        for (McpEndpoint endpoint : endpoints) {
            for (McpTransport transport : endpoint.transports()) {
                if (transport instanceof ReverseTransport) {
                    String name = ((ReverseTransport) transport).getAssignedName();
                    if (name != null) {
                        assigned = name;
                    }
                }
            }
        }
        return displayName(assigned, McmcpConfig.identity().getInstanceName());
    }

    /** Forge listeners kept together so registration cannot accidentally occur twice. */
    public static class Events {

        /** Vanilla can restore its title while changing displays; keep the visible identity stable. */
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || !Display.isCreated()) {
                return;
            }
            String wanted = windowTitle(instanceName());
            if (!wanted.equals(Display.getTitle())) {
                Display.setTitle(wanted);
            }
        }

        /**
         * Adds the instance as a line of the F3 overlay, under the game version.
         *
         * <p>Through Forge's text event rather than drawn afterwards: F3's own lines start at the
         * top-left corner, so anything painted there lands on top of them.
         */
        @SubscribeEvent
        public void onDebugText(RenderGameOverlayEvent.Text event) {
            if (!Minecraft.getMinecraft().gameSettings.showDebugInfo) {
                return;
            }
            List<String> left = event.getLeft();
            left.add(Math.min(1, left.size()),
                TextFormatting.LIGHT_PURPLE + "MCMCP [" + instanceName() + "]");
        }

        /** Identifies a client before a world is opened, where the title is easy to miss. */
        @SubscribeEvent
        public void onMainMenu(GuiScreenEvent.DrawScreenEvent.Post event) {
            if (!(event.getGui() instanceof GuiMainMenu)) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.fontRenderer == null) {
                return;
            }
            ScaledResolution resolution = new ScaledResolution(mc);
            String label = "MCMCP [" + instanceName() + "]";
            int x = resolution.getScaledWidth() - mc.fontRenderer.getStringWidth(label) - 3;
            mc.fontRenderer.drawStringWithShadow(label, x, 3, 0xFF55FF);
        }
    }
}
