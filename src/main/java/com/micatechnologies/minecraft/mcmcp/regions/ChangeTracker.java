package com.micatechnologies.minecraft.mcmcp.regions;

import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.mcp.Principal;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Records block changes on the server so an agent can ask what changed in a box since it last
 * looked (#56) — above all, whether a person edited what it is building, so it never reverts their
 * work.
 *
 * <h2>What is recorded</h2>
 *
 * Every change the server sends to clients (a block update with the client-sync flag, which is what
 * players, pistons, explosions and almost every mod use), tagged {@code player:<name>} when a player
 * placed or broke it and {@code other} otherwise. Changes MCMCP itself makes during a tool call are
 * counted, not stored: an agent's own 270,000-block build would otherwise push every human edit out of
 * the ring, and the agent already knows what it wrote.
 *
 * <p>Not recorded: writes made without the client-sync flag (some mods' internal updates) and terrain
 * generation, which never pass the listener. The tool says so.
 */
public final class ChangeTracker {

    @Nullable
    private static volatile ChangeRing ring;

    /** Set on the server thread while an MCMCP tool's task runs: who the writes belong to. */
    private static final ThreadLocal<Principal> WRITER = new ThreadLocal<>();

    /** Positions a player placed or broke this tick, so the update that follows can be credited. */
    private static final Map<Long, String> PLAYER_EDITS = new HashMap<>();

    private static final AtomicLong MCMCP_WRITES = new AtomicLong();

    private ChangeTracker() {
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.register(new Events());
        ToolContext.setGameTaskObserver(new ToolContext.GameTaskObserver() {
            @Override
            public void enter(Principal principal) {
                WRITER.set(principal);
            }

            @Override
            public void exit() {
                WRITER.remove();
            }
        });
    }

    /** The ring for this server, created at server start with the configured size. */
    public static void serverStarting() {
        ring = new ChangeRing(McmcpConfig.getChangeLogEntries());
        MCMCP_WRITES.set(0L);
    }

    public static void serverStopping() {
        ring = null;
        synchronized (PLAYER_EDITS) {
            PLAYER_EDITS.clear();
        }
    }

    @Nullable
    public static ChangeRing ring() {
        return ring;
    }

    /** Block changes MCMCP's own tool calls made since the server started. */
    public static long mcmcpWrites() {
        return MCMCP_WRITES.get();
    }

    private static long key(int dimension, BlockPos pos) {
        return pos.toLong() * 31L + dimension;
    }

    /** Hears every client-synced block update in one server world. */
    private static final class Listener implements IWorldEventListener {

        private final int dimension;

        Listener(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public void notifyBlockUpdate(World world, BlockPos pos, IBlockState oldState, IBlockState newState, int flags) {
            if (oldState == newState) {
                // A resync of a block that did not change: chests opening, command blocks, and so on.
                return;
            }
            ChangeRing current = ring;
            if (current == null) {
                return;
            }
            if (WRITER.get() != null) {
                MCMCP_WRITES.incrementAndGet();
                return;
            }
            String player;
            synchronized (PLAYER_EDITS) {
                player = PLAYER_EDITS.get(key(dimension, pos));
            }
            current.add(dimension, pos.getX(), pos.getY(), pos.getZ(), Block.getStateId(newState),
                player == null ? "other" : "player:" + player);
        }

        @Override
        public void notifyLightSet(BlockPos pos) {
        }

        @Override
        public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
        }

        @Override
        public void playSoundToAllNearExcept(@Nullable EntityPlayer player, SoundEvent sound, SoundCategory category,
                                             double x, double y, double z, float volume, float pitch) {
        }

        @Override
        public void playRecord(SoundEvent sound, BlockPos pos) {
        }

        @Override
        public void spawnParticle(int id, boolean ignoreRange, double x, double y, double z, double xs, double ys,
                                  double zs, int... parameters) {
        }

        @Override
        public void spawnParticle(int id, boolean ignoreRange, boolean minimise, double x, double y, double z,
                                  double xs, double ys, double zs, int... parameters) {
        }

        @Override
        public void onEntityAdded(Entity entity) {
        }

        @Override
        public void onEntityRemoved(Entity entity) {
        }

        @Override
        public void broadcastSound(int id, BlockPos pos, int data) {
        }

        @Override
        public void playEvent(@Nullable EntityPlayer player, int type, BlockPos pos, int data) {
        }

        @Override
        public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {
        }
    }

    /** On Forge's bus. */
    public static final class Events {

        @SubscribeEvent
        public void onWorldLoad(WorldEvent.Load event) {
            if (!event.getWorld().isRemote && event.getWorld() instanceof WorldServer) {
                event.getWorld().addEventListener(new Listener(event.getWorld().provider.getDimension()));
            }
        }

        /** Lowest priority: only an edit that is going to happen is credited, after protection mods decide. */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onBreak(BlockEvent.BreakEvent event) {
            if (!event.isCanceled() && !event.getWorld().isRemote && event.getPlayer() != null) {
                note(event.getWorld(), event.getPos(), event.getPlayer().getName());
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onPlace(BlockEvent.PlaceEvent event) {
            if (!event.isCanceled() && !event.getWorld().isRemote && event.getPlayer() != null) {
                note(event.getWorld(), event.getPos(), event.getPlayer().getName());
            }
        }

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                synchronized (PLAYER_EDITS) {
                    PLAYER_EDITS.clear();
                }
            }
        }

        private static void note(World world, BlockPos pos, String player) {
            synchronized (PLAYER_EDITS) {
                if (PLAYER_EDITS.size() < 10_000) {
                    PLAYER_EDITS.put(key(world.provider.getDimension(), pos), player);
                }
            }
        }
    }
}
