package com.micatechnologies.minecraft.mcmcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Undo points for the block-writing commands, {@code /fill} and {@code /clone}, run through
 * {@code server_run_command}.
 *
 * <p>A command writes the world without passing through MCMCP's own write path, so there is no
 * per-block hook to record from. Instead the region a command will write is read just before it
 * runs, in the same game-thread task, and compared just after: whatever changed is recorded exactly
 * as a {@code server_set_blocks} write would be. Vanilla caps both commands at 32,768 blocks, which
 * bounds the read.
 *
 * <p>The region is resolved with the command's own sender, so relative coordinates ({@code ~}) mean
 * what they mean to the command. A command whose arguments do not parse is left to fail on its own,
 * unrecorded.
 */
final class CommandUndo {

    /** Vanilla's limit on one fill or clone; past it the command refuses and there is nothing to record. */
    private static final long MAX_BLOCKS = 32_768L;

    /** The blocks one command may change, read before it runs. */
    static final class Snapshot {

        final World world;
        final List<BlockPos> positions = new ArrayList<>();
        final List<UndoPoints.Before> before = new ArrayList<>();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        Snapshot(World world) {
            this.world = world;
        }

        /** Records into {@code recorder} every block that changed since the snapshot. */
        void recordChanges(UndoPoints.Recorder recorder) {
            for (int i = 0; i < positions.size(); i++) {
                BlockPos pos = positions.get(i);
                UndoPoints.Before was = before.get(i);
                boolean changed = world.getBlockState(pos) != was.state
                    || !Objects.equals(UndoPoints.tileEntityNbt(world, pos), was.tileEntity);
                if (changed) {
                    recorder.record(pos, was, world.getBlockState(pos));
                }
            }
        }

        /** The map area to draw, or null when the command spread too far to draw. */
        @Nullable
        int[] area() {
            return positions.isEmpty() ? null : UndoPoints.imageArea(minX, minZ, maxX, maxZ);
        }
    }

    private CommandUndo() {
    }

    /**
     * Reads what a {@code fill} or {@code clone} command is about to write, or returns null for any
     * other command, or one whose arguments do not parse. Game thread.
     */
    @Nullable
    static Snapshot before(ICommandSender sender, String command, UndoPoints.Recorder recorder) {
        List<BlockPos[]> boxes = boxes(sender, command);
        if (boxes == null) {
            return null;
        }
        long volume = 0;
        for (BlockPos[] box : boxes) {
            volume += (long) (box[1].getX() - box[0].getX() + 1) * (box[1].getY() - box[0].getY() + 1)
                * (box[1].getZ() - box[0].getZ() + 1);
        }
        if (volume > MAX_BLOCKS * 2) {
            return null;
        }
        World world = sender.getEntityWorld();
        Snapshot snapshot = new Snapshot(world);
        for (BlockPos[] box : boxes) {
            for (BlockPos pos : BlockPos.getAllInBox(box[0], box[1])) {
                if (pos.getY() < 0 || pos.getY() > 255 || !world.isBlockLoaded(pos)) {
                    continue;
                }
                BlockPos fixed = pos.toImmutable();
                snapshot.positions.add(fixed);
                snapshot.before.add(recorder.capture(world, fixed));
                snapshot.minX = Math.min(snapshot.minX, fixed.getX());
                snapshot.minZ = Math.min(snapshot.minZ, fixed.getZ());
                snapshot.maxX = Math.max(snapshot.maxX, fixed.getX());
                snapshot.maxZ = Math.max(snapshot.maxZ, fixed.getZ());
            }
        }
        return snapshot;
    }

    /**
     * The boxes, {min, max}, a fill or clone command writes: a fill's region, a clone's destination
     * and, when it moves, its source. Null for anything else.
     */
    @Nullable
    static List<BlockPos[]> boxes(ICommandSender sender, String command) {
        String[] parts = command.trim().split("\\s+");
        String name = parts[0].toLowerCase(Locale.ROOT);
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        List<BlockPos[]> boxes = new ArrayList<>();
        try {
            if ("fill".equals(name) && args.length >= 7) {
                boxes.add(box(CommandBase.parseBlockPos(sender, args, 0, false),
                    CommandBase.parseBlockPos(sender, args, 3, false)));
                return boxes;
            }
            if ("clone".equals(name) && args.length >= 9) {
                BlockPos[] source = box(CommandBase.parseBlockPos(sender, args, 0, false),
                    CommandBase.parseBlockPos(sender, args, 3, false));
                BlockPos destination = CommandBase.parseBlockPos(sender, args, 6, false);
                boxes.add(new BlockPos[] {destination, destination.add(source[1].subtract(source[0]))});
                for (int i = 9; i < args.length; i++) {
                    if ("move".equalsIgnoreCase(args[i])) {
                        boxes.add(source);
                    }
                }
                return boxes;
            }
        }
        catch (CommandException e) {
            return null;
        }
        return null;
    }

    private static BlockPos[] box(BlockPos a, BlockPos b) {
        return new BlockPos[] {
            new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
            new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))};
    }
}
