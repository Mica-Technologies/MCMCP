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
}
