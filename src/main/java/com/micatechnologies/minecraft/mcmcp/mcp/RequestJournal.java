package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;
import javax.annotation.Nullable;

/**
 * A file that records each tool call as it starts and as it ends, one compact JSON line each,
 * flushed as it is written.
 *
 * <h2>Why</h2>
 *
 * A game that dies without a crash report or an error log leaves nothing that says what MCMCP was
 * doing at the time, so neither the agent nor anyone else can rule MCMCP in or out (#52). A start
 * line with no end line is that answer, and it survives an abrupt exit because every line is flushed
 * as it is written. On a server the journal is also the audit trail: each line can carry the
 * principal the call ran as.
 *
 * <h2>What it never does</h2>
 *
 * It never throws into the dispatcher and never blocks a call for long: every write is caught, and a
 * journal that keeps failing turns itself off with one warning. It records a summary of the
 * arguments, never the arguments themselves — a block list or an NBT blob would make the journal the
 * largest file MCMCP writes. And it deletes nothing by age: it rotates by size, keeping a fixed
 * number of files, the same kind of bound as the undo point count.
 *
 * <p>No Minecraft or Forge class is named here. The directory and the settings are handed in, so
 * this is tested as plain files.
 */
public final class RequestJournal {

    /** The settings a journal reads, live, on every write. */
    public interface Settings {

        boolean enabled();

        /** Size at which the current file is rotated. */
        long maxFileBytes();

        /** How many files are kept, the current one included. */
        int files();
    }

    /** Consecutive write failures after which the journal stops trying. */
    private static final int MAX_FAILURES = 3;

    /** Ceiling on one start line's argument summary. */
    static final int MAX_SUMMARY_CHARS = 512;

    /** Longest string argument value kept in a summary. */
    static final int MAX_STRING_CHARS = 80;

    /** At most this many result counters are copied into an end line. */
    static final int MAX_COUNTERS = 8;

    private final File file;
    private final String side;
    private final Settings settings;
    private final Object lock = new Object();

    /** What the file said about the previous run, read before this run wrote anything. */
    private final JsonObject previousRun;

    @Nullable
    private Writer writer;
    private long size;
    private int failures;
    private boolean broken;
    private boolean opened;
    private long pid = -1L;
    @Nullable
    private String modVersion;

    @Nullable
    private Runnable onFailure;

    /**
     * @param directory where the files live; created on the first write
     * @param side      names the file, so the client and server endpoints of one game never share one
     */
    public RequestJournal(File directory, String side, Settings settings) {
        this.file = new File(directory, side + "-requests.log");
        this.side = side;
        this.settings = settings;
        this.previousRun = readPreviousRun(file, settings.enabled());
    }

    public File getFile() {
        return file;
    }

    /** Called once if the journal gives up after repeated write failures, with the reason logged by the caller. */
    public void setOnFailure(@Nullable Runnable onFailure) {
        this.onFailure = onFailure;
    }

    // ------------------------------------------------------------------
    // Run markers
    // ------------------------------------------------------------------

    /**
     * Names this run: the process and version its open line records.
     *
     * <p>The open line itself is written now if the journal is on, or with the first call recorded
     * after it is turned on, so a journal enabled by {@code /mcmcp reload} still marks where its run
     * began.
     */
    public void open(long pid, String modVersion, long nowMillis) {
        synchronized (lock) {
            this.pid = pid;
            this.modVersion = modVersion;
        }
        if (settings.enabled()) {
            ensureOpen(nowMillis);
        }
    }

    private void ensureOpen(long nowMillis) {
        long runPid;
        String runVersion;
        synchronized (lock) {
            if (opened || modVersion == null) {
                return;
            }
            opened = true;
            runPid = pid;
            runVersion = modVersion;
        }
        JsonObject line = new JsonObject();
        line.addProperty("t", timestamp(nowMillis));
        line.addProperty("ev", "open");
        line.addProperty("side", side);
        line.addProperty("pid", runPid);
        line.addProperty("version", runVersion);
        write(line);
    }

