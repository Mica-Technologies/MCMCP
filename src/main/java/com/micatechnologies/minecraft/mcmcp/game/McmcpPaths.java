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
        List<String> lines = new ArrayList<>();
        if (!file.isFile()) {
            return lines;
        }

        String needle = filter == null || filter.isEmpty()
            ? null
            : filter.toLowerCase(java.util.Locale.ROOT);

        // Filtering has to look at more of the file than it returns — a filter matching one line in
        // a hundred would otherwise return almost nothing. The cap keeps the worst case bounded.
        int scanBudget = needle == null ? lineCount : Math.min(lineCount * 200, 200_000);

        Deque<String> collected = new ArrayDeque<>();
        try (RandomAccessFile random = new RandomAccessFile(file, "r")) {
            long pointer = random.length();
            byte[] buffer = new byte[8192];
            ByteAccumulator pending = new ByteAccumulator();
            int scanned = 0;

            while (pointer > 0 && collected.size() < lineCount && scanned < scanBudget) {
                int chunkSize = (int) Math.min(buffer.length, pointer);
                pointer -= chunkSize;
                random.seek(pointer);
                random.readFully(buffer, 0, chunkSize);

                for (int i = chunkSize - 1; i >= 0; i--) {
                    byte current = buffer[i];
                    if (current == '\n') {
                        String line = pending.drainReversed();
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
