package com.micatechnologies.minecraft.mcmcp.building;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Doors and railings against the UIA builder's fixtures (MCMCP-UIA-KB tests/mcmcp_fixtures, 2026-10-26). */
class DoorsAndRailingsTest {

    private static JsonObject fixture(String name) throws IOException {
        try (InputStream in = DoorsAndRailingsTest.class.getResourceAsStream("/fixtures/uia/" + name)) {
            byte[] bytes = new byte[1 << 20];
            int length = 0;
            for (int n; (n = in.read(bytes, length, bytes.length - length)) > 0; ) {
                length += n;
            }
            return Json.parse(new String(bytes, 0, length, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static List<String> sorted(List<String> cells) {
        List<String> copy = new ArrayList<>(cells);
        Collections.sort(copy);
        return copy;
    }

    private static List<String> expectedCells(JsonObject fixture) {
        List<String> cells = new ArrayList<>();
        for (JsonElement cell : fixture.getAsJsonObject("expected").getAsJsonArray("cells")) {
            JsonArray c = cell.getAsJsonArray();
            cells.add(c.get(0).getAsInt() + " " + c.get(1).getAsInt() + " " + c.get(2).getAsInt() + " " + c.get(3).getAsString());
        }
        return sorted(cells);
    }

    @Test
    void everyDoorFixtureGetsTheHalvesAndHingesTheBuilderVerifiedLive() throws IOException {
        for (String name : new String[] {"doors_pair_S_exit", "doors_pair_W_storefront", "doors_pair_N_exit",
            "doors_pair_E_wood", "doors_single_N_fire", "doors_single_W_noclose"}) {
            JsonObject fixture = fixture(name + ".json");
            List<String> cells = new ArrayList<>();
            for (JsonElement item : fixture.getAsJsonObject("input").getAsJsonArray("openings")) {
                JsonObject o = item.getAsJsonObject();
                DoorPlan.Opening opening = new DoorPlan.Opening(o.get("x").getAsInt(), o.get("y").getAsInt(),
                    o.get("z").getAsInt(), o.get("block").getAsString(), DoorPlan.Facing.parse(o.get("facing").getAsString()),
                    Json.getInt(o, "width", 1), o.has("axis") ? o.get("axis").getAsString().charAt(0) : null,
                    Json.getBoolean(o, "closer", true), "right".equals(Json.getString(o, "hinge", "left")));
                for (DoorPlan.Leaf leaf : DoorPlan.leaves(opening)) {
                    cells.add(leaf.x + " " + leaf.y + " " + leaf.z + " " + leaf.block + ":" + leaf.lowerMeta());
                    cells.add(leaf.x + " " + (leaf.y + 1) + " " + leaf.z + " " + leaf.block + ":" + leaf.upperMeta());
                }
            }
            assertEquals(expectedCells(fixture), sorted(cells), name);
        }
    }

    private static RailEdges.Solid world(JsonObject fixture) {
        final Set<String> solid = new HashSet<>();
        for (JsonElement cell : fixture.getAsJsonArray("world")) {
            JsonArray c = cell.getAsJsonArray();
            String block = c.get(3).getAsString();
            // The builder's rule: lighting helpers, other guards and railings are not something to stand on.
            if (!block.startsWith("csm:lightupair") && !block.startsWith("novablocks") && !block.startsWith("csm:railing_")) {
                solid.add(c.get(0).getAsInt() + "," + c.get(1).getAsInt() + "," + c.get(2).getAsInt());
            }
        }
        return (x, y, z) -> solid.contains(x + "," + y + "," + z);
    }

    @Test
    void railingsFaceTheDropOnSidesRingCornersAndAtriumVoidsAndShortStepsAreSkipped() throws IOException {
        for (String name : new String[] {"railings_terrace_ring", "railings_atrium_void", "railings_short_drop_skipped"}) {
            JsonObject fixture = fixture(name + ".json");
            JsonObject input = fixture.getAsJsonObject("input");
            String style = input.get("style").getAsString();
            int depth = Json.getInt(input, "dropDepth", 4);
            RailEdges.Solid solid = world(fixture);
            List<String> cells = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (JsonElement item : input.getAsJsonArray("cells")) {
                JsonArray c = item.getAsJsonArray();
                int x = c.get(0).getAsInt();
                int y = c.get(1).getAsInt();
                int z = c.get(2).getAsInt();
                int mask = RailEdges.edges(solid, x, y, z, depth);
                if (mask == 0) {
                    skipped.add(x + " " + y + " " + z);
                }
                else {
                    cells.add(x + " " + y + " " + z + " " + style + ":" + mask);
                }
            }
            assertEquals(expectedCells(fixture), sorted(cells), name);
            List<String> expectedSkipped = new ArrayList<>();
            for (JsonElement item : fixture.getAsJsonObject("expected").getAsJsonArray("skipped")) {
                JsonArray c = item.getAsJsonArray();
                expectedSkipped.add(c.get(0).getAsInt() + " " + c.get(1).getAsInt() + " " + c.get(2).getAsInt());
            }
            assertEquals(sorted(expectedSkipped), sorted(skipped), name + " skipped");
        }
    }

    @Test
    void edgeLettersBecomeTheMaskCsmStores() {
        assertEquals(1 | 2, RailEdges.parse("NE"));
        assertEquals(4 | 8, RailEdges.parse("sw"));
    }
}
