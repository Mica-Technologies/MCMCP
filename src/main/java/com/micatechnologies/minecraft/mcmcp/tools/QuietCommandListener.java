package com.micatechnologies.minecraft.mcmcp.tools;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import java.lang.reflect.Field;
import javax.annotation.Nullable;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandListener;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;

/**
 * What a command's success message does while an MCMCP tool call is running it: it goes back to the
 * sender (and so into the tool result) and into the server log, and to nobody else (#196).
 *
 * <h2>Why</h2>
 *
 * Vanilla's listener ({@code ServerCommandManager.notifyListener}) also sends every success message to
 * every other operator online, as a grey {@code [Name: ...]} chat line. An agent sends hundreds of
 * commands, so that is hundreds of lines in each operator's chat, and it is worse than noise:
 * {@code /blockdata} puts the whole tile entity in its message, and a chat packet over 32,767 bytes
 * fails to encode and disconnects the player it was for. One 135 KB result dropped every operator on
 * a live server, and to them it looked like a network fault.
 *
 * <p>The sender's own {@code sendCommandFeedback} cannot switch the echo off, because a player's
 * command runs as the player itself (#64) and that method reads a game rule. The echo is decided in
 * one place, the static listener on {@link CommandBase}, so that is what is swapped, for the length of
 * the command, on the server thread.
 */
final class QuietCommandListener implements ICommandListener {

    /** The longest admin line written to the server log; the tool result carries the whole message. */
    static final int LOG_LIMIT = 2000;

    /** {@code CommandBase.commandListener}: private, static, and with a setter but no getter. */
    @Nullable
    private static final Field LISTENER = findListenerField();

    private static final QuietCommandListener INSTANCE = new QuietCommandListener();

    private QuietCommandListener() {
    }

    /**
     * Makes this the command listener and returns the one it replaced, to hand to {@link #restore}.
     * Returns null, changing nothing, when the current listener cannot be read: without it there
     * would be nothing to put back. Server thread only.
     */
    @Nullable
    static ICommandListener install() {
        if (LISTENER == null) {
            return null;
        }
        try {
            ICommandListener previous = (ICommandListener) LISTENER.get(null);
            if (previous == null || previous == INSTANCE) {
                return null;
            }
            CommandBase.setCommandListener(INSTANCE);
            return previous;
        }
        catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    /** Puts back what {@link #install} replaced; null (nothing was replaced) is ignored. */
    static void restore(@Nullable ICommandListener previous) {
        if (previous != null) {
            CommandBase.setCommandListener(previous);
        }
    }

    @Override
    public void notifyListener(ICommandSender sender, ICommand command, int flags, String translationKey,
                               Object... translationArgs) {
        MinecraftServer server = sender.getServer();
        if (server != null && sender != server && server.worlds.length > 0
            && server.worlds[0].getGameRules().getBoolean("logAdminCommands")) {
            ITextComponent line = new TextComponentTranslation("chat.type.admin", sender.getName(),
                new TextComponentTranslation(translationKey, translationArgs));
            try {
                server.sendMessage(new TextComponentString(clip(line.getUnformattedText(), LOG_LIMIT)));
            }
            catch (RuntimeException e) {
                // A mod's message that cannot be rendered must not fail the command that sent it.
                Mcmcp.LOGGER.debug("Could not log a command's admin line", e);
            }
        }
        // Flag 1 is vanilla's "do not tell the sender". The sendCommandFeedback game rule is not asked:
        // the caller is waiting for this output, and with a player's chat captured it goes nowhere else.
        if ((flags & 1) != 1) {
            sender.sendMessage(new TextComponentTranslation(translationKey, translationArgs));
        }
    }

    /** {@code text}, or its first {@code limit} characters and a count of what was left out. */
    static String clip(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "... (" + (text.length() - limit) + " more characters)";
    }

    @Nullable
    private static Field findListenerField() {
        try {
            // The SRG name; ObfuscationReflectionHelper maps it to the dev name in a dev workspace.
            return ObfuscationReflectionHelper.findField(CommandBase.class, "field_71533_a");
        }
        catch (RuntimeException e) {
            return null;
        }
    }
}
