package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The sampler behind the commands' {@code profile}: it samples inside the window and nowhere else. */
class CommandSamplerTest {

    private static volatile long sink;

    /** Spins for {@code millis}; the frame a sample should land on. */
    static void insideTheCommand(long millis) {
        long end = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < end) {
            sink++;
        }
    }

    /** Spins too, but outside any window, so no sample may name it. */
    static void outsideTheCommand(long millis) {
        long end = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < end) {
            sink--;
        }
    }

    private static boolean treeNames(CallTree tree, String method) {
        // Anywhere on a sampled stack, not only as the executing frame: the spin's own nanoTime call
        // is often the top one.
        return tree.samples() > 0L && tree.toJson().toString().contains("." + method);
    }

    /** Runs {@code body} on a fresh thread the sampler is pointed at, and returns the tree. */
    private static CallTree sampleWhile(boolean wholeTick, AtomicLong ticks, ThreadBody body) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        CommandSampler[] holder = new CommandSampler[1];
        Throwable[] failed = new Throwable[1];
        Thread worker = new Thread(() -> {
            try {
                go.await();
                body.run(holder[0]);
            } catch (Throwable t) {
                failed[0] = t;
            }
        }, "sampled thread");
        worker.start();
        CommandSampler sampler = new CommandSampler(worker.getId(), 1, wholeTick, ticks::get);
        holder[0] = sampler;
        sampler.start();
        go.countDown();
        worker.join(10_000L);
        CallTree tree = sampler.stop();
        if (failed[0] != null) {
            throw new AssertionError(failed[0]);
        }
        return tree;
    }

    private interface ThreadBody {
        void run(CommandSampler sampler) throws Exception;
    }

    @Test
    void samplesNameTheCommandAndNeverWhatTheThreadDidOutsideIt() throws Exception {
        CallTree tree = sampleWhile(false, new AtomicLong(), sampler -> {
            outsideTheCommand(150);
            sampler.begin();
            insideTheCommand(300);
            sampler.end();
            outsideTheCommand(150);
        });

        assertTrue(tree.samples() > 0L, "a 300 ms command sampled every millisecond yields samples");
        assertTrue(treeNames(tree, "insideTheCommand"));
        assertFalse(treeNames(tree, "outsideTheCommand"), "nothing outside the window is kept");
    }

    @Test
    void aSamplerWhoseWindowNeverOpensKeepsNothing() throws Exception {
        CallTree tree = sampleWhile(false, new AtomicLong(), sampler -> outsideTheCommand(200));

        assertEquals(0L, tree.samples());
    }

    @Test
    void theTickScopeKeepsSamplingAfterTheCommandUntilItsTickEnds() throws Exception {
        AtomicLong ticks = new AtomicLong(7L);
        CallTree tree = sampleWhile(true, ticks, sampler -> {
            sampler.begin();
            insideTheCommand(100);
            sampler.end();
            // Still tick 7: the work a command set off later in its tick belongs in the tree.
            CommandSamplerTest.laterInTheSameTick(200);
            ticks.incrementAndGet();
            // Tick 8: the server has moved on, and so has the window.
            outsideTheCommand(200);
        });

        assertTrue(treeNames(tree, "insideTheCommand"));
        assertTrue(treeNames(tree, "laterInTheSameTick"));
        assertFalse(treeNames(tree, "outsideTheCommand"), "the next tick is not the command's");
    }

    static void laterInTheSameTick(long millis) {
        long end = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < end) {
            sink += 2;
        }
    }
}
