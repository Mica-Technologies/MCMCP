package com.micatechnologies.minecraft.mcmcp.game;

import com.micatechnologies.minecraft.mcmcp.events.EventLog;
import com.micatechnologies.minecraft.mcmcp.events.GameEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/**
 * Feeds the server's {@link EventLog} from Forge's events (#201): who said what, with their exact
 * name and uuid, and who joined or left, with no chat text to parse and nothing that depends on a
 * locale or a plugin's wording.
 *
 * <p>Every handler only appends to the log, which never blocks: the game thread must not wait on
 * an agent. Everything is caught, because an exception escaping an event handler can take the
 * event's other listeners with it.
 */
public final class ServerEventHooks {

    /**
     * Commands whose arguments are a password on servers that run an auth plugin. Their arguments
     * are never recorded: an event stream is read by agents and goes into their transcripts.
     */
    private static final Set<String> SECRET_COMMANDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "login", "l", "log", "register", "reg", "changepassword", "changepass", "unregister", "auth")));

    private static boolean registered;

    private ServerEventHooks() {
    }

    public static synchronized void register() {
        if (!registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.register(new ServerEventHooks());
        }
    }

    private static EventLog log() {
        return EventLog.forSide(McmcpSide.SERVER.id());
    }

    private static void append(GameEvent.Draft draft) {
        try {
            log().append(draft);
        }
        catch (RuntimeException ignored) {
            // Never at the cost of the event this was watching.
        }
    }

    /** Lowest and not cancelled: what a chat-filter mod lets through is what other players saw. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChat(ServerChatEvent event) {
        if (event.isCanceled() || event.getPlayer() == null) {
            return;
        }
        append(GameEvent.of(GameEvent.CHAT)
            .player(event.getUsername(), event.getPlayer().getUniqueID().toString())
            .text(event.getMessage()));
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        List<String> online = online(player.getServer(), null);
        if (!online.contains(player.getName())) {
            online.add(player.getName());
        }
        append(GameEvent.of(GameEvent.JOIN).player(player.getName(), player.getUniqueID().toString())
            .online(online));
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        // Fired before the player leaves the list.
        append(GameEvent.of(GameEvent.LEAVE).player(player.getName(), player.getUniqueID().toString())
            .online(online(player.getServer(), player.getName())));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntityLiving() instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.getEntityLiving();
        String message;
        try {
            message = player.getCombatTracker().getDeathMessage().getUnformattedText();
        }
        catch (RuntimeException e) {
            message = player.getName() + " died";
        }
        append(GameEvent.of(GameEvent.DEATH).player(player.getName(), player.getUniqueID().toString())
            .text(message));
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent event) {
        EntityPlayer player = event.getEntityPlayer();
        Advancement advancement = event.getAdvancement();
        if (!(player instanceof EntityPlayerMP) || advancement == null) {
            return;
        }
        // Only what the game announces: every recipe unlock is an advancement too, with no display.
        DisplayInfo display = advancement.getDisplay();
        if (display == null || !display.shouldAnnounceToChat()) {
            return;
        }
        append(GameEvent.of(GameEvent.ADVANCEMENT).player(player.getName(), player.getUniqueID().toString())
            .text(display.getTitle().getUnformattedText())
            .detail(advancement.getId().toString()));
    }

    /** Commands players run. Not the console's or MCMCP's own, which an agent already knows about. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onCommand(CommandEvent event) {
        if (event.isCanceled() || !(event.getSender() instanceof EntityPlayerMP) || event.getCommand() == null) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.getSender();
        String name = event.getCommand().getName();
        String[] parameters = event.getParameters();
        String text = "/" + name;
        if (parameters != null && parameters.length > 0) {
            text += SECRET_COMMANDS.contains(name.toLowerCase(Locale.ROOT))
                ? " [hidden]" : " " + String.join(" ", parameters);
        }
        append(GameEvent.of(GameEvent.COMMAND).player(player.getName(), player.getUniqueID().toString())
            .text(text));
    }

    private static List<String> online(MinecraftServer server, String leaving) {
        List<String> names = new ArrayList<>();
        if (server == null || server.getPlayerList() == null) {
            return names;
        }
        for (String name : server.getPlayerList().getOnlinePlayerNames()) {
            if (!name.equals(leaving)) {
                names.add(name);
            }
        }
        return names;
    }
}
