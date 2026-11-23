package com.micatechnologies.minecraft.mcmcp.game;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraftforge.fml.common.Loader;

/**
 * Filesystem locations inside the game installation, and the log-tail reader.
 *
 * <p>The game directory is derived from Forge's config directory rather than read off
 * {@code Minecraft.gameDir}: that field is client-only and its MCP name has moved between versions,
 * while {@link Loader#getConfigDir()} is stable, present on both sides, and always
 * {@code <gameDir>/config}. One parent hop gets the install root on a client, a dedicated server,
 * and a dev launch alike.
 */
public final class McmcpPaths {

    private McmcpPaths() {
    }

    /** The game installation root — the directory holding {@code config/}, {@code logs/}, {@code mods/}. */
    public static File gameDirectory() {
        return Loader.instance().getConfigDir().getParentFile();
    }

    public static File logsDirectory() {
        return new File(gameDirectory(), "logs");
    }

    public static File screenshotsDirectory() {
        return new File(gameDirectory(), "screenshots");
    }

    /** The active log file, whether or not it exists yet. */
    public static File latestLog() {
        return new File(logsDirectory(), "latest.log");
    }

    /**
     * Reads the last {@code lineCount} lines of {@code file}, optionally keeping only lines
     * containing {@code filter}.
     *
     * <p>Seeks backwards from the end rather than reading the file forwards. {@code latest.log} on a
     * modded 1.12.2 instance routinely passes 50 MB during a session, and reading all of it to keep
     * the last 100 lines would allocate that much inside the game process — on the HTTP worker
     * thread, but still in the same heap the game is trying to run in.
     *
     * <p>The backwards scan is done on raw bytes and decoded per line, so a multi-byte UTF-8
     * sequence straddling the read boundary cannot be split: line breaks are single bytes in UTF-8
     * and never appear inside a multi-byte sequence, which makes splitting on them safe before
     * decoding.
     *
     * @param filter case-insensitive substring; null or empty keeps every line
     */
    public static List<String> tail(File file, int lineCount, @Nullable String filter) throws IOException {
        return tail(file, lineCount, filter, null);
    }

    /**
     * As {@link #tail(File, int, String)}, with each line passed through {@code rewrite} before the
     * filter sees it: a line is matched as the caller will read it, so a filter cannot test for what
     * the rewrite took out.
     */
    public static List<String> tail(File file, int lineCount, @Nullable String filter,
                                    @Nullable java.util.function.UnaryOperator<String> rewrite) throws IOException {
        return tail(file, lineCount, filter, rewrite, Integer.MAX_VALUE, Integer.MAX_VALUE, null).lines;
    }

    /**
     * The most of one line that is held while a file is scanned. A file with no newline in it is one
     * line as long as the file: buffered whole, a 2 GB log was 2 GB of heap in the game. The scan keeps
     * this much of a longer line (its end, read backwards; its start, in a gzipped log) and says how
     * much it left out.
     */
    static final int MAX_SCANNED_LINE_BYTES = 1024 * 1024;

    /** What a bounded {@link #tail} read, and how it was cut short. */
    public static final class Tail {

        public final List<String> lines = new ArrayList<>();
        /** Older matching lines were left out to stay within the total size asked for. */
        public boolean cutToSize;
        /** The caller's cancel check came true mid-read; {@link #lines} is not the answer. */
        public boolean cancelled;
    }

