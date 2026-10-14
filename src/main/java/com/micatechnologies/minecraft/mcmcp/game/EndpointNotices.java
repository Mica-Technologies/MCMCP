package com.micatechnologies.minecraft.mcmcp.game;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.transport.HttpMcpTransport;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import com.micatechnologies.minecraft.mcmcp.transport.McpTransport;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/**
 * Tells the person playing when the server endpoint is not on the port they expect.
 *
 * <p>A second game started with default ports finds the server port taken by the first. The log said
 * so, but nobody reads the log of a game that seems fine, and a script pointed at the documented port
 * then reaches the other game's world. A chat line on joining is the one place the person is looking.
 *
 * <p>Told to everyone in singleplayer and to operators on a dedicated server.
 */
public final class EndpointNotices {

    private static boolean registered;

    private EndpointNotices() {
    }

    public static synchronized void register() {
        if (!registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.register(new EndpointNotices());
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        MinecraftServer server = player.getServer();
        if (server == null || (server.isDedicatedServer() && !server.getPlayerList().canSendCommands(player.getGameProfile()))) {
            return;
        }
        String notice = describe(Mcmcp.getServerEndpoint());
        if (notice != null) {
            player.sendMessage(new TextComponentString(TextFormatting.GOLD + "[MCMCP] " + TextFormatting.RESET + notice));
        }
    }

    /** What is wrong with the server endpoint's HTTP port, or null if nothing is. */
    private static String describe(McpEndpoint endpoint) {
        if (endpoint == null) {
            return null;
        }
        for (McpTransport transport : endpoint.transports()) {
            if (!(transport instanceof HttpMcpTransport)) {
                continue;
            }
            HttpMcpTransport http = (HttpMcpTransport) transport;
            if (http.fellBack()) {
                return "The server endpoint's usual port was taken, probably by another game. It is on "
                    + http.describeTarget() + " instead; a script using the usual port reaches the other game.";
            }
            if (!http.isRunning()) {
                return "The server endpoint is not listening on " + http.describeTarget()
                    + " (port taken). A script using that port reaches another game.";
            }
        }
        return null;
    }
}
