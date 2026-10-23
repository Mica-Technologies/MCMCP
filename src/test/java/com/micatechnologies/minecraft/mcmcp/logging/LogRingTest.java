package com.micatechnologies.minecraft.mcmcp.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Keeping a captured logger's lines and reading them back after a cursor (#136). */
class LogRingTest {

    private static LogRing ringOf(int capacity, String... messages) {
        LogRing ring = new LogRing(capacity);
        for (String message : messages) {
            ring.add(1000L, "DEBUG", "rcmc-tts", "Server thread", message);
        }
        return ring;
    }

    @Test
    void aReadWithNoCursorGivesTheNewestLinesOldestFirst() {
        LogRing.Page page = ringOf(10, "a", "b", "c", "d").read(null, 2, null);
        assertEquals(2, page.entries.size());
        assertEquals("c", page.entries.get(0).message);
        assertEquals("d", page.entries.get(1).message);
        assertEquals(4, page.next);
    }

    @Test
    void passingBackNextGivesOnlyTheLinesWrittenSince() {
        LogRing ring = ringOf(10, "a", "b");
        long next = ring.read(null, 100, null).next;
        ring.add(2000L, "DEBUG", "rcmc-tts", "t", "SEND Grand Central");
        LogRing.Page page = ring.read(next, 100, null);
        assertEquals(1, page.entries.size());
        assertEquals("SEND Grand Central", page.entries.get(0).message);
        assertEquals(0, ring.read(page.next, 100, null).entries.size(), "nothing new, nothing returned");
    }

    @Test
    void aCursorTheRingHasWrappedPastSaysHowManyLinesWereLost() {
        LogRing ring = ringOf(3, "1", "2", "3", "4", "5", "6");
        LogRing.Page page = ring.read(1L, 100, null);
        assertEquals(2, page.missed, "lines 2 and 3 were overwritten");
        assertEquals("4", page.entries.get(0).message);
        assertEquals(6, page.next);
    }

    @Test
    void aPageCutShortByTheLimitSaysMoreFollowAndWhereToResume() {
        LogRing ring = ringOf(10, "1", "2", "3", "4", "5");
        LogRing.Page first = ring.read(0L, 2, null);
        assertTrue(first.more);
        assertEquals(2, first.next);
        LogRing.Page second = ring.read(first.next, 10, null);
        assertEquals("3", second.entries.get(0).message);
        assertFalse(second.more);
    }

    @Test
    void theFilterMatchesTheFormattedLineInAnyCaseAndTheCursorStillCoversWhatItSkipped() {
        LogRing ring = ringOf(10, "CHIME", "SEND next stop Grand Central", "RECV ok", "HANDOFF");
        LogRing.Page page = ring.read(0L, 10, "grand central");
        assertEquals(1, page.entries.size());
        assertEquals(4, page.next, "lines that did not match are not read again");
        assertEquals(4, ring.read(0L, 10, "rcmc-tts").entries.size(), "the logger name is part of the line");
    }

    @Test
    void aLineIsLaidOutAsLatestLogLaysItOut() {
        String line = ringOf(1, "hello").read(null, 1, null).entries.get(0).format();
        assertTrue(line.matches("\\[\\d\\d:\\d\\d:\\d\\d\\.\\d{3}] \\[Server thread/DEBUG] \\[rcmc-tts]: hello"), line);
    }
}
