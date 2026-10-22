package com.micatechnologies.minecraft.mcmcp.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Who gave up first, the game thread or the caller, and what each then does (#132). */
class BridgeTaskTest {

    @Test
    void aTaskWhoseCallerGaveUpBeforeItStartedNeverRunsAndIsReportedAsNotStarted() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        BridgeTask<String> task = new BridgeTask<>(() -> {
            ran.set(true);
            return "done";
        }, null);
        BridgeTask.NotStarted refused = assertThrows(BridgeTask.NotStarted.class, () -> task.await(20L, null));
        assertEquals(20L, refused.waitedMillis());
        assertTrue(refused.getMessage().contains("nothing changed"), refused.getMessage());
        task.run();
        assertFalse(ran.get(), "the game thread reached it after its caller left, and skipped it");
        assertEquals(BridgeTask.State.ABANDONED, task.state());
    }

    @Test
    void aTaskThatFinishesInTimeGivesItsCallerTheValue() throws Exception {
        BridgeTask<String> task = new BridgeTask<>(() -> "done", value -> {
            throw new AssertionError("no cleanup for a task its caller received");
        });
        Thread game = new Thread(task);
        game.start();
        assertEquals("done", task.await(5_000L, null));
        game.join();
        assertEquals(BridgeTask.State.DONE, task.state());
    }

    @Test
    void aTaskStillRunningWhenItsCallerGivesUpSaysItStartedAndHandsItsValueToTheCleanup() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> cleaned = new AtomicReference<>();
        BridgeTask<String> task = new BridgeTask<>(() -> {
            started.countDown();
            release.await();
            return "hold-7";
        }, cleaned::set);
        Thread game = new Thread(task);
        game.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        BridgeTask.StartedNotFinished late = assertThrows(BridgeTask.StartedNotFinished.class,
            () -> task.await(30L, null));
        assertTrue(late.getMessage().contains("not known"), late.getMessage());
        assertNull(cleaned.get(), "still running: nothing to clean up yet");
        release.countDown();
        game.join();
        assertEquals("hold-7", cleaned.get(), "what it took is given back once it ends");
        assertEquals(BridgeTask.State.ORPHANED, task.state());
    }

    @Test
    void aCallCancelledBeforeItsTaskStartedDropsItAtOnceWithoutWaitingOutTheTimeout() {
        AtomicBoolean ran = new AtomicBoolean();
        BridgeTask<String> task = new BridgeTask<>(() -> {
            ran.set(true);
            return "done";
        }, null);
        long before = System.nanoTime();
        assertThrows(CancellationException.class, () -> task.await(60_000L, () -> true));
        assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(10), "not the minute");
        task.run();
        assertFalse(ran.get());
    }

    @Test
    void aCancellationAfterTheTaskStartedStillWaitsForItsValue() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BridgeTask<String> task = new BridgeTask<>(() -> {
            started.countDown();
            release.await();
            return "done";
        }, null);
        Thread game = new Thread(task);
        game.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(200L);
            }
            catch (InterruptedException ignored) {
                // The test fails on its own if this never releases.
            }
            release.countDown();
        });
        releaser.start();
        assertEquals("done", task.await(10_000L, () -> true), "it cannot be stopped halfway, so its end is awaited");
        game.join();
        releaser.join();
    }

    @Test
    void aTaskThatThrowsTellsItsCallerAndNothingEscapesOntoTheGameThread() {
        BridgeTask<String> task = new BridgeTask<>(() -> {
            throw new IllegalStateException("broken");
        }, null);
        task.run();
        ExecutionException failed = assertThrows(ExecutionException.class, () -> task.await(1_000L, null));
        assertTrue(failed.getCause() instanceof IllegalStateException);
    }

    @Test
    void aCleanupThatThrowsDoesNotEscapeOntoTheGameThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BridgeTask<String> task = new BridgeTask<>(() -> {
            started.countDown();
            release.await();
            return "done";
        }, value -> {
            throw new IllegalStateException("cleanup broke");
        });
        AtomicReference<Throwable> escaped = new AtomicReference<>();
        Thread game = new Thread(() -> {
            try {
                task.run();
            }
            catch (Throwable t) {
                escaped.set(t);
            }
        });
        game.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(BridgeTask.StartedNotFinished.class, () -> task.await(20L, null));
        release.countDown();
        game.join();
        assertNull(escaped.get());
    }
}
