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

/** How server_run_commands reads its commands and where each one's player runs it from (#127). */
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
    void theBlockAnAtStandsInIsFlooredAsTildeResolvesAgainstIt() {
        assertArrayEquals(new int[] {52, 49, -2605}, CommandBatch.blockOf(new double[] {52.5, 49.0, -2604.5, 0, 0}));
    }
}
