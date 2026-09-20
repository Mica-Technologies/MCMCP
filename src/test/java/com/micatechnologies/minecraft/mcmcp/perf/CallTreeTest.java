package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** The aggregation behind {@code game_cpu_sample}. */
class CallTreeTest {

    /** Builds a stack the way the JVM reports one: executing frame first. */
    private static StackTraceElement[] stack(String... framesTopFirst) {
        StackTraceElement[] stack = new StackTraceElement[framesTopFirst.length];
        for (int i = 0; i < framesTopFirst.length; i++) {
            int dot = framesTopFirst[i].lastIndexOf('.');
            stack[i] = new StackTraceElement(framesTopFirst[i].substring(0, dot),
                framesTopFirst[i].substring(dot + 1), null, i + 1);
        }
        return stack;
    }

    private static final String RUN = "net.minecraft.server.MinecraftServer.run";
    private static final String UPDATE_ENTITIES = "net.minecraft.world.World.updateEntities";
    private static final String SIGNAL = "com.example.signals.TileSignal.update";
    private static final String GET_STATE = "net.minecraft.world.World.getBlockState";
    private static final String FURNACE = "net.minecraft.tileentity.TileEntityFurnace.update";

    @Test
    void theHottestFrameIsTheOneExecutingNotTheOneWaitingOnIt() {
        CallTree tree = new CallTree();
        tree.add(stack(GET_STATE, SIGNAL, UPDATE_ENTITIES, RUN), true);
        tree.add(stack(GET_STATE, SIGNAL, UPDATE_ENTITIES, RUN), true);
        tree.add(stack(SIGNAL, UPDATE_ENTITIES, RUN), true);
        tree.add(stack(FURNACE, UPDATE_ENTITIES, RUN), true);

        JsonObject hottest = tree.hottestFrames(5).get(0).getAsJsonObject();

        assertEquals(GET_STATE, hottest.get("frame").getAsString());
        assertEquals(50.0D, hottest.get("percent").getAsDouble());
    }

    @Test
    void vanillaWorkDoneOnAModsBehalfIsCreditedToTheMod() {
        CallTree tree = new CallTree();
        tree.add(stack(GET_STATE, SIGNAL, UPDATE_ENTITIES, RUN), true);
        tree.add(stack(GET_STATE, SIGNAL, UPDATE_ENTITIES, RUN), true);
        tree.add(stack(SIGNAL, UPDATE_ENTITIES, RUN), true);
        tree.add(stack(FURNACE, UPDATE_ENTITIES, RUN), true);

        JsonArray owners = tree.byOwner(5);

        assertEquals("com.example.signals", owners.get(0).getAsJsonObject().get("package").getAsString());
        assertEquals(75.0D, owners.get(0).getAsJsonObject().get("percent").getAsDouble());
        assertEquals("(platform only)", owners.get(1).getAsJsonObject().get("package").getAsString());
    }

    @Test
    void aParkedThreadIsCountedAsIdleAndKeptOutOfTheTree() {
        CallTree tree = new CallTree();
        assertFalse(tree.add(stack("java.lang.Thread.sleep", RUN), true));
        assertTrue(tree.add(stack(FURNACE, UPDATE_ENTITIES, RUN), true));

        assertEquals(1L, tree.samples());
        assertEquals(1L, tree.idleSamples());
    }

    @Test
    void idleSamplesAreKeptWhenAskedFor() {
        CallTree tree = new CallTree();
        assertTrue(tree.add(stack("java.lang.Thread.sleep", RUN), false));

        assertEquals(1L, tree.samples());
        assertEquals(1L, tree.idleSamples());
    }

    @Test
    void samplesAtDifferentLinesOfOneMethodLandInOneNode() {
        CallTree tree = new CallTree();
        tree.add(new StackTraceElement[] {new StackTraceElement("a.b.C", "loop", null, 10)}, true);
        tree.add(new StackTraceElement[] {new StackTraceElement("a.b.C", "loop", null, 99)}, true);

        assertEquals(1, tree.hottestFrames(5).size());
    }

    @Test
    void aRunOfFramesWithNothingBranchingOffFoldsOntoOneLine() {
        CallTree tree = new CallTree();
        tree.add(stack(SIGNAL, UPDATE_ENTITIES, RUN), true);
        tree.add(stack(FURNACE, UPDATE_ENTITIES, RUN), true);

        String rendered = tree.render(1.0D, 50);

        // run > updateEntities folds; the two tile entities branch beneath it.
        assertEquals(3, rendered.split("\n").length);
        assertTrue(rendered.startsWith("100.0% " + RUN + " > " + UPDATE_ENTITIES + "\n"), rendered);
    }

    @Test
    void branchesBelowTheThresholdArePruned() {
        CallTree tree = new CallTree();
        for (int i = 0; i < 99; i++) {
            tree.add(stack(SIGNAL, UPDATE_ENTITIES, RUN), true);
        }
        tree.add(stack(FURNACE, UPDATE_ENTITIES, RUN), true);

        String rendered = tree.render(5.0D, 50);

        assertTrue(rendered.contains("TileSignal"));
        assertFalse(rendered.contains("TileEntityFurnace"));
    }

    @Test
    void theOwnerOfAFrameIsItsFirstThreePackageSegments() {
        assertEquals("com.example.signals", CallTree.owner("com.example.signals.block.TileSignal.update"));
        assertEquals("mymod", CallTree.owner("mymod.Thing.tick"));
        assertEquals("Obfuscated.a", CallTree.owner("Obfuscated.a"));
    }
}
