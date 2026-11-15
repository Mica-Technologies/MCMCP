package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WindowTimelineTest {

    private static final long S = 1_000_000_000L;

    @Test
    void aSampleThatLostFocusForPartOfItsWindowSaysHowMuch() {
        WindowTimeline timeline = new WindowTimeline(16);
        timeline.note(0, false, false);
        timeline.note(4 * S, true, true);
        timeline.note(8 * S, false, false);
        double[] share = timeline.fractions(0, 10 * S);
        assertEquals(0.4, share[0], 1e-9);
        assertEquals(0.4, share[1], 1e-9);
    }

    @Test
    void anUnchangedStateIsOneEntryHoweverOftenItIsNoted() {
        WindowTimeline timeline = new WindowTimeline(2);
        timeline.note(0, true, false);
        for (int i = 1; i < 1000; i++) {
            timeline.note(i * S / 100, true, false);
        }
        timeline.note(10 * S, false, false);
        // Had every note been stored, the two-slot ring would have lost the start of the unfocused run.
        assertEquals(1.0, timeline.fractions(0, 10 * S)[0], 1e-9);
    }

    @Test
    void aWindowEntirelyBeforeTheFirstNoteCountsAsNeither() {
        WindowTimeline timeline = new WindowTimeline(4);
        timeline.note(10 * S, true, true);
        double[] share = timeline.fractions(0, 5 * S);
        assertEquals(0.0, share[0], 1e-9);
        assertEquals(0.0, share[1], 1e-9);
    }
}
