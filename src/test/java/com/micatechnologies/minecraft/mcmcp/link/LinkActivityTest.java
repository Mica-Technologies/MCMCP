package com.micatechnologies.minecraft.mcmcp.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import org.junit.jupiter.api.Test;

/**
 * The orchestrator's {@code activity} frame, as {@code protocol::activity} in the orchestrator builds
 * it. The two sides share field names and nothing else; a rename on one is caught here and by the
 * end-to-end test, not by a compiler.
 */
class LinkActivityTest {

    private static JsonObject frame(String json) {
        return Json.parse(json).getAsJsonObject();
    }

    @Test
    void aTaskFrameIsReadFieldByField() {
        LinkActivity.Task task = LinkActivity.parse(frame("{\"type\":\"activity\",\"task\":{"
            + "\"list\":\"Signal tests\",\"progress\":\"2/5 done\",\"title\":\"test each\","
            + "\"status\":\"doing\"}}"));
        assertEquals("Signal tests", task.getList());
        assertEquals("2/5 done", task.getProgress());
        assertEquals("test each", task.getTitle());
        assertTrue(task.isInProgress());
    }

    @Test
    void aNullTaskClearsWhatIsShown() {
        assertNull(LinkActivity.parse(frame("{\"type\":\"activity\",\"task\":null}")));
        assertNull(LinkActivity.parse(frame("{\"type\":\"activity\"}")));
    }

    @Test
    void aTaskWithNothingToShowIsNoTask() {
        assertNull(LinkActivity.parse(frame("{\"type\":\"activity\",\"task\":{\"list\":\"x\"}}")));
    }

    @Test
    void aTaskNotYetStartedIsNotInProgress() {
        LinkActivity.Task task = LinkActivity.parse(frame(
            "{\"type\":\"activity\",\"task\":{\"title\":\"next\",\"status\":\"todo\"}}"));
        assertFalse(task.isInProgress());
        assertEquals("", task.getList());
    }

    @Test
    void acceptingAFrameReplacesThatLinksTask() {
        LinkActivity.accept(McmcpSide.CLIENT, frame("{\"type\":\"activity\",\"task\":{\"title\":\"a\"}}"));
        assertEquals("a", LinkActivity.current().getTitle());
        LinkActivity.accept(McmcpSide.CLIENT, frame("{\"type\":\"activity\",\"task\":null}"));
        assertNull(LinkActivity.current());
    }

    @Test
    void oneLinkGoingAwayLeavesTheOthersTaskShowing() {
        // A singleplayer world closing takes its server link with it; the client is still linked
        // and the orchestrator will not resend an unchanged task.
        String json = "{\"type\":\"activity\",\"task\":{\"title\":\"build\",\"status\":\"doing\"}}";
        LinkActivity.accept(McmcpSide.CLIENT, frame(json));
        LinkActivity.accept(McmcpSide.SERVER, frame(json));
        LinkActivity.clear(McmcpSide.SERVER);
        assertEquals("build", LinkActivity.current().getTitle());
        LinkActivity.clear(McmcpSide.CLIENT);
        assertNull(LinkActivity.current());
    }
}
