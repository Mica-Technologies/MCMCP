package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What sits at each wire end, and what that makes a piece (#162). */
class WireEndsTest {

    private static int[] at(int x, int y, int z) {
        return new int[] {x, y, z};
    }

    private static WireEnds.End mountedOn(String te, int energy, boolean extract, boolean receive) {
        WireEnds.End end = new WireEnds.End();
        end.block = "immersiveengineering:connector";
        end.mountedTe = te;
        end.energy = energy;
        end.maxEnergy = 1000;
        end.canExtract = extract;
        end.canReceive = receive;
        return end;
    }

    @Test
    void anEndOnSomethingThatOnlyGivesEnergyIsASourceAndOnlyTakesItIsALoad() {
        assertEquals("source", WireEnds.role(mountedOn("csm:meter", 10, true, false)));
        assertEquals("load", WireEnds.role(mountedOn("csm:rftors", 10, false, true)));
        assertEquals("store", WireEnds.role(mountedOn("ie:capacitor", 10, true, true)));
        assertNull(WireEnds.role(new WireEnds.End()));
    }

    @Test
    void anUnloadedEndHasNoRoleAndSaysOnlyThatItIsUnloaded() {
        WireEnds.End end = mountedOn("csm:meter", 10, true, false);
        end.loaded = false;

        assertNull(WireEnds.role(end));
        JsonObject json = WireEnds.toJson(end);
        assertEquals(1, json.entrySet().size());
        assertFalse(json.get("loaded").getAsBoolean());
    }

    @Test
    void aPieceIsSummedByRoleWithEnergisedLoadsAndUnreadNodesCounted() {
        WireGraph graph = new WireGraph();
        graph.add(at(0, 70, 0), at(20, 70, 0), "STEEL", 20);
        graph.add(at(20, 70, 0), at(40, 70, 0), "STEEL", 20);
        graph.add(at(40, 70, 0), at(60, 70, 0), "STEEL", 20);
        List<WireGraph.Component> pieces = graph.components(WireGraph.EVERYWHERE);
        Map<String, WireEnds.End> ends = new HashMap<>();
        ends.put("0,70,0", mountedOn("csm:meter", 500, true, false));
        ends.put("20,70,0", mountedOn("csm:rftors", 100, false, true));
        ends.put("40,70,0", mountedOn("csm:rftors", 0, false, true));
        // 60,70,0 was never read.

        WireEnds.Summary summary = WireEnds.summarise(pieces.get(0), ends);

        assertEquals(1, summary.sources);
        assertEquals(2, summary.loads);
        assertEquals(1, summary.loadsEnergised);
        assertEquals(1, summary.unloaded);
        assertEquals(3, summary.rows.size());
        assertTrue(summary.fed());
    }

    @Test
    void aPieceWithOnlyAStoreIsNotFed() {
        WireGraph graph = new WireGraph();
        graph.add(at(0, 70, 0), at(20, 70, 0), "STEEL", 20);
        Map<String, WireEnds.End> ends = new HashMap<>();
        ends.put("0,70,0", mountedOn("ie:capacitor", 500, true, true));
        ends.put("20,70,0", mountedOn("csm:rftors", 0, false, true));

        WireEnds.Summary summary = WireEnds.summarise(graph.components(WireGraph.EVERYWHERE).get(0), ends);

        assertFalse(summary.fed());
        assertEquals(1, summary.stores);
    }

    @Test
    void roleRowsStopAtTheCapButTheCountsCoverEveryEnd() {
        WireGraph graph = new WireGraph();
        Map<String, WireEnds.End> ends = new HashMap<>();
        int nodes = WireEnds.MAX_ROLE_ROWS + 10;
        for (int i = 0; i < nodes - 1; i++) {
            graph.add(at(i, 70, 0), at(i + 1, 70, 0), "COPPER", 1);
        }
        for (int i = 0; i < nodes; i++) {
            ends.put(i + ",70,0", mountedOn("csm:rftors", 1, false, true));
        }

        WireEnds.Summary summary = WireEnds.summarise(graph.components(WireGraph.EVERYWHERE).get(0), ends);

        assertEquals(nodes, summary.loads);
        assertEquals(WireEnds.MAX_ROLE_ROWS, summary.rows.size());
        assertTrue(summary.rowsTruncated);
    }

    @Test
    void theWholeDimensionBoxHoldsEveryPiece() {
        WireGraph graph = new WireGraph();
        graph.add(at(-30_000_000, 5, -30_000_000), at(-29_999_990, 5, -30_000_000), "STEEL", 10);
        graph.add(at(30_000_000, 250, 30_000_000), at(29_999_990, 250, 30_000_000), "STEEL", 10);

        assertEquals(2, graph.components(WireGraph.EVERYWHERE).size());
    }
}
