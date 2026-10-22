package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A profiler with vanilla's two stacks, guarded the way the server's is from server start. */
class ProfilerGuardTest {

    /** Vanilla's Profiler in the shape that matters: two private lists, popped together. */
    static final class StandInProfiler {
        boolean profilingEnabled;
        // Final, as vanilla's are: the guard has to replace them anyway.
        private final List<String> sectionList = new ArrayList<String>();
        private final List<Long> timestampList = new ArrayList<Long>();
        private String profilingSection = "";

        void startSection(String name) {
            if (profilingEnabled) {
                if (!profilingSection.isEmpty()) {
                    profilingSection = profilingSection + ".";
                }
                profilingSection = profilingSection + name;
                sectionList.add(profilingSection);
                timestampList.add(System.nanoTime());
            }
        }

        void endSection() {
            if (profilingEnabled) {
                long started = timestampList.remove(timestampList.size() - 1);
                sectionList.remove(sectionList.size() - 1);
                profilingSection = sectionList.isEmpty() ? "" : sectionList.get(sectionList.size() - 1);
                if (System.nanoTime() < started) {
                    throw new AssertionError("a section closed before it opened");
                }
            }
        }

        int depth() {
            return sectionList.size();
        }
    }

    @Test
    void anUnguardedProfilerCrashesOnAnEndSectionThatWasNeverStarted() {
        StandInProfiler profiler = new StandInProfiler();
        profiler.profilingEnabled = true;
        assertThrows(IndexOutOfBoundsException.class, profiler::endSection);
    }

    @Test
    void aGuardedProfilerAbsorbsAnUnbalancedEndSectionAndCountsItOnce() {
        StandInProfiler profiler = new StandInProfiler();
        assertEquals(2, ProfilerGuard.install(profiler, null));
        // A mod that skipped its startSection while profiling was off, then ends it once it is on.
        profiler.profilingEnabled = true;
        profiler.startSection("root");
        profiler.endSection();
        profiler.endSection();
        assertEquals(0, profiler.depth());
        assertEquals(1, ProfilerGuard.take(profiler).underflows, "one endSection pops both lists but is one call");
        assertEquals(0, ProfilerGuard.take(profiler).underflows, "taking the counts resets them");
    }

    @Test
    void installingTwiceKeepsTheFirstGuardAndItsCounts() {
        StandInProfiler profiler = new StandInProfiler();
        ProfilerGuard.install(profiler, null);
        Object first = profiler.sectionList;
        profiler.profilingEnabled = true;
        profiler.endSection();
        assertEquals(2, ProfilerGuard.install(profiler, Thread.currentThread()));
        assertSame(first, profiler.sectionList);
        assertEquals(1, ProfilerGuard.take(profiler).underflows);
    }

    @Test
    void aProfilerCallFromAnotherThreadCannotUnbalanceTheOwnersStack() throws InterruptedException {
        final StandInProfiler profiler = new StandInProfiler();
        ProfilerGuard.install(profiler, Thread.currentThread());
        profiler.profilingEnabled = true;
        profiler.startSection("root");
        Thread worker = new Thread(() -> {
            profiler.startSection("async");
            profiler.endSection();
            profiler.endSection();
        });
        worker.start();
        worker.join();
        assertEquals(1, profiler.depth());
        profiler.endSection();
        assertEquals(0, profiler.depth());
        ProfilerGuard.Counts counts = ProfilerGuard.take(profiler);
        assertEquals(3, counts.foreign);
        assertEquals(0, counts.underflows);
    }
}
