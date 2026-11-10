package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.micatechnologies.minecraft.mcmcp.regions.QuarterTurns;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

class NbtPositionsTest {

    /** A 10 x 10 x 10 box at (100, 60, 200), turned a quarter and put down at (500, 70, 900). */
    private static final QuarterTurns.Box BOX = new QuarterTurns.Box(100, 60, 200, 109, 69, 209, 1, 500, 70, 900);

    private static NBTTagCompound nbt(String snbt) throws NBTException {
        return JsonToNBT.getTagFromJson(snbt);
    }

    @Test
    void aLinkToSomethingInTheCopyMovesWithItUnderWhateverNameItIsKept() throws NBTException {
        NBTTagCompound tag = nbt("{id:\"csm:thermostat\",x:101,y:61,z:202,linkX:103,linkY:68,linkZ:205,"
            + "target:{x:100,y:60,z:200},tx:109,tz:209}");
        NbtPositions.Report report = new NbtPositions.Report();
        NbtPositions.move(tag, BOX, report);

        int[] link = BOX.map(103, 68, 205);
        assertEquals(link[0], tag.getInteger("linkX"));
        assertEquals(link[1], tag.getInteger("linkY"));
        assertEquals(link[2], tag.getInteger("linkZ"));
        assertArrayEquals(BOX.map(100, 60, 200), new int[] {tag.getCompoundTag("target").getInteger("x"),
            tag.getCompoundTag("target").getInteger("y"), tag.getCompoundTag("target").getInteger("z")});
        int[] column = BOX.map(109, 60, 209);
        assertEquals(column[0], tag.getInteger("tx"), "an x and z with no y is a column");
        assertEquals(column[2], tag.getInteger("tz"));
        assertEquals(101, tag.getInteger("x"), "the tile entity's own position is its placer's to set");
        assertEquals(1, (int) report.moved.get("linkX/Y/Z"));
        assertEquals(1, (int) report.moved.get("x/y/z"));
        assertEquals(1, (int) report.moved.get("tx/z"));
    }

    @Test
    void aLinkToSomethingOutsideTheCopyIsLeftAndReported() throws NBTException {
        NBTTagCompound tag = nbt("{linkX:5000,linkY:64,linkZ:-300,count:3}");
        NbtPositions.Report report = new NbtPositions.Report();
        NbtPositions.move(tag, BOX, report);
        assertEquals(5000, tag.getInteger("linkX"));
        assertEquals(-300, tag.getInteger("linkZ"));
        assertEquals(1, (int) report.outside.get("linkX/Y/Z"));
        assertTrue(report.moved.isEmpty());
    }

    @Test
    void listsOfPositionsMoveWhetherTheyAreCompoundsArraysOrPackedLongs() throws NBTException {
        long packed = new BlockPos(104, 65, 206).toLong();
        NBTTagCompound tag = nbt("{lU:[{x:101,y:60,z:201},{x:102,y:60,z:201}],vents:[[I;105,66,207],[I;1,2,3]],"
            + "pos:" + packed + "L,seed:1234567890123L,name:\"x\"}");
        NbtPositions.Report report = new NbtPositions.Report();
        NbtPositions.move(tag, BOX, report);

        NBTTagList units = tag.getTagList("lU", 10);
        assertArrayEquals(BOX.map(102, 60, 201), new int[] {units.getCompoundTagAt(1).getInteger("x"),
            units.getCompoundTagAt(1).getInteger("y"), units.getCompoundTagAt(1).getInteger("z")});
        assertEquals(2, (int) report.moved.get("x/y/z"));
        NBTTagList vents = tag.getTagList("vents", 11);
        assertArrayEquals(BOX.map(105, 66, 207), vents.getIntArrayAt(0));
        assertArrayEquals(new int[] {1, 2, 3}, vents.getIntArrayAt(1), "three numbers that are not a cell of the box");
        int[] to = BOX.map(104, 65, 206);
        assertEquals(new BlockPos(to[0], to[1], to[2]).toLong(), tag.getLong("pos"));
        assertEquals(1234567890123L, tag.getLong("seed"), "a long that is not a cell of the box is left alone");
        assertFalse(report.outside.containsKey("seed(long)"));
    }

    @Test
    void aDirectionKeptInTheTagIsReportedWhenTheCopyIsTurnedSinceNothingHereCanTurnIt() throws NBTException {
        NBTTagCompound tag = nbt("{facing:2,Rotation:\"east\",other:1}");
        NbtPositions.Report report = new NbtPositions.Report();
        NbtPositions.move(tag, BOX, report);
        assertTrue(report.directionKeys.containsKey("facing"));
        assertTrue(report.directionKeys.containsKey("Rotation"));
        assertFalse(report.directionKeys.containsKey("other"));

        NbtPositions.Report straight = new NbtPositions.Report();
        NbtPositions.move(nbt("{facing:2}"), new QuarterTurns.Box(0, 0, 0, 1, 1, 1, 0, 9, 9, 9), straight);
        assertTrue(straight.directionKeys.isEmpty(), "a copy that is not turned keeps its directions");
    }

    @Test
    void numbersKeepTheTagTypeTheyHad() throws NBTException {
        NBTTagCompound tag = nbt("{ax:101L,ay:61L,az:202L}");
        NbtPositions.move(tag, BOX, new NbtPositions.Report());
        assertEquals(4, tag.getTagId("ax"));
        assertEquals(BOX.map(101, 61, 202)[0], tag.getLong("ax"));
    }
}
