package com.micatechnologies.minecraft.mcmcp.perf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A list that, popped while empty, hands back a harmless value instead of throwing (#107, #134).
 *
 * <p>The vanilla profiler keeps its open sections and their start times in two lists, and
 * {@code endSection} pops both with {@code remove(size() - 1)}. A mod that checks
 * {@code profilingEnabled} itself and skips its {@code startSection} while profiling is off, then
 * finds it on by the time it calls {@code endSection}, pops an empty list: {@code remove(-1)} throws
 * on the game thread and the game crashes. MTS does exactly that on the client; on a dedicated server
 * the same pop took a live server down during {@code server_profile_sections}.
 *
 * <p>Swapped in for those lists, this answers {@code remove(-1)} with what the caller expects to
 * cast — an empty string for the section names, the current time for the start times, so the
 * section closed reads as taking no time — and counts it.
 *
 * <p>Given an owner thread, it also ignores pushes and pops from any other thread, and counts them.
 * The profiler belongs to one thread and is guarded by nothing; a mod that profiles from a worker
 * (an asynchronous world editor, say) otherwise pushes onto the owner's stack and pops from it
 * out of order. Ignoring both halves keeps the owner's stack exactly as the owner left it.
 *
 * <p>Every other call is an ordinary {@link ArrayList}'s. Imports no Minecraft class, so it is
 * unit-tested directly.
 */
public final class GuardedStack extends ArrayList<Object> {

    private static final long serialVersionUID = 1L;

    /** Pops of an empty stack absorbed since {@link #takeUnderflows()} last read them. */
    private final AtomicInteger underflows = new AtomicInteger();

    /** Pushes and pops from a thread other than the owner, ignored since last read. */
    private final AtomicInteger foreign = new AtomicInteger();

    /** The one thread whose pushes and pops count, or null to accept every thread's. */
    private final transient Thread owner;

    /** What an underflow returns until an element shows what the list holds. */
    private Class<?> holds;

    public GuardedStack(Collection<?> contents, Class<?> holds) {
        this(contents, holds, null);
    }

    public GuardedStack(Collection<?> contents, Class<?> holds, Thread owner) {
        super(contents);
        this.holds = holds;
        this.owner = owner;
    }

    @Override
    public boolean add(Object element) {
        if (owner != null && Thread.currentThread() != owner) {
            foreign.incrementAndGet();
            return true;
        }
        if (element != null) {
            holds = element.getClass();
        }
        return super.add(element);
    }

    @Override
    public Object remove(int index) {
        if (owner != null && Thread.currentThread() != owner) {
            foreign.incrementAndGet();
            return placeholder();
        }
        if (index < 0 && isEmpty()) {
            underflows.incrementAndGet();
            return placeholder();
        }
        return super.remove(index);
    }

    /** Reads and resets the count of absorbed empty pops. */
    public int takeUnderflows() {
        return underflows.getAndSet(0);
    }

    /** Reads and resets the count of ignored calls from other threads. */
    public int takeForeign() {
        return foreign.getAndSet(0);
    }

    private Object placeholder() {
        if (holds == String.class) {
            return "";
        }
        if (holds == Long.class) {
            return System.nanoTime();
        }
        return null;
    }
}
