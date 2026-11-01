package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.BiomeSurvey;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.gen.structure.MapGenVillage;

/**
 * Which biomes cover an area (#175), without loading a chunk.
 *
 * <p>A survey used to be one {@code server_get_block} per sample: 1,024 round trips and chunk loads to
 * ask why no village generated in a pregenerated square. Every sample here comes from the cheapest
 * place that knows it: a loaded chunk's own biome array; a generated chunk's array as saved in its
 * region file, read off the server thread (region file reads are synchronized, and a missing region
 * file is never created); or, for a column not generated yet, the world's biome provider, which is
 * what generation will use, flagged {@code predicted}. Nothing is loaded, generated or written.
 *
 * <p>The provider is not thread-safe (its gen layers share a cache), so predictions run on the server
 * thread, a batch per task.
 */
public final class ServerBiomeTools {

    /** Chunks looked up, or columns predicted, in one server-thread task. */
    private static final int BATCH = 1024;

    private ServerBiomeTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_get_biomes")
            .title("Survey biomes over an area")
            .description("Which biomes cover a box of columns, sampled every 'step' blocks, without loading or "
                + "generating anything. Each sample comes from a loaded chunk, a generated chunk's saved data, "
                + "or, where nothing is generated yet, the world's biome provider (counted as 'predicted'). "
                + "'summary' (default): each biome with its count and share. 'grid': a palette and a flat index "
                + "array, rows along X from the lowest Z, plus one source letter per sample (L loaded, S saved, "
                + "P predicted). 'match' (biome ids) or villageBiomes: true adds the share that is one of those, "
                + "such as the biomes a village may start in. Read-only.")
            .schema(JsonSchema.object()
                .integer("x", "X of one corner.")
                .integer("z", "Z of one corner.")
                .integer("toX", "X of the opposite corner. Default x.")
                .integer("toZ", "Z of the opposite corner. Default z.")
                .integer("step", "Blocks between samples. Default: what keeps the box near "
                    + BiomeSurvey.DEFAULT_SAMPLES + " samples.", 1, 4096)
                .integer("dimension", "Dimension id. Default 0.")
                .enumeration("format", "'summary' (default) or 'grid'.", "summary", "grid")
                .bool("predict", "Ask the biome provider for columns not generated yet. Default true; false "
                    + "leaves them unknown.")
                .array("match", "Biome ids to report the share of, such as [\"minecraft:plains\"].",
                    Json.obj("type", "string"))
                .bool("villageBiomes", "Report the share in the biomes a village may start in, as this game "
                    + "has them (vanilla's list plus any a mod added).")
                .required("x", "z")
                .build())
            .serverOnly()
            .readOnly()
            .handler(ServerBiomeTools::survey)
            .build());
    }

    private static ToolResult survey(final ToolContext context) throws Exception {
        final int x1 = context.requireInt("x");
        final int z1 = context.requireInt("z");
        final int x2 = context.getInt("toX", x1);
        final int z2 = context.getInt("toZ", z1);
        long width = Math.abs((long) x2 - x1) + 1;
        long depth = Math.abs((long) z2 - z1) + 1;
        final int step = context.has("step") ? context.getBoundedInt("step", 1, 1, 4096)
            : BiomeSurvey.defaultStep(width, depth);
        final boolean grid = "grid".equals(context.getString("format", "summary"));
        final boolean predict = context.getBoolean("predict", true);
        final int dimension = context.getInt("dimension", 0);
        final List<String> match = Json.getStringList(context.getArguments(), "match");
        final boolean villages = context.getBoolean("villageBiomes", false);

        long samples = ((width - 1) / step + 1) * ((depth - 1) / step + 1);
        int cap = grid ? BiomeSurvey.MAX_GRID_SAMPLES : BiomeSurvey.MAX_SAMPLES;
        if (samples > cap) {
            int needed = (int) Math.ceil(Math.sqrt((double) width * depth / cap));
            return ToolResult.error("That is " + samples + " samples at step " + step + "; a " + (grid ? "grid" : "summary")
                + " reads at most " + cap + ". Use step " + needed + " or more" + (grid ? ", or format 'summary'" : "")
                + ". Nothing was read.");
        }
        final BiomeSurvey survey = new BiomeSurvey(BiomeSurvey.axis(x1, x2, step), BiomeSurvey.axis(z1, z2, step));

        // Samples by chunk, so each chunk is looked up once.
        final Map<Long, List<Integer>> byChunk = new LinkedHashMap<>();
        for (int index = 0; index < survey.size(); index++) {
            long key = chunkKey(survey.x(index) >> 4, survey.z(index) >> 4);
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(index);
        }
        final List<Long> chunks = new ArrayList<>(byChunk.keySet());
        context.reportProgress(0, 3, "reading " + chunks.size() + " chunk(s)");

        // 1. Loaded chunks, from their own arrays. The rest are noted for the disk.
        final List<Long> unloaded = new ArrayList<>();
        File saveDir = null;
        for (int start = 0; start < chunks.size(); start += BATCH) {
            final int from = start;
            File dir = context.onGameThread(() -> {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                for (int i = from; i < Math.min(chunks.size(), from + BATCH); i++) {
                    long key = chunks.get(i);
                    Chunk chunk = world.getChunkProvider().getLoadedChunk(chunkX(key), chunkZ(key));
                    if (chunk == null) {
                        unloaded.add(key);
                        continue;
                    }
                    byte[] array = chunk.getBiomeArray();
                    for (int index : byChunk.get(key)) {
                        int id = array[((survey.z(index) & 15) << 4) | (survey.x(index) & 15)] & 255;
                        if (id != 255) {
                            survey.set(index, id, BiomeSurvey.LOADED);
                        }
                    }
                }
                return world.getChunkSaveLocation();
            });
            saveDir = dir;
        }

        // 2. Generated chunks that are not loaded, from their saved arrays. Off the server thread.
        context.reportProgress(1, 3, "reading " + unloaded.size() + " saved chunk(s)");
        int unreadable = 0;
        if (saveDir != null) {
            for (long key : unloaded) {
                context.getCancellation().throwIfCancelled();
                byte[] array;
                try {
                    array = savedBiomes(saveDir, chunkX(key), chunkZ(key));
                }
                catch (IOException | RuntimeException e) {
                    unreadable++;
                    continue;
                }
                if (array == null || array.length != 256) {
                    continue;
                }
                for (int index : byChunk.get(key)) {
                    int id = array[((survey.z(index) & 15) << 4) | (survey.x(index) & 15)] & 255;
                    if (id != 255) {
                        survey.set(index, id, BiomeSurvey.SAVED);
                    }
                }
            }
        }

        // 3. The rest from the biome provider, on the server thread a batch at a time; then the names.
        final List<Integer> missing = new ArrayList<>();
        for (int index = 0; index < survey.size(); index++) {
            if (!survey.known(index)) {
                missing.add(index);
            }
        }
        context.reportProgress(2, 3, (predict ? "predicting " : "skipping ") + missing.size() + " column(s)");
        if (predict) {
            for (int start = 0; start < missing.size(); start += BATCH) {
                final int from = start;
                context.onGameThread(() -> {
                    BiomeProvider provider = ServerWorldTools.requireWorld(dimension).getBiomeProvider();
                    for (int i = from; i < Math.min(missing.size(), from + BATCH); i++) {
                        int index = missing.get(i);
                        Biome biome = provider.getBiome(new BlockPos(survey.x(index), 64, survey.z(index)));
                        if (biome != null) {
                            survey.set(index, Biome.getIdForBiome(biome), BiomeSurvey.PREDICTED);
                        }
                    }
                    return null;
                });
            }
        }

        final List<String> unknownMatch = new ArrayList<>();
        final List<Integer> matchIds = new ArrayList<>();
        final List<Integer> villageIds = new ArrayList<>();
        Map<Integer, String> names = context.onGameThread(() -> {
            Map<Integer, String> found = new HashMap<>();
            for (int id : survey.distinctIds()) {
                found.put(id, nameOf(Biome.getBiome(id)));
            }
            for (String wanted : match) {
                Biome biome = Biome.REGISTRY.getObject(new ResourceLocation(wanted.trim()));
                if (biome == null) {
                    unknownMatch.add(wanted);
                }
                else {
                    matchIds.add(Biome.getIdForBiome(biome));
                }
            }
            if (villages) {
                for (Biome biome : MapGenVillage.VILLAGE_SPAWN_BIOMES) {
                    villageIds.add(Biome.getIdForBiome(biome));
                    found.putIfAbsent(Biome.getIdForBiome(biome), nameOf(biome));
                }
            }
            return found;
        });
        if (!unknownMatch.isEmpty()) {
            return ToolResult.error("No biome is registered as " + unknownMatch + ". Biome ids look like "
                + "minecraft:plains or biomesoplenty:grassland.");
        }

        JsonObject json = new JsonObject();
        JsonArray box = new JsonArray();
        box.add(Math.min(x1, x2));
        box.add(Math.min(z1, z2));
        box.add(Math.max(x1, x2));
        box.add(Math.max(z1, z2));
        json.add("box", box);
        json.addProperty("step", step);
        json.addProperty("samples", survey.size());
        json.add("sources", survey.sources());
        if (unreadable > 0) {
            json.addProperty("unreadableChunks", unreadable);
        }
        if (grid) {
            json.add("grid", survey.grid(names));
        }
        else {
            json.add("biomes", survey.tally(names));
        }
        if (!matchIds.isEmpty()) {
            json.add("match", survey.matching(matchIds));
        }
        if (villages) {
            JsonObject village = survey.matching(villageIds);
            JsonArray list = new JsonArray();
            for (int id : villageIds) {
                list.add(names.get(id));
            }
            village.add("biomes", list);
            json.add("villageBiomes", village);
        }
        return ToolResult.structured(json);
    }

    /**
     * A generated chunk's saved biome array, or null when it was never saved. Never creates a region
     * file: {@code getChunkInputStream} would, for a region nothing has generated in.
     */
    private static byte[] savedBiomes(File saveDir, int chunkX, int chunkZ) throws IOException {
        RegionFile region = RegionFileCache.getRegionFileIfExists(saveDir, chunkX, chunkZ);
        if (region == null || !region.isChunkSaved(chunkX & 31, chunkZ & 31)) {
            return null;
        }
        try (DataInputStream in = region.getChunkDataInputStream(chunkX & 31, chunkZ & 31)) {
            if (in == null) {
                return null;
            }
            NBTTagCompound root = CompressedStreamTools.read(in);
            NBTTagCompound level = root.getCompoundTag("Level");
            return level.hasKey("Biomes", 7) ? level.getByteArray("Biomes") : null;
        }
    }

    private static String nameOf(Biome biome) {
        if (biome == null) {
            return "?";
        }
        ResourceLocation id = biome.getRegistryName();
        return id == null ? "?" : id.toString();
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }
}
