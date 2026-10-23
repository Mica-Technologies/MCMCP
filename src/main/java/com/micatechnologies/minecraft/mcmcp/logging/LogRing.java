package com.micatechnologies.minecraft.mcmcp.logging;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The lines one captured logger has written (#136), newest kept, oldest overwritten. Each line has a
 * sequence number, so a reader passes back the last one it saw and gets only what came after, with a
 * count of any it missed because the ring wrapped.
 *
 * <p>Imports no Minecraft or log4j class: {@link LogCapture} feeds it from log4j's threads, and a test
 * feeds it directly. Every method is synchronized, because lines arrive on whatever thread logged them.
 */
public final class LogRing {

    /** One logged line, copied out of the log event as it was appended. */
    public static final class Entry {
        public final long seq;
        public final long timeMillis;
        public final String level;
        public final String logger;
        public final String thread;
        public final String message;

        Entry(long seq, long timeMillis, String level, String logger, String thread, String message) {
            this.seq = seq;
            this.timeMillis = timeMillis;
            this.level = level;
            this.logger = logger;
            this.thread = thread;
            this.message = message;
        }

        /** As latest.log lays a line out, with milliseconds: {@code [12:01:02.345] [thread/DEBUG] [logger]: text}. */
        public String format() {
            SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
            return "[" + clock.format(new Date(timeMillis)) + "] [" + thread + "/" + level + "] [" + logger + "]: "
                + message;
        }
    }

    /** What one read returned. */
    public static final class Page {
        /** Oldest first. */
        public final List<Entry> entries;
        /** The sequence number to pass as {@code since} next time: the newest line this read looked at. */
        public final long next;
        /** Lines after {@code since} that the ring had already overwritten. */
        public final long missed;
        /** Whether more matching lines follow the last one returned. */
        public final boolean more;

        Page(List<Entry> entries, long next, long missed, boolean more) {
            this.entries = entries;
            this.next = next;
            this.missed = missed;
            this.more = more;
        }
    }

    private final Entry[] slots;
    /** The sequence number the next line gets. The first is 1, so 0 means "from the start". */
    private long nextSeq = 1;

    public LogRing(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity " + capacity);
        }
        this.slots = new Entry[capacity];
    }

    public synchronized void add(long timeMillis, String level, String logger, String thread, String message) {
        long seq = nextSeq++;
        slots[(int) (seq % slots.length)] = new Entry(seq, timeMillis, level, logger, thread, message);
    }

    /** Lines written so far, overwritten ones included. */
    public synchronized long written() {
        return nextSeq - 1;
    }

    /**
     * Reads lines matching {@code filter} (a case-insensitive substring of the formatted line, or null
     * for every line). With {@code since} null, the newest {@code limit}; otherwise the oldest
     * {@code limit} after that sequence number, and {@code more} says whether others follow.
     */
    public synchronized Page read(Long since, int limit, String filter) {
        long newest = nextSeq - 1;
        long oldest = Math.max(1, nextSeq - slots.length);
        String needle = filter == null || filter.isEmpty() ? null : filter.toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        if (since == null) {
            for (long seq = newest; seq >= oldest && out.size() < limit; seq--) {
                Entry entry = slots[(int) (seq % slots.length)];
                if (matches(entry, needle)) {
                    out.add(0, entry);
                }
            }
            return new Page(out, newest, 0, false);
        }
        long from = Math.max(since + 1, oldest);
        long missed = Math.max(0, oldest - (since + 1));
        long seq = from;
        long lastLooked = Math.max(since, Math.min(from - 1, newest));
        for (; seq <= newest && out.size() < limit; seq++) {
            Entry entry = slots[(int) (seq % slots.length)];
            lastLooked = seq;
            if (matches(entry, needle)) {
                out.add(entry);
            }
        }
        boolean more = false;
        for (long rest = lastLooked + 1; rest <= newest; rest++) {
            if (matches(slots[(int) (rest % slots.length)], needle)) {
                more = true;
                break;
            }
        }
        return new Page(out, more ? lastLooked : newest, missed, more);
    }

    private static boolean matches(Entry entry, String needle) {
        return needle == null || entry.format().toLowerCase(Locale.ROOT).contains(needle);
    }
}