    /** Marks a clean end of the run. A run with no close line ended some other way. */
    public void close(long nowMillis) {
        synchronized (lock) {
            if (!opened) {
                return;
            }
            opened = false;
        }
        JsonObject line = new JsonObject();
        line.addProperty("t", timestamp(nowMillis));
        line.addProperty("ev", "close");
        write(line);
        synchronized (lock) {
            closeWriter();
        }
    }

    // ------------------------------------------------------------------
    // Calls
    // ------------------------------------------------------------------

    /**
     * Records a call starting.
     *
     * @param principal who the call ran as, when that is known (a companion caller); null otherwise
     */
    public void start(long id, String tool, JsonObject arguments, @Nullable String client,
                      @Nullable JsonObject principal, long nowMillis) {
        if (!settings.enabled()) {
            return;
        }
        ensureOpen(nowMillis);
        JsonObject line = new JsonObject();
        line.addProperty("t", timestamp(nowMillis));
        line.addProperty("ev", "start");
        line.addProperty("id", id);
        line.addProperty("tool", tool);
        if (client != null) {
            line.addProperty("client", client);
        }
        if (principal != null) {
            line.add("principal", principal);
        }
        line.add("args", summarizeArguments(arguments));
        write(line);
    }

    /**
     * Records a call ending.
     *
     * @param status        {@code ok}, {@code error}, {@code cancelled}, {@code timeout} or {@code exception}
     * @param responseBytes size of the serialised result, or a negative number when unknown
     * @param result        the result's structured content, for a few counters; may be null
     * @param notes         timings the tool noted while it ran (see {@link ToolContext#noteTiming}); may be null
     */
    public void end(long id, String status, long durationMillis, long responseBytes,
                    @Nullable JsonObject result, @Nullable JsonObject notes, long nowMillis) {
        if (!settings.enabled()) {
            return;
        }
        JsonObject line = new JsonObject();
        line.addProperty("t", timestamp(nowMillis));
        line.addProperty("ev", "end");
        line.addProperty("id", id);
        line.addProperty("status", status);
        line.addProperty("ms", durationMillis);
        if (responseBytes >= 0) {
            line.addProperty("bytes", responseBytes);
        }
        JsonObject counters = counters(result);
        if (counters.size() > 0) {
            line.add("counts", counters);
        }
        if (notes != null && notes.size() > 0) {
            line.add("steps", notes);
        }
        write(line);
    }

    public boolean isEnabled() {
        return settings.enabled() && !isBroken();
    }

    public boolean isBroken() {
        synchronized (lock) {
            return broken;
        }
    }

    // ------------------------------------------------------------------
    // The previous run
    // ------------------------------------------------------------------

    /**
     * What the journal said about the run before this one: whether it ended cleanly, and which calls
     * started and never finished.
     *
     * <p>Always says whether the journal is on. An empty list from a journal that is off would read
     * as "nothing was running", which is exactly the false all-clear #52 exists to prevent.
     */
    public JsonObject previousRun() {
        JsonObject copy = Json.parse(Json.write(previousRun)).getAsJsonObject();
        copy.addProperty("journal", settings.enabled() ? (isBroken() ? "failed" : "enabled") : "disabled");
        copy.addProperty("file", file.getAbsolutePath());
        return copy;
    }

