package com.micatechnologies.minecraft.mcmcp.regions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * How the world compares with a {@link BuildTarget}, as counts and a few samples per kind of difference.
 *
 * <p>Fed one cell at a time, so the game side can walk a big target in slices across ticks. What it
 * compares is the <em>normalised</em> state: a door that someone opened, a lamp that came on, a fence that
 * joined its neighbour, are not differences a build check should report (the list came from the
 * builder that runs these checks on every building).
 *
 * <p>Pure, and not thread-safe.
 */
public final class TargetDiff {

    /** What a cell turned out to be. */
    public enum Kind {
        MATCH("matching"),
        /** A different block. */
        WRONG_BLOCK("wrongBlock"),
        /** The right block in a different state (facing, half, type...). */
        WRONG_STATE("wrongState"),
        /** Air where a block is wanted. */
        MISSING("missing"),
        /** A block where air is wanted. */
        EXTRA("extra"),
        /** Not loaded, so not checked. */
        UNLOADED("unloaded"),
        /** A protected cell that is no longer as it was kept. Never repaired. */
        PROTECTED_CHANGED("protectedChanged");

        public final String key;

        Kind(String key) {
            this.key = key;
        }

        boolean isDifference() {
            return this != MATCH && this != UNLOADED;
        }
    }

    /**
     * Turns a state string into the form a check compares: block ids that mean the same thing mapped to
     * one, and stored properties that change at runtime dropped.
     *
     * <p>It expects states already put through their block's metadata round trip by the game side
     * ({@code getStateFromMeta(getMetaFromState(s))}). That drops exactly the properties a block does
     * not store (fence and pane connections, stair shape, a railing's slope) and keeps every one it
     * does, so this list holds only stored bits that flip at runtime. An earlier version dropped
     * {@code north}, {@code shape} and the like for every block, and so passed CSM railings with the
     * wrong edges and rails with the wrong shape (review by the UIA builder, 2026-10-26).
     */
    public static final class Normaliser {

        private final Set<String> ignoredProperties;
        private final Map<String, String> sameAs;
        /** Block id pattern ({@code *} matches any run) to properties ignored for those blocks only. */
        private final Map<String, Set<String>> ignoredFor;

        public Normaliser(Set<String> ignoredProperties, Map<String, String> sameAs) {
            this(ignoredProperties, sameAs, Collections.<String, Set<String>>emptyMap());
        }

