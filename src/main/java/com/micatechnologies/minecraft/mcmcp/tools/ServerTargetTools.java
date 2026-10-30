package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkKeys;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.BuildTarget;
import com.micatechnologies.minecraft.mcmcp.regions.CellOverlay;
import com.micatechnologies.minecraft.mcmcp.regions.TargetDiff;
import com.micatechnologies.minecraft.mcmcp.regions.TargetStore;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/**
 * Build targets: what an area should be, kept by the server under a name, and the checks against them.
 *
 * <p>A builder's checks were Python: replay the generator into a map, read the box back, print the
 * differences. They were the second-largest share of its context, after running the generators
 * themselves (measured 2026-10-26). Here a write saves the target as it goes ({@code saveTarget} on
 * {@code server_apply_ops}), and {@code server_verify} answers with counts and a few samples.
 * {@code server_repair} rewrites only the cells that differ, through {@code server_apply_ops}, so a fix
 * never needs the ops sent again.
 */
public final class ServerTargetTools {

    private static final long SLICE_NANOS = 25_000_000L;
    /** Most cells one capture reads. */
    static final int MAX_CAPTURE_CELLS = 2_000_000;
    /** Most differences a detail file lists. */
    private static final int MAX_DETAIL_LINES = 1_000_000;

    private ServerTargetTools() {
    }

    /** The current world's targets. Worker thread or game thread. */
    static TargetStore store() {
        File root = DimensionManager.getCurrentSaveRootDirectory();
        if (root == null) {
            throw new IllegalStateException("No world is loaded.");
        }
        return new TargetStore(new File(root, TargetStore.FOLDER));
    }

    /**
     * Saves a replay's cells as a target layer.
     *
     * @param stateOf the state text for an overlay value
     * @return the layer's entry, for the reply
     */
    static JsonObject saveOverlay(String name, boolean replace, int dimension, CellOverlay overlay,
                                  IntFunction<String> stateOf) throws IOException {
        final BuildTarget target = new BuildTarget(dimension);
        for (long key : overlay.sectionsBottomUp()) {
            overlay.visit(key, (x, y, z, value) -> target.put(x, y, z, stateOf.apply(value)));
        }
        return store().save(name, target, replace, System.currentTimeMillis()).toJson();
    }

    public static void register() {
        registerTarget();
        registerVerify();
        registerRepair();
    }

    // ------------------------------------------------------------------
    // server_target
    // ------------------------------------------------------------------

    private static void registerTarget() {
        McpRegistry.registerTool(McpTool.named("server_target")
            .title("Manage build targets")
            .description("Build targets are what an area should be, kept by the server under a name. A write "
                + "saves one with server_apply_ops' saveTarget. Names are layered with '/': 'tower/structure', "
                + "'tower/fitout-l3', 'tower/life'; 'tower/*' means every layer under it, merged in the order "
                + "they were created, later layers winning per cell. op 'list' (default) lists them; 'info' "
                + "describes a name or pattern; 'capture' saves the live blocks of a box as a layer (\"this is "
                + "right\"); 'protect' does the same but marks the cells must-stay-as-is (a hand edit): "
                + "server_verify reports them apart and server_repair never writes them; 'delete' removes a "
                + "name or pattern. 'defaults' shows this world's verify defaults, or with 'rules' "
                + "({ignore, compare, sameAs, ignoreFor}) replaces them: a modpack's runtime state set once, "
                + "e.g. {ignoreFor: {'csm:*': ['state']}}.")
            .schema(ServerChunkTools.loadArgument(JsonSchema.object()
                .enumeration("op", "What to do. Default list.", "list", "info", "capture", "protect", "delete",
                    "defaults")
                .string("name", "Target name, or a pattern ending '/*' for info and delete.")
                .integer("x", "capture/protect: X of one corner.")
                .integer("y", "capture/protect: Y of one corner.")
                .integer("z", "capture/protect: Z of one corner.")
                .integer("toX", "X of the opposite corner. Defaults to x.")
                .integer("toY", "Y of the opposite corner. Defaults to y.")
                .integer("toZ", "Z of the opposite corner. Defaults to z.")
                .integer("dimension", "Dimension id. Defaults to 0.")
                .bool("skipAir", "capture/protect: leave air cells out (don't care) instead of wanting them clear.")
                .bool("replace", "capture/protect: start the layer over instead of merging into it.")
                .property("rules", Json.obj("type", "object")))
                .build())
            .serverOnly()
            .idempotent()
            .handler(ServerTargetTools::target)
            .build());
    }

