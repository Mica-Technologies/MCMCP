package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The profiler's stacks, popped once too often. */
class GuardedStackTest {

    /** Vanilla's endSection, verbatim in shape: the casts are what a wrong return value breaks. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static long endSection(List sections, List timestamps) {
        long now = System.nanoTime();
        long started = ((Long) timestamps.remove(timestamps.size() - 1)).longValue();
        sections.remove(sections.size() - 1);
        String current = !sections.isEmpty() ? (String) sections.get(sections.size() - 1) : "";
        assertTrue(current != null);
        return now - started;
    }

    @Test
    void anEndSectionWithNothingOpenIsIgnoredAndCountedInsteadOfThrowing() {
        GuardedStack.UNDERFLOWS.set(0);
        GuardedStack sections = new GuardedStack(new ArrayList<String>(), String.class);
        GuardedStack timestamps = new GuardedStack(new ArrayList<Long>(), Long.class);
        long took = endSection(sections, timestamps);
        assertTrue(took <= 0, "an unopened section reads as taking no time, not " + took);
        assertEquals(2, GuardedStack.UNDERFLOWS.get());
    }

    @Test
    void aBalancedSectionBehavesExactlyAsAnArrayList() {
        GuardedStack.UNDERFLOWS.set(0);
        GuardedStack sections = new GuardedStack(Arrays.asList("root"), String.class);
        GuardedStack timestamps = new GuardedStack(Arrays.asList(1L), Long.class);
        sections.add("root.tick");
        timestamps.add(System.nanoTime());
        endSection(sections, timestamps);
        assertEquals(Arrays.asList("root"), sections);
        assertEquals(0, GuardedStack.UNDERFLOWS.get());
        assertThrows(IndexOutOfBoundsException.class, () -> sections.remove(5));
    }

    @Test
    void anUnknownElementTypeUnderflowsToNull() {
        assertNull(new GuardedStack(new ArrayList<>(), Object.class).remove(-1));
        GuardedStack learned = new GuardedStack(new ArrayList<>(), Object.class);
        learned.add("x");
        learned.remove(0);
        assertEquals("", learned.remove(-1));
    }
}
