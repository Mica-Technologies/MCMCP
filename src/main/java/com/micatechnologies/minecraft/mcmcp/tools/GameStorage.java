package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * What MCMCP keeps in a game folder, how much room it takes, and removing the old.
 *
 * <p>Four kinds, all written by MCMCP and nothing else: performance and registry dumps under
 * {@code mcmcp/dumps}, the screenshots and maps MCMCP named itself ({@code screenshots/mcmcp-*.png} —
 * a screenshot an agent gave its own name cannot be told from a person's, so it is left alone), undo
 * points in each world's {@code mcmcp-undo} folder, and marks.
 *
 * <p>No Minecraft class here: it is files, and it is tested as files.
 */
public final class GameStorage {

    public static final String DUMPS = "dumps";
    public static final String SCREENSHOTS = "screenshots";
    public static final String UNDO = "undo";
    public static final String MARKS = "marks";
    public static final String JOURNAL = "journal";

    public static final String[] KINDS = {DUMPS, SCREENSHOTS, UNDO, MARKS};

    private static final long DAY_MILLIS = 86_400_000L;

    private GameStorage() {
    }

    /** One kind's footprint. */
    public static final class Usage {

        long bytes;
        int items;
        long oldestMillis = Long.MAX_VALUE;

        void add(long size, long at) {
            bytes += size;
            items++;
            oldestMillis = Math.min(oldestMillis, at);
        }

        JsonObject toJson(String kind, String label) {
            JsonObject json = new JsonObject();
            json.addProperty("kind", kind);
            json.addProperty("label", label);
            json.addProperty("bytes", bytes);
            json.addProperty("items", items);
            if (items > 0) {
                json.addProperty("oldestMs", oldestMillis);
            }
            return json;
        }
    }

    /** Every kind's footprint in {@code game}, as JSON rows. */
    public static JsonArray usage(File game) throws IOException {
        JsonArray rows = new JsonArray();
        rows.add(dumps(game).toJson(DUMPS, "Dumps (registries, heap, CPU samples)"));
        rows.add(screenshots(game).toJson(SCREENSHOTS, "Screenshots and maps MCMCP named"));
        rows.add(undo(game).toJson(UNDO, "Undo points"));
        rows.add(marks(game).toJson(MARKS, "Marks"));
        // Reported, not expirable: the journal rotates itself by size and keeps a fixed number of
        // files, like the orchestrator's own logs, and it is the record a crash is read from.
        JsonObject journal = journal(game).toJson(JOURNAL, "Request journal");
        journal.addProperty("expirable", false);
        journal.addProperty("note", "Rotates by size; journal.files sets how many files are kept");
        rows.add(journal);
        return rows;
    }

    /**
     * Removes what is older than {@code days} of one kind. Returns {removed, bytes}.
     *
     * @param now the current time, passed in so a test can fix it
     */
    public static JsonObject expire(File game, String kind, int days, long now) throws IOException {
        long cutoff = now - days * DAY_MILLIS;
        long[] removed = new long[2];
        switch (kind) {
            case DUMPS:
                for (File file : files(new File(game, "mcmcp/dumps"))) {
                    deleteIfOlder(file, file.lastModified(), cutoff, removed);
                }
                break;
            case SCREENSHOTS:
                for (File file : mcmcpScreenshots(game)) {
                    deleteIfOlder(file, file.lastModified(), cutoff, removed);
                }
                break;
            case UNDO:
                for (File folder : undoFolders(game)) {
                    for (File file : files(folder)) {
                        long at = undoTime(file);
                        if (at >= 0) {
                            deleteIfOlder(file, at, cutoff, removed);
                        }
                    }
                }
                break;
            case MARKS:
                for (File file : markFiles(game)) {
                    expireMarks(file, cutoff, removed);
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown kind '" + kind + "'; use dumps, screenshots, "
                    + "undo or marks.");
        }
        JsonObject json = new JsonObject();
        json.addProperty("kind", kind);
        json.addProperty("olderThanDays", days);
        json.addProperty("removed", removed[0]);
        json.addProperty("bytes", removed[1]);
        return json;
    }

    // ------------------------------------------------------------------
    // Finding things
    // ------------------------------------------------------------------

    private static Usage dumps(File game) {
        Usage usage = new Usage();
        for (File file : files(new File(game, "mcmcp/dumps"))) {
            usage.add(file.length(), file.lastModified());
        }
        return usage;
    }

    private static Usage journal(File game) {
        Usage usage = new Usage();
        for (File file : files(new File(game, "mcmcp/journal"))) {
            usage.add(file.length(), file.lastModified());
        }
        return usage;
    }

    private static Usage screenshots(File game) {
        Usage usage = new Usage();
        for (File file : mcmcpScreenshots(game)) {
            usage.add(file.length(), file.lastModified());
        }
        return usage;
    }

    /** Undo points counted per point, sized with every file that belongs to them. */
    private static Usage undo(File game) {
        Usage usage = new Usage();
        for (File folder : undoFolders(game)) {
            for (File file : files(folder)) {
                long at = undoTime(file);
                if (at < 0) {
                    continue;
                }
                if (file.getName().endsWith(".json")) {
                    usage.add(0, at);
                }
                usage.bytes += file.length();
            }
        }
        return usage;
    }

    private static Usage marks(File game) throws IOException {
        Usage usage = new Usage();
        for (File file : markFiles(game)) {
            usage.bytes += file.length();
            for (JsonObject mark : readMarks(file)) {
                usage.items++;
                usage.oldestMillis = Math.min(usage.oldestMillis, Json.getLong(mark, "at", 0));
            }
        }
        return usage;
    }

    private static List<File> mcmcpScreenshots(File game) {
        List<File> found = new ArrayList<>();
        for (File file : files(new File(game, "screenshots"))) {
            String name = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.startsWith("mcmcp-") && name.endsWith(".png")) {
                found.add(file);
            }
        }
        return found;
    }

    /** {@code <game>/saves/<world>/mcmcp-undo} and, for a dedicated server, {@code <game>/<world>/mcmcp-undo}. */
    static List<File> undoFolders(File game) {
        List<File> folders = new ArrayList<>();
        for (File parent : new File[] {game, new File(game, "saves")}) {
            for (File world : directories(parent)) {
                File undo = new File(world, UndoPoints.FOLDER);
                if (undo.isDirectory()) {
                    folders.add(undo);
                }
            }
        }
        return folders;
    }

    static List<File> markFiles(File game) {
        List<File> found = new ArrayList<>();
        for (File world : directories(new File(game, "saves"))) {
            File marks = new File(world, "mcmcp-marks.json");
            if (marks.isFile()) {
                found.add(marks);
            }
        }
        for (File file : files(new File(game, "mcmcp-marks"))) {
            if (file.getName().endsWith(".json")) {
                found.add(file);
            }
        }
        return found;
    }

    /** The millisecond an undo point file was made, from its name ({@code u<ms>...}), or -1. */
    static long undoTime(File file) {
        String name = file.getName();
        if (!name.startsWith("u")) {
            return -1;
        }
        int end = 1;
        while (end < name.length() && Character.isDigit(name.charAt(end))) {
            end++;
        }
        try {
            return end > 1 ? Long.parseLong(name.substring(1, end)) : -1;
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    private static List<JsonObject> readMarks(File file) throws IOException {
        List<JsonObject> marks = new ArrayList<>();
        JsonElement parsed = Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        if (parsed != null && parsed.isJsonArray()) {
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    marks.add(element.getAsJsonObject());
                }
            }
        }
        return marks;
    }

    private static void expireMarks(File file, long cutoff, long[] removed) throws IOException {
        List<JsonObject> marks = readMarks(file);
        JsonArray kept = new JsonArray();
        for (JsonObject mark : marks) {
            if (Json.getLong(mark, "at", 0) < cutoff) {
                removed[0]++;
            }
            else {
                kept.add(mark);
            }
        }
        if (kept.size() == marks.size()) {
            return;
        }
        long before = file.length();
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.write(temporary.toPath(), Json.writePretty(kept).getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        removed[1] += Math.max(0, before - file.length());
    }

    private static void deleteIfOlder(File file, long at, long cutoff, long[] removed) {
        if (at >= cutoff) {
            return;
        }
        long size = file.length();
        if (file.delete()) {
            removed[0]++;
            removed[1] += size;
        }
    }

    private static List<File> files(File folder) {
        List<File> found = new ArrayList<>();
        File[] entries = folder.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                if (entry.isFile()) {
                    found.add(entry);
                }
            }
        }
        return found;
    }

    private static List<File> directories(File folder) {
        List<File> found = new ArrayList<>();
        File[] entries = folder.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                if (entry.isDirectory()) {
                    found.add(entry);
                }
            }
        }
        return found;
    }
}
