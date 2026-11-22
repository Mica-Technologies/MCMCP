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
        List<String> lines = new ArrayList<>();
        if (!file.isFile()) {
            return lines;
        }

        String needle = filter == null || filter.isEmpty()
            ? null
            : filter.toLowerCase(java.util.Locale.ROOT);

        // A rolled log (logs/2026-11-19-1.log.gz) is gzip, and read as bytes it is noise a filter never
        // matches: "0 lines matching" for text that was there (#213). Told apart by its magic bytes, not
        // its name, and a file that is neither is refused rather than reported empty.
        switch (kindOf(file)) {
            case GZIP:
                return tailGzip(file, lineCount, needle, rewrite);
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
            // The newline that ends the file's last line ends no line after it: read as one, every
            // unfiltered tail's newest line was empty.
            boolean atFileEnd = true;

            while (pointer > 0 && collected.size() < lineCount && scanned < scanBudget) {
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
                            collected.addFirst(line);
                            if (collected.size() >= lineCount) {
                                break;
                            }
                        }
                    }
                    else if (current != '\r') {
                        pending.push(current);
                    }
                }
            }

            // Whatever is left when we reach the start of the file is the first line, which has no
            // preceding newline to terminate it.
            if (collected.size() < lineCount && pointer == 0) {
                String line = pending.drainReversed();
                if (rewrite != null) {
                    line = rewrite.apply(line);
                }
                if (!line.isEmpty() && accept(line, needle)) {
                    collected.addFirst(line);
                }
            }
        }

        lines.addAll(collected);
        return lines;
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
    private static List<String> tailGzip(File file, int lineCount, @Nullable String needle,
                                         @Nullable java.util.function.UnaryOperator<String> rewrite) throws IOException {
        Deque<String> kept = new ArrayDeque<>();
        CountingStream counted = new CountingStream(new java.util.zip.GZIPInputStream(
            Files.newInputStream(file.toPath()), 65536));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(counted, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (counted.count > MAX_GZIP_TEXT_BYTES) {
                    throw new IOException("'" + file.getName() + "' inflates to more than "
                        + MAX_GZIP_TEXT_BYTES / (1024L * 1024L) + " MB of text; it is not read past that.");
                }
                if (line.indexOf('\u0000') >= 0) {
                    throw new NotTextException(file.getName());
                }
                if (rewrite != null) {
                    line = rewrite.apply(line);
                }
                if (accept(line, needle)) {
                    kept.addLast(line);
                    if (kept.size() > lineCount) {
                        kept.removeFirst();
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
        return new ArrayList<>(kept);
    }

    /** Counts the bytes read through it: how far a gzipped log has inflated. */
    private static final class CountingStream extends java.io.FilterInputStream {

        long count;

        CountingStream(java.io.InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
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

        void push(byte value) {
            if (size == data.length) {
                byte[] grown = new byte[data.length * 2];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
            data[size++] = value;
        }

        /** Returns the accumulated bytes in forward order, decoded as UTF-8, and resets. */
        String drainReversed() {
            byte[] forward = new byte[size];
            for (int i = 0; i < size; i++) {
                forward[i] = data[size - 1 - i];
            }
            size = 0;
            return new String(forward, StandardCharsets.UTF_8);
        }
    }
}
