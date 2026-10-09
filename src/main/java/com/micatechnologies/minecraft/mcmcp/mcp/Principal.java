package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;

/**
 * Who a call runs as.
 *
 * <p>An endpoint caller — anyone holding the endpoint's token, or the orchestrator link — has always
 * been the game's owner, and is {@link #ENDPOINT}: permissions come from the config. A companion
 * caller is one player on a multiplayer server, identified by the connection their client's
 * messages arrived on, and carries the classes of call that player has been granted.
 *
 * <p>A companion principal's grants are replaced, not edited, when they change (a reload, a revoke,
 * a permission node changing hands), so a call reading them sees one consistent set.
 */
public final class Principal {

    /** The game's own endpoint caller. */
    public static final Principal ENDPOINT = new Principal(null, null, Collections.emptySet(), false);

    /** Reading world and player state. */
    public static final String CLASS_READ = "read";
    /** Writing blocks, restoring undo points, moving players. */
    public static final String CLASS_WRITE = "write";
    /** Running commands as oneself, and messaging players. */
    public static final String CLASS_COMMAND = "command";
    /** Keeping chunks loaded: server_keep_loaded, and load: true on a read or write. */
    public static final String CLASS_LOAD = "load";
    /** Naming a player other than oneself in a tool that takes one. */
    public static final String CLASS_OTHERS = "others";
    /** Running a command with the server console's authority instead of one's own. */
    public static final String CLASS_CONSOLE = "command.console";

    @Nullable
    private final String playerId;
    @Nullable
    private final String playerName;
    private final boolean companion;
    private volatile Set<String> classes;

    private Principal(@Nullable String playerId, @Nullable String playerName, Set<String> classes,
                      boolean companion) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.classes = Collections.unmodifiableSet(new TreeSet<>(classes));
        this.companion = companion;
    }

    /** A player reaching this server through the companion. */
    public static Principal companion(String playerId, String playerName, Set<String> classes) {
        return new Principal(playerId, playerName, classes, true);
    }

    public boolean isCompanion() {
        return companion;
    }

    @Nullable
    public String getPlayerId() {
        return playerId;
    }

    @Nullable
    public String getPlayerName() {
        return playerName;
    }

    public Set<String> getClasses() {
        return classes;
    }

    public boolean has(String callClass) {
        return classes.contains(callClass);
    }

    /** Replaces the grants. Returns whether they changed. */
    public boolean setClasses(Set<String> granted) {
        Set<String> next = Collections.unmodifiableSet(new TreeSet<>(granted));
        if (next.equals(classes)) {
            return false;
        }
        classes = next;
        return true;
    }

    /**
     * Whether this companion caller may exercise {@code capability}. Endpoint callers are answered from
     * the config by the tool, not here.
     */
    public boolean allows(Capability capability) {
        switch (capability) {
            case WORLD_EDITS:
                return has(CLASS_WRITE);
            case COMMANDS:
            case CHAT:
                return has(CLASS_COMMAND);
            default:
                return false;
        }
    }

    /** The class a capability needs, for a refusal that names what to ask for. */
    @Nullable
    public static String classFor(Capability capability) {
        switch (capability) {
            case WORLD_EDITS:
                return CLASS_WRITE;
            case COMMANDS:
            case CHAT:
                return CLASS_COMMAND;
            default:
                return null;
        }
    }

    /** For the request journal's start line; null for an endpoint caller, which the line omits. */
    @Nullable
    public JsonObject toJournal() {
        if (!companion) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("via", "companion");
        json.addProperty("player", playerName);
        json.addProperty("uuid", playerId);
        return json;
    }

    @Override
    public String toString() {
        return companion ? playerName + " (companion, " + Json.write(Json.arrayOfStrings(classes)) + ")" : "endpoint";
    }
}
