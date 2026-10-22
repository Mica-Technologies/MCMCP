package com.micatechnologies.minecraft.mcmcp.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The server task pump's queue: turns between callers, the tick's budget, and who spends it (#133). */
class FairDrainTest {

    /** A clock the tasks move: each task's name says how many milliseconds it takes. */
    private static final class Clock {
        long nanos;
    }

    private static FairDrain.Runner<String> running(Clock clock, List<String> ran) {
        return task -> {
            if (task.startsWith("abandoned")) {
                return false;
            }
            ran.add(task);
            clock.nanos += Long.parseLong(task.substring(task.indexOf('@') + 1)) * 1_000_000L;
            return true;
        };
    }

    @Test
    void callersTakeTurnsSoOneWithManyTasksCannotKeepAnotherWaiting() {
        Clock clock = new Clock();
        FairDrain<String> queue = new FairDrain<>(() -> clock.nanos);
        for (int i = 0; i < 4; i++) {
            queue.offer("busy", "busy" + i + "@1");
        }
        queue.offer("quick", "quick@1");
        List<String> ran = new ArrayList<>();
        queue.drain(100_000_000L, Integer.MAX_VALUE, running(clock, ran));
        assertEquals(Arrays.asList("busy0@1", "quick@1", "busy1@1", "busy2@1", "busy3@1"), ran);
        assertEquals(0, queue.depth());
    }

    @Test
    void theTurnOrderCarriesOverFromOneTickToTheNext() {
        Clock clock = new Clock();
        FairDrain<String> queue = new FairDrain<>(() -> clock.nanos);
        queue.offer("a", "a0@10");
        queue.offer("a", "a1@10");
        queue.offer("b", "b0@10");
        queue.offer("b", "b1@10");
        List<String> ran = new ArrayList<>();
        queue.drain(5_000_000L, Integer.MAX_VALUE, running(clock, ran));
        queue.drain(5_000_000L, Integer.MAX_VALUE, running(clock, ran));
        queue.drain(5_000_000L, Integer.MAX_VALUE, running(clock, ran));
        assertEquals(Arrays.asList("a0@10", "b0@10", "a1@10"), ran, "a went first, so b goes first next tick");
    }

    @Test
    void aTickStopsStartingTasksOnceItsBudgetIsSpentButAlwaysRunsOne() {
        Clock clock = new Clock();
        FairDrain<String> queue = new FairDrain<>(() -> clock.nanos);
        queue.offer("a", "a0@40");
        queue.offer("a", "a1@1");
        List<String> ran = new ArrayList<>();
        assertEquals(1, queue.drain(15_000_000L, Integer.MAX_VALUE, running(clock, ran)),
            "a slice longer than the budget still runs, and is the tick's last");
        assertEquals(1, queue.depth());
        assertEquals(1, queue.drain(0L, Integer.MAX_VALUE, running(clock, ran)), "no budget at all still runs one");
    }

    @Test
    void aTaskLimitOfOneRunsExactlyOneLiveTaskATick() {
        Clock clock = new Clock();
        FairDrain<String> queue = new FairDrain<>(() -> clock.nanos);
        queue.offer("a", "abandoned-1@0");
        queue.offer("b", "abandoned-2@0");
        queue.offer("a", "a0@1");
        queue.offer("b", "b0@1");
        List<String> ran = new ArrayList<>();
        assertEquals(1, queue.drain(1_000_000_000L, 1, running(clock, ran)));
        assertEquals(Arrays.asList("a0@1"), ran, "abandoned tasks are skipped and use no turn of the one");
        assertEquals(1, queue.depth());
    }

    @Test
    void eachCallersShareOfTheTickIsAMovingAverageTheBiggestFirst() {
        Clock clock = new Clock();
        FairDrain<String> queue = new FairDrain<>(() -> clock.nanos);
        List<String> ran = new ArrayList<>();
        for (int tick = 0; tick < 200; tick++) {
            queue.offer("heavy", "h@10");
            queue.offer("light", "l@1");
            queue.drain(100_000_000L, Integer.MAX_VALUE, running(clock, ran));
        }
        List<FairDrain.Share> shares = queue.shares();
        assertEquals("heavy", shares.get(0).key);
        assertEquals(10.0D, shares.get(0).msPerTick, 0.01D);
        assertEquals(1.0D, shares.get(1).msPerTick, 0.01D);
        assertEquals(11.0D, queue.totalMsPerTick(), 0.02D);
        for (int tick = 0; tick < 400; tick++) {
            queue.drain(100_000_000L, Integer.MAX_VALUE, running(clock, ran));
        }
        assertTrue(queue.shares().isEmpty(), "callers who stopped spending are forgotten");
    }

    @Test
    void aFollowUpRunsBeforeEveryCallersTurnSoItIsNotLeftBehindOtherCallersSlices() {
        Clock clock = new Clock();
        FairDrain<String> queue = new FairDrain<>(() -> clock.nanos);
        queue.offer("a", "a0@40");
        queue.offer("b", "b0@40");
        queue.offer("c", "c0@40");
        queue.offerFirst("c", "c-undo@1");
        List<String> ran = new ArrayList<>();
        assertEquals(2, queue.drain(15_000_000L, Integer.MAX_VALUE, running(clock, ran)),
            "the follow-up, then one slice before the budget is spent");
        assertEquals(Arrays.asList("c-undo@1", "a0@40"), ran);
    }

    @Test
    void followUpsRunInTheOrderOfferedAndTakeNoCallersTurn() {
        Clock clock = new Clock();
        FairDrain<String> queue = new FairDrain<>(() -> clock.nanos);
        queue.offer("a", "a0@1");
        queue.offer("b", "b0@1");
        queue.offerFirst("b", "b-undo@1");
        queue.offerFirst("a", "a-undo@1");
        List<String> ran = new ArrayList<>();
        queue.drain(100_000_000L, Integer.MAX_VALUE, running(clock, ran));
        assertEquals(Arrays.asList("b-undo@1", "a-undo@1", "a0@1", "b0@1"), ran);
        assertEquals(0, queue.depth());
    }

    @Test
    void aFollowUpIsTheOneTaskOfATickThatRunsOnlyOne() {
        Clock clock = new Clock();
        FairDrain<String> queue = new FairDrain<>(() -> clock.nanos);
        queue.offer("a", "a0@1");
        queue.offerFirst("b", "b-undo@1");
        List<String> ran = new ArrayList<>();
        assertEquals(1, queue.drain(0L, 1, running(clock, ran)));
        assertEquals(Arrays.asList("b-undo@1"), ran);
    }

    @Test
    void clearingTakesEverythingQueuedWithoutRunningIt() {
        Clock clock = new Clock();
        FairDrain<String> queue = new FairDrain<>(() -> clock.nanos);
        queue.offer("a", "a0@1");
        queue.offer("b", "b0@1");
        queue.offerFirst("b", "b-undo@1");
        assertEquals(3, queue.clear().size());
        assertEquals(0, queue.depth());
        List<String> ran = new ArrayList<>();
        assertEquals(0, queue.drain(1_000_000L, Integer.MAX_VALUE, running(clock, ran)));
    }

    @Test
    void theBudgetShrinksWithTheServersHealth() {
        assertEquals(15_000_000L, TaskBudget.budgetNanos(ServerLoad.Level.OK, 15));
        assertEquals(5_000_000L, TaskBudget.budgetNanos(ServerLoad.Level.THROTTLED, 15), "jobs held: 5 ms");
        assertEquals(3_000_000L, TaskBudget.budgetNanos(ServerLoad.Level.THROTTLED, 3), "never more than configured");
        assertEquals(1, TaskBudget.maxTasks(ServerLoad.Level.PAUSED), "writes refused: one task a tick");
        assertEquals(1, TaskBudget.maxTasks(ServerLoad.Level.STALLED));
        assertEquals(Integer.MAX_VALUE, TaskBudget.maxTasks(ServerLoad.Level.OK));
    }
}