    private static JsonObject readPreviousRun(File file, boolean enabled) {
        if (!file.isFile()) {
            JsonObject json = new JsonObject();
            json.addProperty("found", false);
            if (!enabled) {
                json.addProperty("note", "The request journal is off, so nothing was recorded. Turn on "
                    + "journal.enabled in the MCMCP config to record each tool call.");
            }
            return json;
        }
        try {
            return scan(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        }
        catch (IOException | RuntimeException e) {
            JsonObject json = new JsonObject();
            json.addProperty("found", false);
            json.addProperty("error", "Could not read the journal: " + e.getMessage());
            return json;
        }
    }

    /**
     * Reads one file's lines back into a summary of its last run.
     *
     * <p>Tolerates anything a crash can leave: a half-written last line, a line from an older format.
     * Such lines are skipped, and counted.
     */
    static JsonObject scan(List<String> lines) {
        int lastOpen = -1;
        List<JsonObject> parsed = new ArrayList<>(lines.size());
        int unreadable = 0;
        for (String raw : lines) {
            JsonObject line = parseLine(raw);
            if (line == null) {
                if (!raw.trim().isEmpty()) {
                    unreadable++;
                }
                continue;
            }
            parsed.add(line);
            if ("open".equals(Json.getString(line, "ev"))) {
                lastOpen = parsed.size() - 1;
            }
        }

        JsonObject json = new JsonObject();
        if (lastOpen < 0) {
            json.addProperty("found", false);
            return json;
        }

        JsonObject open = parsed.get(lastOpen);
        Map<Long, JsonObject> running = new LinkedHashMap<>();
        boolean closed = false;
        String lastSeen = Json.getString(open, "t");
        int calls = 0;
        for (int i = lastOpen + 1; i < parsed.size(); i++) {
            JsonObject line = parsed.get(i);
            String event = Json.getString(line, "ev", "");
            String at = Json.getString(line, "t");
            if (at != null) {
                lastSeen = at;
            }
            if ("start".equals(event)) {
                running.put(Json.getLong(line, "id", -1L), line);
                calls++;
            }
            else if ("end".equals(event)) {
                running.remove(Json.getLong(line, "id", -1L));
            }
            else if ("close".equals(event)) {
                closed = true;
            }
        }

        json.addProperty("found", true);
        json.addProperty("startedAt", Json.getString(open, "t"));
        json.addProperty("pid", Json.getLong(open, "pid", -1L));
        json.addProperty("version", Json.getString(open, "version"));
        json.addProperty("lastRecordAt", lastSeen);
        json.addProperty("endedCleanly", closed);
        json.addProperty("calls", calls);
        JsonArray unfinished = new JsonArray();
        for (JsonObject start : running.values()) {
            JsonObject call = new JsonObject();
            call.addProperty("tool", Json.getString(start, "tool"));
            call.addProperty("startedAt", Json.getString(start, "t"));
            JsonElement args = start.get("args");
            if (args != null) {
                call.add("args", args);
            }
            JsonElement principal = start.get("principal");
            if (principal != null) {
                call.add("principal", principal);
            }
            unfinished.add(call);
        }
        json.add("unfinishedCalls", unfinished);
        if (unreadable > 0) {
            json.addProperty("unreadableLines", unreadable);
        }
        return json;
    }

    @Nullable
    private static JsonObject parseLine(String raw) {
        String text = raw.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return null;
        }
        try {
            JsonElement element = Json.parse(text);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Summaries
    // ------------------------------------------------------------------

    /**
     * A small description of a call's arguments: scalars as they are (long strings cut), arrays as
     * their length, nested objects one level deep, and a box's volume when it has corners.
     *
     * <p>Falls back to the argument names alone when even that would run past
     * {@link #MAX_SUMMARY_CHARS}: the journal must stay the small file.
     */
    public static JsonObject summarizeArguments(@Nullable JsonObject arguments) {
        JsonObject summary = new JsonObject();
        if (arguments == null) {
            return summary;
        }
        for (Entry<String, JsonElement> entry : arguments.entrySet()) {
            summary.add(entry.getKey(), summarizeValue(entry.getValue(), 1));
        }
        Long volume = boxVolume(arguments);
        if (volume != null) {
            summary.addProperty("~volume", volume);
        }
        if (Json.write(summary).length() <= MAX_SUMMARY_CHARS) {
            return summary;
        }
        List<String> names = new ArrayList<>();
        for (Entry<String, JsonElement> entry : arguments.entrySet()) {
            names.add(entry.getKey());
        }
        Collections.sort(names);
        JsonObject reduced = new JsonObject();
        reduced.add("~keys", Json.arrayOfStrings(names));
        if (volume != null) {
            reduced.addProperty("~volume", volume);
        }
        return reduced;
    }

    private static JsonElement summarizeValue(JsonElement value, int depth) {
        if (value == null || value.isJsonNull()) {
            return value == null ? com.google.gson.JsonNull.INSTANCE : value;
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString() && primitive.getAsString().length() > MAX_STRING_CHARS) {
                String text = primitive.getAsString();
                return new JsonPrimitive(text.substring(0, MAX_STRING_CHARS) + "…(" + text.length() + ")");
            }
            return primitive;
        }
        if (value.isJsonArray()) {
            JsonObject array = new JsonObject();
            array.addProperty("~len", value.getAsJsonArray().size());
            return array;
        }
        if (depth >= 2) {
            JsonObject object = new JsonObject();
            object.addProperty("~fields", value.getAsJsonObject().size());
            return object;
        }
        JsonObject nested = new JsonObject();
        for (Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            nested.add(entry.getKey(), summarizeValue(entry.getValue(), depth + 1));
        }
        return nested;
    }

    /** The volume of a box given as x/y/z and toX/toY/toZ, or null when the arguments are not one. */
    @Nullable
    static Long boxVolume(JsonObject arguments) {
        String[] from = {"x", "y", "z"};
        String[] to = {"toX", "toY", "toZ"};
        long volume = 1L;
        for (int i = 0; i < 3; i++) {
            JsonElement a = arguments.get(from[i]);
            JsonElement b = arguments.get(to[i]);
            if (!isNumber(a) || !isNumber(b)) {
                return null;
            }
            volume *= Math.abs(b.getAsLong() - a.getAsLong()) + 1L;
        }
        return volume;
    }

    private static boolean isNumber(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    /** The first few top-level numbers and booleans of a result: written, attempted, unloaded, … */
    static JsonObject counters(@Nullable JsonObject result) {
        JsonObject counters = new JsonObject();
        if (result == null) {
            return counters;
        }
        for (Entry<String, JsonElement> entry : result.entrySet()) {
            if (counters.size() >= MAX_COUNTERS) {
                break;
            }
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && !value.getAsJsonPrimitive().isString()) {
                counters.add(entry.getKey(), value);
            }
            else if (value.isJsonArray()) {
                counters.addProperty(entry.getKey() + ".len", value.getAsJsonArray().size());
            }
        }
        return counters;
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    private void write(JsonObject line) {
        String text = Json.write(line) + "\n";
        Runnable failed = null;
        synchronized (lock) {
            if (broken) {
                return;
            }
            try {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                if (writer != null && size + bytes.length > settings.maxFileBytes()) {
                    rotate();
                }
                if (writer == null) {
                    openWriter();
                }
                writer.write(text);
                writer.flush();
                size += bytes.length;
                failures = 0;
            }
            catch (IOException | RuntimeException e) {
                closeWriter();
                failures++;
                if (failures >= MAX_FAILURES) {
                    broken = true;
                    failed = onFailure;
                }
            }
        }
        if (failed != null) {
            failed.run();
        }
    }

    private void openWriter() throws IOException {
        File directory = file.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        size = file.isFile() ? file.length() : 0L;
        writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            }
            catch (IOException ignored) {
                // Nothing to do: the next write opens a fresh one.
            }
            writer = null;
        }
    }

    /** Current file becomes {@code .1}, {@code .1} becomes {@code .2}, and the last one goes. */
    private void rotate() throws IOException {
        closeWriter();
        int keep = Math.max(1, settings.files());
        File oldest = rotated(keep - 1);
        if (keep == 1) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        Files.deleteIfExists(oldest.toPath());
        for (int i = keep - 2; i >= 1; i--) {
            File from = rotated(i);
            if (from.isFile() && !from.renameTo(rotated(i + 1))) {
                throw new IOException("Could not rotate " + from);
            }
        }
        if (file.isFile() && !file.renameTo(rotated(1))) {
            throw new IOException("Could not rotate " + file);
        }
    }

    File rotated(int index) {
        return new File(file.getParentFile(), file.getName() + "." + index);
    }

    /** Every file the journal currently keeps, current first. */
    public List<File> files() {
        List<File> files = new ArrayList<>();
        if (file.isFile()) {
            files.add(file);
        }
        for (int i = 1; i < Math.max(1, settings.files()) + 4; i++) {
            File older = rotated(i);
            if (older.isFile()) {
                files.add(older);
            }
        }
        return files;
    }

    private static String timestamp(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }
}
