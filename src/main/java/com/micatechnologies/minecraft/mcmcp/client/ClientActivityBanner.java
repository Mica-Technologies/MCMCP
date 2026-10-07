package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.link.LinkActivity;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolActivity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * A small line at the top of the screen saying what an agent is doing in this game.
 *
 * <h2>Why</h2>
 *
 * A person watching the game, rather than the orchestrator's window, saw the player move and blocks
 * change with no idea why, or whether the agent was half way through something or finished. This
 * shows the tool running and how far through it is, and — when the agent keeps a task list — the task
 * it is working on, which the orchestrator sends over the link.
 *
 * <h2>When</h2>
 *
 * While a tool is running or the agent says a task is in progress, then for a few seconds after,
 * fading out. Never under F1, never with {@code display.showActivityBanner} off, and never in a
 * {@code client_screenshot}: the line is for the person at the screen, and an agent would only pay to
 * look at its own status. {@link #beginCapture()} is how the screenshot tool keeps it out.
 */
@SideOnly(Side.CLIENT)
public final class ClientActivityBanner {

    /** How long the line stays after the work stops, then how long it takes to fade. */
    private static final long LINGER_MILLIS = 4000L;
    private static final long FADE_MILLIS = 1000L;

    /** Room left for the input-lock banner, which also sits at the top centre, when it is up. */
    private static final int BELOW_INPUT_LOCK = 30;

    private static boolean registered;

    /** Set while a screenshot is being taken; read on the client thread every frame. */
    private static volatile boolean capturing;
    /** Whether the line was drawn in the last frame, so a capture knows whether to wait one out. */
    private static volatile boolean shownLastFrame;
    private static long lastActiveMillis;

    private ClientActivityBanner() {
    }

    /** Subscribes to overlay rendering. Called once from the client proxy. */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new Events());
        registered = true;
    }

    /**
     * Keeps the line out of frames until {@link #endCapture()}. Returns whether it was on screen, in
     * which case the caller must let a frame render before reading the framebuffer — the frame there
     * now still has the line in it. When it was not on screen there is nothing to wait for, so an
     * ordinary screenshot costs nothing extra.
     */
    public static boolean beginCapture() {
        capturing = true;
        return shownLastFrame;
    }

    public static void endCapture() {
        capturing = false;
    }

    public static final class Events {

        @SubscribeEvent
        public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
            if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
                return;
            }
            shownLastFrame = draw(event.getResolution());
        }
    }

    /** Draws the line if it should be up; returns whether it drew anything. */
    private static boolean draw(ScaledResolution resolution) {
        Minecraft mc = Minecraft.getMinecraft();
        if (capturing || !McmcpConfig.isShowActivityBanner() || mc.gameSettings.hideGUI
            || mc.fontRenderer == null) {
            return false;
        }

        ToolActivity.RunningCall call = ToolActivity.newestRunning();
        LinkActivity.Task task = LinkActivity.current();
        long now = System.currentTimeMillis();
        boolean active = call != null || (task != null && task.isInProgress());
        if (active) {
            lastActiveMillis = now;
        }
        long idle = now - lastActiveMillis;
        if (lastActiveMillis == 0 || idle > LINGER_MILLIS + FADE_MILLIS) {
            return false;
        }
        float opacity = idle <= LINGER_MILLIS ? 1F : 1F - (idle - LINGER_MILLIS) / (float) FADE_MILLIS;

        String callText = call == null ? null : describe(call);
        String first;
        String second;
        if (task != null) {
            first = "● " + task.getList() + (task.getProgress().isEmpty() ? "" : " · " + task.getProgress());
            second = task.getTitle() + (callText == null ? "" : " — " + callText);
        }
        else {
            first = "● MCMCP" + (callText == null ? " · idle" : " · " + callText);
            second = null;
        }

        FontRenderer font = mc.fontRenderer;
        int maxWidth = Math.max(120, resolution.getScaledWidth() * 3 / 5);
        first = font.trimStringToWidth(first, maxWidth);
        second = second == null ? null : font.trimStringToWidth(second, maxWidth);
        int width = Math.max(font.getStringWidth(first), second == null ? 0 : font.getStringWidth(second));
        int left = (resolution.getScaledWidth() - width) / 2;
        int top = ClientInputLock.isLocked() ? BELOW_INPUT_LOCK + 4 : 4;
        int height = second == null ? 10 : 21;

        int background = (int) (0x90 * opacity) << 24;
        int alpha = Math.max(0x10, (int) (0xFF * opacity)) << 24;
        Gui.drawRect(left - 4, top - 3, left + width + 4, top + height, background);
        font.drawStringWithShadow(first, left, top, alpha | 0x7FC8F8);
        if (second != null) {
            font.drawStringWithShadow(second, left, top + 11, alpha | 0xE6E6E6);
        }
        return true;
    }

    /** "client_sequence 12/40 · client_wait", or the tool alone before it reports anything. */
    private static String describe(ToolActivity.RunningCall call) {
        StringBuilder text = new StringBuilder(call.getTool());
        if (call.getProgress() >= 0) {
            text.append(' ').append((long) Math.floor(call.getProgress()));
            if (call.getTotal() > 0) {
                text.append('/').append((long) Math.floor(call.getTotal()));
            }
        }
        if (call.getMessage() != null && !call.getMessage().isEmpty()) {
            text.append(" · ").append(call.getMessage());
        }
        return text.toString();
    }
}
