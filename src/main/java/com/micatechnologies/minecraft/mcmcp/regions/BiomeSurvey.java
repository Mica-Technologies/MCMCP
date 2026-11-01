package com.micatechnologies.minecraft.mcmcp.regions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The arithmetic of an area biome survey ({@code server_get_biomes}, #175), kept free of Minecraft so
 * it can be tested: where the samples fall, and how the biomes found there are counted and encoded.
 *
 * <p>Each sample is a biome id and where that id came from: {@link #LOADED} from a loaded chunk's own
 * array, {@link #SAVED} from a generated chunk's saved array on disk, {@link #PREDICTED} from the
 * world's biome provider for a column not generated yet, or {@link #UNKNOWN}.
 */
public final class BiomeSurvey {

    public static final char LOADED = 'L';
    public static final char SAVED = 'S';
    public static final char PREDICTED = 'P';
    public static final char UNKNOWN = '-';

    /** Samples one call reads. */
    public static final int MAX_SAMPLES = 65_536;
    /** Samples one grid returns: each costs a few bytes of the reply. */
    public static final int MAX_GRID_SAMPLES = 16_384;
    /** Samples a default step aims for. */
    public static final int DEFAULT_SAMPLES = 4_096;

    private final int[] xs;
    private final int[] zs;
    private final int[] ids;
    private final char[] sources;

    public BiomeSurvey(int[] xs, int[] zs) {
        this.xs = xs;
        this.zs = zs;
        this.ids = new int[xs.length * zs.length];
        this.sources = new char[ids.length];
        java.util.Arrays.fill(ids, -1);
        java.util.Arrays.fill(sources, UNKNOWN);
    }

    /** Sample positions on one axis: {@code min}, then every {@code step}, never past {@code max}. */
    public static int[] axis(int from, int to, int step) {
        int min = Math.min(from, to);
        int max = Math.max(from, to);
        int count = (int) ((long) (max - min) / step + 1);
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = min + i * step;
        }
        return values;
    }

    /** The step that keeps a box near {@link #DEFAULT_SAMPLES} samples: at least 1, whole blocks. */
    public static int defaultStep(long width, long depth) {
        double area = (double) width * depth;
        return (int) Math.max(1L, (long) Math.ceil(Math.sqrt(area / DEFAULT_SAMPLES)));
    }

    public int width() {
        return xs.length;
    }

    public int depth() {
        return zs.length;
    }

    public int size() {
        return ids.length;
    }

    /** Sample index of column {@code i} along X and {@code j} along Z: rows run along X. */
    public int index(int i, int j) {
        return j * xs.length + i;
    }

    public int x(int index) {
        return xs[index % xs.length];
    }

    public int z(int index) {
        return zs[index / xs.length];
    }

    public boolean known(int index) {
        return ids[index] >= 0;
    }

    public void set(int index, int biomeId, char source) {
        ids[index] = biomeId;
        sources[index] = source;
    }

    public int id(int index) {
        return ids[index];
    }

    /** Distinct biome ids found, for the caller to name. */
    public Set<Integer> distinctIds() {
        Set<Integer> found = new HashSet<>();
        for (int id : ids) {
            if (id >= 0) {
                found.add(id);
            }
        }
        return found;
    }

    /** How many samples came from each source, by name. */
    public JsonObject sources() {
        int loaded = 0;
        int saved = 0;
        int predicted = 0;
        int unknown = 0;
        for (char source : sources) {
            if (source == LOADED) {
                loaded++;
            }
            else if (source == SAVED) {
                saved++;
            }
            else if (source == PREDICTED) {
                predicted++;
            }
            else {
                unknown++;
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("loaded", loaded);
        json.addProperty("saved", saved);
        json.addProperty("predicted", predicted);
        if (unknown > 0) {
            json.addProperty("unknown", unknown);
        }
        return json;
    }

    /**
     * Each biome found with its sample count and share of the known samples, most common first.
     *
     * @param names biome id to registry name
     */
    public JsonArray tally(Map<Integer, String> names) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        int known = 0;
        for (int id : ids) {
            if (id >= 0) {
                counts.merge(id, 1, Integer::sum);
                known++;
            }
        }
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue()
            : nameOf(names, a.getKey()).compareTo(nameOf(names, b.getKey())));
        JsonArray json = new JsonArray();
        for (Map.Entry<Integer, Integer> entry : sorted) {
            JsonObject row = new JsonObject();
            row.addProperty("biome", nameOf(names, entry.getKey()));
            row.addProperty("count", entry.getValue());
            row.addProperty("share", share(entry.getValue(), known));
            json.add(row);
        }
        return json;
    }

    /** How many known samples are one of {@code wanted}, and their share. */
    public JsonObject matching(Collection<Integer> wanted) {
        Set<Integer> set = new HashSet<>(wanted);
        int known = 0;
        int count = 0;
        for (int id : ids) {
            if (id >= 0) {
                known++;
                if (set.contains(id)) {
                    count++;
                }
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("count", count);
        json.addProperty("share", share(count, known));
        return json;
    }

    /**
     * The samples as a palette of biome names and a flat index array, rows along X from the lowest Z,
     * with -1 for an unknown sample; and one source letter per sample.
     */
    public JsonObject grid(Map<Integer, String> names) {
        Map<Integer, Integer> palette = new LinkedHashMap<>();
        JsonArray indexes = new JsonArray();
        StringBuilder source = new StringBuilder(ids.length);
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i];
            if (id < 0) {
                indexes.add(-1);
            }
            else {
                Integer at = palette.get(id);
                if (at == null) {
                    at = palette.size();
                    palette.put(id, at);
                }
                indexes.add(at);
            }
            source.append(sources[i]);
        }
        JsonArray paletteJson = new JsonArray();
        for (Integer id : palette.keySet()) {
            paletteJson.add(nameOf(names, id));
        }
        JsonObject json = new JsonObject();
        json.addProperty("width", xs.length);
        json.addProperty("depth", zs.length);
        json.add("palette", paletteJson);
        json.add("indexes", indexes);
        json.addProperty("source", source.toString());
        return json;
    }

    private static String nameOf(Map<Integer, String> names, int id) {
        String name = names.get(id);
        return name == null ? "#" + id : name;
    }

    private static double share(int count, int total) {
        return total == 0 ? 0.0D : Math.round(count * 10000.0D / total) / 10000.0D;
    }
}
