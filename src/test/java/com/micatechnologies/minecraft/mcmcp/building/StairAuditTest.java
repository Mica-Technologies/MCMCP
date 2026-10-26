package com.micatechnologies.minecraft.mcmcp.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The stair audit, run on the planner's output the way design.self_audit runs it: every reference plan
 * passes (the fixtures record 0 self-audit failures), and breaking one rule in a plan is caught by that
 * rule.
 */
class StairAuditTest {

    static StairDesign.Input input(String name) throws Exception {
        JsonObject fx = StairDesignTest.fixture(name);
        StairDesign.Input in = StairDesign.Input.fromJson(fx.getAsJsonObject("input"));
        assertEquals(0, fx.getAsJsonObject("expected").get("selfAuditFailures").getAsInt());
        return in;
    }

    static StairAudit.Result selfAudit(String name) throws Exception {
        StairDesign.Input in = input(name);
        return StairAudit.selfAudit(in, StairDesign.plan(in));
    }

    /** Audits the plan with one cell replaced. */
    static StairAudit.Result auditWith(StairDesign.Input in, StairDesign.Plan plan, Pos at, String block) {
        Map<Pos, String> grid = StairAudit.selfAuditGrid(in, plan);
        grid.put(at, block);
        List<Integer> fl = in.floors;
        StairAudit.Core c = new StairAudit.Core("t", in.box[0], in.box[1], in.box[2], in.box[3], fl.get(0),
                                                fl.get(fl.size() - 1) + 2, grid);
        return StairAudit.audit(c, in.width, fl, false);
    }

    @Test
    void everyReferencePlanPassesItsOwnAudit() throws Exception {
        for (String name : Arrays.asList("small_4_floors", "alto1873_N", "marquee_W", "marquee_P")) {
            StairAudit.Result r = selfAudit(name);
            assertTrue(r.failures.isEmpty(), name + ": " + r.failures);
            assertEquals(StairDesign.plan(input(name)).flights.size(), r.flights.size(), name);
        }
    }

    @Test
    void aMissingLandingSconceIsALightFailure() throws Exception {
        StairDesign.Input in = input("small_4_floors");
        StairDesign.Plan plan = StairDesign.plan(in);
        List<Pos> sconces = new ArrayList<>();
        for (Map.Entry<Pos, String> e : plan.cells.entrySet()) {
            if (e.getValue().startsWith("csm:sconce")) {
                sconces.add(e.getKey());
            }
        }
        Map<Pos, String> grid = StairAudit.selfAuditGrid(in, plan);
        for (Pos p : sconces) {
            grid.put(p, "minecraft:concrete:7");
        }
        List<Integer> fl = in.floors;
        StairAudit.Core c = new StairAudit.Core("t", in.box[0], in.box[1], in.box[2], in.box[3], fl.get(0),
                                                fl.get(fl.size() - 1) + 2, grid);
        StairAudit.Result r = StairAudit.audit(c, in.width, fl, false);
        assertTrue(r.byRule().containsKey("LIGHT"), r.failures.toString());
    }

    @Test
    void aBlockedDoorFailsDoorAndCutsTheWalkToTheFloorsAbove() throws Exception {
        StairDesign.Input in = input("small_4_floors");
        StairDesign.Plan plan = StairDesign.plan(in);
        Map<Pos, String> grid = StairAudit.selfAuditGrid(in, plan);
        grid.put(new Pos(2, 72, 8), "minecraft:concrete:7");
        grid.put(new Pos(2, 73, 8), "minecraft:concrete:7");
        List<Integer> fl = in.floors;
        StairAudit.Core c = new StairAudit.Core("t", in.box[0], in.box[1], in.box[2], in.box[3], fl.get(0),
                                                fl.get(fl.size() - 1) + 2, grid);
        StairAudit.Result r = StairAudit.audit(c, in.width, fl, false);
        Map<String, List<StairAudit.Failure>> by = r.byRule();
        assertEquals("no door at floor y72", by.get("DOOR").get(0).detail);
        assertEquals("floor y72 not reached from y64", by.get("CONT").get(0).detail);
    }

    @Test
    void aMissingTreadBreaksTheStepRule() throws Exception {
        StairDesign.Input in = input("small_4_floors");
        StairDesign.Plan plan = StairDesign.plan(in);
        Pos tread = null;
        for (Map.Entry<Pos, String> e : plan.cells.entrySet()) {
            if (e.getValue().contains("stairs") && e.getKey().y == 66) {
                tread = e.getKey();
                break;
            }
        }
        StairAudit.Result r = auditWith(in, plan, tread, StairDesign.AIR);
        assertTrue(r.byRule().containsKey("STEP"), r.failures.toString());
    }

