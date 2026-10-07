package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

/**
 * What a block write replaced, kept so the write can be put back.
 *
 * <h2>Why</h2>
 *
 * An agent clearing the wrong region, or filling with the wrong block, used to be a mistake only a
 * backup could fix. Each {@code server_set_blocks} call now leaves an undo point: every block it
 * changed, what that block was before (with its tile-entity data), and what was written. A restore
 * puts the old blocks back, and refuses — unless forced — where something has changed them since,
 * because restoring over a player's later work is a second mistake, not a fix.
 *
 * <h2>Cost</h2>
 *
 * Recorded as the write happens, one block at a time, for only the blocks that changed: there is no
 * snapshot pass over the region, and a fill that changes nothing records nothing. The record is a
 * palette of states plus int arrays, kept as gzipped NBT in the world's {@code mcmcp-undo} folder,
 * with a small JSON file beside it for listing and, for a batch, before and after maps.
 *
 * <p>World state is touched only by {@link Recorder} methods and {@link #resolveStates}, both on the
 * game thread. Everything else is files.
 */
public final class UndoPoints {

    /** The folder, inside a world's save folder, that holds its undo points. */
    public static final String FOLDER = "mcmcp-undo";

    /** The largest area, in columns, drawn as a before/after map. Beyond it there are no images. */
    private static final int MAX_IMAGE_COLUMNS = 512;

    /** The long edge of a before/after map, in pixels. */
    private static final int IMAGE_EDGE = 256;

    private static final AtomicLong LAST_ID = new AtomicLong();

    private UndoPoints() {
    }

    /** The folder holding a world's undo points. */
    public static File folder(World world) {
        return new File(world.getSaveHandler().getWorldDirectory(), FOLDER);
    }

    /** A new id: the time in milliseconds, made unique within this process. Sorts by age. */
    public static String newId() {
        long now = System.currentTimeMillis();
        long id = LAST_ID.updateAndGet(last -> Math.max(last + 1, now));
        return "u" + id;
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    /** Collects what a write changes, block by block. Game thread only. */
    public static final class Recorder {

        private final Map<IBlockState, Integer> palette = new IdentityHashMap<>();
        private final List<IBlockState> states = new ArrayList<>();
        private int[] positions = new int[96];
        private int[] before = new int[32];
        private int[] after = new int[32];
        private final NBTTagList tileEntities = new NBTTagList();
        private int count;
        private int minX = Integer.MAX_VALUE;
        private int minY = Integer.MAX_VALUE;
        private int minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private int maxZ = Integer.MIN_VALUE;

        /** What is at {@code pos} now, to pass to {@link #record} once the write is done. */
        public Before capture(World world, BlockPos pos) {
            return new Before(world.getBlockState(pos), tileEntityNbt(world, pos));
        }

        /** Records one block the write changed. */
        public void record(BlockPos pos, Before was, IBlockState now) {
            if (count == before.length) {
                before = Arrays.copyOf(before, count * 2);
                after = Arrays.copyOf(after, count * 2);
                positions = Arrays.copyOf(positions, count * 6);
            }
            positions[count * 3] = pos.getX();
            positions[count * 3 + 1] = pos.getY();
            positions[count * 3 + 2] = pos.getZ();
            before[count] = index(was.state);
            after[count] = index(now);
            if (was.tileEntity != null) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setInteger("i", count);
                entry.setTag("nbt", was.tileEntity);
                tileEntities.appendTag(entry);
            }
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
            count++;
        }

        public int count() {
            return count;
        }

        private int index(IBlockState state) {
            Integer index = palette.get(state);
            if (index == null) {
                index = states.size();
                palette.put(state, index);
                states.add(state);
            }
            return index;
        }

        /** The record as plain NBT, safe to hand off the game thread. */
        public NBTTagCompound toNbt() {
            NBTTagList paletteTag = new NBTTagList();
            for (IBlockState state : states) {
                ResourceLocation name = state.getBlock().getRegistryName();
                paletteTag.appendTag(new NBTTagString((name == null ? "minecraft:air" : name.toString())
                    + "#" + state.getBlock().getMetaFromState(state)));
            }
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("version", 1);
            tag.setTag("palette", paletteTag);
            tag.setIntArray("positions", Arrays.copyOf(positions, count * 3));
            tag.setIntArray("before", Arrays.copyOf(before, count));
            tag.setIntArray("after", Arrays.copyOf(after, count));
            tag.setTag("tileEntities", tileEntities);
            return tag;
        }

        /** {from, to} of what was recorded, or null when nothing was. */
        @Nullable
        public JsonObject bounds() {
            if (count == 0) {
                return null;
            }
            JsonObject json = new JsonObject();
            json.add("from", GameJson.blockPos(new BlockPos(minX, minY, minZ)));
            json.add("to", GameJson.blockPos(new BlockPos(maxX, maxY, maxZ)));
            return json;
        }
    }

