package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.events.ChatAttribution;
import com.micatechnologies.minecraft.mcmcp.events.EventLog;
import com.micatechnologies.minecraft.mcmcp.events.GameEvent;
import com.micatechnologies.minecraft.mcmcp.events.Roster;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Feeds the client's {@link EventLog} (#201): chat lines as {@link ClientChatRecorder} records them,
 * and joins and leaves from the tab list.
 *
 * <p>Client thread only. Appending never blocks, so nothing here waits on an agent.
 */
@SideOnly(Side.CLIENT)
public final class ClientEventHooks {

    /** How often the tab list is compared, in ticks. A join a quarter-second late is fine. */
    private static final int ROSTER_INTERVAL_TICKS = 5;

    private static final Roster ROSTER = new Roster();

    /** The connection the roster's baseline belongs to; a new one starts a new baseline. */
    private static NetHandlerPlayClient rosterConnection;

    private static int ticks;

    private static boolean registered;

    private ClientEventHooks() {
    }

    public static synchronized void register() {
        if (!registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.register(new ClientEventHooks());
        }
    }

    private static EventLog log() {
        return EventLog.forSide(McmcpSide.CLIENT.id());
    }

    /**
     * A line {@link ClientChatRecorder} recorded. The action bar is left out (it is a status display
     * that some mods rewrite every tick), and so is what this client printed itself, which no other
     * player sent.
     */
    static void onChatRecorded(ClientChatRecorder.Entry entry) {
        try {
            String type = entry.getType();
            if ("GAME_INFO".equals(type) || "LOCAL".equals(type)) {
                return;
            }
            ChatAttribution.Line line = ChatAttribution.attribute(entry.getTranslationKey(),
                entry.getArguments(), entry.getText(), ROSTER::isOnline);
            log().append(GameEvent.of(GameEvent.CHAT).player(line.player, null).text(line.text));
        }
        catch (RuntimeException ignored) {
            // The chat line is recorded either way; an event is not worth an exception in a handler.
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % ROSTER_INTERVAL_TICKS != 0) {
            return;
        }
        try {
            lookAtRoster(Minecraft.getMinecraft());
        }
        catch (RuntimeException ignored) {
            // Next look.
        }
    }

    private static void lookAtRoster(Minecraft mc) {
        NetHandlerPlayClient connection = mc.getConnection();
        if (connection != rosterConnection) {
            rosterConnection = connection;
            ROSTER.reset();
        }
        if (connection == null || mc.player == null) {
            return;
        }
        Map<String, String> now = new LinkedHashMap<>();
        for (NetworkPlayerInfo info : connection.getPlayerInfoMap()) {
            if (info.getGameProfile() == null) {
                continue;
            }
            String name = info.getGameProfile().getName();
            if (ChatAttribution.isPlayerName(name)) {
                now.put(name, info.getGameProfile().getId() == null ? null
                    : info.getGameProfile().getId().toString());
            }
        }
        for (Roster.Change change : ROSTER.update(now)) {
            log().append(GameEvent.of(change.joined ? GameEvent.JOIN : GameEvent.LEAVE)
                .player(change.player, change.uuid)
                .online(ROSTER.online()));
        }
    }
}
