package com.micatechnologies.minecraft.mcmcp.client;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Whether the chunks round the player arrived and were drawn, and the two ways to put it right (#207).
 *
 * <p>After a long teleport the world round the player sometimes stays empty until someone presses
 * F3+A. Either the chunks never came, or they came and the renderer never built them: a long jump
 * moves the renderer's chunk grid without marking anything to rebuild, and a chunk whose data arrived
 * at the wrong moment is left with no mesh. The two need different cures, so they are counted apart:
 * an absent chunk is asked of the server again, an unbuilt section is rebuilt with
 * {@code RenderGlobal.loadRenderers()}, which is what F3+A calls.
 *
 * <p>A section counts as unbuilt when it holds blocks, is not waiting in the build queue, and its
 * compiled mesh is empty. A section of nothing but tile-entity-drawn blocks reads the same, so a few
 * are tolerated before anything is rebuilt.
 *
 * <p>Every MCMCP teleport, and any other jump of {@link #JUMP_BLOCKS} or more, is checked a few seconds
 * later and logged, and healed when {@code display.healChunksAfterTeleport} is on.
 */
public final class ChunkRenderHealth {

    /** A move this long in one tick is a teleport, not walking or flying. */
    static final double JUMP_BLOCKS = 64.0D;

    /** How long after a teleport the chunks round the player are checked. */
    static final long FIRST_CHECK_MILLIS = 6_000L;
    static final long SECOND_CHECK_MILLIS = 16_000L;

    /** Chunks each side of the player's that a teleport check looks at. */
    static final int CHECK_RADIUS = 2;

    /** Section layers above and below the player's that are looked at. */
    static final int SECTION_RANGE = 2;

    private ChunkRenderHealth() {
    }

    /** What one check found. */
    public static final class Report {
        /** The radius a check uses when the caller has no reason to pick another. */
        public static final int DEFAULT_RADIUS = CHECK_RADIUS;

        public int columns;
        public int absent;
        public int sections;
        public int built;
        public int queued;
        public int unbuilt;
        public boolean frustumReadable = true;

        /** Enough unbuilt sections that a rebuild is due, not a few tile-entity-only ones. */
        public boolean needsRebuild() {
            return unbuilt >= 3 && unbuilt * 4 >= sections;
        }

        public boolean healthy() {
            return absent == 0 && !needsRebuild();
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("chunksChecked", columns);
            json.addProperty("chunksAbsent", absent);
            json.addProperty("sectionsWithBlocks", sections);
            json.addProperty("sectionsBuilt", built);
            json.addProperty("sectionsQueued", queued);
            json.addProperty("sectionsUnbuilt", unbuilt);
            if (!frustumReadable) {
                json.addProperty("note", "The renderer's chunk grid could not be read, so only arrival was checked.");
            }
            json.addProperty("healthy", healthy());
            return json;
        }
    }

    /** Checks the chunks within {@code radius} of the player. Client thread. */
    public static Report check(Minecraft mc, int radius) {
        Report report = new Report();
        if (mc.world == null || mc.player == null) {
            return report;
        }
        int centreX = MathHelper.floor(mc.player.posX) >> 4;
        int centreZ = MathHelper.floor(mc.player.posZ) >> 4;
        int centreY = MathHelper.clamp(MathHelper.floor(mc.player.posY) >> 4, 0, 15);
        int fromY = Math.max(0, centreY - SECTION_RANGE);
        int toY = Math.min(15, centreY + SECTION_RANGE);
        int side = radius * 2 + 1;

        // The render chunk at each section of the box, from the renderer's own grid.
        RenderChunk[] grid = new RenderChunk[side * side * 16];
        ViewFrustum frustum = frustum(mc.renderGlobal);
        if (frustum == null || frustum.renderChunks == null) {
            report.frustumReadable = false;
        }
        else {
            for (RenderChunk renderChunk : frustum.renderChunks) {
                if (renderChunk == null) {
                    continue;
                }
                BlockPos at = renderChunk.getPosition();
                int dx = (at.getX() >> 4) - centreX + radius;
                int dz = (at.getZ() >> 4) - centreZ + radius;
                int sy = at.getY() >> 4;
                if (dx >= 0 && dx < side && dz >= 0 && dz < side && sy >= 0 && sy < 16) {
                    grid[(dx * side + dz) * 16 + sy] = renderChunk;
                }
            }
        }

        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                report.columns++;
                Chunk chunk = mc.world.getChunkProvider().getLoadedChunk(centreX + dx - radius, centreZ + dz - radius);
                if (chunk == null || chunk.isEmpty()) {
                    report.absent++;
                    continue;
                }
                if (!report.frustumReadable) {
                    continue;
                }
                ExtendedBlockStorage[] storage = chunk.getBlockStorageArray();
                for (int sy = fromY; sy <= toY; sy++) {
                    ExtendedBlockStorage section = storage[sy];
                    if (section == Chunk.NULL_BLOCK_STORAGE || section.isEmpty()) {
                        continue;
                    }
                    report.sections++;
                    RenderChunk renderChunk = grid[(dx * side + dz) * 16 + sy];
                    if (renderChunk == null || renderChunk.needsUpdate()) {
                        report.queued++;
                        continue;
                    }
                    CompiledChunk compiled = renderChunk.getCompiledChunk();
                    if (compiled == null || compiled == CompiledChunk.DUMMY || compiled.isEmpty()) {
                        report.unbuilt++;
                    }
                    else {
                        report.built++;
                    }
                }
            }
        }
        return report;
    }

    /** Rebuilds every chunk mesh, as F3+A does. Client thread. */
    public static void rebuild(Minecraft mc) {
        mc.renderGlobal.loadRenderers();
    }

    /**
     * Asks the server, through the companion, to send this player the chunks within {@code radius}
     * again. Blocks: never call it on the client thread.
     *
     * @return the server's structured answer, or null when there is no companion or it refused
     */
    @Nullable
    public static JsonObject requestResend(String player, int blockX, int blockZ, int radius,
                                           @Nullable JsonObject stamp) {
        JsonObject args = new JsonObject();
        args.addProperty("player", player);
        args.addProperty("x", blockX - radius * 16);
        args.addProperty("z", blockZ - radius * 16);
        args.addProperty("toX", blockX + radius * 16);
        args.addProperty("toZ", blockZ + radius * 16);
        try {
            JsonObject result = com.micatechnologies.minecraft.mcmcp.client.companion.ClientCompanion.callServerTool(
                "server_refresh_chunks", args, 30_000L, stamp);
            if (Json.getBoolean(result, "isError", false)) {
                return null;
            }
            return Json.getObject(result, "structuredContent");
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    private static volatile Field frustumField;
    private static volatile boolean frustumMissing;

    @Nullable
    private static ViewFrustum frustum(RenderGlobal renderGlobal) {
        if (frustumMissing) {
            return null;
        }
        try {
            Field field = frustumField;
            if (field == null) {
                // The SRG name: findField maps it to the MCP name in a dev client, where fields keep those.
                field = ObfuscationReflectionHelper.findField(RenderGlobal.class, "field_175008_n");
                frustumField = field;
            }
            return (ViewFrustum) field.get(renderGlobal);
        }
        catch (RuntimeException | IllegalAccessException e) {
            frustumMissing = true;
            Mcmcp.LOGGER.warn("MCMCP: cannot read the renderer's chunk grid; chunk checks will cover arrival only", e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // After a teleport
    // ------------------------------------------------------------------

    /** The last teleport check, for {@code client_reload_chunks} to report. */
    @Nullable
    private static volatile JsonObject lastTeleportCheck;

    @Nullable
    public static JsonObject lastTeleportCheck() {
        return lastTeleportCheck;
    }

    private static final ExecutorService RESENDS = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MCMCP chunk resend");
        thread.setDaemon(true);
        return thread;
    });

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new Watcher());
    }

    /** Notices a long jump and checks the chunks round the player twice after it. */
    public static final class Watcher {
        private double lastX = Double.NaN;
        private double lastZ = Double.NaN;
        private int lastDimension;
        private long jumpedAt;
        private double jumpBlocks;
        private int checksDone;
        private boolean resentOnce;

        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || mc.world == null) {
                lastX = Double.NaN;
                jumpedAt = 0L;
                return;
            }
            double x = mc.player.posX;
            double z = mc.player.posZ;
            int dimension = mc.player.dimension;
            if (!Double.isNaN(lastX)) {
                double moved = Math.sqrt((x - lastX) * (x - lastX) + (z - lastZ) * (z - lastZ));
                if (moved >= JUMP_BLOCKS || dimension != lastDimension) {
                    jumpedAt = System.currentTimeMillis();
                    jumpBlocks = moved;
                    checksDone = 0;
                    resentOnce = false;
                }
            }
            lastX = x;
            lastZ = z;
            lastDimension = dimension;
            if (jumpedAt == 0L || checksDone >= 2) {
                return;
            }
            long since = System.currentTimeMillis() - jumpedAt;
            if (since < (checksDone == 0 ? FIRST_CHECK_MILLIS : SECOND_CHECK_MILLIS)) {
                return;
            }
            checksDone++;
            afterJump(mc, since);
        }

        private void afterJump(Minecraft mc, long since) {
            Report report = check(mc, CHECK_RADIUS);
            boolean heal = McmcpConfig.isHealChunksAfterTeleport();
            JsonObject json = report.toJson();
            json.addProperty("teleportBlocks", Math.round(jumpBlocks));
            json.addProperty("secondsAfter", since / 1000L);
            json.addProperty("at", System.currentTimeMillis());
            String action = "none";
            if (heal && report.needsRebuild()) {
                rebuild(mc);
                action = "rebuilt chunk meshes (F3+A)";
            }
            if (heal && report.absent > 0 && !resentOnce && mc.getIntegratedServer() == null && mc.getSession() != null) {
                resentOnce = true;
                final String player = mc.getSession().getUsername();
                final int bx = MathHelper.floor(mc.player.posX);
                final int bz = MathHelper.floor(mc.player.posZ);
                RESENDS.execute(() -> {
                    JsonObject resent = requestResend(player, bx, bz, CHECK_RADIUS, null);
                    Mcmcp.LOGGER.info("MCMCP chunk check: asked the server to resend the chunks round " + player
                        + (resent == null ? "; it could not (no companion access, or it refused)"
                        : ": " + Json.getInt(resent, "resent", 0) + " resent"));
                });
                action = "none".equals(action) ? "asked the server to resend the absent chunks"
                    : action + "; asked the server to resend the absent chunks";
            }
            json.addProperty("action", action);
            lastTeleportCheck = json;
            String line = "MCMCP chunk check " + since / 1000L + " s after a " + Math.round(jumpBlocks)
                + "-block jump: " + report.absent + " of " + report.columns + " chunks absent, " + report.unbuilt
                + " of " + report.sections + " sections unbuilt, " + report.queued + " queued; " + action;
            if (report.healthy()) {
                Mcmcp.LOGGER.debug(line);
            }
            else {
                Mcmcp.LOGGER.info(line);
            }
        }
    }
}