    /**
     * As {@link #tail(File, int, String, java.util.function.UnaryOperator)}, bounded for a caller that
     * returns the lines to a model: each line is cut to {@code maxLineChars} with how much was cut,
     * the newest lines are kept up to {@code maxTotalChars} in all, and {@code cancelled} is asked as
     * the file is read, so a filter searching a large log stops when its caller has gone.
     */
    public static Tail tail(File file, int lineCount, @Nullable String filter,
                            @Nullable java.util.function.UnaryOperator<String> rewrite, int maxLineChars,
                            int maxTotalChars, @Nullable java.util.function.BooleanSupplier cancelled)
        throws IOException {
        Tail tail = new Tail();
        List<String> lines = tail.lines;
        if (!file.isFile()) {
            return tail;
        }

        String needle = filter == null || filter.isEmpty()
            ? null
            : filter.toLowerCase(java.util.Locale.ROOT);

        // A rolled log (logs/2026-11-19-1.log.gz) is gzip, and read as bytes it is noise a filter never
        // matches: "0 lines matching" for text that was there (#213). Told apart by its magic bytes, not
        // its name, and a file that is neither is refused rather than reported empty.
        switch (kindOf(file)) {
            case GZIP:
                tailGzip(file, lineCount, needle, rewrite, maxLineChars, maxTotalChars, cancelled, tail);
                return tail;
            case BINARY:
                throw new NotTextException(file.getName());
            default:
                break;
        }

        // Filtering has to look at more of the file than it returns — a filter matching one line in
        // a hundred would otherwise return almost nothing. The cap keeps the worst case bounded.
        int scanBudget = needle == null ? lineCount : Math.min(lineCount * 200, 200_000);

        Deque<String> collected = new ArrayDeque<>();
        try (RandomAccessFile random = new RandomAccessFile(file, "r")) {
            long pointer = random.length();
            byte[] buffer = new byte[8192];
            ByteAccumulator pending = new ByteAccumulator();
            int scanned = 0;
            long total = 0L;
            // The newline that ends the file's last line ends no line after it: read as one, every
            // unfiltered tail's newest line was empty.
            boolean atFileEnd = true;

            while (pointer > 0 && collected.size() < lineCount && scanned < scanBudget && !tail.cutToSize) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    tail.cancelled = true;
                    return tail;
                }
                int chunkSize = (int) Math.min(buffer.length, pointer);
                pointer -= chunkSize;
                random.seek(pointer);
                random.readFully(buffer, 0, chunkSize);

                for (int i = chunkSize - 1; i >= 0; i--) {
                    byte current = buffer[i];
                    if (current == '\n' && atFileEnd) {
                        atFileEnd = false;
                        continue;
                    }
                    if (current != '\r') {
                        atFileEnd = false;
                    }
                    if (current == '\n') {
                        String line = pending.drainReversed();
                        if (rewrite != null) {
                            line = rewrite.apply(line);
                        }
                        scanned++;
                        if (accept(line, needle)) {
                            line = clip(line, maxLineChars);
                            // Newest first, so what does not fit is the oldest: left out, and said.
                            if (total + line.length() > maxTotalChars && !collected.isEmpty()) {
                                tail.cutToSize = true;
                                break;
                            }
                            total += line.length() + 1;
                            collected.addFirst(line);
                            if (collected.size() >= lineCount) {
                                break;
                            }
                        }
                    }
                    else if (current != '\r' && !pending.push(current)
                        && pending.dropped % MAX_SCANNED_LINE_BYTES == 0) {
                        // A line with no end in sight is charged to the scan as it goes, or a file
                        // with no newline at all was read to its first byte whatever the budget.
                        scanned++;
                    }
                }
            }

