package com.micatechnologies.minecraft.mcmcp.events;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The tab list as a client last saw it, and who joined or left since (#201).
 *
 * <p>A client has no join or leave event of its own; the tab list is the only locale-free record of
 * who is online. The first look after connecting only sets the baseline, or every player already
 * online would arrive as a join.
 */
public final class Roster {

    /** Name to uuid (may be null), in the order first seen. */
    @Nullable
    private Map<String, String> known;

    /** One change between two looks. */
    public static final class Change {

        public final String player;
        @Nullable
        public final String uuid;
        public final boolean joined;

        Change(String player, @Nullable String uuid, boolean joined) {
            this.player = player;
            this.uuid = uuid;
            this.joined = joined;
        }
    }

    /** Forgets the baseline: the next look is a new connection's first. */
    public void reset() {
        known = null;
    }

    /** Names online at the last look. */
    public List<String> online() {
        return known == null ? new ArrayList<String>() : new ArrayList<>(known.keySet());
    }

    /**
     * Records a look at the tab list.
     *
     * @param now name to uuid of everyone listed now
     * @return who joined and left since the last look; empty on the first look
     */
    public List<Change> update(Map<String, String> now) {
        List<Change> changes = new ArrayList<>();
        if (known == null) {
            known = new LinkedHashMap<>(now);
            return changes;
        }
        for (Map.Entry<String, String> entry : known.entrySet()) {
            if (!now.containsKey(entry.getKey())) {
                changes.add(new Change(entry.getKey(), entry.getValue(), false));
            }
        }
        for (Map.Entry<String, String> entry : now.entrySet()) {
            if (!known.containsKey(entry.getKey())) {
                changes.add(new Change(entry.getKey(), entry.getValue(), true));
            }
        }
        known = new LinkedHashMap<>(now);
        return changes;
    }

    /** Whether {@code name} was online at the last look. */
    public boolean isOnline(String name) {
        return known != null && known.containsKey(name);
    }
}
