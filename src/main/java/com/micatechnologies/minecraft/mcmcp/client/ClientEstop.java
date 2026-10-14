package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.game.Estop;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

/**
 * What an emergency stop does on a client: lets go of every key an agent is holding and gives the
 * mouse back, so a player stops walking the moment the stop lands, and says so in chat.
 *
 * <p>The client endpoint's dispatcher already refuses every call that is not read-only while stopped;
 * this ends what was already in motion.
 */
public final class ClientEstop {

    private static boolean registered;

    private ClientEstop() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        Estop.addListener((now, before) -> {
            boolean fromServer = now != null ? now.source == Estop.Source.REMOTE
                : before != null && before.source == Estop.Source.REMOTE;
            if (fromServer) {
                announce(now);
            }
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                if (now != null) {
                    ClientInputScheduler.releaseAll();
                    ClientInputLock.release("an MCMCP emergency stop");
                }
                // The server announces its own stop in chat; only one from a remote server needs saying here.
                boolean remote = now != null ? now.source == Estop.Source.REMOTE
                    : before != null && before.source == Estop.Source.REMOTE;
                if (remote && mc.player != null) {
                    mc.player.sendMessage(new TextComponentString(now == null
                        ? TextFormatting.GREEN + "[MCMCP] The server lifted its emergency stop."
                        : TextFormatting.RED + "[MCMCP] Emergency stop: this client's MCMCP will not move you or change "
                            + "anything until the server lifts it (" + now.by + ": " + now.reason + ")."));
                }
            });
        });
    }

    /**
     * Tells the client endpoint's sessions, the orchestrator among them, that the server this client
     * is on set or lifted its stop: the app shows a companion game's stop on the game a person sees.
     */
    private static void announce(@javax.annotation.Nullable Estop.Stop now) {
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("message", now == null ? "The server lifted its MCMCP emergency stop." : Estop.refusal());
        data.addProperty("active", now != null);
        if (now != null) {
            data.add("estop", now.toJson());
        }
        com.google.gson.JsonObject params = new com.google.gson.JsonObject();
        params.addProperty("level", now == null ? "notice" : "alert");
        params.addProperty("logger", "mcmcp.estop");
        params.add("data", data);
        com.google.gson.JsonObject notification = com.micatechnologies.minecraft.mcmcp.protocol.JsonRpc.notification(
            com.micatechnologies.minecraft.mcmcp.protocol.McpProtocol.NOTIFICATION_MESSAGE, params);
        for (com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint endpoint : com.micatechnologies.minecraft.mcmcp.Mcmcp.allEndpoints()) {
            if (endpoint.getSide().isClient()) {
                endpoint.getSessions().broadcast(notification);
            }
        }
    }
}