    @Test
    void signsAreWantedAtEveryDoorWhenAsked() throws Exception {
        StairDesign.Input in = input("small_4_floors");
        StairDesign.Plan plan = StairDesign.plan(in);
        List<Integer> fl = in.floors;
        StairAudit.Core c = new StairAudit.Core("t", in.box[0], in.box[1], in.box[2], in.box[3], fl.get(0),
                                                fl.get(fl.size() - 1) + 2, StairAudit.selfAuditGrid(in, plan));
        StairAudit.Result r = StairAudit.audit(c, in.width, fl, true);
        assertEquals(2 * fl.size(), r.byRule().get("DOOR").size());
    }

    @Test
    void aFloorServedByOnlyOneOfSeveralCoresFailsTwo() {
        Map<String, StairAudit.Result> cores = new LinkedHashMap<>();
        cores.put("A", result(64, 72));
        cores.put("B", result(64));
        List<StairAudit.Failure> f = StairAudit.twoStairs(cores);
        assertEquals(1, f.size());
        assertEquals("y72: only 1 core door(s)", f.get(0).detail);
        cores.remove("B");
        assertTrue(StairAudit.twoStairs(cores).isEmpty(), "one core alone is not checked");
    }

    @Test
    void keywordsClassifyModdedBlocksAsAuditPyDoes() {
        assertTrue(StairAudit.passable("csm:sconcehalfshellnickel:7"));
        assertTrue(StairAudit.passable("csm:door_metal_exit:3"));
        assertFalse(StairAudit.passable("csm:exit_sign_lit"));
        assertFalse(StairAudit.passable("minecraft:stone_brick_stairs:2"));
        assertFalse(StairAudit.passable("?"));
        assertFalse(StairAudit.passable("csm:illuminated_sign"));
        assertEquals(0, StairAudit.meta("minecraft:stone_brick_stairs"));
        assertEquals(6, StairAudit.meta("minecraft:stone_brick_stairs:6"));
        assertEquals(0, StairAudit.meta("a:b:c"));
    }

    /**
     * Failure counts per rule that audit.py itself reported for the same damaged copies of the
     * small_4_floors plan (run against the reference implementation when this port was written).
     */
    @Test
    void damagedPlansFailTheSameRulesTheSameNumberOfTimesAsTheReferenceAudit() throws Exception {
        StairDesign.Input in = input("small_4_floors");
        StairDesign.Plan plan = StairDesign.plan(in);
        assertEquals("{DOOR=8}", counts(in, plan, false, false, false, false, false, true));
        assertEquals("{GAP=15, HEAD=7, LIGHT=12}", counts(in, plan, true, false, false, false, false, false));
        assertEquals("{CONT=1, DOOR=1}", counts(in, plan, false, true, false, false, false, false));
        assertEquals("{GAP=1, STEP=1, WIDTH=1}", counts(in, plan, false, false, true, false, false, false));
        assertEquals("{CONT=1, DOOR=7, GAP=23, HEAD=7, LIGHT=12, STEP=1, WIDTH=1}",
                     counts(in, plan, true, true, true, true, true, true));
    }

    private static String counts(StairDesign.Input in, StairDesign.Plan plan, boolean noSconces, boolean blockDoor,
                                 boolean dropTread, boolean noRails, boolean hole, boolean signs) {
        Map<Pos, String> grid = StairAudit.selfAuditGrid(in, plan);
        Pos firstTread = null;
        for (Map.Entry<Pos, String> e : new TreeMap<>(grid).entrySet()) {
            if (e.getValue().contains("stairs") && e.getKey().y == 66) {
                firstTread = e.getKey();
                break;
            }
        }
        for (Map.Entry<Pos, String> e : grid.entrySet()) {
            if (noSconces && e.getValue().contains("sconce")) {
                e.setValue("minecraft:concrete:7");
            }
            if (noRails && e.getValue().equals("minecraft:glass_pane")) {
                e.setValue(StairDesign.AIR);
            }
        }
        if (blockDoor) {
            grid.put(new Pos(2, 72, 8), "minecraft:concrete:7");
            grid.put(new Pos(2, 73, 8), "minecraft:concrete:7");
        }
        if (dropTread) {
            grid.put(firstTread, StairDesign.AIR);
        }
        if (hole) {
            grid.put(new Pos(5, 70, 4), StairDesign.AIR);
        }
        List<Integer> fl = in.floors;
        StairAudit.Core c = new StairAudit.Core("t", in.box[0], in.box[1], in.box[2], in.box[3], fl.get(0),
                                                fl.get(fl.size() - 1) + 2, grid);
        Map<String, Integer> n = new TreeMap<>();
        for (Map.Entry<String, List<StairAudit.Failure>> e : StairAudit.audit(c, in.width, fl, signs).byRule().entrySet()) {
            n.put(e.getKey(), e.getValue().size());
        }
        return n.toString();
    }

    private static StairAudit.Result result(Integer... levels) {
        List<Integer> fl = Arrays.asList(levels);
        return new StairAudit.Result(new ArrayList<>(), fl, new ArrayList<>(), new LinkedHashMap<>(),
                                     new LinkedHashSet<>(fl));
    }
}