        public Normaliser(Set<String> ignoredProperties, Map<String, String> sameAs,
                          Map<String, Set<String>> ignoredFor) {
            this.ignoredProperties = new HashSet<>(ignoredProperties);
            this.sameAs = new HashMap<>(sameAs);
            this.ignoredFor = new HashMap<>();
            for (Map.Entry<String, Set<String>> entry : ignoredFor.entrySet()) {
                this.ignoredFor.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
        }

        /** Stored state that changes by itself in a built world, by the builder's account (2026-10-26). */
        public static Normaliser defaults() {
            Set<String> properties = new HashSet<>(Arrays.asList(
                // doors, gates, trapdoors opened; anything powered or lit
                "open", "powered", "lit", "triggered", "enabled", "power",
                // leaves, snow, fluids
                "decayable", "check_decay", "snowy", "level"));
            Map<String, String> same = new HashMap<>();
            same.put("csm:lightupair", "minecraft:air");
            same.put("minecraft:snow_layer", "minecraft:air");
            // 1.12 swaps the block itself, not a property, when these light up or power.
            same.put("minecraft:lit_redstone_lamp", "minecraft:redstone_lamp");
            same.put("minecraft:lit_furnace", "minecraft:furnace");
            same.put("minecraft:unlit_redstone_torch", "minecraft:redstone_torch");
            same.put("minecraft:powered_repeater", "minecraft:unpowered_repeater");
            same.put("minecraft:powered_comparator", "minecraft:unpowered_comparator");
            same.put("minecraft:lit_redstone_ore", "minecraft:redstone_ore");
            // Grass turning to dirt under a placed block is the game side's call: only where vanilla
            // would have turned it, so a design's missing sod is still found.
            return new Normaliser(properties, same);
        }

        /** A copy with more properties ignored (everywhere, and per block pattern) and more ids made equal. */
        public Normaliser with(Set<String> moreProperties, Map<String, String> moreSame,
                               Map<String, Set<String>> moreFor) {
            Set<String> properties = new HashSet<>(ignoredProperties);
            properties.addAll(moreProperties);
            Map<String, String> same = new HashMap<>(sameAs);
            same.putAll(moreSame);
            Map<String, Set<String>> forBlocks = new HashMap<>(ignoredFor);
            for (Map.Entry<String, Set<String>> entry : moreFor.entrySet()) {
                Set<String> merged = new HashSet<>(entry.getValue());
                Set<String> before = forBlocks.get(entry.getKey());
                if (before != null) {
                    merged.addAll(before);
                }
                forBlocks.put(entry.getKey(), merged);
            }
            return new Normaliser(properties, same, forBlocks);
        }

        /** A copy with more properties ignored and more ids made equal. */
        public Normaliser with(Set<String> moreProperties, Map<String, String> moreSame) {
            return with(moreProperties, moreSame, Collections.<String, Set<String>>emptyMap());
        }

        /** A copy that compares these properties after all, everywhere. */
        public Normaliser without(Set<String> properties) {
            Set<String> kept = new HashSet<>(ignoredProperties);
            kept.removeAll(properties);
            return new Normaliser(kept, sameAs, ignoredFor);
        }

        /** Whether a block id matches a pattern, where {@code *} matches any run of characters. */
        public static boolean matches(String pattern, String id) {
            if (!pattern.contains("*")) {
                return pattern.equals(id);
            }
            String[] parts = pattern.split("\\*", -1);
            int at = 0;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (i == 0) {
                    if (!id.startsWith(part)) {
                        return false;
                    }
                    at = part.length();
                }
                else if (i == parts.length - 1) {
                    return id.length() - part.length() >= at && id.endsWith(part);
                }
                else {
                    int found = id.indexOf(part, at);
                    if (found < 0) {
                        return false;
                    }
                    at = found + part.length();
                }
            }
            return true;
        }

        private boolean ignored(String id, String property) {
            if (ignoredProperties.contains(property)) {
                return true;
            }
            for (Map.Entry<String, Set<String>> entry : ignoredFor.entrySet()) {
                if (entry.getValue().contains(property) && matches(entry.getKey(), id)) {
                    return true;
                }
            }
            return false;
        }

        public String normalise(String state) {
            int open = state.indexOf('[');
            String id = open < 0 ? state : state.substring(0, open);
            String mapped = sameAs.get(id);
            if (mapped != null) {
                // An id mapped to air keeps nothing (a snow layer's depth means nothing as air); any other
                // keeps its properties, so a lit furnace still compares its facing.
                if ("minecraft:air".equals(mapped)) {
                    return mapped;
                }
                id = mapped;
            }
            if (open < 0 || !state.endsWith("]")) {
                return id;
            }
            // Sorted, so two states that differ only in the order properties were written compare equal.
            TreeMap<String, String> properties = new TreeMap<>();
            for (String pair : state.substring(open + 1, state.length() - 1).split(",")) {
                int equals = pair.indexOf('=');
                if (equals > 0 && !ignored(id, pair.substring(0, equals))) {
                    properties.put(pair.substring(0, equals), pair.substring(equals + 1));
                }
            }
            if (properties.isEmpty()) {
                return id;
            }
            StringBuilder text = new StringBuilder(id).append('[');
            boolean first = true;
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                text.append(first ? "" : ",").append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
            return text.append(']').toString();
        }

        static String idOf(String state) {
            int open = state.indexOf('[');
            return open < 0 ? state : state.substring(0, open);
        }
    }

    private static final String AIR = "minecraft:air";

