package com.micatechnologies.minecraft.mcmcp.tools;

import com.micatechnologies.minecraft.mcmcp.perf.EntityTickEvidence;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Watches whether each dimension's entity update runs, by its effects (#140).
 *
 * <p>Vanilla's server tick calls {@code WorldServer.updateEntities} between the START and END phases
 * of {@code WorldTickEvent}, and only that call advances an entity's {@code ticksExisted}. So a few
 * entities vanilla would tick are noted at START and looked at again at END, together with vanilla's
 * idle count, which the call moves on every run while the world is empty. A mod that cancels the call
 * (a pauser, a lag limiter) moves neither, and that is what this sees and the idle rule alone cannot.
 *
 * <p>Every {@value #EVERY}th tick, a handful of entities: cheap enough to leave on. Server thread only.
 */
final class EntityTickObserver {

    /** Sample one tick in this many. */
    private static final int EVERY = 4;
    /** Entities watched per sampled tick. */
    private static final int WATCH = 8;
    /** Entries of the loaded list looked at to find them. */
    private static final int SCAN = 64;
    /** Vanilla's {@code isAreaLoaded} margin for an entity outside a forced chunk. */
    private static final int MARGIN = 32;

    private static final Map<WorldServer, Probe> PROBES = new WeakHashMap<WorldServer, Probe>();

    private static boolean registered;

    private EntityTickObserver() {
    }

    static synchronized void register() {
        if (registered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new Events());
        registered = true;
    }

    /** What has been seen in this world lately. Server thread. */
    static EntityTickEvidence.Verdict verdict(WorldServer world) {
        Probe probe = PROBES.get(world);
        return probe == null ? EntityTickEvidence.Verdict.UNOBSERVED : probe.evidence.verdict();
    }

    private static final class Probe {
        final EntityTickEvidence evidence = new EntityTickEvidence();
        final Entity[] watched = new Entity[WATCH];
        final int[] ticksBefore = new int[WATCH];
        int count;
        int idleBefore;
        boolean armed;
        int ticks;
        int cursor;
    }

    /** Nested so that a second {@link #register()} cannot subscribe a second copy. */
    public static class Events {

        // LOWEST at the start and HIGHEST at the end, so as little as possible runs between the two
        // readings besides the world's own tick.
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onStart(TickEvent.WorldTickEvent event) {
            if (event.phase != TickEvent.Phase.START || !(event.world instanceof WorldServer)) {
                return;
            }
            WorldServer world = (WorldServer) event.world;
            Probe probe = PROBES.get(world);
            if (probe == null) {
                PROBES.put(world, probe = new Probe());
            }
            if (probe.ticks++ % EVERY != 0) {
                return;
            }
            arm(world, probe);
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onEnd(TickEvent.WorldTickEvent event) {
            if (event.phase != TickEvent.Phase.END || !(event.world instanceof WorldServer)) {
                return;
            }
            WorldServer world = (WorldServer) event.world;
            Probe probe = PROBES.get(world);
            if (probe == null || !probe.armed) {
                return;
            }
            probe.armed = false;
            boolean anyAdvanced = false;
            int watched = 0;
            for (int i = 0; i < probe.count; i++) {
                Entity entity = probe.watched[i];
                probe.watched[i] = null;
                // One that died or left during the tick says nothing either way.
                if (entity.isDead || entity.world != world) {
                    continue;
                }
                watched++;
                if (entity.ticksExisted != probe.ticksBefore[i]) {
                    anyAdvanced = true;
                }
            }
            boolean idleRuleHeld = world.playerEntities.isEmpty() && world.getPersistentChunks().isEmpty();
            probe.evidence.record(EntityTickEvidence.classify(watched, anyAdvanced, probe.idleBefore,
                WorldIdle.rawIdleTicks(world), idleRuleHeld, WorldIdle.CUTOFF));
        }
    }

    /** Notes players first, then entities vanilla would tick, spread through the loaded list. */
    private static void arm(WorldServer world, Probe probe) {
        probe.count = 0;
        for (EntityPlayer player : world.playerEntities) {
            if (probe.count == WATCH) {
                break;
            }
            // A player is ticked by the update whenever it runs, wherever the player stands.
            if (watchable(player)) {
                watch(probe, player);
            }
        }
        List<Entity> loaded = world.loadedEntityList;
        int size = loaded.size();
        for (int seen = 0; seen < SCAN && seen < size && probe.count < WATCH; seen++) {
            probe.cursor = (probe.cursor + 1) % size;
            Entity entity = loaded.get(probe.cursor);
            if (!(entity instanceof EntityPlayer) && watchable(entity) && wouldTick(world, entity)) {
                watch(probe, entity);
            }
        }
        probe.idleBefore = WorldIdle.rawIdleTicks(world);
        probe.armed = true;
    }

    private static boolean watchable(Entity entity) {
        // A passenger is ticked through its mount, and updateBlocked is Forge's per-entity off switch.
        return entity != null && !entity.isDead && entity.addedToChunk && !entity.updateBlocked
            && !entity.isRiding();
    }

    /** Vanilla's own test in {@code updateEntityWithOptionalForce}, without Forge's override. */
    private static boolean wouldTick(WorldServer world, Entity entity) {
        int x = MathHelper.floor(entity.posX);
        int z = MathHelper.floor(entity.posZ);
        int range = world.getPersistentChunks().containsKey(new ChunkPos(x >> 4, z >> 4)) ? 0 : MARGIN;
        return world.isAreaLoaded(new BlockPos(x - range, 0, z - range),
            new BlockPos(x + range, 0, z + range), true);
    }

    private static void watch(Probe probe, Entity entity) {
        probe.watched[probe.count] = entity;
        probe.ticksBefore[probe.count] = entity.ticksExisted;
        probe.count++;
    }
}
