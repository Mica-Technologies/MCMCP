package com.micatechnologies.minecraft.mcmcp.perf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A list that, popped while empty, hands back a harmless value instead of throwing (#107).
 *
 * <p>The vanilla profiler keeps its open sections and their start times in two lists, and
 * {@code endSection} pops both with {@code remove(size() - 1)}. A mod that checks
 * {@code profilingEnabled} itself and skips its {@code startSection} while profiling is off, then
 * finds it on by the time it calls {@code endSection}, pops an empty list: {@code remove(-1)} throws
 * on the client thread and the game crashes. MTS does exactly that, and a pack with OptiFine reached
 * it while {@code client_profile_sections} had the profiler on.
 *
 * <p>Swapped in for those lists, this answers {@code remove(-1)} with what the caller expects to
 * cast — an empty string for the section names, the current time for the start times, so the
 * section closed reads as taking no time — and counts it. Every other call is an ordinary
 * {@link ArrayList}'s.
 *
 * <p>Imports no Minecraft class, so it is unit-tested directly.
 */
public final class GuardedStack extends ArrayList<Object> {

    private static final long serialVersionUID = 1L;

    /** Pops of an empty stack absorbed since the counter was last read, across every instance. */
    public static final AtomicInteger UNDERFLOWS = new AtomicInteger();

    /** What an underflow returns until an element shows what the list holds. */
    private Class<?> holds;

    public GuardedStack(Collection<?> contents, Class<?> holds) {
        super(contents);
        this.holds = holds;
    }

    @Override
    public boolean add(Object element) {
        if (element != null) {
            holds = element.getClass();
        }
        return super.add(element);
    }

    @Override
    public Object remove(int index) {
        if (index < 0 && isEmpty()) {
            UNDERFLOWS.incrementAndGet();
            if (holds == String.class) {
                return "";
            }
            if (holds == Long.class) {
                return System.nanoTime();
            }
            return null;
        }
        return super.remove(index);
    }
}
