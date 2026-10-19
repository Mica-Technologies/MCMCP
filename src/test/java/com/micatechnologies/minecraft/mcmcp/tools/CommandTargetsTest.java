package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandTargetsTest {

    private static final int[] PLAYER = {100, 64, -200};

    @Test
    void aFillCoversTheBoxBetweenItsCornersWhicheverOrderTheyAreIn() {
        List<int[]> boxes = CommandTargets.boxes("/fill 10 70 5 0 60 -5 minecraft:stone", PLAYER);
        assertEquals(1, boxes.size());
        assertArrayEquals(new int[]{0, 60, -5, 10, 70, 5}, boxes.get(0));
    }

    @Test
    void aTildeIsRelativeToTheSendersBlockAndAFractionIsFloored() {
        List<int[]> boxes = CommandTargets.boxes("setblock ~ ~-1 ~2.5 minecraft:stone", PLAYER);
        assertArrayEquals(new int[]{100, 63, -198, 100, 63, -198}, boxes.get(0));
    }

    @Test
    void aCloneTouchesBothItsSourceAndItsDestination() {
        List<int[]> boxes = CommandTargets.boxes("/clone 0 0 0 4 2 4 100 10 100", PLAYER);
        assertEquals(2, boxes.size());
        assertArrayEquals(new int[]{0, 0, 0, 4, 2, 4}, boxes.get(0));
        assertArrayEquals(new int[]{100, 10, 100, 104, 12, 104}, boxes.get(1));
    }

    @Test
    void aNamespacedCommandIsRecognised() {
        assertEquals(1, CommandTargets.boxes("/minecraft:blockdata 1 2 3 {}", PLAYER).size());
    }

    @Test
    void anythingItCannotReadIsUnknownRatherThanAnError() {
        assertTrue(CommandTargets.boxes("/say hello", PLAYER).isEmpty());
        assertTrue(CommandTargets.boxes("/fill 1 2", PLAYER).isEmpty());
        assertTrue(CommandTargets.boxes("/setblock ^ ^ ^1 stone", PLAYER).isEmpty());
        assertTrue(CommandTargets.boxes("/", PLAYER).isEmpty());
    }

    @Test
    void anElevatorsXZPairLoadsItsWholeColumn() {
        List<int[]> boxes = CommandTargets.loadBoxes("/elevator floors 171 -6227 east 63:L 75:2 210:R replace", PLAYER);
        assertEquals(1, boxes.size());
        assertArrayEquals(new int[]{167, 0, -6231, 175, 255, -6223}, boxes.get(0));
    }

    @Test
    void aModsSixNumbersAreABoxAndItsTriplesArePoints() {
        List<int[]> relight = CommandTargets.loadBoxes("/csmlighting relight 0 60 0 40 70 30", PLAYER);
        assertArrayEquals(new int[]{-4, 60, -4, 44, 70, 34}, relight.get(0));
        List<int[]> panel = CommandTargets.loadBoxes("/elevator panel call 10 64 20 north with 12 64 20", PLAYER);
        assertEquals(2, panel.size());
        assertArrayEquals(new int[]{8, 64, 16, 16, 64, 24}, panel.get(1));
        List<int[]> link = CommandTargets.loadBoxes("/csmfirealarm link 1 2 3 4 5 6 7000 8 9", PLAYER);
        assertEquals(3, link.size());
    }

    @Test
    void commaListsAreReadAndSemicolonsSeparateThem() {
        List<int[]> trees = CommandTargets.loadBoxes("/csmtree plant oak 10,64,20,90,7;300,65,400", PLAYER);
        assertEquals(2, trees.size());
        assertArrayEquals(new int[]{6, 64, 16, 14, 64, 24}, trees.get(0));
        assertArrayEquals(new int[]{296, 65, 396, 304, 65, 404}, trees.get(1));
    }

    @Test
    void sixNumbersSpanningTooFarAreTwoPointsNotOneHugeBox() {
        assertEquals(2, CommandTargets.loadBoxes("/csmthing 0 64 0 5000 64 5000", PLAYER).size());
    }

    @Test
    void vanillaCommandsKeepTheirOwnGrammarAndCountsAreNotCoordinates() {
        assertArrayEquals(new int[]{1, 2, 3, 1, 2, 3}, CommandTargets.loadBoxes("setblock 1 2 3 stone", PLAYER).get(0));
        assertTrue(CommandTargets.loadBoxes("/give Alex minecraft:stone 64 0", PLAYER).isEmpty());
        assertTrue(CommandTargets.loadBoxes("/setblock ^ ^ ^1 stone", PLAYER).isEmpty());
        assertTrue(CommandTargets.loadBoxes("/say 12 34", PLAYER).isEmpty());
    }
}
