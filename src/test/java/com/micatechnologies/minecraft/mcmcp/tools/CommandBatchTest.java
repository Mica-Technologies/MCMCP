package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How server_run_commands reads its commands, where each one runs from (#127), and how far it loads (#119). */
class CommandBatchTest {

    private static JsonArray array(String json) {
        return Json.parse(json).getAsJsonArray();
    }

    @Test
    void plainStringsReadAsTheyAlwaysDidWithTheSlashDroppedAndBlanksSkipped() {
        List<CommandBatch.Entry> entries = CommandBatch.parse(array("[\"/say hi\", \"  \", \" time set day \"]"), null);
        assertEquals(2, entries.size());
        assertEquals("say hi", entries.get(0).command);
        assertEquals("time set day", entries.get(1).command);
        assertNull(entries.get(0).at);
    }

    @Test
    void aCallLevelAtAppliesToEveryCommandThatHasNoneOfItsOwn() {
        double[] callAt = CommandBatch.parseAt(Json.parse("{\"x\":52.5,\"y\":49,\"z\":-2604.5}"), "at");
        List<CommandBatch.Entry> entries = CommandBatch.parse(array("[\"rcmc station InnovationMed\","
            + "{\"command\":\"rcmc station platform InnovationMed add\",\"at\":{\"x\":66.5,\"y\":49,\"z\":-2718.5,"
            + "\"yaw\":90}},{\"command\":\"say done\"}]"), callAt);
        assertEquals(3, entries.size());
        assertArrayEquals(new double[] {52.5, 49, -2604.5}, java.util.Arrays.copyOf(entries.get(0).at, 3));
        assertEquals(66.5, entries.get(1).at[0]);
        assertEquals(90, entries.get(1).at[3]);
        assertTrue(Double.isNaN(entries.get(1).at[4]), "a pitch not given keeps the player's own");
        assertEquals(52.5, entries.get(2).at[0], "an object without its own at takes the call's");
        assertTrue(CommandBatch.anyAt(entries));
    }

    @Test
    void withNoAtAnywhereNoCommandMovesThePlayer() {
        assertFalse(CommandBatch.anyAt(CommandBatch.parse(array("[\"say a\", {\"command\":\"say b\"}]"), null)));
    }

    @Test
    void anAtSentAsAJsonStringIsReadAsTheObjectItSpells() {
        double[] at = CommandBatch.parseAt(new JsonPrimitive("{\"x\":1,\"y\":\"64\",\"z\":-3}"), "at");
        assertArrayEquals(new double[] {1, 64, -3}, java.util.Arrays.copyOf(at, 3));
    }

    @Test
    void anAtMissingACoordinateOrOutsideTheWorldIsRefusedWithItsName() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
            () -> CommandBatch.parse(array("[{\"command\":\"say\",\"at\":{\"x\":1,\"z\":2}}]"), null));
        assertTrue(missing.getMessage().contains("commands[0].at"), missing.getMessage());
        assertTrue(missing.getMessage().contains("'y'"), missing.getMessage());
        assertThrows(IllegalArgumentException.class,
            () -> CommandBatch.parseAt(Json.parse("{\"x\":40000000,\"y\":1,\"z\":0}"), "at"));
        assertThrows(IllegalArgumentException.class,
            () -> CommandBatch.parseAt(Json.parse("{\"x\":0,\"y\":9000,\"z\":0}"), "at"));
        assertThrows(IllegalArgumentException.class,
            () -> CommandBatch.parseAt(Json.parse("{\"x\":0,\"y\":1,\"z\":0,\"pitch\":120}"), "at"));
        assertThrows(IllegalArgumentException.class, () -> CommandBatch.parseAt(Json.parse("[1,2,3]"), "at"));
    }

    @Test
    void anObjectWithoutACommandOrAnEntryOfAnotherKindIsRefusedRatherThanSkipped() {
        assertThrows(IllegalArgumentException.class,
            () -> CommandBatch.parse(array("[{\"at\":{\"x\":0,\"y\":64,\"z\":0}}]"), null));
        assertThrows(IllegalArgumentException.class, () -> CommandBatch.parse(array("[[\"say\"]]"), null));
    }

    @Test
    void anAbsentOrNullAtMeansTheCommandRunsWhereThePlayerStands() {
        assertNull(CommandBatch.parseAt(null, "at"));
        assertNull(CommandBatch.parse(array("[{\"command\":\"say\",\"at\":null}]"), null).get(0).at);
    }

    @Test
    void aLoadMarginOfEightReachesTheElevatorDoorwaysInTheNextChunk() {
        // #119: /elevator install 68 -2719 ... builds doorways on the cab front at x 63, chunk 3, while
        // the controller column read from the text (68, grown by 4 to 64..72) is all in chunk 4.
        int[] column = CommandTargets.loadBoxes("elevator install 68 -2719 west 65:Mezzanine 78:Street",
            new int[] {0, 64, 0}).get(0);
        assertEquals(4, column[0] >> 4, "the default reaches chunk 4 only");
        int[] grown = CommandBatch.grow(column, 8);
        assertEquals(3, grown[0] >> 4);
        assertEquals(column[1], grown[1], "y is not grown: chunks are columns");
        assertEquals(column[4], grown[4]);
        assertEquals(column[5] + 8, grown[5]);
    }

    @Test
    void aMarginOfZeroLeavesTheBoxAsItWasAndOnePastTheCeilingIsHeldToIt() {
        int[] box = {10, 0, 20, 30, 255, 40};
        assertArrayEquals(box, CommandBatch.grow(box, 0));
        assertArrayEquals(new int[] {10 - 64, 0, 20 - 64, 30 + 64, 255, 40 + 64}, CommandBatch.grow(box, 1000));
        assertArrayEquals(box, CommandBatch.grow(box, -5));
    }

    @Test
    void repliesNamingSomethingUnloadedAreRecognisedWhateverTheirCaseOrSeparator() {
        assertTrue(CommandBatch.saysUnloaded("doors.landing_unloaded(65, 63 65 -2719)"));
        assertTrue(CommandBatch.saysUnloaded("Chunk NOT LOADED at 1 2"));
        assertTrue(CommandBatch.saysUnloaded("callpanel.Unloaded"));
        assertFalse(CommandBatch.saysUnloaded("doors.summary(0, 2, double)"));
        assertFalse(CommandBatch.saysUnloaded("Loaded 4 chunks"));
    }

    @Test
    void theUnloadedWarningQuotesTheFirstLinesAndSaysTheyStillCountedAsSucceeded() {
        String warning = CommandBatch.unloadedWarning(1, 1, java.util.Arrays.asList(
            "#0 doors.landing_unloaded(65, 63 65 -2719)", "#0 doors.landing_unloaded(78, 63 78 -2719)"), true);
        assertTrue(warning.startsWith("1 command(s) replied that something was unloaded, all still counted as "
            + "succeeded"), warning);
        assertTrue(warning.contains("#0 doors.landing_unloaded(65, 63 65 -2719); #0 doors.landing_unloaded(78"),
            warning);
        assertTrue(warning.contains("loadMargin"), warning);
    }

    @Test
    void theUnloadedWarningCutsLongLinesAndWithLoadOffSaysToTurnItOn() {
        StringBuilder longLine = new StringBuilder("#3 ");
        for (int i = 0; i < 50; i++) {
            longLine.append("unloaded ");
        }
        String warning = CommandBatch.unloadedWarning(3, 1, java.util.Collections.singletonList(longLine.toString()),
            false);
        assertTrue(warning.contains("1 of them still counted as succeeded"), warning);
        assertTrue(warning.contains("..."), warning);
        assertTrue(warning.length() < 400, warning);
        assertTrue(warning.contains("with load on"), warning);
    }

    @Test
    void aSetblockTagInTheModeSlotGetsReplacePutBeforeItSoVanillaKeepsIt() {
        assertEquals("/setblock -700 125 -5990 csm:dynamic_street_sign 0 replace {signData:\"A - B\"}",
            CommandBatch.withModeBeforeTag("/setblock -700 125 -5990 csm:dynamic_street_sign 0 {signData:\"A - B\"}"));
        assertEquals("setblock 1 2 3 csm:sign 0 replace {a:1}",
            CommandBatch.withModeBeforeTag("setblock 1 2 3 csm:sign 0 {a:1}"));
    }

    @Test
    void aFillTagInTheModeSlotGetsReplaceToo() {
        assertEquals("/fill 0 0 0 1 1 1 minecraft:chest 0 replace {Lock:\"x\"}",
            CommandBatch.withModeBeforeTag("/fill 0 0 0 1 1 1 minecraft:chest 0 {Lock:\"x\"}"));
    }

    @Test
    void aCommandThatAlreadyNamesItsModeOrHasNoTagIsLeftAlone() {
        assertNull(CommandBatch.withModeBeforeTag("/setblock 1 2 3 csm:sign 0 replace {a:1}"));
        assertNull(CommandBatch.withModeBeforeTag("/setblock 1 2 3 minecraft:stone 0"));
        assertNull(CommandBatch.withModeBeforeTag("/setblock 1 2 3 minecraft:stone"));
        assertNull(CommandBatch.withModeBeforeTag("/fill 0 0 0 1 1 1 minecraft:stone 0 keep"));
        assertNull(CommandBatch.withModeBeforeTag("/say setblock 1 2 3 a 0 {b}"));
    }

    @Test
    void theModeWarningNamesTheChangedCommandsByIndex() {
        String warning = CommandBatch.modeInsertedWarning(java.util.Arrays.asList(0, 4));
        assertTrue(warning.startsWith("2 /setblock or /fill"), warning);
        assertTrue(warning.contains("#0, #4"), warning);
    }

    @Test
    void theBlockAnAtStandsInIsFlooredAsTildeResolvesAgainstIt() {
        assertArrayEquals(new int[] {52, 49, -2605}, CommandBatch.blockOf(new double[] {52.5, 49.0, -2604.5, 0, 0}));
    }
}
