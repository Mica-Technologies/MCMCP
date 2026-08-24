package com.micatechnologies.minecraft.mcmcp.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Reconnect pacing: that it climbs, that it stops climbing, and that it actually resets. */
class LinkBackoffTest {

    @Test
    void doublesTheDelayAfterEachAttempt() {
        LinkBackoff backoff = new LinkBackoff(1000L, 30000L);

        assertEquals(1000L, backoff.nextDelayMillis());
        assertEquals(2000L, backoff.nextDelayMillis());
        assertEquals(4000L, backoff.nextDelayMillis());
        assertEquals(8000L, backoff.nextDelayMillis());
    }

    @Test
    void stopsClimbingAtTheCeiling() {
        // The event being waited for is a person starting an app. Unbounded growth would mean a
        // game that took four minutes to notice the orchestrator it has been waiting for all along.
        LinkBackoff backoff = new LinkBackoff(1000L, 4000L);

        for (int i = 0; i < 20; i++) {
            assertTrue(backoff.nextDelayMillis() <= 4000L);
        }
        assertEquals(4000L, backoff.peekMillis());
    }

    @Test
    void returnsToTheInitialDelayAfterASuccessfulConnection() {
        // Without this, an orchestrator restarted twice in a session would leave the instance
        // waiting half a minute to notice it came back, having "learned" a long delay from an
        // outage that is already over.
        LinkBackoff backoff = new LinkBackoff(1000L, 30000L);
        for (int i = 0; i < 10; i++) {
            backoff.nextDelayMillis();
        }
        assertEquals(30000L, backoff.peekMillis());

        backoff.reset();

        assertEquals(1000L, backoff.nextDelayMillis());
    }

    @Test
    void peekingDoesNotAdvanceTheDelay() {
        LinkBackoff backoff = new LinkBackoff(1000L, 30000L);

        assertEquals(1000L, backoff.peekMillis());
        assertEquals(1000L, backoff.peekMillis());
        assertEquals(1000L, backoff.nextDelayMillis());
    }

    @Test
    void clampsACeilingThatSitsBelowTheFloor() {
        // Both numbers come from a hand-edited config file. A max under the initial would otherwise
        // make the very first doubling shorten the delay.
        LinkBackoff backoff = new LinkBackoff(5000L, 1000L);

        assertEquals(5000L, backoff.nextDelayMillis());
        assertEquals(5000L, backoff.nextDelayMillis());
    }

    @Test
    void survivesAnAbsurdCeilingWithoutOverflowing() {
        // Doubling toward Long.MAX_VALUE would wrap to a negative delay, and a negative delay is
        // scheduled immediately — turning a backoff into a spin.
        LinkBackoff backoff = new LinkBackoff(1000L, Long.MAX_VALUE);

        long previous = 0L;
        for (int i = 0; i < 200; i++) {
            long delay = backoff.nextDelayMillis();
            assertTrue(delay > 0L, "delay went non-positive at iteration " + i + ": " + delay);
            assertTrue(delay >= previous, "delay went backwards at iteration " + i);
            previous = delay;
        }
    }
}
