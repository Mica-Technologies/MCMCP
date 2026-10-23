package com.micatechnologies.minecraft.mcmcp.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

/** Raising a real log4j logger's level for a while and keeping what it writes (#136). */
class LogCaptureTest {

    @Test
    void aDebugLineNobodyWouldSeeIsKeptWhileTheCaptureRunsAndNotAfter() {
        Logger logger = LogManager.getLogger("mcmcp-test-tts");
        assertFalse(logger.isDebugEnabled(), "the test configuration starts above DEBUG");
        assertNull(LogCapture.start("mcmcp-test-tts", "DEBUG", 60));
        assertTrue(logger.isDebugEnabled());
        logger.debug("SEND {}", "next stop Grand Central");

        LogRing.Page page = LogCapture.ring("mcmcp-test-tts").read(null, 10, null);
        assertEquals(1, page.entries.size());
        assertEquals("SEND next stop Grand Central", page.entries.get(0).message);
        assertEquals("DEBUG", page.entries.get(0).level);

        assertTrue(LogCapture.stop("mcmcp-test-tts"));
        assertFalse(logger.isDebugEnabled(), "the level is put back");
        logger.error("after");
        assertEquals(1, LogCapture.ring("mcmcp-test-tts").written(), "nothing is captured once it is put back");
        assertFalse(LogCapture.stop("mcmcp-test-tts"), "a second stop has nothing to put back");
    }

    @Test
    void aChildLoggerWithNoConfigOfItsOwnIsCapturedWithItsParent() {
        Logger child = LogManager.getLogger("mcmcp-test-parent.child");
        assertNull(LogCapture.start("mcmcp-test-parent", "TRACE", 60));
        child.trace("from the child");
        LogRing.Entry entry = LogCapture.ring("mcmcp-test-parent").read(null, 1, null).entries.get(0);
        assertEquals("mcmcp-test-parent.child", entry.logger);
        LogCapture.stop("mcmcp-test-parent");
        assertFalse(child.isTraceEnabled());
    }

    @Test
    void onlyTheFiveLevelsAreTakenInAnyCase() {
        assertEquals("DEBUG", LogCapture.parseLevel(" debug "));
        assertEquals("TRACE", LogCapture.parseLevel("Trace"));
        assertNull(LogCapture.parseLevel("ALL"));
        assertNull(LogCapture.parseLevel("verbose"));
        assertNull(LogCapture.parseLevel(null));
    }
}
