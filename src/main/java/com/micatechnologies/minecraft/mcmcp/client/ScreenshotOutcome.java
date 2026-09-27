package com.micatechnologies.minecraft.mcmcp.client;

import java.io.File;
import javax.annotation.Nullable;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.client.event.ScreenshotEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * What became of the last {@link ScreenshotEvent}, as the last handler left it.
 *
 * <p>{@code ScreenShotHelper.saveScreenshot} fires this event between capturing the frame and writing
 * it, and a mod handling it can do two things the helper's return value only half reports: cancel the
 * save (the return value is then the mod's cancel message, or null) or point it at a different file
 * (the return value then says "saved" and names nothing MCMCP asked for). Both look, from outside, like
 * a screenshot that "was saved" and cannot be found — which is exactly how issue #29 presented, with
 * nothing in the log because neither path logs.
 *
 * <p>Subscribed at the lowest priority with {@code receiveCanceled}, so it sees the decision every
 * other handler has already made. Client thread only; the save and this handler run on the same thread
 * in the same call, so plain fields are enough.
 */
@SideOnly(Side.CLIENT)
public final class ScreenshotOutcome {

    private static boolean registered;

    private static boolean seen;
    private static boolean cancelled;
    @Nullable
    private static File file;
    @Nullable
    private static String cancelMessage;

    private ScreenshotOutcome() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new ScreenshotOutcome.Events());
        registered = true;
    }

    /** Forgets the previous event. Call immediately before {@code saveScreenshot}. */
    public static void reset() {
        seen = false;
        cancelled = false;
        file = null;
        cancelMessage = null;
    }

    /** Whether a screenshot event fired since {@link #reset}. */
    public static boolean wasSeen() {
        return seen;
    }

    public static boolean wasCancelled() {
        return cancelled;
    }

    /** The file the save was finally aimed at, after every handler had its say. */
    @Nullable
    public static File getFile() {
        return file;
    }

    @Nullable
    public static String getCancelMessage() {
        return cancelMessage;
    }

    public static class Events {

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void onScreenshot(ScreenshotEvent event) {
            seen = true;
            cancelled = event.isCanceled();
            file = event.getScreenshotFile();
            ITextComponent message = event.getCancelMessage();
            cancelMessage = message == null ? null : message.getUnformattedText();
        }
    }
}
