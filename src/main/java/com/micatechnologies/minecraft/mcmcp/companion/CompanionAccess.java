package com.micatechnologies.minecraft.mcmcp.companion;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The parts of companion access that are pure rules, kept apart from the Forge code that applies
 * them so they are tested.
 */
public final class CompanionAccess {

    private CompanionAccess() {
    }

    /**
     * True when the allowlist names this player: by UUID, or by name for an entry not yet resolved to
     * one. Case does not matter for either. An empty list names nobody.
     */
    public static boolean isAllowlisted(UUID id, String name, List<String> allowlist) {
        String uuid = id.toString().toLowerCase(Locale.ROOT);
        String lowerName = name.toLowerCase(Locale.ROOT);
        for (String entry : allowlist) {
            String value = entry == null ? "" : entry.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty() && (value.equals(uuid) || value.equals(lowerName))) {
                return true;
            }
        }
        return false;
    }
}
