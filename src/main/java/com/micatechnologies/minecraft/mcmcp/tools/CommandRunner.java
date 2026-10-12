package com.micatechnologies.minecraft.mcmcp.tools;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.command.CommandException;
import net.minecraft.command.CommandHandler;
import net.minecraft.command.CommandResultStats;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandManager;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;

/**
 * Runs one command for a command tool: Forge's {@link CommandEvent} sees who the command is really
 * for, and the command itself runs through a {@link CapturingCommandSender} so its output comes back.
 *
 * <h2>Why not {@code executeCommand}</h2>
 *
 * {@code CommandHandler.executeCommand} posts the event with the sender it is given, and permission
 * mods decide on that event by the sender's type. ForgeEssentials moves its whole permission check
 * there: a sender that is neither a player nor the server is an unknown NPC with no permissions, so
 * every MCMCP command was cancelled. And Forge returns 1 for a cancelled command, the value for
 * success, so the tool reported success for commands that never ran (#60).
 *
 * <p>This is that method with one change: the event carries the identity — the player, or the server
 * for console authority — while the command runs with the capturing sender, which delegates every
 * permission check to the same identity. Nothing is granted that the identity lacked; a mod's veto
 * now judges the right party, and is reported as a failure.
 */
final class CommandRunner {

    /** What one command did. */
    static final class Outcome {

        /** Minecraft's result: the number of times the command succeeded, 0 for none. */
        final int result;
        /** A {@link CommandEvent} listener cancelled it before it ran. */
        final boolean cancelled;

        Outcome(int result, boolean cancelled) {
            this.result = result;
            this.cancelled = cancelled;
        }

        boolean succeeded() {
            return result != 0 && !cancelled;
        }
    }

    private CommandRunner() {
    }

    /**
     * Runs {@code rawCommand} (no leading slash needed) on the game thread.
     *
     * @param identity who the command is for: a player, or the server
     * @param sender   the capturing sender wrapping {@code identity}, which receives the output
     */
    static Outcome run(MinecraftServer server, ICommandSender identity, CapturingCommandSender sender,
                       String rawCommand) {
        ICommandManager manager = server.getCommandManager();
        if (!(manager instanceof CommandHandler)) {
            // A replaced command system (Sponge and the like) is driven only through its own entry.
            return new Outcome(manager.executeCommand(sender, rawCommand), false);
        }
        String command = rawCommand.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        String[] split = command.split(" ");
        String[] args = new String[split.length - 1];
        System.arraycopy(split, 1, args, 0, args.length);
        ICommand icommand = manager.getCommands().get(split[0]);
        int succeeded = 0;
        try {
            int usernameIndex = usernameIndex(icommand, args);
            if (icommand == null) {
                sender.sendMessage(red("commands.generic.notFound"));
            }
            else if (icommand.checkPermission(server, sender)) {
                CommandEvent event = new CommandEvent(icommand, identity, args);
                if (MinecraftForge.EVENT_BUS.post(event)) {
                    Throwable thrown = event.getException();
                    if (thrown != null) {
                        sender.sendMessage(red("commands.generic.exception"));
                        Mcmcp.LOGGER.warn("A CommandEvent listener failed on: " + command, thrown);
                    }
                    return new Outcome(0, true);
                }
                if (event.getParameters() != null) {
                    args = event.getParameters();
                }
                if (usernameIndex > -1) {
                    List<Entity> entities = EntitySelector.matchEntities(sender, args[usernameIndex], Entity.class);
                    String selector = args[usernameIndex];
                    sender.setCommandStat(CommandResultStats.Type.AFFECTED_ENTITIES, entities.size());
                    if (entities.isEmpty()) {
                        throw new PlayerNotFoundException("commands.generic.selector.notFound", selector);
                    }
                    for (Entity entity : entities) {
                        args[usernameIndex] = entity.getCachedUniqueIdString();
                        if (tryExecute(server, sender, args, icommand, command)) {
                            succeeded++;
                        }
                    }
                    args[usernameIndex] = selector;
                }
                else {
                    sender.setCommandStat(CommandResultStats.Type.AFFECTED_ENTITIES, 1);
                    if (tryExecute(server, sender, args, icommand, command)) {
                        succeeded++;
                    }
                }
            }
            else {
                sender.sendMessage(red("commands.generic.permission"));
            }
        }
        catch (CommandException e) {
            sender.sendMessage(red(e.getMessage(), e.getErrorObjects()));
        }
        sender.setCommandStat(CommandResultStats.Type.SUCCESS_COUNT, succeeded);
        return new Outcome(succeeded, false);
    }

    /** Says where a cancelling mod's own reason went, which is not to the capturing sender. */
    static String cancelledMessage(ICommandSender identity) {
        String who = identity instanceof EntityPlayerMP ? identity.getName() : "the server console";
        String where = identity instanceof EntityPlayerMP ? identity.getName() + "'s chat" : "the server log";
        return "A mod on the server cancelled this command before it ran (Forge's CommandEvent): usually "
            + "a permissions or protection mod refusing it for " + who + ". Its reason, if it gave one, "
            + "went to " + where + ".";
    }

    private static int usernameIndex(@Nullable ICommand command, String[] args) throws CommandException {
        if (command == null) {
            return -1;
        }
        for (int i = 0; i < args.length; i++) {
            if (command.isUsernameIndex(args, i) && EntitySelector.matchesMultiplePlayers(args[i])) {
                return i;
            }
        }
        return -1;
    }

    private static boolean tryExecute(MinecraftServer server, ICommandSender sender, String[] args, ICommand command,
                                      String input) {
        try {
            command.execute(server, sender, args);
            return true;
        }
        catch (WrongUsageException e) {
            sender.sendMessage(red("commands.generic.usage",
                new TextComponentTranslation(e.getMessage(), e.getErrorObjects())));
        }
        catch (CommandException e) {
            sender.sendMessage(red(e.getMessage(), e.getErrorObjects()));
        }
        catch (Throwable e) {
            sender.sendMessage(red("commands.generic.exception"));
            Mcmcp.LOGGER.warn("Couldn't process command: " + input, e);
        }
        return false;
    }

    private static TextComponentTranslation red(String key, Object... arguments) {
        TextComponentTranslation text = new TextComponentTranslation(key, arguments);
        text.getStyle().setColor(TextFormatting.RED);
        return text;
    }
}
