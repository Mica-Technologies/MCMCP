package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpContent;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.tools.BlockPatterns;
import com.micatechnologies.minecraft.mcmcp.tools.GameJson;
import com.micatechnologies.minecraft.mcmcp.tools.MapImage;
import com.micatechnologies.minecraft.mcmcp.tools.ScreenshotImages;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * A top-down picture of the chunks this client holds.
 *
 * <p>An agent learning a large server map had three views, none of them a plan: a screenshot sees
 * only what the camera faces and nothing at night, a heightmap says how tall but not what, and the
 * server's web map is stale or unreachable (#39). This draws what the client holds as a vanilla map
 * would, with a height cut so it doubles as a floor-plan tool, and highlights for the blocks being
 * looked for.
 *
 * <p>Sampling touches the world, so it runs on the client thread, in strips of rows so one call
 * never holds a frame for long. Everything after that — shading, grid, labels, encoding — is done on
 * the worker by {@link MapImage}.
 */
@SideOnly(Side.CLIENT)
public final class ClientMapTools {

    private static final int DEFAULT_RADIUS = 128;
    private static final int MAX_RADIUS = 1024;
    private static final int MAX_SCALE = 16;
    private static final int DEFAULT_MAX_DIMENSION = 1024;

    /** Most pixels a block is drawn as, for a small area. Past this a map is only bigger. */
    private static final int MAX_PIXELS_PER_BLOCK = 8;

    /** Columns sampled per hop to the client thread; a few milliseconds of one frame. */
    private static final int SAMPLES_PER_HOP = 65_536;

    /** How many of the commonest top blocks the result names. */
    private static final int TOP_BLOCKS_LISTED = 12;

    /** Unloaded chunks named in the result; the count beyond this stays exact. */
    private static final int MAX_LISTED_CHUNKS = 64;

