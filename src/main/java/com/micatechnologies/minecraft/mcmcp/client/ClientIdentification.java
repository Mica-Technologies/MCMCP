package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.McmcpIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ScaledResolution;
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
 * The configured instance name is intentionally safe to show: unlike the instance secret and bearer
 * token, it is an operator-facing label already sent to the orchestrator.
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

    private static String instanceName() {
        McmcpIdentity identity = McmcpConfig.identity();
        return identity.getInstanceName();
    }

    private static void drawLabel(Minecraft mc, int x, int y) {
        if (mc.fontRenderer != null) {
            mc.fontRenderer.drawStringWithShadow("MCMCP [" + instanceName() + "]", x, y, 0xFF55FF);
        }
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

        /** Adds the instance name to the F3 overlay without altering any debug values. */
        @SubscribeEvent
        public void onDebugOverlay(RenderGameOverlayEvent.Post event) {
            if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.gameSettings.showDebugInfo) {
                drawLabel(mc, 2, 2);
            }
        }

        /** Identifies a client before a world is opened, where the title is easy to miss. */
        @SubscribeEvent
        public void onMainMenu(GuiScreenEvent.DrawScreenEvent.Post event) {
            if (!(event.getGui() instanceof GuiMainMenu)) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            ScaledResolution resolution = new ScaledResolution(mc);
            String label = "MCMCP [" + instanceName() + "]";
            if (mc.fontRenderer != null) {
                int x = resolution.getScaledWidth() - mc.fontRenderer.getStringWidth(label) - 3;
                mc.fontRenderer.drawStringWithShadow(label, x, 3, 0xFF55FF);
            }
        }
    }
}
