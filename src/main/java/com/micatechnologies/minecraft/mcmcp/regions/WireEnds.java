package com.micatechnologies.minecraft.mcmcp.regions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * What sits at each end of an Immersive Engineering wire, and what that makes a connected piece (#162).
 *
 * <p>A grid audit needed three passes after the wire read: the block at every end, what each connector
 * is bolted to, and which pieces are fed. Whether a piece is live is a question about its ends' mounted
 * blocks, not its wires: a connector on a generator (or a generation meter) supplies it, one on a
 * consumer draws from it. The game half ({@code ServerWireTools}) reads each end into an {@link End};
 * the roles and the per-piece sums are here, so they are tested as plain Java.
 */
public final class WireEnds {

    /** Most role rows listed per piece; the counts always cover them all. */
    public static final int MAX_ROLE_ROWS = 64;

    private WireEnds() {
    }

    /** One wire end as read in the world. Everything but {@code loaded} is null when not known. */
    public static final class End {
        /** False when the end's chunk was not loaded: nothing else was read. */
        public boolean loaded = true;
        @Nullable public String block;
        @Nullable public String te;
        /** The block the connector is mounted on, from its facing. */
        @Nullable public int[] mounted;
        @Nullable public String mountedBlock;
        @Nullable public String mountedTe;
        /** Forge energy of the mounted block, read on the face the connector touches. */
        @Nullable public Integer energy;
        @Nullable public Integer maxEnergy;
        @Nullable public Boolean canExtract;
        @Nullable public Boolean canReceive;
    }

    /**
     * The end's role in its piece: {@code source} (mounted on something that only gives energy),
     * {@code load} (only takes it), {@code store} (both, as a capacitor does), or null (no energy
     * block, or not loaded).
     */
    @Nullable
    public static String role(End end) {
        if (!end.loaded || end.canExtract == null || end.canReceive == null) {
            return null;
        }
        if (end.canExtract && end.canReceive) {
            return "store";
        }
        if (end.canExtract) {
            return "source";
        }
        if (end.canReceive) {
            return "load";
        }
        return null;
    }

    /** One end, compactly: only the fields that were read. */
    public static JsonObject toJson(End end) {
        JsonObject json = new JsonObject();
        if (!end.loaded) {
            json.addProperty("loaded", false);
            return json;
        }
        if (end.block != null) {
            json.addProperty("block", end.block);
        }
        if (end.te != null) {
            json.addProperty("te", end.te);
        }
        if (end.mounted != null) {
            json.add("mounted", ints(end.mounted));
        }
        if (end.mountedBlock != null) {
            json.addProperty("mountedBlock", end.mountedBlock);
        }
        if (end.mountedTe != null) {
            json.addProperty("mountedTe", end.mountedTe);
        }
        if (end.energy != null) {
            json.addProperty("energy", end.energy);
        }
        if (end.maxEnergy != null) {
            json.addProperty("maxEnergy", end.maxEnergy);
        }
        String role = role(end);
        if (role != null) {
            json.addProperty("role", role);
        }
        return json;
    }

    /** A piece's ends summed. */
    public static final class Summary {
        public int sources;
        public int sourcesEnergised;
        public int loads;
        public int loadsEnergised;
        public int stores;
        public int unloaded;
        /** Role rows: {@code [role, node, mountedTe, energy, maxEnergy]}, at most {@link #MAX_ROLE_ROWS}. */
        public final JsonArray rows = new JsonArray();
        public boolean rowsTruncated;

        int storesEnergised;

        /** Whether anything supplies the piece. A store alone is not counted: it only passes on what reaches it. */
        public boolean fed() {
            return sources > 0;
        }
    }

    /**
     * Sums a piece's ends by role. A node with no entry in {@code ends} counts as unloaded: nothing
     * was read there.
     */
    public static Summary summarise(WireGraph.Component piece, Map<String, End> ends) {
        Summary summary = new Summary();
        for (int[] node : piece.nodes) {
            End end = ends.get(key(node));
            if (end == null || !end.loaded) {
                summary.unloaded++;
                continue;
            }
            String role = role(end);
            if (role == null) {
                continue;
            }
            boolean energised = end.energy != null && end.energy > 0;
            switch (role) {
                case "source":
                    summary.sources++;
                    if (energised) {
                        summary.sourcesEnergised++;
                    }
                    break;
                case "load":
                    summary.loads++;
                    if (energised) {
                        summary.loadsEnergised++;
                    }
                    break;
                default:
                    summary.stores++;
                    if (energised) {
                        summary.storesEnergised++;
                    }
                    break;
            }
            if (summary.rows.size() >= MAX_ROLE_ROWS) {
                summary.rowsTruncated = true;
                continue;
            }
            JsonArray row = new JsonArray();
            row.add(role);
            row.add(ints(node));
            row.add(end.mountedTe == null ? end.mountedBlock : end.mountedTe);
            row.add(end.energy);
            row.add(end.maxEnergy);
            summary.rows.add(row);
        }
        return summary;
    }

    /** Adds a summary's fields to a piece's row; zero stores, unloaded ends and an empty role list are left out. */
    public static void addTo(JsonObject row, Summary summary) {
        row.addProperty("sources", summary.sources);
        row.addProperty("loads", summary.loads);
        if (summary.stores > 0) {
            row.addProperty("stores", summary.stores);
        }
        if (summary.loads > 0) {
            row.addProperty("loadsEnergised", summary.loadsEnergised);
        }
        if (summary.unloaded > 0) {
            row.addProperty("unloadedEnds", summary.unloaded);
        }
        if (summary.rows.size() > 0) {
            row.add("roles", summary.rows);
        }
        if (summary.rowsTruncated) {
            row.addProperty("rolesTruncated", true);
        }
    }

    /** The key {@link WireGraph} and the end map share for a node. */
    public static String key(int[] at) {
        return at[0] + "," + at[1] + "," + at[2];
    }

    private static JsonArray ints(int[] values) {
        JsonArray array = new JsonArray();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }
}
