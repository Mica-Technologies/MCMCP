package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShellWalkTest {

    /** The loops the walk replaced: every cell of each shell's cube, keeping the surface. */
    private static List<String> nested(int radius) {
        List<String> cells = new ArrayList<>();
        for (int shell = 0; shell <= radius; shell++) {
            for (int dx = -shell; dx <= shell; dx++) {
                for (int dy = -shell; dy <= shell; dy++) {
                    for (int dz = -shell; dz <= shell; dz++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) == shell) {
                            cells.add(shell + ":" + dx + "," + dy + "," + dz);
                        }
                    }
                }
            }
        }
        return cells;
    }

    @Test
    void theWalkVisitsTheSameCellsInTheSameOrderAsTheNestedLoopsItReplaced() {
        for (int radius = 0; radius <= 6; radius++) {
            List<String> walked = new ArrayList<>();
            ServerAreaTools.ShellWalk walk = new ServerAreaTools.ShellWalk(radius);
            while (walk.next()) {
                walked.add(walk.shell + ":" + walk.dx + "," + walk.dy + "," + walk.dz);
            }
            assertEquals(nested(radius), walked, "radius " + radius);
        }
    }
}