    private final Normaliser normaliser;
    private final int samplesPerKind;
    private final List<int[]> skipBoxes;
    private final Map<Kind, long[]> counts = new EnumMap<>(Kind.class);
    private final Map<Kind, JsonArray> samples = new EnumMap<>(Kind.class);
    private long skipped;

    /**
     * @param skipBoxes {minX, minY, minZ, maxX, maxY, maxZ} each: volumes not checked at all (a lift
     *                  shaft whose cab moves)
     */
    public TargetDiff(Normaliser normaliser, int samplesPerKind, List<int[]> skipBoxes) {
        this.normaliser = normaliser;
        this.samplesPerKind = Math.max(0, samplesPerKind);
        this.skipBoxes = new ArrayList<>(skipBoxes);
        for (Kind kind : Kind.values()) {
            counts.put(kind, new long[1]);
            samples.put(kind, new JsonArray());
        }
    }

    public boolean skips(int x, int y, int z) {
        for (int[] box : skipBoxes) {
            if (x >= box[0] && y >= box[1] && z >= box[2] && x <= box[3] && y <= box[4] && z <= box[5]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares one cell and counts it.
     *
     * @param live the world's state there, or null when the cell is not loaded
     * @return what it is, or null when the cell is in a skipped box
     */
    @Nullable
    public Kind add(int x, int y, int z, String want, boolean protect, @Nullable String live) {
        if (skips(x, y, z)) {
            skipped++;
            return null;
        }
        Kind kind = classify(want, protect, live);
        counts.get(kind)[0]++;
        JsonArray list = samples.get(kind);
        if (kind.isDifference() && list.size() < samplesPerKind) {
            JsonObject sample = new JsonObject();
            JsonArray at = new JsonArray();
            at.add(x);
            at.add(y);
            at.add(z);
            sample.add("at", at);
            sample.addProperty("want", want);
            sample.addProperty("is", live);
            list.add(sample);
        }
        return kind;
    }

    Kind classify(String want, boolean protect, @Nullable String live) {
        if (live == null) {
            return Kind.UNLOADED;
        }
        String wanted = normaliser.normalise(want);
        String found = normaliser.normalise(live);
        if (wanted.equals(found)) {
            return Kind.MATCH;
        }
        if (protect) {
            return Kind.PROTECTED_CHANGED;
        }
        String wantedId = Normaliser.idOf(wanted);
        String foundId = Normaliser.idOf(found);
        if (wantedId.equals(foundId)) {
            return Kind.WRONG_STATE;
        }
        if (AIR.equals(foundId)) {
            return Kind.MISSING;
        }
        if (AIR.equals(wantedId)) {
            return Kind.EXTRA;
        }
        return Kind.WRONG_BLOCK;
    }

    public long count(Kind kind) {
        return counts.get(kind)[0];
    }

    /** Cells a repair would write: every difference but a protected one. */
    public long repairable() {
        return count(Kind.WRONG_BLOCK) + count(Kind.WRONG_STATE) + count(Kind.MISSING) + count(Kind.EXTRA);
    }

    /** {matching, differ, then each kind that occurred, with its samples}. Compact by construction. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("matching", count(Kind.MATCH));
        json.addProperty("differ", repairable());
        JsonObject kinds = new JsonObject();
        for (Kind kind : Kind.values()) {
            if (kind == Kind.MATCH || count(kind) == 0) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("count", count(kind));
            if (samples.get(kind).size() > 0) {
                entry.add("samples", samples.get(kind));
            }
            kinds.add(kind.key, entry);
        }
        if (kinds.size() > 0) {
            json.add("byKind", kinds);
        }
        if (skipped > 0) {
            json.addProperty("skipped", skipped);
        }
        return json;
    }

    /** The kinds, in report order, for documentation. */
    public static List<String> kindKeys() {
        List<String> keys = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            keys.add(kind.key);
        }
        return Collections.unmodifiableList(keys);
    }
}