    /** A block as it was before a write. */
    public static final class Before {

        final IBlockState state;
        @Nullable
        final NBTTagCompound tileEntity;

        Before(IBlockState state, @Nullable NBTTagCompound tileEntity) {
            this.state = state;
            this.tileEntity = tileEntity;
        }
    }

    @Nullable
    private static NBTTagCompound tileEntityNbt(World world, BlockPos pos) {
        TileEntity tileEntity = world.getTileEntity(pos);
        return tileEntity == null ? null : tileEntity.writeToNBT(new NBTTagCompound());
    }

    // ------------------------------------------------------------------
    // Storing
    // ------------------------------------------------------------------

    /**
     * Writes an undo point and prunes the oldest beyond {@code undo.maxUndoPoints}. Off the game
     * thread: {@code record} is plain NBT by now.
     *
     * @param meta id, tool, dimension, blocks, from/to and anything else to list it by
     */
    public static void save(File folder, JsonObject meta, NBTTagCompound record,
                            @Nullable BufferedImage beforeImage, @Nullable BufferedImage afterImage)
        throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("could not create " + folder);
        }
        String id = meta.get("id").getAsString();
        try (OutputStream out = new FileOutputStream(new File(folder, id + ".dat"))) {
            CompressedStreamTools.writeCompressed(record, out);
        }
        if (beforeImage != null) {
            File file = new File(folder, id + "-before.png");
            Files.write(file.toPath(), ScreenshotImages.toPng(beforeImage));
            meta.addProperty("beforeImage", file.toPath().toAbsolutePath().normalize().toString());
        }
        if (afterImage != null) {
            File file = new File(folder, id + "-after.png");
            Files.write(file.toPath(), ScreenshotImages.toPng(afterImage));
            meta.addProperty("afterImage", file.toPath().toAbsolutePath().normalize().toString());
        }
        // The listing file last: an undo point is listed only once everything it names exists.
        Files.write(new File(folder, id + ".json").toPath(),
            Json.write(meta).getBytes(StandardCharsets.UTF_8));
        prune(folder, McmcpConfig.getMaxUndoPoints());
    }

    /** Every undo point's listing, newest first. */
    public static List<JsonObject> list(File folder) {
        List<JsonObject> points = new ArrayList<>();
        File[] files = folder.listFiles((directory, name) -> name.endsWith(".json"));
        if (files == null) {
            return points;
        }
        for (File file : files) {
            try {
                JsonElement json = Json.parse(new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8));
                if (json != null && json.isJsonObject()) {
                    points.add(json.getAsJsonObject());
                }
            }
            catch (IOException | RuntimeException e) {
                Mcmcp.LOGGER.warn("MCMCP skipped an undo point it could not read: " + file, e);
            }
        }
        points.sort((a, b) -> Json.getString(b, "id", "").compareTo(Json.getString(a, "id", "")));
        return points;
    }

    /** One undo point's listing, or null. Ids are checked, so this reads nothing outside the folder. */
    @Nullable
    public static JsonObject find(File folder, String id) {
        if (!isId(id)) {
            return null;
        }
        for (JsonObject point : list(folder)) {
            if (id.equals(Json.getString(point, "id", ""))) {
                return point;
            }
        }
        return null;
    }

    public static NBTTagCompound read(File folder, String id) throws IOException {
        if (!isId(id)) {
            throw new IOException("not an undo point id: " + id);
        }
        try (InputStream in = new FileInputStream(new File(folder, id + ".dat"))) {
            return CompressedStreamTools.readCompressed(in);
        }
    }

    /** Records in the listing that a point has been restored, and by which new point. */
    public static void markRestored(File folder, String id, String by) throws IOException {
        JsonObject point = find(folder, id);
        if (point == null) {
            return;
        }
        point.addProperty("restoredBy", by);
        Files.write(new File(folder, id + ".json").toPath(),
            Json.write(point).getBytes(StandardCharsets.UTF_8));
    }

    static boolean isId(String id) {
        return id != null && id.matches("u[0-9]{1,19}");
    }

    /** Keeps the newest {@code keep} points. Ids sort by age, so no file is stat'ed. */
    static void prune(File folder, int keep) {
        File[] listings = folder.listFiles((directory, name) -> name.endsWith(".json"));
        if (listings == null || listings.length <= keep) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (File listing : listings) {
            ids.add(listing.getName().substring(0, listing.getName().length() - ".json".length()));
        }
        Collections.sort(ids, (a, b) -> Long.compare(Long.parseLong(a.substring(1)),
            Long.parseLong(b.substring(1))));
        for (String id : ids.subList(0, ids.size() - keep)) {
            for (String suffix : new String[] {".json", ".dat", "-before.png", "-after.png"}) {
                File file = new File(folder, id + suffix);
                if (file.exists() && !file.delete()) {
                    Mcmcp.LOGGER.warn("MCMCP could not delete old undo file " + file);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Restoring
    // ------------------------------------------------------------------

    /** A stored record's palette as block states. Game thread: it looks blocks up in the registry. */
    public static IBlockState[] resolveStates(NBTTagCompound record) {
        NBTTagList palette = record.getTagList("palette", Constants.NBT.TAG_STRING);
        IBlockState[] states = new IBlockState[palette.tagCount()];
        for (int i = 0; i < states.length; i++) {
            String entry = palette.getStringTagAt(i);
            int hash = entry.lastIndexOf('#');
            Block block = Block.getBlockFromName(hash < 0 ? entry : entry.substring(0, hash));
            int meta = hash < 0 ? 0 : Integer.parseInt(entry.substring(hash + 1));
            // A block whose mod has gone restores as air rather than failing the whole restore.
            states[i] = block == null ? net.minecraft.init.Blocks.AIR.getDefaultState()
                : block.getStateFromMeta(meta);
        }
        return states;
    }

    /** Tile-entity data by record index, from a stored record. */
    public static Map<Integer, NBTTagCompound> tileEntities(NBTTagCompound record) {
        Map<Integer, NBTTagCompound> byIndex = new java.util.HashMap<>();
        NBTTagList list = record.getTagList("tileEntities", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            byIndex.put(entry.getInteger("i"), entry.getCompoundTag("nbt"));
        }
        return byIndex;
    }

    // ------------------------------------------------------------------
    // Pictures
    // ------------------------------------------------------------------

    /** The area a before/after map covers, or null when the write is too spread out to draw. */
    @Nullable
    public static int[] imageArea(int minX, int minZ, int maxX, int maxZ) {
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        if (width > MAX_IMAGE_COLUMNS || depth > MAX_IMAGE_COLUMNS) {
            return null;
        }
        // A margin of a few blocks, so a small build is seen in its surroundings.
        int margin = Math.max(2, Math.max(width, depth) / 8);
        return new int[] {minX - margin, minZ - margin, maxX + margin, maxZ + margin};
    }

    /** Draws a top-down map of an area. Game thread: it samples the world. */
    public static BufferedImage drawArea(World world, int[] area) {
        int width = area[2] - area[0] + 1;
        int depth = area[3] - area[1] + 1;
        int scale = Math.max(1, (int) Math.ceil(Math.max(width, depth) / (double) IMAGE_EDGE));
        int pixelsX = (width + scale - 1) / scale;
        int pixelsZ = (depth + scale - 1) / scale;
        int cell = Math.max(1, IMAGE_EDGE / Math.max(pixelsX, pixelsZ));
        MapImage.Columns columns = new MapImage.Columns(pixelsX, pixelsZ);
        MapSampler sampler = new MapSampler(area[0], area[1], scale, 255,
            BlockPatterns.of(Collections.<String>emptyList()));
        sampler.sampleRows(world, columns, 0, pixelsZ);
        return MapImage.render(columns, area[0], area[1], scale, cell, 0, null, null);
    }

    /** The listing fields every undo point has. */
    public static JsonObject meta(String id, String tool, int dimension, int blocks,
                                  @Nullable JsonObject bounds) {
        JsonObject meta = new JsonObject();
        meta.addProperty("id", id);
        meta.addProperty("tool", tool);
        meta.addProperty("at", System.currentTimeMillis());
        meta.addProperty("dimension", dimension);
        meta.addProperty("blocks", blocks);
        if (bounds != null) {
            meta.add("from", bounds.get("from"));
            meta.add("to", bounds.get("to"));
        }
        return meta;
    }
}
