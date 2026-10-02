package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolActivity;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Holds the client's frame limit up while an agent is driving it.
 *
 * <h2>Why</h2>
 *
 * Agents drive a client while the person is using another window, and mods throttle exactly that
 * state. FPS Reducer, in the Alto packs, writes {@code gameSettings.limitFramerate = 10} when the
 * window loses focus or sits without input for five minutes. Everything an agent does runs through
 * those frames: each hop to the client thread waits for one, chunk meshes are built in a per-frame
 * budget, and screenshots show terrain that has not arrived. Surveys of a remote server came back
 * with whole regions unloaded (#41), indistinguishable from empty ones.
 *
 * <h2>How</h2>
 *
 * While active — {@code client_input_lock} held, or a client tool called within
 * {@code keepAwakeSeconds} — any frame limit below {@code keepAwakeFps} is raised to it. Nothing is
 * ever lowered. Checked at the end of every tick and frame, so a mod that writes the limit at tick
 * start is overridden before the frame limiter reads it. This works on the setting rather than on
 * any one mod, so it covers whatever else throttles the same way.
 *
 * <p>When the agent goes quiet the overwritten value is given back, but only if the limit still
 * holds what this wrote. If something else changed it meanwhile — FPS Reducer restoring the
 * player's own limit when they click back in — that value is newer and is left alone.
 */
@SideOnly(Side.CLIENT)
public final class ClientKeepAwake {

    private static boolean registered;

    /** The limit this replaced, or -1 when it is not holding anything. Client thread only. */
    private static int replacedLimit = -1;
    /** The limit this wrote, to tell whether anyone has written over it since. */
    private static int heldLimit = -1;

    private ClientKeepAwake() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new ClientKeepAwake.Events());
        registered = true;
    }

    /** Whether an agent counts as driving the client right now. Any thread. */
    public static boolean isActive() {
        int seconds = McmcpConfig.getKeepAwakeSeconds();
        if (seconds <= 0) {
            return false;
        }
        if (ClientInputLock.isLocked()) {
            return true;
        }
        long last = ToolActivity.lastCallMillis(McmcpSide.CLIENT);
        return last > 0 && System.currentTimeMillis() - last < seconds * 1000L;
    }

    /** Whether this is currently overriding a lower limit. Client thread only. */
    public static boolean isHolding() {
        return replacedLimit >= 0;
    }

    /** The limit this replaced, or -1. Client thread only. */
    public static int replacedLimit() {
        return replacedLimit;
    }

    private static void apply() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings == null) {
            return;
        }
        int current = mc.gameSettings.limitFramerate;
        if (isActive()) {
            int floor = McmcpConfig.getKeepAwakeFps();
            if (current < floor) {
                if (replacedLimit < 0 || current != heldLimit) {
                    replacedLimit = current;
                }
                mc.gameSettings.limitFramerate = floor;
                heldLimit = floor;
            }
            return;
        }
        if (replacedLimit >= 0) {
            if (current == heldLimit) {
                mc.gameSettings.limitFramerate = replacedLimit;
            }
            replacedLimit = -1;
            heldLimit = -1;
        }
    }

    /** Nested so a second register() cannot put the statics on the bus twice. */
    public static class Events {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                apply();
            }
        }

        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                apply();
            }
        }
    }
}
