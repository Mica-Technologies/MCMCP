package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class BuildTargetTest {

    @Test
    void aCellNoBuildTouchedIsDontCareNotAir() {
        BuildTarget target = new BuildTarget(0);
        target.put(0, 64, 0, "minecraft:air");

        assertEquals("minecraft:air", target.get(0, 64, 0), "a cleared cell is wanted air");
        assertNull(target.get(1, 64, 0), "an untouched cell is not claimed");
        assertEquals(1, target.cells());
    }

    @Test
    void roundTripsThroughItsCodecAtNegativeCoordinatesWithProtectedCells() throws IOException {
        BuildTarget target = new BuildTarget(-1);
        for (int x = -20; x < 20; x++) {
            for (int y = 0; y < 3; y++) {
                target.put(x, 60 + y, -300, y == 0 ? "minecraft:stone" : "minecraft:air");
            }
        }
        target.put(-5, 61, -300, "minecraft:oak_door[facing=north,half=lower]", true);

        BuildTarget back = BuildTarget.fromBytes(target.toBytes());

        assertEquals(-1, back.dimension());
        assertEquals(target.cells(), back.cells());
        assertEquals("minecraft:stone", back.get(-20, 60, -300));
        assertEquals("minecraft:oak_door[facing=north,half=lower]", back.get(-5, 61, -300));
        assertTrue(back.isProtected(-5, 61, -300));
        assertFalse(back.isProtected(-4, 61, -300));
        assertArrayEquals(new int[] {-20, 60, -300, 19, 62, -300}, back.bounds());
    }

    @Test
    void aCompressedTargetIsSmall() {
        // A 64 x 32 x 64 shell is 131,072 cells; runs of one state compress to almost nothing.
        BuildTarget target = new BuildTarget(0);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 64; z++) {
                    boolean wall = x == 0 || x == 63 || z == 0 || z == 63 || y == 0;
                    target.put(x, y, z, wall ? "csm:brick" : "minecraft:air");
                }
            }
        }
        assertTrue(target.toBytes().length < 4_000, "got " + target.toBytes().length + " bytes");
    }

    @Test
    void aLaterLayerOverwritesItsCellsButNeverAProtectedOne() {
        BuildTarget structure = new BuildTarget(0);
        structure.put(0, 0, 0, "minecraft:air");
        structure.put(1, 0, 0, "minecraft:air");
        structure.put(2, 0, 0, "minecraft:stone", true);
        BuildTarget fitout = new BuildTarget(0);
        fitout.put(1, 0, 0, "csm:desk");
        fitout.put(2, 0, 0, "csm:desk");
        fitout.put(3, 0, 0, "csm:chair");

        structure.merge(fitout);

        assertEquals("minecraft:air", structure.get(0, 0, 0), "untouched by the later layer");
        assertEquals("csm:desk", structure.get(1, 0, 0), "the later layer wins");
        assertEquals("minecraft:stone", structure.get(2, 0, 0), "a hand edit is kept");
        assertEquals("csm:chair", structure.get(3, 0, 0), "and it adds its own cells");
    }

    @Test
    void refusesSomethingThatIsNotATarget() {
        assertThrows(IOException.class, () -> BuildTarget.fromBytes(new byte[] {31, -117, 8, 0}));
    }
}
