package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import javax.annotation.Nullable;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;

/**
 * Whether a dimension still ticks its entities and tile entities (#101).
 *
 * <p>Vanilla 1.12's {@code WorldServer.updateEntities} stops ticking every entity <em>and tile entity</em>
 * once the dimension has had no player and no forced chunk for 300 ticks, about 15 seconds. Nothing
 * else shows it: the tick rate stays at 20, world time advances, reads find the chunks loaded and
 * {@code /blockdata} succeeds. A dedicated server nobody has joined is in this state 15 seconds after
 * it starts, so a test of a machine there sees it do nothing and blames the machine. A forced chunk,
 * which {@code server_keep_loaded} adds, keeps the dimension ticking.
 *
 * <p>Game thread.
 */
final class WorldIdle {

    /** The idle count past which {@code updateEntities} returns early. */
    static final int CUTOFF = 300;

    @Nullable
    private static final Field UPDATE_ENTITY_TICK = find();

    private WorldIdle() {
    }

    @Nullable
    private static Field find() {
        try {
            return ObfuscationReflectionHelper.findField(WorldServer.class, "field_80004_Q");
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** Ticks the dimension has gone with no player and no forced chunk, or -1 if it cannot be read. */
    static int idleTicks(WorldServer world) {
        if (!world.playerEntities.isEmpty() || !world.getPersistentChunks().isEmpty()) {
            return 0;
        }
        if (UPDATE_ENTITY_TICK == null) {
            return -1;
        }
        try {
            return UPDATE_ENTITY_TICK.getInt(world);
        }
        catch (IllegalAccessException | RuntimeException e) {
            return -1;
        }
    }

    /** Whether the next tick will tick entities and tile entities; true when it cannot be told. */
    static boolean entitiesTicking(WorldServer world) {
        return idleTicks(world) < CUTOFF;
    }

    /** Adds {@code entitiesTicking} and, while the dimension is empty, {@code idleTicks}. */
    static void describe(WorldServer world, JsonObject into) {
        int idle = idleTicks(world);
        into.addProperty("entitiesTicking", idle < CUTOFF);
        if (idle > 0) {
            into.addProperty("idleTicks", idle);
        }
    }

    /** What to tell a caller about a dimension that has stopped ticking, or null while it ticks. */
    @Nullable
    static String warning(WorldServer world) {
        if (entitiesTicking(world)) {
            return null;
        }
        return "Dimension " + world.provider.getDimension() + " has had no player and no forced chunk for over "
            + CUTOFF + " ticks, so vanilla has stopped ticking its entities and tile entities: world time "
            + "advances, but no machine, controller or mob updates. Hold the area with server_keep_loaded (a "
            + "forced chunk keeps the dimension ticking) or have a player join.";
    }
}