    private static ToolResult target(ToolContext context) throws Exception {
        String op = context.getString("op", "list");
        String name = TargetStore.normalise(context.getString("name", null));
        TargetStore store = store();
        switch (op) {
            case "list": {
                JsonArray layers = new JsonArray();
                for (TargetStore.Entry entry : store.list()) {
                    layers.add(entry.toJson());
                }
                return ToolResult.structured(Json.obj("targets", layers));
            }
            case "info": {
                if (name == null) {
                    return ToolResult.error("info needs a name or pattern.");
                }
                BuildTarget union = store.union(name);
                if (union == null) {
                    return ToolResult.error(unknown(store, name));
                }
                JsonObject json = new JsonObject();
                json.addProperty("target", name);
                JsonArray layers = new JsonArray();
                for (TargetStore.Entry entry : store.resolve(name)) {
                    layers.add(entry.name);
                }
                json.add("layers", layers);
                json.addProperty("dimension", union.dimension());
                json.addProperty("cells", union.cells());
                json.addProperty("states", union.palette().size());
                int[] bounds = union.bounds();
                if (bounds != null) {
                    JsonObject box = Json.obj("from", ints(bounds[0], bounds[1], bounds[2]));
                    box.add("to", ints(bounds[3], bounds[4], bounds[5]));
                    json.add("bounds", box);
                }
                return ToolResult.structured(json);
            }
            case "delete": {
                if (name == null) {
                    return ToolResult.error("delete needs a name or pattern.");
                }
                List<String> deleted = store.delete(name);
                if (deleted.isEmpty()) {
                    return ToolResult.error(unknown(store, name));
                }
                return ToolResult.structured(Json.obj("deleted", Json.arrayOfStrings(deleted)));
            }
            case "defaults": {
                JsonObject rules = Json.getObject(context.getArguments(), "rules");
                if (rules != null) {
                    store.writeDefaults(rules);
                }
                return ToolResult.structured(Json.obj("defaults", store.readDefaults()));
            }
            case "capture":
            case "protect":
                return capture(context, name, "protect".equals(op));
            default:
                return ToolResult.error("op is one of list, info, capture, protect, delete.");
        }
    }

