package com.micatechnologies.minecraft.mcmcp.perf;

import java.util.ArrayList;
import java.util.List;

/**
 * Feeds a {@link CallTree} only the samples taken during ticks that ran long.
 *
 * <p>A server that hitches once a minute spends 99.9% of its time not hitching, so an ordinary
 * profile of it is a profile of a healthy server: the hitch is a rounding error in the tree. The
 * fix is to not know, when a sample is taken, whether it will be kept. Samples are held back per
 * tick, and when the tick ends its measured duration decides whether they go into the tree or are
 * thrown away.
 *
 * <p>The duration is the tick's real one, from the {@link TickClock}, not an estimate from how many
 * samples landed in it — a sampler that was descheduled for half a slow tick would otherwise judge
 * it fast.
 *
 * <p>Imports no Minecraft class. Not thread-safe: one sampler owns it.
 */
public final class SlowTickFilter {

    private final CallTree tree;
    private final long thresholdNanos;
    private final boolean skipIdle;

    private final List<StackTraceElement[]> held = new ArrayList<StackTraceElement[]>();
    private long heldForCount = -1L;
    private long ticksSeen;
    private long ticksKept;

    public SlowTickFilter(CallTree tree, long thresholdNanos, boolean skipIdle) {
        this.tree = tree;
        this.thresholdNanos = thresholdNanos;
        this.skipIdle = skipIdle;
    }

    /**
     * Offers one sample, with the clock as it read when the sample was taken.
     *
     * @param completedTicks how many ticks had finished — so the sample belongs to the next one
     * @param lastTickNanos  how long the most recently finished tick took
     */
    public void sample(StackTraceElement[] stack, long completedTicks, long lastTickNanos) {
        if (heldForCount >= 0L && completedTicks != heldForCount) {
            // The tick the held samples belong to has ended. Its duration is only known if it is
            // the one that just finished; if the count moved by more than one, a tick went by
            // between two samples, the held ones cannot be matched to a duration, and guessing
            // would put a fast tick's samples in a tree that claims to hold only slow ones.
            if (completedTicks == heldForCount + 1L) {
                ticksSeen++;
                if (lastTickNanos >= thresholdNanos) {
                    ticksKept++;
                    for (StackTraceElement[] each : held) {
                        tree.add(each, skipIdle);
                    }
                }
            }
            held.clear();
        }
        heldForCount = completedTicks;
        held.add(stack);
    }

    /** Ticks whose samples could be matched to a duration. */
    public long ticksSeen() {
        return ticksSeen;
    }

    /** Of those, the ones that ran at least as long as the threshold and went into the tree. */
    public long ticksKept() {
        return ticksKept;
    }
}
