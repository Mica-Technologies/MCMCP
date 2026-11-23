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
    void aJobsStatusSaysPlainlyWhenItsPartsDeletedUndoHistoryAndCountsHowMuch() {
        Job job = new Job("j2", "apply_ops", "server_apply_ops", null, "p", "player:p",
            JobParts.split("apply_ops", args("{\"ops\":[\"setblock 0 0 0 a:b\",\"setblock 1 0 0 a:b\"]}"), LIMITS), 0L);
        job.started(1L);
        JobParts.Part part = job.parts.get(0);
        job.partDone(part, args("{\"written\":5,\"undoPoint\":\"u1\"}"), false, 1L << 20);
        assertFalse(job.toJson(false).has("undoWarning"), "nothing deleted, nothing said");
        job.partDone(part, args("{\"written\":5,\"undoPoint\":\"u2\",\"undoDropped\":3,"
            + "\"undoWarning\":\"3 older undo point(s) were deleted\"}"), false, 1L << 20);
        job.partDone(part, args("{\"written\":5,\"undoPoint\":\"u3\",\"undoDropped\":1}"), false, 1L << 20);
        JsonObject status = job.toJson(false);
        assertTrue(status.get("undoWarning").getAsString().contains("deleted"), "kept once said, not only in full");
        assertEquals(4, status.getAsJsonObject("totals").get("undoDropped").getAsInt());
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

    @Test
    void aPregenAreaIsCutIntoSquaresOfChunksAsBlockBoxes() {
        // A radius of 300 blocks around 0,0 spans chunks -19..18 on each axis: 38 chunks, so 3 x 3 parts.
        List<JobParts.Part> parts = JobParts.split("pregen",
            args("{\"centerX\":0,\"centerZ\":0,\"radius\":300,\"chunksPerTick\":2}"), LIMITS);

        assertEquals(9, parts.size());
        JsonObject first = parts.get(0).args;
        assertEquals(-19 * 16, first.get("x").getAsInt());
        assertEquals((-19 + 15) * 16 + 15, first.get("toX").getAsInt());
        assertFalse(first.has("radius"), "a part is a box; centre and radius would contradict it");
        assertEquals(2, first.get("chunksPerTick").getAsInt(), "the pacing goes with every part");
        assertEquals(18 * 16 + 15, parts.get(8).args.get("toZ").getAsInt(), "the last part ends at the edge");
    }

    @Test
    void aPregenAreaNeedsABoxOrACentreAndRadiusButNotBoth() {
        assertThrows(IllegalArgumentException.class, () -> JobParts.split("pregen", args("{\"centerX\":0}"), LIMITS));
        assertThrows(IllegalArgumentException.class,
            () -> JobParts.split("pregen", args("{\"x\":0,\"z\":0,\"radius\":10}"), LIMITS));
        assertThrows(IllegalArgumentException.class,
            () -> JobParts.split("pregen", args("{\"centerX\":0,\"centerZ\":0,\"radius\":99999}"), LIMITS));
    }

    @org.junit.jupiter.api.Test
    void aWideSearchIsCutIntoChunkAlignedSquaresThatCoverItOnce() {
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("x", -300);
        args.addProperty("z", 10);
        args.addProperty("toX", 700);
        args.addProperty("toZ", 520);
        args.addProperty("y", 0);
        args.addProperty("toY", 80);
        args.addProperty("load", true);
        java.util.List<JobParts.Part> parts = JobParts.split("find_blocks", args,
            new JobParts.Limits(1, 1, 1, 1, 1, 1));
        // x: -512..-257, -256..-1, 0..255, 256..511, 512..767 (5 tiles); z: 0..255, 256..511, 512..767 (3).
        org.junit.jupiter.api.Assertions.assertEquals(15, parts.size());
        long columns = 0;
        for (JobParts.Part part : parts) {
            int x = part.args.get("x").getAsInt();
            int toX = part.args.get("toX").getAsInt();
            int z = part.args.get("z").getAsInt();
            int toZ = part.args.get("toZ").getAsInt();
            org.junit.jupiter.api.Assertions.assertTrue(x >= -300 && toX <= 700 && z >= 10 && toZ <= 520);
            org.junit.jupiter.api.Assertions.assertTrue(toX - x < 256 && toZ - z < 256);
            org.junit.jupiter.api.Assertions.assertEquals(Math.floorDiv(x, 256), Math.floorDiv(toX, 256));
            org.junit.jupiter.api.Assertions.assertTrue(part.args.get("load").getAsBoolean());
            org.junit.jupiter.api.Assertions.assertEquals(80, part.args.get("toY").getAsInt());
            columns += (long) (toX - x + 1) * (toZ - z + 1);
        }
        org.junit.jupiter.api.Assertions.assertEquals(1001L * 511L, columns);
        org.junit.jupiter.api.Assertions.assertTrue(JobParts.keepsPages("count_blocks"));
        org.junit.jupiter.api.Assertions.assertEquals("server_count_blocks", JobParts.toolFor("count_blocks"));
    }
}
