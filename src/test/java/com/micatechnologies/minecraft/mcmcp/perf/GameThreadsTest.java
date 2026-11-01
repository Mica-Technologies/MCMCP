package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ThreadInfo;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

/** The cheap and the expensive way to read a thread (#170). */
class GameThreadsTest {

    /**
     * The watchdog polls a paused server's thread once a second. It must read frames only: owned
     * synchronizers are found by a stop-the-world walk of the whole heap. A ThreadInfo that lists them
     * is one that paid for that walk.
     */
    @Test
    void stackOnlyReadsFramesWithoutLockDetails() {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        try {
            long self = Thread.currentThread().getId();
            ThreadInfo cheap = GameThreads.stackOnly(self);
            assertNotNull(cheap);
            assertTrue(cheap.getStackTrace().length > 0, "frames are read");
            assertEquals(0, cheap.getLockedSynchronizers().length, "owned synchronizers are not looked for");
            assertEquals(0, cheap.getLockedMonitors().length, "locked monitors are not looked for");

            ThreadInfo full = GameThreads.snapshot(self);
            assertNotNull(full);
            assertEquals(1, full.getLockedSynchronizers().length, "the one-off dump still names the lock held");
        }
        finally {
            lock.unlock();
        }
    }

    @Test
    void stackOnlyOfNoThreadIsNull() {
        assertEquals(null, GameThreads.stackOnly(-1L));
    }
}
