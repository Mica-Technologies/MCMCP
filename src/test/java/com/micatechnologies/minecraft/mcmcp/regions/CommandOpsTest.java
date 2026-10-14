package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parsing a generator's fill, setblock and blockdata lines, and keeping what a replay leaves. */
class CommandOpsTest {

    @Test
    void aFillWithAReplaceFilterKeepsTheFilterAndOrdersItsCorners() throws Exception {
        CommandOps.Op op = CommandOps.parse("/fill 10 70 5 2 64 1 minecraft:stone 3 replace minecraft:dirt 1");
        assertEquals(CommandOps.Kind.FILL, op.kind);
        assertEquals(2, op.x1);
        assertEquals(64, op.y1);
        assertEquals(10, op.x2);
        assertEquals("minecraft:stone", op.block);
        assertEquals("3", op.data);
        assertEquals(CommandOps.Mode.REPLACE, op.mode);
        assertEquals("minecraft:dirt", op.filterBlock);
        assertEquals(1, op.filterMeta);
        assertEquals(9L * 7L * 5L, op.cells());
    }

    @Test
    void aModeRightAfterTheBlockIsAModeNotData() throws Exception {
        CommandOps.Op op = CommandOps.parse("fill 0 0 0 1 1 1 minecraft:glass keep");
        assertNull(op.data);
        assertEquals(CommandOps.Mode.KEEP, op.mode);
        CommandOps.Op plain = CommandOps.parse("fill 0 0 0 1 1 1 minecraft:glass replace minecraft:air");
        assertEquals("minecraft:air", plain.filterBlock);
        assertEquals(-1, plain.filterMeta, "no filter data means any");
    }

    @Test
    void aStateStringIsKeptAsDataForTheGameToResolve() throws Exception {
        assertEquals("facing=north,half=top",
            CommandOps.parse("setblock 1 2 3 minecraft:oak_stairs facing=north,half=top").data);
    }

    @Test
    void anNbtTagRunsToTheEndOfTheLineSpacesAndAll() throws Exception {
        CommandOps.Op op = CommandOps.parse("setblock 1 2 3 csm:sign 2 replace {Text1:\"{\\\"text\\\":\\\"Main St\\\"}\"}");
        assertEquals(CommandOps.Mode.REPLACE, op.mode);
        assertEquals("{Text1:\"{\\\"text\\\":\\\"Main St\\\"}\"}", op.nbt);
        CommandOps.Op data = CommandOps.parse("blockdata 4 5 6 {tcCp: 3b, links: [1, 2]}");
        assertEquals(CommandOps.Kind.BLOCKDATA, data.kind);
        assertEquals("{tcCp: 3b, links: [1, 2]}", data.nbt);
    }

    @Test
    void relativeCoordinatesAreRefusedBecauseAReplayHasNoSender() {
        CommandOps.ParseException e = assertThrows(CommandOps.ParseException.class,
            () -> CommandOps.parse("setblock ~ ~1 ~ minecraft:stone"));
        assertTrue(e.getMessage().contains("absolute"));
    }

    @Test
    void anotherCommandIsRefusedByName() {
        CommandOps.ParseException e = assertThrows(CommandOps.ParseException.class,
            () -> CommandOps.parse("/clone 0 0 0 1 1 1 5 5 5"));
        assertTrue(e.getMessage().contains("clone"));
    }

    @Test
    void parsingManyLinesKeepsGoingAndSaysWhichLineFailed() {
        CommandOps.Parsed parsed = CommandOps.parseAll(Arrays.asList("setblock 0 0 0 a:b", "say hi", "fill 0 0 0 1 1 1 a:c"));
        assertEquals(2, parsed.ops.size());
        assertEquals(Arrays.asList(0, 2), parsed.lineOf);
        assertEquals(Arrays.asList(1), parsed.errorLines);
    }

    @Test
    void anOverlayCountsEachCellOnceAndHandsSectionsBackBottomUp() {
        CellOverlay overlay = new CellOverlay();
        overlay.set(-1, 70, -1, 5);
        overlay.set(-1, 70, -1, 6);
        overlay.set(20, 3, 40, 7);
        overlay.set(-300, 255, 17, 8);
        assertEquals(3, overlay.cells(), "writing a cell twice is still one cell");
        assertEquals(6, overlay.get(-1, 70, -1), "the last write wins");
        assertEquals(0, overlay.get(-1, 71, -1));
        List<int[]> seen = new ArrayList<>();
        for (long key : overlay.sectionsBottomUp()) {
            overlay.visit(key, (x, y, z, v) -> seen.add(new int[] {x, y, z, v}));
        }
        assertEquals(3, seen.size());
        assertEquals(3, seen.get(0)[1], "the lowest section comes first");
        assertEquals(-300, seen.get(2)[0]);
        assertEquals(17, seen.get(2)[2], "negative and positive coordinates survive the section key");
    }
}
