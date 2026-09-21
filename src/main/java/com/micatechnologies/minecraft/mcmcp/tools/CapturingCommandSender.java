package com.micatechnologies.minecraft.mcmcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.command.CommandResultStats;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

/**
 * An {@link ICommandSender} that runs a command with a delegate's identity and permissions while
 * collecting everything the command says.
 *
 * <p>Commands in 1.12.2 report their results by calling {@code sendMessage} on their sender. Run one
 * through the raw server sender and that output goes to the console and is lost; the caller gets
 * back only the integer result value, which for most commands is 1 for "worked" and 0 for "did not"
 * with no indication of why. For an MCP tool that is close to useless — {@code /tp} failing because
 * the target is offline and {@code /tp} failing because the coordinates are out of range are the
 * same integer.
 *
 * <p>So every command tool runs through this wrapper: identity, position, world and — critically —
 * {@link #canUseCommand} all delegate to the real sender, so nothing here grants permission the
 * player did not have. The only behaviour that changes is where the output goes.
 */
public class CapturingCommandSender implements ICommandSender {

    private final ICommandSender delegate;
    private final List<String> output = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    public CapturingCommandSender(ICommandSender delegate) {
        this.delegate = delegate;
    }

    /** Everything the command sent to its sender, in order, as unformatted text. */
    public List<String> getOutput() {
        return Collections.unmodifiableList(output);
    }

    public String getOutputAsText() {
        return output.isEmpty() ? "" : String.join("\n", output);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public ITextComponent getDisplayName() {
        return new TextComponentString(delegate.getName());
    }

    @Override
    public void sendMessage(ITextComponent component) {
        // getUnformattedText strips the colour codes and click events that make sense in a chat
        // window and are noise in a tool result.
        output.add(component.getUnformattedText());
        // Red is how the command handler reports a CommandException, whether the command's usage was
        // wrong or it declined to act. It is the only thing that tells a failure apart from a command
        // that ran and returned 0.
        if (component.getStyle().getColor() == TextFormatting.RED) {
            errors.add(component.getUnformattedText());
        }
    }

    /** The lines the command handler sent as errors (in red). */
    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    /**
     * Delegates the permission check verbatim.
     *
     * <p>This is the security boundary of every command tool in MCMCP. Returning true here would
     * silently promote every MCP client to operator on the server the player happens to be on.
     */
    @Override
    public boolean canUseCommand(int permLevel, String commandName) {
        return delegate.canUseCommand(permLevel, commandName);
    }

    @Override
    public BlockPos getPosition() {
        return delegate.getPosition();
    }

    @Override
    public Vec3d getPositionVector() {
        return delegate.getPositionVector();
    }

    @Override
    public World getEntityWorld() {
        return delegate.getEntityWorld();
    }

    @Override
    @Nullable
    public Entity getCommandSenderEntity() {
        return delegate.getCommandSenderEntity();
    }

    /**
     * True so that commands actually produce their output.
     *
     * <p>Commands consult this before calling {@code sendMessage} with their success text. Returning
     * the delegate's value would mean a player with {@code sendCommandFeedback} off gets an empty
     * tool result, which looks identical to a command that silently did nothing.
     */
    @Override
    public boolean sendCommandFeedback() {
        return true;
    }

    @Override
    public void setCommandStat(CommandResultStats.Type type, int amount) {
        delegate.setCommandStat(type, amount);
    }

    @Override
    @Nullable
    public MinecraftServer getServer() {
        return delegate.getServer();
    }
}
