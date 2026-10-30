package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Immersive Engineering's wires as a graph (#160). */
class WireGraphTest {

    private static int[] at(int x, int y, int z) {
        return new int[] {x, y, z};
    }

    @Test
    void aWireHeldFromBothEndsIsListedOnce() {
        WireGraph graph = new WireGraph();
        graph.add(at(0, 70, 0), at(20, 70, 0), "STEEL", 20);
        graph.add(at(20, 70, 0), at(0, 70, 0), "STEEL", 20);

        assertEquals(1, graph.wires().size());
    }

    @Test
    void onlyWiresWithAnEndInTheBoxAreListed() {
        WireGraph graph = new WireGraph();
        graph.add(at(0, 70, 0), at(20, 70, 0), "STEEL", 20);
        graph.add(at(100, 70, 0), at(120, 70, 0), "COPPER_INS", 20);

        assertEquals(1, graph.touching(new int[] {-5, 0, -5, 5, 255, 5}).size());
    }

    @Test
    void aFeederIsOneComponentReachingPastTheBox() {
        WireGraph graph = new WireGraph();
        // A feeder: substation, three poles, a load.
        graph.add(at(0, 70, 0), at(20, 72, 0), "STEEL", 20);
        graph.add(at(20, 72, 0), at(40, 72, 0), "STEEL", 20);
        graph.add(at(40, 72, 0), at(60, 72, 0), "STEEL", 20);
        graph.add(at(60, 72, 0), at(62, 70, 0), "COPPER_INS", 3);
        // Another grid, nowhere near.
        graph.add(at(500, 70, 0), at(510, 70, 0), "COPPER_INS", 10);

        List<WireGraph.Component> pieces = graph.components(new int[] {-5, 0, -5, 5, 255, 5});

        assertEquals(1, pieces.size());
        assertEquals(5, pieces.get(0).nodes.size());
        assertEquals(4, pieces.get(0).wires.size());
        assertEquals(62, pieces.get(0).max[0]);
        assertEquals(2, pieces.get(0).types.size());
    }
}
