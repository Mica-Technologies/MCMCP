package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

/**
 * The region a /fill or /clone writes, worked out the way the command works it out. Getting it wrong
 * records the wrong blocks, and an undo then puts back nothing — or the wrong things.
 */
class CommandUndoTest {

    /** A sender standing at 100 64 -20, which is where ~ is measured from. */
    private static final ICommandSender SENDER = new ICommandSender() {
        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean canUseCommand(int permLevel, String commandName) {
            return true;
        }

        @Override
        public BlockPos getPosition() {
            return new BlockPos(100, 64, -20);
        }

        @Override
        public Vec3d getPositionVector() {
            return new Vec3d(100.5, 64, -19.5);
        }

        @Override
        public World getEntityWorld() {
            return null;
        }

        @Override
        public MinecraftServer getServer() {
            return null;
        }
    };

    private static String box(BlockPos[] box) {
        return box[0].getX() + "," + box[0].getY() + "," + box[0].getZ() + " -> "
            + box[1].getX() + "," + box[1].getY() + "," + box[1].getZ();
    }

    @Test
    void aFillWritesItsRegionWhicheverWayRoundTheCornersAre() {
        List<BlockPos[]> boxes = CommandUndo.boxes(SENDER, "fill 5 70 5 1 60 9 minecraft:stone");
        assertEquals(1, boxes.size());
        assertEquals("1,60,5 -> 5,70,9", box(boxes.get(0)));
    }

    @Test
    void relativeCoordinatesAreMeasuredFromTheSender() {
        List<BlockPos[]> boxes = CommandUndo.boxes(SENDER, "minecraft:fill ~ ~ ~ ~2 ~1 ~-3 air");
        assertEquals("100,64,-23 -> 102,65,-20", box(boxes.get(0)));
    }

    @Test
    void aCloneWritesItsDestinationAndAMoveEmptiesItsSourceToo() {
        List<BlockPos[]> copy = CommandUndo.boxes(SENDER, "clone 0 60 0 3 61 3 50 60 50");
        assertEquals(1, copy.size());
        assertEquals("50,60,50 -> 53,61,53", box(copy.get(0)));

        List<BlockPos[]> move = CommandUndo.boxes(SENDER, "clone 0 60 0 3 61 3 50 60 50 replace move");
        assertEquals(2, move.size());
        assertEquals("0,60,0 -> 3,61,3", box(move.get(1)));
    }

    @Test
    void otherCommandsAndUnparseableOnesAreLeftAlone() {
        assertNull(CommandUndo.boxes(SENDER, "time set day"));
        assertNull(CommandUndo.boxes(SENDER, "fill a b c 1 2 3 stone"));
        assertNull(CommandUndo.boxes(SENDER, "fill 1 2 3"));
    }
}
