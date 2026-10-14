package com.micatechnologies.minecraft.mcmcp.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Splitting a job into calls that each fit, folding their results back, and pausing and cancelling it. */
class JobPartsTest {

    private static final JobParts.Limits LIMITS = new JobParts.Limits(10, 100, 5, 50, 64, 1000);

    private static JsonObject args(String json) {
        return Json.parse(json).getAsJsonObject();
    }

    @Test
    void anOpReplayIsCutWhereTheNextOpWouldPassTheCellCapAndRemembersItsLastOp() {
        JsonObject ops = args("{\"ops\":[\"fill 0 0 0 9 0 9 a:b\",\"setblock 1 1 1 a:c\",\"fill 0 0 0 4 0 4 a:d\","
            + "\"fill 0 0 0 9 0 9 a:e\",\"say hi\"]}");
        List<JobParts.Part> parts = JobParts.split("apply_ops", ops, LIMITS);
        // Reaches of 100, 1, 25, 100 and 1 (an unparsable line costs one) against a cap of 100.
        assertEquals(4, parts.size());
        assertEquals(1, parts.get(0).args.getAsJsonArray("ops").size(), "100 + 1 would pass the cap");
        assertEquals(0, parts.get(0).lastOp);
        assertEquals(1, parts.get(1).opOffset);
        assertEquals(2, parts.get(1).lastOp);
        assertEquals(2, parts.get(1).args.getAsJsonArray("ops").size(), "1 + 25 fit; + 100 would not");
        assertEquals(4, parts.get(3).opOffset, "the line that will be refused still keeps its place");
    }

    @Test
    void paletteCellsAreSplitOnCellBoundariesWhateverTheirStride() {
        JsonArray cells = new JsonArray();
        for (int i = 0; i < 25 * 5; i++) {
            cells.add(i);
        }
        JsonObject write = new JsonObject();
        write.add("cells", cells);
        write.add("expectPalette", new JsonArray());
        List<JobParts.Part> parts = JobParts.split("set_blocks", write, LIMITS);
        assertEquals(3, parts.size());
        assertEquals(50, parts.get(0).args.getAsJsonArray("cells").size(), "10 cells of five numbers");
        assertEquals(25, parts.get(2).args.getAsJsonArray("cells").size());
        assertTrue(parts.get(0).args.has("expectPalette"), "every part keeps the other arguments");
    }

    @Test
    void aBoxSplitsByLayersThenRowsThenColumnsAndCoversEveryCellOnce() {
        List<JobParts.Part> layers = JobParts.split("get_blocks", args("{\"x\":0,\"y\":10,\"z\":0,\"toX\":3,\"toY\":17,\"toZ\":3}"), LIMITS);
        assertEquals(2, layers.size(), "16 cells a layer, four layers a part");
        assertEquals(13, layers.get(0).region.get("toY").getAsInt());
        List<JobParts.Part> rows = JobParts.split("get_blocks", args("{\"x\":0,\"y\":0,\"z\":0,\"toX\":9,\"toY\":0,\"toZ\":9}"), LIMITS);
        int cells = 0;
        for (JobParts.Part part : rows) {
            JsonObject r = part.region;
            int count = (r.get("toX").getAsInt() - r.get("x").getAsInt() + 1) * (r.get("toZ").getAsInt() - r.get("z").getAsInt() + 1);
            assertTrue(count <= 64);
            cells += count;
        }
        assertEquals(100, cells);
    }

    @Test
    void aJobOfAnUnknownKindIsRefusedWithTheKindsThereAre() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> JobParts.split("teleport", new JsonObject(), LIMITS));
        assertTrue(e.getMessage().contains("apply_ops"));
    }

    @Test
    void foldingSumsCountsAndMakesRefusedLinesRelativeToTheWholeList() {
        JsonObject totals = new JsonObject();
        JsonArray lists = new JsonArray();
        JobParts.fold(totals, lists, args("{\"dimension\":0,\"written\":3,\"unchanged\":1,\"undoPoint\":\"u1\"}"), 0);
        JobParts.fold(totals, lists, args("{\"dimension\":0,\"written\":2,\"refusedOps\":[{\"line\":4,\"reason\":\"x\"}]}"), 100);
        assertEquals(5, totals.get("written").getAsInt());
        assertFalse(totals.has("dimension"), "a dimension is not a count");
        assertEquals(104, lists.get(1).getAsJsonObject().get("line").getAsInt());
        assertEquals("u1", lists.get(0).getAsJsonObject().get("undoPoint").getAsString());
    }

    @Test
    void aPausedJobWaitsUntilEveryReasonToPauseIsLiftedAndACancelEndsTheWait() throws Exception {
        Job job = new Job("j1", "apply_ops", "server_apply_ops", null, "p", "player:p",
            JobParts.split("apply_ops", args("{\"ops\":[\"setblock 0 0 0 a:b\"]}"), LIMITS), 0L);
        job.started(1L);
        job.pause(Job.Hold.ESTOP);
        job.pause(Job.Hold.OWNER);
        job.resume(Job.Hold.OWNER);
        assertEquals(Job.State.PAUSED, job.state(), "the stop still holds it");
        job.resume(Job.Hold.ESTOP);
        assertEquals(Job.State.RUNNING, job.state());
        assertTrue(job.awaitRunnable());
        job.pause(Job.Hold.OPERATOR);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(50L);
            }
            catch (InterruptedException e) {
                return;
            }
            job.cancel();
        });
        canceller.start();
        assertFalse(job.awaitRunnable(), "a cancel releases a paused runner, which then stops");
        canceller.join();
    }

    @Test
    void aJobReadBackAfterARestartIsInterruptedAndSaysWhereToResume() {
        JsonObject saved = args("{\"id\":\"j9\",\"kind\":\"apply_ops\",\"state\":\"running\",\"partsDone\":3,\"parts\":7,"
            + "\"lastOp\":4410,\"owner\":\"p\",\"ownerKey\":\"player:p\",\"totals\":{\"written\":12}}");
        Job job = Job.interrupted(saved);
        assertEquals(Job.State.INTERRUPTED, job.state());
        JsonObject status = job.toJson(false);
        assertEquals(4410, status.get("lastOp").getAsInt());
        assertEquals(7, status.get("parts").getAsInt());
        assertTrue(status.get("error").getAsString().contains("lastOp + 1"));
        assertEquals(Arrays.asList(12), Arrays.asList(status.getAsJsonObject("totals").get("written").getAsInt()));
    }
}