    private ClientMapTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("client_render_map")
            .title("Render a map")
            .description("Draw a top-down map of the chunks this client holds and return it as an "
                + "image, on any server: each column in its top block's vanilla map colour, shaded by "
                + "height, with a coordinate grid. Use it to learn the layout of an area — roads, "
                + "districts, where a structure is — far faster than screenshots or heightmaps, and at "
                + "any time of day.\n\n"
                + "'y_max' cuts away everything above a height: set it just under a roof or a deck "
                + "to see a floor plan or what is below. 'highlight' paints blocks matching any of its "
                + "patterns magenta ('*road*', 'minecraft:wool:14'). Unloaded chunks are hatched grey "
                + "and listed; the player is a red cross.\n\n"
                + "The image costs about width x height / 750 tokens; 'max_dimension' caps its long "
                + "edge, and 'scale' (blocks per pixel) is chosen to fit unless you set it. The PNG is "
                + "also saved under screenshots/.")
            .schema(JsonSchema.object()
                .integer("x", "Block X of the centre, or of one corner with toX. Defaults to the "
                    + "player's.")
                .integer("z", "Block Z of the centre, or of one corner with toZ. Defaults to the "
                    + "player's.")
                .integer("radius", "Blocks from the centre to each edge. Default 128.", 8, MAX_RADIUS)
                .integer("toX", "X of the opposite corner, for a box instead of a radius.")
                .integer("toZ", "Z of the opposite corner, for a box instead of a radius.")
                .integer("scale", "Blocks per pixel, 1-16. Defaults to the smallest that fits "
                    + "max_dimension.", 1, MAX_SCALE)
                .integer("y_max", "Ignore everything above this height. Default 255.", 0, 255)
                .stringArray("highlight", "Block patterns to paint magenta, as in client_get_blocks.")
                .integer("grid", "Blocks between grid lines, labelled with their coordinate. Default "
                    + "chosen for about eight lines; 0 for none.", 0, 4096)
                .integer("max_dimension", "Longest edge of the image in pixels. Default 1024.",
                    ScreenshotImages.MIN_MAX_DIMENSION, ScreenshotImages.PROVIDER_CEILING)
                .bool("inline", "Include the image in the response. Default true; false returns only "
                    + "the saved file's path.")
                .build())
            .clientOnly()
            .readOnly()
            .handler(context -> {
                if (!McmcpConfig.isAllowScreenshots()) {
                    return ToolResult.error("Map images are disabled by permissions.allowScreenshots "
                        + "in the MCMCP config.");
                }
                final boolean box = context.has("toX") || context.has("toZ");
                if (box && !(context.has("x") && context.has("z") && context.has("toX")
                    && context.has("toZ"))) {
                    return ToolResult.error("A box needs all of x, z, toX and toZ. Nothing was drawn.");
                }
                if (box && context.has("radius")) {
                    return ToolResult.error("Give either a radius or a box (toX, toZ), not both. "
                        + "Nothing was drawn.");
                }
                final int radius = context.getBoundedInt("radius", DEFAULT_RADIUS, 8, MAX_RADIUS);
                final int yMax = context.getBoundedInt("y_max", 255, 0, 255);
                final int maxDimension = context.getBoundedInt("max_dimension", DEFAULT_MAX_DIMENSION,
                    ScreenshotImages.MIN_MAX_DIMENSION, ScreenshotImages.PROVIDER_CEILING);
                final boolean inline = context.getBoolean("inline", true);
                final BlockPatterns highlight = BlockPatterns.of(
                    Json.getStringList(context.getArguments(), "highlight"));

                // Where the player is, resolved once: the map is of one area, and following the
                // player between strips would tear it.
                final JsonObject origin = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        Minecraft mc = ClientStateTools.requireInWorld();
                        BlockPos player = GameJson.blockPosOf(mc.player);
                        JsonObject json = new JsonObject();
                        json.addProperty("x", player.getX());
                        json.addProperty("z", player.getZ());
                        json.addProperty("world", System.identityHashCode(mc.world));
                        return json;
                    }
                });
                final int playerX = origin.get("x").getAsInt();
                final int playerZ = origin.get("z").getAsInt();
                final int worldIdentity = origin.get("world").getAsInt();

                int x1;
                int z1;
                int x2;
                int z2;
                if (box) {
                    x1 = context.getInt("x", 0);
                    z1 = context.getInt("z", 0);
                    x2 = context.getInt("toX", 0);
                    z2 = context.getInt("toZ", 0);
                }
                else {
                    int centreX = context.has("x") ? context.getInt("x", 0) : playerX;
                    int centreZ = context.has("z") ? context.getInt("z", 0) : playerZ;
                    x1 = centreX - radius;
                    z1 = centreZ - radius;
                    x2 = centreX + radius;
                    z2 = centreZ + radius;
                }
                final int minX = Math.min(x1, x2);
                final int minZ = Math.min(z1, z2);
                final int spanX = Math.abs(x2 - x1) + 1;
                final int spanZ = Math.abs(z2 - z1) + 1;
                if (Math.max(spanX, spanZ) > 2 * MAX_RADIUS + 1) {
                    return ToolResult.error("That box is " + spanX + " x " + spanZ + " blocks; a map "
                        + "covers at most " + (2 * MAX_RADIUS + 1) + " on a side, which is already "
                        + "past any client's render distance. Nothing was drawn.");
                }

                int fitting = Math.max(1, (Math.max(spanX, spanZ) + maxDimension - 1) / maxDimension);
                int scale = context.has("scale") ? context.getBoundedInt("scale", 1, 1, MAX_SCALE) : fitting;
                String scaleNote = null;
                if (scale < fitting) {
                    scaleNote = "scale " + scale + " would be wider than max_dimension " + maxDimension
                        + "; used " + fitting + ".";
                    scale = fitting;
                }
                if (scale > MAX_SCALE) {
                    return ToolResult.error("That area needs more than " + MAX_SCALE + " blocks per "
                        + "pixel to fit max_dimension " + maxDimension + ". Raise max_dimension or draw "
                        + "a smaller area.");
                }
                final int pixelsX = (spanX + scale - 1) / scale;
                final int pixelsZ = (spanZ + scale - 1) / scale;
                // A small area is drawn several pixels to a block, up to max_dimension: at one
                // pixel a block, a 129-block map is too small to read and has no room for labels.
                final int pixelsPerBlock = scale == 1
                    ? Math.max(1, Math.min(MAX_PIXELS_PER_BLOCK, maxDimension / Math.max(pixelsX, pixelsZ)))
                    : 1;
                final int grid = context.has("grid") ? context.getBoundedInt("grid", 0, 0, 4096)
                    : autoGrid(Math.max(spanX, spanZ));

                final MapImage.Columns columns = new MapImage.Columns(pixelsX, pixelsZ);
                final Sampler sampler = new Sampler(minX, minZ, scale, yMax, highlight);
                int rowsPerHop = Math.max(1, SAMPLES_PER_HOP / pixelsX);
                for (int startRow = 0; startRow < pixelsZ; startRow += rowsPerHop) {
                    final int from = startRow;
                    final int to = Math.min(pixelsZ, startRow + rowsPerHop);
                    Boolean sameWorld = context.onGameThread(new Callable<Boolean>() {
                        @Override
                        public Boolean call() {
                            Minecraft mc = ClientStateTools.requireInWorld();
                            if (System.identityHashCode(mc.world) != worldIdentity) {
                                return false;
                            }
                            sampler.sampleRows(mc.world, columns, from, to);
                            return true;
                        }
                    });
                    if (!sameWorld) {
                        return ToolResult.error("The client changed world while the map was being "
                            + "drawn. Nothing was saved; draw it again.");
                    }
                    context.reportProgress(to, pixelsZ, "rows drawn");
                }

                Integer markerX = playerX >= minX && playerX < minX + spanX ? playerX : null;
                Integer markerZ = playerZ >= minZ && playerZ < minZ + spanZ ? playerZ : null;
                BufferedImage image = MapImage.render(columns, minX, minZ, scale, pixelsPerBlock, grid,
                    markerX == null || markerZ == null ? null : markerX,
                    markerX == null || markerZ == null ? null : markerZ);
                byte[] png = ScreenshotImages.toPng(image);

                String fileName = "mcmcp-map-" + System.currentTimeMillis() + ".png";
                File file = new File(new File(McmcpPaths.gameDirectory(), "screenshots"), fileName);
                file.getParentFile().mkdirs();
                Files.write(file.toPath(), png);

                JsonObject structured = new JsonObject();
                JsonObject fromCorner = new JsonObject();
                fromCorner.addProperty("x", minX);
                fromCorner.addProperty("z", minZ);
                JsonObject toCorner = new JsonObject();
                toCorner.addProperty("x", minX + spanX - 1);
                toCorner.addProperty("z", minZ + spanZ - 1);
                structured.add("from", fromCorner);
                structured.add("to", toCorner);
                structured.addProperty("scale", scale);
                structured.addProperty("pixelsPerBlock", pixelsPerBlock);
                structured.addProperty("layout", "pixel (px, py) is block x = from.x + floor(px / "
                    + "pixelsPerBlock) * scale, z likewise from py; north is up");
                structured.addProperty("width", pixelsX * pixelsPerBlock);
                structured.addProperty("height", pixelsZ * pixelsPerBlock);
                structured.addProperty("yMax", yMax);
                structured.addProperty("grid", grid);
                structured.add("topBlocks", sampler.topBlocks());
                if (!highlight.isEmpty()) {
                    structured.addProperty("highlightedColumns", sampler.highlighted);
                }
                if (sampler.unloadedCount > 0) {
                    structured.addProperty("unloadedChunks", sampler.unloadedCount);
                    structured.add("unloadedChunkList", sampler.unloadedList);
                }
                if (scaleNote != null) {
                    structured.addProperty("note", scaleNote);
                }
                structured.addProperty("path", file.getAbsolutePath());
                String uri = McmcpConstants.RESOURCE_SCHEME + "://client/screenshot/" + fileName;
                structured.addProperty("resourceUri", uri);
                structured.addProperty("approximateImageTokens",
                    ScreenshotImages.approximateTokens(image.getWidth(), image.getHeight()));

                ToolResult result = ToolResult.text("Map of " + spanX + " x " + spanZ + " blocks at "
                        + (pixelsPerBlock > 1 ? pixelsPerBlock + " pixels per block"
                            : scale + " block(s) per pixel")
                        + ", saved to " + file.getAbsolutePath()
                        + (sampler.unloadedCount > 0
                            ? ". " + sampler.unloadedCount + " chunk(s) are not loaded on this client "
                                + "and are hatched grey."
                            : "."))
                    .withStructured(structured)
                    .withContent(McpContent.resourceLink(uri, fileName,
                        "Top-down map rendered from the Minecraft client.", "image/png"));
                if (inline) {
                    result.withContent(McpContent.image(png, "image/png"));
                }
                return result;
            })
            .build());
    }

    /** Grid spacing giving about eight lines across the larger side, as a round number. */
    static int autoGrid(int span) {
        int[] steps = {16, 32, 64, 128, 256, 512, 1024};
        for (int step : steps) {
            if (span / step <= 8) {
                return step;
            }
        }
        return 2048;
    }

    /** Reads the columns. Its methods run on the client thread; its results are plain values. */
    private static final class Sampler {

        private final int minX;
        private final int minZ;
        private final int scale;
        private final int yMax;
        private final BlockPatterns highlight;

        /** Building an id string per block is most of the cost; there are few states. */
        private final Map<IBlockState, String> ids = new IdentityHashMap<>();
        private final Map<String, int[]> topCounts = new HashMap<>();
        private final Map<Long, Boolean> unloadedSeen = new HashMap<>();
        final JsonArray unloadedList = new JsonArray();
        int unloadedCount;
        int highlighted;

        Sampler(int minX, int minZ, int scale, int yMax, BlockPatterns highlight) {
            this.minX = minX;
            this.minZ = minZ;
            this.scale = scale;
            this.yMax = yMax;
            this.highlight = highlight;
        }

        void sampleRows(World world, MapImage.Columns columns, int fromRow, int toRow) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int pz = fromRow; pz < toRow; pz++) {
                // The middle of the pixel's block square, so a scaled map is not biased north-west.
                int z = minZ + pz * scale + scale / 2;
                for (int px = 0; px < columns.width(); px++) {
                    int x = minX + px * scale + scale / 2;
                    Chunk chunk = world.getChunkProvider().getLoadedChunk(x >> 4, z >> 4);
                    if (chunk == null || chunk.isEmpty()) {
                        columns.setUnloaded(px, pz);
                        noteUnloaded(x >> 4, z >> 4);
                        continue;
                    }
                    sampleColumn(world, chunk, columns, px, pz, x, z, pos);
                }
            }
        }

        private void sampleColumn(World world, Chunk chunk, MapImage.Columns columns, int px, int pz,
                                  int x, int z, BlockPos.MutableBlockPos pos) {
            int top = Math.min(yMax, chunk.getTopFilledSegment() + 15);
            for (int y = top; y >= 0; y--) {
                IBlockState state = chunk.getBlockState(x, y, z);
                if (state.getMaterial() == Material.AIR) {
                    continue;
                }
                String id = idOf(state);
                if (!highlight.isEmpty() && highlight.matches(id)) {
                    columns.set(px, pz, MapImage.HIGHLIGHT_RGB, y, 0, true);
                    count(id);
                    highlighted++;
                    return;
                }
                pos.setPos(x, y, z);
                MapColor colour = state.getMapColor(world, pos);
                if (colour == MapColor.AIR) {
                    // Glass, torches, flowers: vanilla maps look through them, and so does this.
                    continue;
                }
                int depth = 0;
                if (state.getMaterial().isLiquid()) {
                    while (depth < 32 && y - depth >= 0
                        && chunk.getBlockState(x, y - depth, z).getMaterial().isLiquid()) {
                        depth++;
                    }
                }
                columns.set(px, pz, colour.colorValue, y, depth, false);
                count(id);
                return;
            }
            columns.setEmpty(px, pz);
        }

        private String idOf(IBlockState state) {
            String id = ids.get(state);
            if (id == null) {
                ResourceLocation name = state.getBlock().getRegistryName();
                id = name == null ? "unknown" : name.toString();
                int metadata = state.getBlock().getMetaFromState(state);
                if (metadata != 0) {
                    id = id + ":" + metadata;
                }
                ids.put(state, id);
            }
            return id;
        }

        private void count(String id) {
            int[] count = topCounts.get(id);
            if (count == null) {
                topCounts.put(id, new int[]{1});
            }
            else {
                count[0]++;
            }
        }

        private void noteUnloaded(int chunkX, int chunkZ) {
            long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
            if (unloadedSeen.put(key, Boolean.TRUE) != null) {
                return;
            }
            unloadedCount++;
            if (unloadedList.size() < MAX_LISTED_CHUNKS) {
                JsonArray pair = new JsonArray();
                pair.add(chunkX);
                pair.add(chunkZ);
                unloadedList.add(pair);
            }
        }

        /** The commonest top blocks, most common first, as sampled columns. */
        JsonObject topBlocks() {
            List<Map.Entry<String, int[]>> entries = new ArrayList<>(topCounts.entrySet());
            Collections.sort(entries, (a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
            JsonObject json = new JsonObject();
            for (int i = 0; i < Math.min(TOP_BLOCKS_LISTED, entries.size()); i++) {
                json.addProperty(entries.get(i).getKey(), entries.get(i).getValue()[0]);
            }
            return json;
        }
    }
}
