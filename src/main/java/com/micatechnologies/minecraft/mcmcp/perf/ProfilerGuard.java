package com.micatechnologies.minecraft.mcmcp.perf;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts a {@link GuardedStack} in place of every list a profiler holds, and reads back what they
 * absorbed (#107, #134).
 *
 * <p>Works on any object by reflection, found by field type rather than name: the names differ
 * between a dev game and a released jar, and OptiFine's client profiler has more lists than
 * vanilla's. That is also what keeps this free of Minecraft classes, so it is tested against a
 * stand-in with the same fields.
 */
public final class ProfilerGuard {

    /** What a profile run reports: both are counts of profiler calls, not of list operations. */
    public static final class Counts {
        public final int underflows;
        public final int foreign;

        Counts(int underflows, int foreign) {
            this.underflows = underflows;
            this.foreign = foreign;
        }
    }

    private ProfilerGuard() {
    }

    /**
     * Swaps each list field of {@code profiler} not already guarded. Must run on the thread that
     * owns the profiler, with no profiler call in progress. Left in place for good: a guarded list
     * differs from the one it replaced only where that one would throw, or, given an owner, where
     * another thread would have corrupted it.
     *
     * @param owner the only thread whose pushes and pops are kept, or null to keep every thread's
     * @return how many lists are guarded afterwards; 0 means none could be, which is how every
     *         earlier version ran
     */
    public static int install(Object profiler, Thread owner) {
        int guarded = 0;
        for (Field field : listFields(profiler)) {
            try {
                Object current = field.get(profiler);
                if (current instanceof GuardedStack) {
                    guarded++;
                    continue;
                }
                if (!(current instanceof List)) {
                    continue;
                }
                field.set(profiler, new GuardedStack((List<?>) current, elementType(field), owner));
                guarded++;
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Unguarded is how every earlier version ran; not a reason to fail.
            }
        }
        return guarded;
    }

    /**
     * Reads and resets what the guarded lists absorbed. One {@code endSection} with nothing open
     * pops both lists, so each count is the largest over the lists, not their sum.
     */
    public static Counts take(Object profiler) {
        int underflows = 0;
        int foreign = 0;
        for (Field field : listFields(profiler)) {
            try {
                Object current = field.get(profiler);
                if (current instanceof GuardedStack) {
                    GuardedStack stack = (GuardedStack) current;
                    underflows = Math.max(underflows, stack.takeUnderflows());
                    foreign = Math.max(foreign, stack.takeForeign());
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Nothing to report from a field that cannot be read.
            }
        }
        return new Counts(underflows, foreign);
    }

    private static List<Field> listFields(Object profiler) {
        List<Field> fields = new ArrayList<Field>();
        for (Class<?> type = profiler.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != List.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (RuntimeException e) {
                    // Inaccessible: left as it is.
                }
            }
        }
        return fields;
    }

    private static Class<?> elementType(Field field) {
        Type generic = field.getGenericType();
        if (generic instanceof ParameterizedType) {
            Type argument = ((ParameterizedType) generic).getActualTypeArguments()[0];
            if (argument instanceof Class) {
                return (Class<?>) argument;
            }
        }
        return Object.class;
    }
}
