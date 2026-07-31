package com.micatechnologies.minecraft.mcmcp.command;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;

/**
 * {@code /mcmcp} — in-game control and diagnostics for the MCP endpoints.
 *
 * <p>Exists because the failure modes of an embedded network service are otherwise invisible from
 * inside the game. "Is it listening?", "did anything connect?", "what is my token?" and "pick up my
 * config edit" all have to be answerable without alt-tabbing to a log file, or the mod is
 * unpleasant to run.
 *
 * <p>{@code token} deliberately requires operator permission and never prints to chat on a shared
 * server — see {@link #handleToken}.
 */
public class CommandMcmcp extends CommandBase {

    private static final List<String> SUBCOMMANDS =
        Arrays.asList("status", "tools", "sessions", "token", "reload", "restart", "stop");

    @Override
    public String getName() {
        return "mcmcp";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/mcmcp <status|tools|sessions|token|reload|restart|stop>";
    }

    /**
     * Operator-only, in full.
     *
     * <p>Every subcommand either reveals the endpoint's address, reveals its token, or restarts it.
     * There is no read-only subset worth opening up on a shared server.
     */
    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCOMMANDS);
        }
        return new ArrayList<>();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws WrongUsageException {
        if (args.length == 0) {
            throw new WrongUsageException(getUsage(sender));
        }

        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "status":
                handleStatus(sender);
                break;
            case "tools":
                handleTools(sender);
                break;
            case "sessions":
                handleSessions(sender);
                break;
            case "token":
                handleToken(sender);
                break;
            case "reload":
                McmcpConfig.reload();
                reply(sender, TextFormatting.GREEN, "MCMCP config reloaded. Network settings take effect "
                    + "on '/mcmcp restart'.");
                break;
            case "restart":
                Mcmcp.restartEndpoints();
                reply(sender, TextFormatting.GREEN, "MCMCP endpoints restarted.");
                handleStatus(sender);
                break;
            case "stop":
                Mcmcp.shutdownEndpoints();
                reply(sender, TextFormatting.YELLOW, "MCMCP endpoints stopped. Use '/mcmcp restart' to "
                    + "bring them back.");
                break;
            default:
                throw new WrongUsageException(getUsage(sender));
        }
    }

    private void handleStatus(ICommandSender sender) {
        List<McpEndpoint> endpoints = Mcmcp.allEndpoints();
        if (endpoints.isEmpty()) {
            reply(sender, TextFormatting.RED, "No MCMCP endpoint is running.");
            reply(sender, TextFormatting.GRAY, "Client endpoint enabled: "
                + McmcpConfig.isClientEndpointEnabled() + ", server endpoint enabled: "
                + McmcpConfig.isServerEndpointEnabled() + ". Check the game log for bind errors.");
            return;
        }

        reply(sender, TextFormatting.AQUA, "MCMCP endpoints:");
        for (McpEndpoint endpoint : endpoints) {
            reply(sender, TextFormatting.WHITE, "  " + endpoint.getSide().id() + ": "
                + endpoint.getSettings().describeUrl()
                + " — " + endpoint.getSessions().count() + " session(s), "
                + McpRegistry.tools(endpoint.getSide()).size() + " tool(s)"
                + (endpoint.getSettings().isRequireAuth() ? "" : ", AUTH DISABLED"));
            if (endpoint.getSettings().isExposedBeyondLoopback()) {
                reply(sender, TextFormatting.RED, "    Bound beyond loopback and reachable from the "
                    + "network.");
            }
        }
    }

    private void handleTools(ICommandSender sender) {
        List<McpEndpoint> endpoints = Mcmcp.allEndpoints();
        if (endpoints.isEmpty()) {
            reply(sender, TextFormatting.RED, "No MCMCP endpoint is running.");
            return;
        }
        for (McpEndpoint endpoint : endpoints) {
            reply(sender, TextFormatting.AQUA, endpoint.getSide().id() + " endpoint tools:");
            for (com.micatechnologies.minecraft.mcmcp.mcp.McpTool tool
                : McpRegistry.tools(endpoint.getSide())) {
                reply(sender, TextFormatting.GRAY, "  " + tool.getName());
            }
        }
    }

    private void handleSessions(ICommandSender sender) {
        List<McpEndpoint> endpoints = Mcmcp.allEndpoints();
        if (endpoints.isEmpty()) {
            reply(sender, TextFormatting.RED, "No MCMCP endpoint is running.");
            return;
        }
        for (McpEndpoint endpoint : endpoints) {
            reply(sender, TextFormatting.AQUA, endpoint.getSide().id() + " endpoint sessions:");
            if (endpoint.getSessions().count() == 0) {
                reply(sender, TextFormatting.GRAY, "  (none connected)");
                continue;
            }
            for (McpSession session : endpoint.getSessions().all()) {
                long ageSeconds = (System.currentTimeMillis() - session.getCreatedAtMillis()) / 1000L;
                reply(sender, TextFormatting.GRAY, "  " + session.describeClient()
                    + " — protocol " + session.getProtocolVersion()
                    + ", up " + ageSeconds + "s"
                    + ", " + session.getSubscriptions().size() + " subscription(s)"
                    + (session.isInitialized() ? "" : ", NOT INITIALIZED"));
            }
        }
    }

    /**
     * Shows the bearer token.
     *
     * <p>Never printed into chat text that could be seen by anyone else: on a dedicated server the
     * token is only revealed to a player who runs the command themselves, and it is delivered as a
     * click-to-copy component so it does not sit in chat history. Console senders get it plainly —
     * a console operator already has the config file.
     */
    private void handleToken(ICommandSender sender) {
        if (!McmcpConfig.isAllowCommands() && sender instanceof EntityPlayer) {
            // Not a security control, a consistency one: if command tools are off the operator is
            // running a locked-down instance, and the token still lives in config/mcmcp.cfg.
            reply(sender, TextFormatting.YELLOW, "Command tools are disabled; read the token from "
                + "config/mcmcp.cfg.");
            return;
        }

        String token = McmcpConfig.getAuthToken();
        if (token.isEmpty()) {
            reply(sender, TextFormatting.YELLOW, "Authentication is disabled, so there is no token. "
                + "Any process that can reach the endpoint can control this game.");
            return;
        }

        if (sender instanceof EntityPlayer) {
            TextComponentString message = new TextComponentString("[MCMCP] Click to copy your API token");
            message.getStyle()
                .setColor(TextFormatting.AQUA)
                .setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, token))
                .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new TextComponentString("Puts the token in your chat box. Anyone holding it can "
                        + "control your game — do not paste it into chat.")));
            sender.sendMessage(message);
        }
        else {
            reply(sender, TextFormatting.AQUA, "MCMCP token: " + token);
        }
    }

    private static void reply(ICommandSender sender, TextFormatting colour, String message) {
        TextComponentString component = new TextComponentString(message);
        component.getStyle().setColor(colour);
        sender.sendMessage(component);
    }
}
