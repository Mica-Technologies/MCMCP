package com.micatechnologies.minecraft.mcmcp.regions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * The named build targets of one world, kept in its {@code mcmcp-targets} folder.
 *
 * <p>Names are layered with {@code /}: {@code alto1873/structure}, {@code alto1873/fitout-L13},
 * {@code alto1873/life}. A pattern ending {@code /*} means every layer under it, merged in the order
 * the layers were <em>created</em>, so a later phase wins over an earlier one cell by cell even when the
 * earlier one is saved again (the structure re-run after the fit-out must not cover the fit-out). That
 * is how the builder that asked for this layers its work (2026-10-26).
 *
 * <p>Saving under an existing name merges by default, the new cells winning; {@code replace} starts
 * the layer over. An index beside the files records when each was created and last saved.
 *
 * <p>No Minecraft class: it is handed the folder. Synchronized, since calls come from worker threads.
 */
public final class TargetStore {

    public static final String FOLDER = "mcmcp-targets";
    private static final String INDEX = "index.json";
    private static final String SUFFIX = ".target.gz";
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}(/[a-z0-9][a-z0-9_.-]{0,63}){0,7}");

    private final File folder;

    public TargetStore(File folder) {
        this.folder = folder;
    }

    /** What the index knows about a layer. */
    public static final class Entry {
        public final String name;
        public final int dimension;
        public final long created;
        public final long updated;
        public final long cells;
        public final long bytes;

        Entry(String name, int dimension, long created, long updated, long cells, long bytes) {
            this.name = name;
            this.dimension = dimension;
            this.created = created;
            this.updated = updated;
            this.cells = cells;
            this.bytes = bytes;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("name", name);
            json.addProperty("dimension", dimension);
            json.addProperty("cells", cells);
            json.addProperty("kb", Math.max(1L, bytes / 1024L));
            json.addProperty("created", created);
            json.addProperty("updated", updated);
            return json;
        }
    }

    /** A message for the caller when a name cannot be used, or null when it can. */
    @Nullable
    public static String checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return "A target name is lower-case letters, digits, '_', '-' and '.', in up to 8 parts separated "
                + "by '/' (building/phase), e.g. 'alto1873/fitout-l13'.";
        }
        return null;
    }

    /** Whether a pattern names layers: {@code a/*} (every layer under a) or {@code *} (all). */
    public static boolean isPattern(String pattern) {
        return pattern.equals("*") || pattern.endsWith("/*");
    }

    public static boolean matches(String pattern, String name) {
        if (pattern.equals("*")) {
            return true;
        }
        if (pattern.endsWith("/*")) {
            return name.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(name);
    }

    /** Every layer, in creation order. */
    public synchronized List<Entry> list() {
        List<Entry> entries = new ArrayList<>(readIndex().values());
        entries.sort(Comparator.comparingLong((Entry e) -> e.created).thenComparing(e -> e.name));
        return entries;
    }

    /** The layers a name or pattern means, in the order they are merged. */
    public synchronized List<Entry> resolve(String pattern) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : list()) {
            if (matches(pattern, entry.name)) {
                out.add(entry);
            }
        }
        return out;
    }

    @Nullable
    public synchronized BuildTarget load(String name) throws IOException {
        File file = file(name);
        if (!file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            return BuildTarget.read(in);
        }
    }

    /**
     * The union of the layers a name or pattern means, later layers winning per cell. Null when it
     * means none.
     *
     * @throws IOException when the layers are in different dimensions, or one cannot be read
     */
    @Nullable
    public synchronized BuildTarget union(String pattern) throws IOException {
        BuildTarget union = null;
        for (Entry entry : resolve(pattern)) {
            BuildTarget layer = load(entry.name);
            if (layer == null) {
                continue;
            }
            if (union == null) {
                union = new BuildTarget(layer.dimension());
            }
            else if (union.dimension() != layer.dimension()) {
                throw new IOException("'" + pattern + "' spans dimensions " + union.dimension() + " and "
                    + layer.dimension() + "; check one dimension's layers at a time.");
            }
            union.merge(layer);
        }
        return union;
    }

    /**
     * Saves a layer. Merges into what is there unless {@code replace}; a different dimension always
     * replaces, since a target belongs to one world.
     *
     * @return the saved layer's entry
     */
    public synchronized Entry save(String name, BuildTarget target, boolean replace, long now) throws IOException {
        String bad = checkName(name);
        if (bad != null) {
            throw new IOException(bad);
        }
        Map<String, Entry> index = readIndex();
        Entry previous = index.get(name);
        BuildTarget saved = target;
        if (!replace && previous != null) {
            BuildTarget existing = load(name);
            if (existing != null && existing.dimension() == target.dimension()) {
                existing.merge(target);
                saved = existing;
            }
        }
        File file = file(name);
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
        File temporary = new File(parent, file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(temporary)) {
            saved.write(out);
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Entry entry = new Entry(name, saved.dimension(), previous == null ? now : previous.created, now,
            saved.cells(), file.length());
        index.put(name, entry);
        writeIndex(index);
        return entry;
    }

    /** Deletes the layers a name or pattern means; returns their names. */
    public synchronized List<String> delete(String pattern) throws IOException {
        Map<String, Entry> index = readIndex();
        List<String> deleted = new ArrayList<>();
        for (Entry entry : resolve(pattern)) {
            Files.deleteIfExists(file(entry.name).toPath());
            index.remove(entry.name);
            deleted.add(entry.name);
        }
        writeIndex(index);
        return deleted;
    }

    private static final String DEFAULTS = "verify-defaults.json";

    /**
     * This world's verify defaults ({@code ignore}, {@code compare}, {@code sameAs}, {@code ignoreFor}),
     * applied before a call's own: a modpack's runtime state, set once rather than passed every call.
     */
    public synchronized JsonObject readDefaults() {
        File file = new File(folder, DEFAULTS);
        if (!file.isFile()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        }
        catch (IOException | RuntimeException e) {
            return new JsonObject();
        }
    }

    public synchronized void writeDefaults(JsonObject defaults) throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("could not create " + folder);
        }
        Files.write(new File(folder, DEFAULTS).toPath(), Json.writePretty(defaults).getBytes(StandardCharsets.UTF_8));
    }

    private File file(String name) {
        return new File(folder, name.replace('/', File.separatorChar) + SUFFIX);
    }

    private Map<String, Entry> readIndex() {
        Map<String, Entry> index = new LinkedHashMap<>();
        File file = new File(folder, INDEX);
        if (!file.isFile()) {
            return index;
        }
        try {
            JsonElement parsed = Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (parsed == null || !parsed.isJsonObject()) {
                return index;
            }
            for (Map.Entry<String, JsonElement> item : parsed.getAsJsonObject().entrySet()) {
                if (!item.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject e = item.getValue().getAsJsonObject();
                index.put(item.getKey(), new Entry(item.getKey(), Json.getInt(e, "dimension", 0),
                    Json.getLong(e, "created", 0L), Json.getLong(e, "updated", 0L), Json.getLong(e, "cells", 0L),
                    Json.getLong(e, "bytes", 0L)));
            }
        }
        catch (IOException | RuntimeException e) {
            // An unreadable index is rebuilt by the next save; the targets themselves are untouched.
        }
        return index;
    }

    private void writeIndex(Map<String, Entry> index) throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("could not create " + folder);
        }
        JsonObject json = new JsonObject();
        for (Entry entry : index.values()) {
            JsonObject e = new JsonObject();
            e.addProperty("dimension", entry.dimension);
            e.addProperty("created", entry.created);
            e.addProperty("updated", entry.updated);
            e.addProperty("cells", entry.cells);
            e.addProperty("bytes", entry.bytes);
            json.add(entry.name, e);
        }
        File file = new File(folder, INDEX);
        File temporary = new File(folder, INDEX + ".tmp");
        Files.write(temporary.toPath(), Json.writePretty(json).getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