    private static ToolResult capture(final ToolContext context, final String name, final boolean protect)
        throws Exception {
        String bad = name == null ? "capture and protect need a name." : TargetStore.checkName(name);
        if (bad != null) {
            return ToolResult.error(bad);
        }
        final int x1 = context.requireInt("x");
        final int y1 = context.requireInt("y");
        final int z1 = context.requireInt("z");
        final int minX = Math.min(x1, context.getInt("toX", x1));
        final int maxX = Math.max(x1, context.getInt("toX", x1));
        final int minY = Math.max(0, Math.min(y1, context.getInt("toY", y1)));
        final int maxY = Math.min(255, Math.max(y1, context.getInt("toY", y1)));
        final int minZ = Math.min(z1, context.getInt("toZ", z1));
        final int maxZ = Math.max(z1, context.getInt("toZ", z1));
        long cells = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (cells > MAX_CAPTURE_CELLS) {
            return ToolResult.error("That box is " + cells + " cells; a capture takes at most " + MAX_CAPTURE_CELLS
                + ". Capture it in parts under the same name: they merge.");
        }
        final int dimension = context.getInt("dimension", 0);
        final boolean skipAir = context.getBoolean("skipAir", false);
        final boolean replace = context.getBoolean("replace", false);
        final Set<Long> chunks = ChunkKeys.box(minX, minZ, maxX, maxZ);
        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            final BuildTarget target = new BuildTarget(dimension);
            final long[] unloaded = {0L};
            final int[] cursor = {minY, minX};  // y, x: a column row at a time
            while (cursor[0] <= maxY) {
                context.onGameThread(() -> {
                    WorldServer world = ServerWorldTools.requireWorld(dimension);
                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                    long started = System.nanoTime();
                    while (cursor[0] <= maxY && System.nanoTime() - started < SLICE_NANOS) {
                        for (int z = minZ; z <= maxZ; z++) {
                            pos.setPos(cursor[1], cursor[0], z);
                            if (!world.isBlockLoaded(pos)) {
                                unloaded[0]++;
                                continue;
                            }
                            String state = canonical(world.getBlockState(pos));
                            if (skipAir && "minecraft:air".equals(state)) {
                                continue;
                            }
                            target.put(cursor[1], cursor[0], z, state, protect);
                        }
                        if (++cursor[1] > maxX) {
                            cursor[1] = minX;
                            cursor[0]++;
                        }
                    }
                    return null;
                });
            }
            TargetStore.Entry entry = store().save(name, target, replace, System.currentTimeMillis());
            JsonObject json = entry.toJson();
            json.addProperty("captured", target.cells());
            json.addProperty("protected", protect);
            if (unloaded[0] > 0) {
                json.addProperty("unloaded", unloaded[0]);
                json.addProperty("note", unloaded[0] + " cells were in unloaded chunks and were left out. Pass "
                    + "load: true to read them.");
            }
            return ToolResult.structured(json);
        });
    }

    // ------------------------------------------------------------------
    // server_verify and server_repair
    // ------------------------------------------------------------------

    private static JsonSchema checkArguments(JsonSchema schema) {
        return ServerChunkTools.loadArgument(schema
            .string("target", "A target name, or a pattern ending '/*' to check the union of its layers.")
            .property("skipBoxes", described("array", "Boxes not checked at all, each "
                + "[x1, y1, z1, x2, y2, z2]: a lift shaft whose cab moves, a room left for later."))
            .array("ignore", "More block-state properties to ignore everywhere, on top of the defaults (open, "
                + "powered, lit, power, triggered, enabled, leaf decay, snowy, fluid level). Properties a block "
                + "does not store (fence connections, stair shape) are never compared.", Json.obj("type", "string"))
            .array("compare", "Default-ignored properties to compare after all, e.g. ['open'].",
                Json.obj("type", "string"))
            .property("ignoreFor", described("object", "Properties to ignore for some blocks only, {pattern: "
                + "[props]}, '*' matching any run: {'csm:*': ['state']}. Added to this world's defaults "
                + "(server_target op 'defaults')."))
            .property("sameAs", described("object", "More block ids to treat as equal, "
                + "{id: id}. Defaults already equate csm:lightupair and snow_layer with air, and lit/powered block "
                + "swaps (lit_redstone_lamp...) with their unlit forms; grass counts as dirt under a block that "
                + "stops light.")));
    }

    private static void registerVerify() {
        McpRegistry.registerTool(McpTool.named("server_verify")
            .title("Check a build against its target")
            .description("Compare the world with a build target (see server_target) and answer with counts: "
                + "matching, differ, and per kind (wrongBlock, wrongState, missing = air where a block is wanted, "
                + "extra = a block where air is wanted, protectedChanged, unloaded) a few samples each. Runtime "
                + "changes are not differences: doors opened, lamps lit, fences joined, leaves decaying (see "
                + "'ignore'). detail: 'file' writes every difference to a file and returns its path instead of "
                + "listing more. Fix what differs with server_repair, without re-sending ops.")
            .schema(checkArguments(JsonSchema.object())
                .integer("samples", "Samples per kind. Default 5.", 0, 50)
                .enumeration("detail", "'file' writes every difference to a file.", "summary", "file")
                .required("target")
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> check(context, false))
            .build());
    }

    private static void registerRepair() {
        McpRegistry.registerTool(McpTool.named("server_repair")
            .title("Repair a build from its target")
            .description("Rewrite the cells that differ from a build target (see server_target) to what the "
                + "target wants, through server_apply_ops: undo points, the write budget and two-part pairing as "
                + "for any write. Protected cells are never written; neither are cells matching after the "
                + "ignore rules (an open door stays open). dryRun counts what it would write. Needs "
                + "permissions.allowWorldEdits.")
            .schema(checkArguments(JsonSchema.object())
                .bool("dryRun", "Count what would be written; write nothing.")
                .required("target")
                .build())
            .serverOnly()
            .destructive()
            .idempotent()
            .handler(context -> check(context, true))
            .build());
    }

    /** The built-in rules, then this world's defaults file, then the call's own arguments. */
    static TargetDiff.Normaliser normaliser(ToolContext context, TargetStore store) {
        return apply(apply(TargetDiff.Normaliser.defaults(), store.readDefaults()), context.getArguments());
    }

    private static TargetDiff.Normaliser apply(TargetDiff.Normaliser base, JsonObject rules) {
        Set<String> ignore = new HashSet<>(Json.getStringList(rules, "ignore"));
        Set<String> compare = new HashSet<>(Json.getStringList(rules, "compare"));
        Map<String, String> same = new HashMap<>();
        JsonObject sameAs = Json.getObject(rules, "sameAs");
        if (sameAs != null) {
            for (Map.Entry<String, JsonElement> entry : sameAs.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    same.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        Map<String, Set<String>> ignoreFor = new HashMap<>();
        JsonObject forBlocks = Json.getObject(rules, "ignoreFor");
        if (forBlocks != null) {
            for (Map.Entry<String, JsonElement> entry : forBlocks.entrySet()) {
                ignoreFor.put(entry.getKey(), new HashSet<>(Json.getStringList(forBlocks, entry.getKey())));
            }
        }
        return base.with(ignore, same, ignoreFor).without(compare);
    }

    /**
     * A state as its block stores it: through the metadata round trip, which drops what the block does
     * not store (fence connections, stair shape, a railing's slope) and keeps all it does. Game thread.
     */
    static String canonical(IBlockState state) {
        try {
            Block block = state.getBlock();
            return block.getStateFromMeta(block.getMetaFromState(state)).toString();
        }
        catch (RuntimeException e) {
            return state.toString();
        }
    }

    /** A target's wanted state text, canonical as {@link #canonical(IBlockState)}. Game thread; cached. */
    static String canonical(String want, Map<String, String> cache) {
        String known = cache.get(want);
        if (known != null) {
            return known;
        }
        String result = want;
        try {
            int open = want.indexOf('[');
            Block block = BlockIds.resolve(open < 0 ? want : want.substring(0, open));
            if (block != null) {
                IBlockState state = open < 0 || !want.endsWith("]") ? block.getDefaultState()
                    : CommandBase.convertArgToBlockState(block, want.substring(open + 1, want.length() - 1));
                result = canonical(state);
            }
        }
        catch (Exception e) {
            // Not a state this game knows: compared as written.
        }
        cache.put(want, result);
        return result;
    }

    /**
     * Whether a dirt cell where grass is wanted is grass that vanilla turned to dirt: under a block that
     * stops light. Anywhere else it is a missing sod, and a real difference. Game thread.
     */
    private static boolean grassTurnedToDirt(WorldServer world, BlockPos pos, String want, String live) {
        if (!want.startsWith("minecraft:grass") || !live.startsWith("minecraft:dirt") || !live.contains("variant=dirt")) {
            return false;
        }
        BlockPos up = pos.up();
        return world.getBlockState(up).getLightOpacity(world, up) > 2;
    }

    @Nullable
    private static List<int[]> skipBoxes(ToolContext context) {
        List<int[]> boxes = new ArrayList<>();
        JsonElement raw = context.getArguments().get("skipBoxes");
        if (raw == null || raw.isJsonNull()) {
            return boxes;
        }
        if (!raw.isJsonArray()) {
            return null;
        }
        for (JsonElement item : raw.getAsJsonArray()) {
            if (!item.isJsonArray() || item.getAsJsonArray().size() != 6) {
                return null;
            }
            int[] v = new int[6];
            for (int i = 0; i < 6; i++) {
                v[i] = item.getAsJsonArray().get(i).getAsInt();
            }
            boxes.add(new int[] {Math.min(v[0], v[3]), Math.min(v[1], v[4]), Math.min(v[2], v[5]),
                Math.max(v[0], v[3]), Math.max(v[1], v[4]), Math.max(v[2], v[5])});
        }
        return boxes;
    }

    private static ToolResult check(final ToolContext context, final boolean repair) throws Exception {
        final String name = TargetStore.normalise(context.getString("target", ""));
        final TargetStore store = store();
        final BuildTarget target = store.union(name);
        if (target == null) {
            return ToolResult.error(unknown(store, name));
        }
        final List<int[]> skip = skipBoxes(context);
        if (skip == null) {
            return ToolResult.error("'skipBoxes' is a list of [x1, y1, z1, x2, y2, z2] boxes.");
        }
        final int samples = repair ? 3 : context.getBoundedInt("samples", 5, 0, 50);
        final boolean toFile = !repair && "file".equals(context.getString("detail", "summary"));
        final TargetDiff diff = new TargetDiff(normaliser(context, store), samples, skip);
        final Map<String, String> wanted = new HashMap<>();
        final int dimension = target.dimension();
        final List<String> fixes = new ArrayList<>();
        final List<String> detail = new ArrayList<>();

        Set<Long> chunks = new LinkedHashSet<>();
        for (long key : target.sectionKeys()) {
            int[] chunk = BuildTarget.chunkOf(key);
            chunks.add(ChunkKeys.of(chunk[0], chunk[1]));
        }
        return ServerChunkTools.withChunks(context, dimension, chunks, outcome -> {
            final List<Long> keys = target.sectionKeys();
            final int[] cursor = {0};
            while (cursor[0] < keys.size()) {
                context.onGameThread(() -> {
                    WorldServer world = ServerWorldTools.requireWorld(dimension);
                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                    long started = System.nanoTime();
                    while (cursor[0] < keys.size() && System.nanoTime() - started < SLICE_NANOS) {
                        target.forEachCellIn(keys.get(cursor[0]++), (x, y, z, rawWant, protect) -> {
                            pos.setPos(x, y, z);
                            String want = canonical(rawWant, wanted);
                            String live = world.isBlockLoaded(pos) ? canonical(world.getBlockState(pos)) : null;
                            if (live != null && grassTurnedToDirt(world, pos, want, live)) {
                                live = want;
                            }
                            TargetDiff.Kind kind = diff.add(x, y, z, want, protect, live);
                            if (kind == null || kind == TargetDiff.Kind.MATCH || kind == TargetDiff.Kind.UNLOADED) {
                                return;
                            }
                            if (repair && kind != TargetDiff.Kind.PROTECTED_CHANGED) {
                                fixes.add(setblock(x, y, z, rawWant));
                            }
                            if (toFile && detail.size() < MAX_DETAIL_LINES) {
                                detail.add(x + " " + y + " " + z + " " + kind.key + " want " + want + " is " + live);
                            }
                        });
                    }
                    return null;
                });
                if (keys.size() > 8) {
                    context.reportProgress(cursor[0], keys.size(), "Checked " + cursor[0] + " of " + keys.size()
                        + " sections");
                }
            }

            JsonObject json = new JsonObject();
            json.addProperty("target", name);
            json.addProperty("cells", target.cells());
            for (Map.Entry<String, JsonElement> entry : diff.toJson().entrySet()) {
                json.add(entry.getKey(), entry.getValue());
            }
            if (toFile && !detail.isEmpty()) {
                json.addProperty("detailFile", writeDetail(name, detail));
            }
            if (repair) {
                json.add("repair", repairCells(context, dimension, fixes));
            }
            return ToolResult.structured(json);
        });
    }

    /** {@code setblock x y z id [state]}: the exact state the target wants. */
    static String setblock(int x, int y, int z, String state) {
        int open = state.indexOf('[');
        if (open < 0 || !state.endsWith("]")) {
            return "setblock " + x + " " + y + " " + z + " " + state;
        }
        return "setblock " + x + " " + y + " " + z + " " + state.substring(0, open) + " "
            + state.substring(open + 1, state.length() - 1);
    }

    /** Writes the fixes through server_apply_ops, in calls it accepts. */
    private static JsonObject repairCells(ToolContext context, int dimension, List<String> fixes) throws Exception {
        JsonObject totals = new JsonObject();
        boolean dryRun = context.getBoolean("dryRun", false);
        totals.addProperty("cells", fixes.size());
        if (fixes.isEmpty()) {
            return totals;
        }
        McpTool apply = McpRegistry.tool("server_apply_ops", McmcpSide.SERVER);
        if (apply == null) {
            totals.addProperty("error", "server_apply_ops is not available.");
            return totals;
        }
        int batch = Math.max(1, Math.min(50_000, com.micatechnologies.minecraft.mcmcp.McmcpConfig.getMaxWriteCells()));
        long written = 0L;
        long unchanged = 0L;
        long refused = 0L;
        List<String> errors = new ArrayList<>();
        JsonArray undoPoints = new JsonArray();
        for (int from = 0; from < fixes.size(); from += batch) {
            List<String> slice = fixes.subList(from, Math.min(fixes.size(), from + batch));
            JsonObject args = new JsonObject();
            args.add("ops", Json.arrayOfStrings(slice));
            args.addProperty("dimension", dimension);
            args.addProperty("dryRun", dryRun);
            args.addProperty("load", context.getBoolean("load", false));
            ToolResult result = apply.call(context.forPart(args, from, fixes.size(), "repair"));
            if (result.isError()) {
                errors.add(String.valueOf(result.getText()));
                break;
            }
            JsonObject part = result.getStructuredContent();
            written += Json.getLong(part, dryRun ? "wouldWrite" : "written", 0L);
            unchanged += Json.getLong(part, "unchanged", 0L);
            refused += Json.getLong(part, "refused", 0L);
            if (part != null && part.has("undoPoint")) {
                undoPoints.add(part.get("undoPoint"));
            }
        }
        totals.addProperty(dryRun ? "wouldWrite" : "written", written);
        totals.addProperty("unchanged", unchanged);
        if (refused > 0) {
            totals.addProperty("refused", refused);
        }
        if (undoPoints.size() > 0) {
            totals.add("undoPoints", undoPoints);
        }
        if (!errors.isEmpty()) {
            totals.addProperty("error", errors.get(0));
        }
        return totals;
    }

    private static String writeDetail(String name, List<String> lines) throws IOException {
        File root = DimensionManager.getCurrentSaveRootDirectory();
        if (root == null) {
            throw new IOException("No world is loaded.");
        }
        File folder = new File(root, TargetStore.FOLDER + File.separator + "reports");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("could not create " + folder);
        }
        File file = new File(folder, name.replace("/*", "").replace('/', '_') + "-" + System.currentTimeMillis()
            + ".txt");
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        return file.getAbsolutePath();
    }

    private static String unknown(TargetStore store, String name) {
        List<String> names = new ArrayList<>();
        for (TargetStore.Entry entry : store.list()) {
            names.add(entry.name);
        }
        Collections.sort(names);
        return "No target '" + name + "' in this world." + (names.isEmpty()
            ? " None has been saved yet: pass saveTarget to server_apply_ops, or capture one with server_target."
            : " Saved: " + String.join(", ", names.size() > 30 ? names.subList(0, 30) : names) + ".");
    }

    private static JsonObject described(String type, String description) {
        JsonObject property = Json.obj("type", type);
        property.addProperty("description", description);
        return property;
    }

    private static JsonArray ints(int... values) {
        JsonArray array = new JsonArray();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }
}
