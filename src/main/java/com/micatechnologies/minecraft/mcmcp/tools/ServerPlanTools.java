package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpContent;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.BuildTarget;
import com.micatechnologies.minecraft.mcmcp.regions.TargetDiff;
import com.micatechnologies.minecraft.mcmcp.regions.TargetStore;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * A floor plan from the server: a top-down render of a box cut at a height, with the cells that differ
 * from a build target painted over it (#138 plan, Phase 3).
 *
 * <p>The builder rendered its plans with its own script into a PNG and read the file back: a script
 * to keep and run, and an image read in full. {@code client_render_map} draws the same kind of picture
 * from a client, but only where that client is. This is drawn by the server, so it needs no player
 * and no render distance, and it can answer "where does it differ" as a picture rather than a list.
 */
public final class ServerPlanTools {

    static final int MAX_SPAN = 2049;
    static final int MAX_SCALE = 16;
    static final int DEFAULT_MAX_DIMENSION = 768;
    private static final int MAX_PIXELS_PER_BLOCK = 8;
    private static final int ROWS_PER_HOP = 64;
    /** What a differing column is painted. */
    static final int DIFF_RGB = 0xE02020;

    private ServerPlanTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("server_plan_view")
            .title("Draw a floor plan")
            .description("Draw a top-down plan of a box on the server and return it as an image: each column in "
                + "its top block's map colour, shaded by height, with a labelled grid. 'y_max' cuts away "
                + "everything above a height: set it just under a storey's ceiling for its floor plan. Give x, z, "
                + "toX, toZ, or 'target' (a build target or pattern: its bounds, and its top as y_max). "
                + "'diffTarget' paints red every column where the world differs from that build target (as "
                + "server_verify judges it, within y_max), over the plan: where a storey is unfinished or "
                + "wrong, in one picture. 'highlight' paints matching blocks magenta. Needs no player and no "
                + "render distance; 'load' reads unloaded chunks. Costs about width x height / 750 tokens: "
                + "'max_dimension' (default " + DEFAULT_MAX_DIMENSION + ") caps the long edge.")
            .schema(ServerChunkTools.loadArgument(JsonSchema.object()
                .integer("x", "X of one corner.")
                .integer("z", "Z of one corner.")
                .integer("toX", "X of the opposite corner.")
                .integer("toZ", "Z of the opposite corner.")
                .string("target", "Instead of a box: a build target or pattern whose bounds are drawn.")
                .string("diffTarget", "A build target or pattern; columns where the world differs are painted red.")
                .integer("y_max", "Ignore everything above this height. Default 255, or the target's top.", 0, 255)
                .integer("y_min", "With diffTarget: ignore differences below this height. Default 0.", 0, 255)
                .integer("scale", "Blocks per pixel, 1-16. Default: the smallest that fits.", 1, MAX_SCALE)
                .integer("grid", "Blocks between grid lines; 0 for none. Default: about eight lines.", 0, 4096)
                .stringArray("highlight", "Block patterns to paint magenta.")
                .integer("max_dimension", "Longest edge in pixels. Default " + DEFAULT_MAX_DIMENSION + ".",
                    ScreenshotImages.MIN_MAX_DIMENSION, ScreenshotImages.PROVIDER_CEILING)
                .integer("dimension", "Dimension id. Default 0, or the target's.")
                .bool("inline", "Include the image in the response. Default true; false returns only the file path."))
                .build())
            .serverOnly()
            .readOnly()
            .handler(ServerPlanTools::plan)
            .build());
    }

    private static ToolResult plan(final ToolContext context) throws Exception {
        TargetStore store = ServerTargetTools.store();
        int x1;
        int z1;
        int x2;
        int z2;
        int yMaxDefault = 255;
        int dimension = context.getInt("dimension", 0);
        if (context.has("target")) {
            String name = context.getString("target", "");
            BuildTarget target = store.union(name);
            int[] bounds = target == null ? null : target.bounds();
            if (bounds == null) {
                return ToolResult.error("No target '" + name + "' with any cells in this world.");
            }
            x1 = bounds[0];
            z1 = bounds[2];
            x2 = bounds[3];
            z2 = bounds[5];
            yMaxDefault = bounds[4];
            dimension = context.has("dimension") ? dimension : target.dimension();
        }
        else if (context.has("x") && context.has("z") && context.has("toX") && context.has("toZ")) {
            x1 = context.getInt("x", 0);
            z1 = context.getInt("z", 0);
            x2 = context.getInt("toX", 0);
            z2 = context.getInt("toZ", 0);
        }
        else {
            return ToolResult.error("Give x, z, toX and toZ, or a 'target'. Nothing was drawn.");
        }
        final int minX = Math.min(x1, x2);
        final int minZ = Math.min(z1, z2);
        final int spanX = Math.abs(x2 - x1) + 1;
        final int spanZ = Math.abs(z2 - z1) + 1;
        if (Math.max(spanX, spanZ) > MAX_SPAN) {
            return ToolResult.error("That box is " + spanX + " x " + spanZ + " blocks; a plan covers at most "
                + MAX_SPAN + " on a side. Nothing was drawn.");
        }
        final int yMax = context.getBoundedInt("y_max", yMaxDefault, 0, 255);
        final int maxDimension = context.getBoundedInt("max_dimension", DEFAULT_MAX_DIMENSION,
            ScreenshotImages.MIN_MAX_DIMENSION, ScreenshotImages.PROVIDER_CEILING);
        int fitting = Math.max(1, (Math.max(spanX, spanZ) + maxDimension - 1) / maxDimension);
        final int scale = Math.max(fitting, context.getBoundedInt("scale", fitting, 1, MAX_SCALE));
        if (scale > MAX_SCALE) {
            return ToolResult.error("That area needs more than " + MAX_SCALE + " blocks per pixel to fit "
                + "max_dimension " + maxDimension + ". Raise max_dimension or draw a smaller area.");
        }
        final int pixelsX = (spanX + scale - 1) / scale;
        final int pixelsZ = (spanZ + scale - 1) / scale;
        final int pixelsPerBlock = scale == 1
            ? Math.max(1, Math.min(MAX_PIXELS_PER_BLOCK, maxDimension / Math.max(pixelsX, pixelsZ))) : 1;
        final int grid = context.has("grid") ? context.getBoundedInt("grid", 0, 0, 4096)
            : autoGrid(Math.max(spanX, spanZ));
        final BlockPatterns highlight = BlockPatterns.of(Json.getStringList(context.getArguments(), "highlight"));
        final int dim = dimension;

        // Differences first, off the target, as a set of columns.
        final Set<Long> differing = new HashSet<>();
        final long[] differCells = {0L};
        final String diffName = context.getString("diffTarget", null);
        final BuildTarget diffTarget = diffName == null ? null : store.union(diffName);
        if (diffName != null && diffTarget == null) {
            return ToolResult.error("No target '" + diffName + "' in this world. Nothing was drawn.");
        }
        final int yMin = context.getBoundedInt("y_min", 0, 0, 255);
        final TargetDiff.Normaliser normaliser = ServerTargetTools.normaliser(context, store);

        return ServerChunkTools.withChunks(context, dim, ChunkKeys.box(minX, minZ, minX + spanX - 1, minZ + spanZ - 1),
            outcome -> {
                if (diffTarget != null) {
                    final Map<String, String> wanted = new HashMap<>();
                    final TargetDiff judge = new TargetDiff(normaliser, 0, java.util.Collections.<int[]>emptyList());
                    final List<Long> keys = diffTarget.sectionKeys();
                    final int[] cursor = {0};
                    while (cursor[0] < keys.size()) {
                        context.onGameThread(() -> {
                            WorldServer world = ServerWorldTools.requireWorld(dim);
                            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                            long started = System.nanoTime();
                            while (cursor[0] < keys.size() && System.nanoTime() - started < 25_000_000L) {
                                diffTarget.forEachCellIn(keys.get(cursor[0]++), (x, y, z, want, protect) -> {
                                    if (y > yMax || y < yMin || x < minX || z < minZ || x >= minX + spanX
                                        || z >= minZ + spanZ) {
                                        return;
                                    }
                                    pos.setPos(x, y, z);
                                    if (!world.isBlockLoaded(pos)) {
                                        return;
                                    }
                                    TargetDiff.Kind kind = judge.add(x, y, z, ServerTargetTools.canonical(want, wanted),
                                        protect, ServerTargetTools.canonical(world.getBlockState(pos)));
                                    if (kind != null && kind != TargetDiff.Kind.MATCH && kind != TargetDiff.Kind.UNLOADED) {
                                        differing.add(((long) x << 32) | (z & 0xFFFFFFFFL));
                                        differCells[0]++;
                                    }
                                });
                            }
                            return null;
                        });
                    }
                }

                final MapImage.Columns columns = new MapImage.Columns(pixelsX, pixelsZ);
                final MapSampler sampler = new MapSampler(minX, minZ, scale, yMax, highlight);
                for (int row = 0; row < pixelsZ; row += ROWS_PER_HOP) {
                    final int from = row;
                    final int to = Math.min(pixelsZ, row + ROWS_PER_HOP);
                    context.onGameThread(() -> {
                        sampler.sampleRows(ServerWorldTools.requireWorld(dim), columns, from, to);
                        return null;
                    });
                    context.reportProgress(to, pixelsZ, "rows drawn");
                }
                BufferedImage image = MapImage.render(columns, minX, minZ, scale, pixelsPerBlock, grid, null, null);
                for (long column : differing) {
                    int x = (int) (column >> 32);
                    int z = (int) column;
                    int px = MapImage.toPixel(x, minX, scale, pixelsPerBlock);
                    int pz = MapImage.toPixel(z, minZ, scale, pixelsPerBlock);
                    for (int dy = 0; dy < pixelsPerBlock; dy++) {
                        for (int dx = 0; dx < pixelsPerBlock; dx++) {
                            if (px + dx < image.getWidth() && pz + dy < image.getHeight()) {
                                image.setRGB(px + dx, pz + dy, DIFF_RGB);
                            }
                        }
                    }
                }
                byte[] png = ScreenshotImages.toPng(image);
                File folder = new File(McmcpPaths.gameDirectory(), "mcmcp-plans");
                folder.mkdirs();
                File file = new File(folder, "plan-" + System.currentTimeMillis() + ".png");
                Files.write(file.toPath(), png);

                JsonObject json = new JsonObject();
                JsonArray box = new JsonArray();
                box.add(minX);
                box.add(minZ);
                box.add(minX + spanX - 1);
                box.add(minZ + spanZ - 1);
                json.add("box", box);
                json.addProperty("yMax", yMax);
                json.addProperty("scale", scale);
                json.addProperty("pixelsPerBlock", pixelsPerBlock);
                json.addProperty("width", image.getWidth());
                json.addProperty("height", image.getHeight());
                json.addProperty("grid", grid);
                json.addProperty("layout", "pixel (px, py) is block x = box[0] + floor(px / pixelsPerBlock) * scale, "
                    + "z likewise from py; north is up");
                json.addProperty("path", file.getAbsolutePath());
                if (diffTarget != null) {
                    json.addProperty("differCells", differCells[0]);
                    json.addProperty("differColumns", differing.size());
                }
                if (sampler.unloadedCount > 0) {
                    json.addProperty("unloadedColumns", sampler.unloadedCount);
                }
                if (!highlight.isEmpty()) {
                    json.addProperty("highlightedColumns", sampler.highlighted);
                }
                ToolResult result = ToolResult.structured(json);
                if (context.getBoolean("inline", true)) {
                    result.withContent(McpContent.image(png, "image/png"));
                }
                return result;
            });
    }

    static int autoGrid(int span) {
        int[] steps = {8, 16, 32, 64, 128, 256, 512, 1024};
        for (int step : steps) {
            if (span / step <= 8) {
                return step;
            }
        }
        return 2048;
    }
}