            // Whatever is left when we reach the start of the file is the first line, which has no
            // preceding newline to terminate it.
            if (collected.size() < lineCount && pointer == 0 && !tail.cutToSize) {
                String line = pending.drainReversed();
                if (rewrite != null) {
                    line = rewrite.apply(line);
                }
                if (!line.isEmpty() && accept(line, needle)) {
                    line = clip(line, maxLineChars);
                    if (total + line.length() > maxTotalChars && !collected.isEmpty()) {
                        tail.cutToSize = true;
                    }
                    else {
                        collected.addFirst(line);
                    }
                }
            }
        }

        lines.addAll(collected);
        return tail;
    }

    /** {@code line} cut to {@code maxChars}, saying how much more there was. */
    private static String clip(String line, int maxChars) {
        if (line.length() <= maxChars) {
            return line;
        }
        // Not between the halves of a surrogate pair, which would leave half a character.
        int keep = maxChars > 0 && Character.isHighSurrogate(line.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
        return line.substring(0, keep) + "…(+" + (line.length() - keep) + " chars)";
    }

    private static boolean accept(String line, @Nullable String needle) {
        return needle == null || line.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    /** What a log file holds, by its first bytes. */
    public enum LogKind {
        TEXT,
        GZIP,
        /** Not text: a NUL in its first 8 KB, which no log line has. */
        BINARY
    }

    /** A log file that is not text, gzipped or plain: read, it would only be noise. */
    public static final class NotTextException extends IOException {

        private static final long serialVersionUID = 1L;

        public NotTextException(String name) {
            super("'" + name + "' is not a text log (nor a gzipped one), so it cannot be read or filtered as lines.");
        }
    }

    /**
     * How far a gzipped log is decompressed at most. A rolled log of a busy modded server runs to
     * hundreds of megabytes of text; this stops a file that inflates without end, not a real log.
     */
    static final long MAX_GZIP_TEXT_BYTES = 2L * 1024L * 1024L * 1024L;

    public static LogKind kindOf(File file) throws IOException {
        byte[] head = new byte[8192];
        int read;
        try (java.io.InputStream in = Files.newInputStream(file.toPath())) {
            read = in.read(head);
        }
        if (read >= 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) {
            return LogKind.GZIP;
        }
        for (int i = 0; i < read; i++) {
            if (head[i] == 0) {
                return LogKind.BINARY;
            }
        }
        return LogKind.TEXT;
    }

    /**
     * The tail of a gzipped log. Gzip cannot be read backwards, so it is read forwards once, keeping only
     * the last {@code lineCount} matching lines: memory stays bounded by what is returned, whatever the
     * file inflates to. A filter then searches the whole file, not only its end.
     */
    private static void tailGzip(File file, int lineCount, @Nullable String needle,
                                 @Nullable java.util.function.UnaryOperator<String> rewrite, int maxLineChars,
                                 int maxTotalChars, @Nullable java.util.function.BooleanSupplier cancelled,
                                 Tail tail) throws IOException {
        Deque<String> kept = new ArrayDeque<>();
        long total = 0L;
        long read = 0L;
        // The limit is enforced where the bytes are read, not after each line: a file that inflates
        // without end and without a newline never finishes its first line.
        CountingStream counted = new CountingStream(new java.util.zip.GZIPInputStream(
            Files.newInputStream(file.toPath()), 65536), MAX_GZIP_TEXT_BYTES, "'" + file.getName()
            + "' inflates to more than " + MAX_GZIP_TEXT_BYTES / (1024L * 1024L)
            + " MB of text; it is not read past that.");
        try (BufferedReader source = new BufferedReader(new InputStreamReader(counted, StandardCharsets.UTF_8))) {
            LineReader reader = new LineReader(source);
            String line;
            // Not BufferedReader.readLine, which holds a whole line however long it is.
            while ((line = reader.next(MAX_SCANNED_LINE_BYTES)) != null) {
                if ((++read & 0xFFF) == 0 && cancelled != null && cancelled.getAsBoolean()) {
                    tail.cancelled = true;
                    return;
                }
                if (line.indexOf('\u0000') >= 0) {
                    throw new NotTextException(file.getName());
                }
                if (rewrite != null) {
                    line = rewrite.apply(line);
                }
                if (accept(line, needle)) {
                    line = clip(line, maxLineChars);
                    kept.addLast(line);
                    total += line.length() + 1;
                    if (kept.size() > lineCount) {
                        total -= kept.removeFirst().length() + 1;
                    }
                    // Oldest out first, as from the end of a plain log; the newest line always stays.
                    while (total > maxTotalChars && kept.size() > 1) {
                        total -= kept.removeFirst().length() + 1;
                        tail.cutToSize = true;
                    }
                }
            }
        }
        catch (java.util.zip.ZipException corrupt) {
            throw new IOException("'" + file.getName() + "' starts as gzip but could not be decompressed: "
                + corrupt.getMessage() + ".", corrupt);
        }
        catch (java.io.EOFException truncated) {
            throw new IOException("'" + file.getName() + "' is a gzipped log cut off before its end (still "
                + "being written, or damaged).", truncated);
        }
        tail.lines.addAll(kept);
    }

    /**
     * Lines from a reader, each held only up to a length: the rest of a longer line is read past and
     * counted, not kept. Splits on {@code \n} and drops a {@code \r} before it, as the backwards scan
     * of a plain log does.
     */
    private static final class LineReader {

        private final java.io.Reader in;
        private final char[] buffer = new char[8192];
        private int position;
        private int end;
        private boolean finished;

        LineReader(java.io.Reader in) {
            this.in = in;
        }

        /** The next line without its line break, cut to {@code maxChars} and saying so; null at the end. */
        @Nullable
        String next(int maxChars) throws IOException {
            StringBuilder line = new StringBuilder();
            long cut = 0L;
            boolean any = false;
            while (true) {
                if (position >= end) {
                    if (finished) {
                        break;
                    }
                    int read = in.read(buffer, 0, buffer.length);
                    if (read < 0) {
                        finished = true;
                        break;
                    }
                    position = 0;
                    end = read;
                    continue;
                }
                any = true;
                int start = position;
                while (position < end && buffer[position] != '\n') {
                    position++;
                }
                int length = position - start;
                int room = maxChars - line.length();
                if (length > room) {
                    cut += length - room;
                    length = room;
                }
                line.append(buffer, start, length);
                if (position < end) {
                    position++;
                    return finish(line, cut);
                }
            }
            return any ? finish(line, cut) : null;
        }

        private static String finish(StringBuilder line, long cut) {
            if (cut > 0) {
                return line.append("…(+").append(cut).append(" chars left out)").toString();
            }
            int length = line.length();
            if (length > 0 && line.charAt(length - 1) == '\r') {
                line.setLength(length - 1);
            }
            return line.toString();
        }
    }

    /** Counts the bytes read through it, and stops at a limit: how far a gzipped log may inflate. */
    private static final class CountingStream extends java.io.FilterInputStream {

        long count;
        private final long limit;
        private final String overLimit;

        CountingStream(java.io.InputStream in, long limit, String overLimit) {
            super(in);
            this.limit = limit;
            this.overLimit = overLimit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++count > limit) {
                throw new IOException(overLimit);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
                if (count > limit) {
                    throw new IOException(overLimit);
                }
            }
            return read;
        }
    }

    /** One file in the logs directory, for a caller choosing which rolled log covers a time. */
    public static final class LogFile {

        public final String name;
        public final long bytes;
        public final long modifiedMillis;
        public final boolean gzip;

        LogFile(String name, long bytes, long modifiedMillis, boolean gzip) {
            this.name = name;
            this.bytes = bytes;
            this.modifiedMillis = modifiedMillis;
            this.gzip = gzip;
        }
    }

    /** The files in {@code directory}, newest first, at most {@code limit}. Subdirectories are left out. */
    public static List<LogFile> listLogs(File directory, int limit) {
        List<LogFile> files = new ArrayList<>();
        File[] found = directory.listFiles(File::isFile);
        if (found == null) {
            return files;
        }
        java.util.Arrays.sort(found, (a, b) -> {
            int byTime = Long.compare(b.lastModified(), a.lastModified());
            return byTime != 0 ? byTime : a.getName().compareTo(b.getName());
        });
        for (File file : found) {
            if (files.size() >= limit) {
                break;
            }
            files.add(new LogFile(file.getName(), file.length(), file.lastModified(),
                file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".gz")));
        }
        return files;
    }

    /**
     * Reads a whole text file, capped at {@code maxBytes}.
     *
     * <p>The cap is not optional: these paths are reachable from an MCP tool, and an uncapped read
     * of an attacker-chosen path is how a request turns into an OutOfMemoryError in the game.
     */
    public static String readCapped(File file, int maxBytes) throws IOException {
        if (!file.isFile()) {
            return "";
        }
        if (file.length() > maxBytes) {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))) {
                char[] buffer = new char[maxBytes];
                int read = reader.read(buffer);
                return read <= 0 ? "" : new String(buffer, 0, read);
            }
        }
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * A growable byte buffer filled back-to-front, for the reverse line scan.
     *
     * <p>A {@code StringBuilder} of chars would decode each byte in isolation and mangle every
     * non-ASCII character in the log — mod names, player names, and the box-drawing characters
     * several loaders use in their banners.
     */
    private static final class ByteAccumulator {

        private byte[] data = new byte[256];
        private int size;
        /** Bytes of the line being read that were not kept, past {@link #MAX_SCANNED_LINE_BYTES}. */
        long dropped;

        /** @return false when the line is already as long as is kept, and the byte was dropped */
        boolean push(byte value) {
            if (size >= MAX_SCANNED_LINE_BYTES) {
                dropped++;
                return false;
            }
            if (size == data.length) {
                byte[] grown = new byte[Math.min(data.length * 2, MAX_SCANNED_LINE_BYTES)];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
            data[size++] = value;
            return true;
        }

        /**
         * Returns the accumulated bytes in forward order, decoded as UTF-8, and resets. Filled from
         * the line's end, so what a too-long line lost is its start, and the text says so there.
         */
        String drainReversed() {
            byte[] forward = new byte[size];
            for (int i = 0; i < size; i++) {
                forward[i] = data[size - 1 - i];
            }
            size = 0;
            String text = new String(forward, StandardCharsets.UTF_8);
            if (dropped > 0) {
                text = "…(" + dropped + " bytes before this left out)" + text;
                dropped = 0L;
                // One long line should not leave a megabyte held for the rest of the scan.
                data = new byte[256];
            }
            return text;
        }
    }
}
