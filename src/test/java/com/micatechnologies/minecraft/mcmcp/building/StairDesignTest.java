package com.micatechnologies.minecraft.mcmcp.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The stair planner against the reference planner's own output: each fixture is design.py's input and
 * the exact cells, flights and refusals it produced (tests/mcmcp_fixtures/gen_fixtures.py, stairs()).
 */
class StairDesignTest {

    static JsonObject fixture(String name) throws IOException {
        String path = "/fixtures/uia/stairs_" + name + ".json";
        try (InputStream in = StairDesignTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) {
                bytes.write(buf, 0, n);
            }
            return Json.parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    static StairDesign.Plan planOf(JsonObject fixture) throws StairDesign.DesignException {
        return StairDesign.plan(StairDesign.Input.fromJson(fixture.getAsJsonObject("input")));
    }

    /** Plans the fixture's input and checks every cell, the flights, the axis and the run. */
    static StairDesign.Plan assertMatches(String name) throws Exception {
        JsonObject fx = fixture(name);
        JsonObject exp = fx.getAsJsonObject("expected");
        StairDesign.Plan plan = planOf(fx);

        Map<Pos, String> want = new TreeMap<>();
        for (JsonElement e : exp.getAsJsonArray("cells")) {
            JsonArray c = e.getAsJsonArray();
            want.put(new Pos(c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt()), c.get(3).getAsString());
        }
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<Pos, String> e : want.entrySet()) {
            String got = plan.cells.get(e.getKey());
            if (!e.getValue().equals(got)) {
                diffs.add(e.getKey() + " want " + e.getValue() + " got " + got);
            }
        }
        for (Map.Entry<Pos, String> e : plan.cells.entrySet()) {
            if (!want.containsKey(e.getKey())) {
                diffs.add(e.getKey() + " want nothing got " + e.getValue());
            }
        }
        assertTrue(diffs.isEmpty(), diffs.size() + " cells differ, first: " + diffs.subList(0, Math.min(20, diffs.size())));
        assertEquals(exp.get("count").getAsInt(), plan.cells.size());
        assertEquals(exp.get("axis").getAsString(), plan.axis);
        assertEquals(exp.get("run").getAsInt(), plan.run);
        assertEquals(Json.write(exp.getAsJsonArray("flights")), plan.flights.toString().replace(" ", ""));
        return plan;
    }

    static void assertRefused(String name) throws Exception {
        JsonObject fx = fixture(name);
        String want = fx.getAsJsonObject("expected").get("refused").getAsString();
        StairDesign.DesignException e = assertThrows(StairDesign.DesignException.class, () -> planOf(fx));
        assertEquals(want, e.getMessage());
    }

    @Test
    void aFourFloorCoreWithEvenStoreysTakesTwoFourTreadFlightsPerStorey() throws Exception {
        assertMatches("small_4_floors");
    }

    @Test
    void aTwentyTwoLevelTowerCoreMatchesTheReferencePlanCellForCell() throws Exception {
        StairDesign.Plan plan = assertMatches("alto1873_N");
        assertEquals(42, plan.flights.size());
        assertEquals(252, plan.top);
    }

    @Test
    void anUnevenFirstStoreyTakesAnExtraFlightToLandAtItsDoorAndSplitsShorterFirst() throws Exception {
        StairDesign.Plan plan = assertMatches("marquee_W");
        assertEquals(2, plan.flights.get(0).n);
        assertEquals(3, plan.flights.get(3).n);
    }

    @Test
    void aSingleTallStoreyUnderAnExistingRoofPutsTheLongestFlightLast() throws Exception {
        StairDesign.Plan plan = assertMatches("marquee_P");
        assertEquals(4, plan.flights.get(plan.flights.size() - 1).n);
        assertEquals(102, plan.top);
    }

    @Test
    void aCoreTooNarrowForTwoFlightsAndADividerIsRefusedWithTheCellsMissing() throws Exception {
        assertRefused("refuse_too_narrow");
    }

    @Test
    void aDoorOpeningOntoTheRunRatherThanALandingIsRefused() throws Exception {
        assertRefused("refuse_door_on_run");
    }

    @Test
    void thePaletteReplacesEveryBlockItNames() throws Exception {
        JsonObject input = fixture("small_4_floors").getAsJsonObject("input");
        StairDesign.Input in = StairDesign.Input.fromJson(input);
        in.palette = new StairDesign.Palette("a:floor", "a:wall", "a:steps", "a:rail", "a:sconce");
        StairDesign.Plan swapped = StairDesign.plan(in);
        StairDesign.Plan plain = StairDesign.plan(StairDesign.Input.fromJson(input));
        assertEquals(plain.cells.size(), swapped.cells.size());
        for (Map.Entry<Pos, String> e : plain.cells.entrySet()) {
            String b = e.getValue()
                .replace("minecraft:concrete:8", "a:floor")
                .replace("minecraft:concrete:7", "a:wall")
                .replace("minecraft:stone_brick_stairs", "a:steps")
                .replace("minecraft:glass_pane", "a:rail")
                .replace("csm:sconcehalfshellnickel", "a:sconce");
            assertEquals(b, swapped.cells.get(e.getKey()), e.getKey().toString());
        }
    }

    @Test
    void aWidthUnderTheMinimumIsRefused() throws Exception {
        StairDesign.Input in = StairDesign.Input.fromJson(fixture("small_4_floors").getAsJsonObject("input"));
        in.width = 2;
        StairDesign.DesignException e = assertThrows(StairDesign.DesignException.class, () -> StairDesign.plan(in));
        assertEquals("width 2 < rules.MIN_WIDTH 3", e.getMessage());
    }

    @Test
    void aFloorWithNoDoorIsRefused() throws Exception {
        StairDesign.Input in = StairDesign.Input.fromJson(fixture("small_4_floors").getAsJsonObject("input"));
        in.doors.remove(80);
        StairDesign.DesignException e = assertThrows(StairDesign.DesignException.class, () -> StairDesign.plan(in));
        assertEquals("no door given for floor y80 (rules.DOOR_EVERY_FLOOR)", e.getMessage());
    }

    @Test
    void aCsmRailOnTheRoofParapetFacesTheStairWellAndNotTheDeckBehindIt() throws Exception {
        // The UIA builder's review fixture: a drop depth of one cell also counts the deck (its walking cell
        // is air) and gives the rails every edge; the deck block one further down is what rules it out.
        JsonObject fixture = fixture("alto1873_N_csmrail");
        StairDesign.Input input = StairDesign.Input.fromJson(fixture.getAsJsonObject("input"));
        StairDesign.Plan plan = StairDesign.plan(input);
        String rail = input.palette.rail;
        RailEdges.Solid solid = RailEdges.ofPlan(plan.cells, rail);

        List<String> got = new ArrayList<>();
        for (Map.Entry<Pos, String> cell : plan.cells.entrySet()) {
            if (cell.getValue().equals(rail)) {
                Pos p = cell.getKey();
                got.add(p.x + " " + p.y + " " + p.z + " " + rail + ":"
                    + RailEdges.edges(solid, p.x, p.y, p.z, RailEdges.PARAPET_DEPTH));
            }
        }
        List<String> expected = new ArrayList<>();
        for (JsonElement item : fixture.getAsJsonObject("expected").getAsJsonArray("railCells")) {
            JsonArray c = item.getAsJsonArray();
            expected.add(c.get(0).getAsInt() + " " + c.get(1).getAsInt() + " " + c.get(2).getAsInt() + " "
                + c.get(3).getAsString());
        }
        java.util.Collections.sort(got);
        java.util.Collections.sort(expected);
        assertEquals(expected, got);
    }
}
