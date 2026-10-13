package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.regions.BlockWatchLog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * {@code server_watch_blocks} and {@code server_wait_tick}: a timeline of block changes at tick
 * resolution, and waiting for a particular tick (#68).
 *
 * <h2>Why</h2>
 *
 * Checking a traffic-signal controller meant knowing when each of five signal heads changed colour
 * over 4,000 ticks. The only way was a {@code server_get_blocks} call every 10 to 40 seconds, which
 * missed a four-second yellow entirely and took fifteen round trips per run. A watch samples the
 * positions every tick on the server thread and keeps only the changes, so the agent gets an exact
 * timeline in one or two calls.
 *
 * <h2>How</h2>
 *
 * Polled, not hooked: each tick reads every watched position and compares the state object with the
 * last one, which is an identity check. That sees what {@code server_changes_since}'s listener cannot:
 * writes made without the client-sync flag, MCMCP's own writes, and properties that exist only in
 * {@code getActualState}. Chunk loads are never caused; an unloaded position reads as
 * {@code (unloaded)}, so a chunk unloading and reloading appears in the timeline too.
 *
 * <p>The sampled state never leaves the server thread: what is recorded is its key string, in a
 * {@link BlockWatchLog}.
 */
public final class ServerWatchTools {

    /** Most positions one watch samples every tick. */
    private static final int MAX_POSITIONS = 4096;

    /** Longest watch: an hour of ticks. */
    private static final int MAX_TICKS = 72_000;

    /** Most changes one watch records before it stops. */
    private static final int MAX_CHANGES = 20_000;

    /** Most watches kept at once, running and finished. */
    private static final int MAX_WATCHES = 8;

    /** Positions up to which a result lists each one's starting block. */
    private static final int MAX_INITIAL_LISTED = 256;

    /** Longest a call blocks, inside the orchestrator's call timeout. */
    private static final int MAX_WAIT_SECONDS = 90;

    /** How long a finished watch is kept for reading. */
    private static final long KEEP_FINISHED_MILLIS = 30 * 60_000L;

    private static final String UNLOADED = "(unloaded)";

    private static final AtomicLong LAST_ID = new AtomicLong();

    /** Every watch, oldest first. Guarded by itself. */
    private static final Map<String, Watch> WATCHES = new LinkedHashMap<>();

    /** Calls waiting for a tick. Guarded by itself. */
    private static final List<TickWaiter> WAITERS = new ArrayList<>();

    private ServerWatchTools() {
    }

    /** One watch: its record, and the server-thread state it samples against. */
    private static final class Watch {

        final BlockWatchLog log;
        final BlockPos[] positions;
        /** The state last seen at each position, null when unloaded. Server thread only. */
        final IBlockState[] last;
        @Nullable
        final Set<String> properties;
        final boolean actual;
        volatile long finishedAtMillis;

        Watch(BlockWatchLog log, BlockPos[] positions, IBlockState[] last, @Nullable Set<String> properties,
              boolean actual) {
            this.log = log;
            this.positions = positions;
            this.last = last;
            this.properties = properties;
            this.actual = actual;
        }
    }

    /** A call waiting for the world's total time to reach {@code target}. */
    private static final class TickWaiter {

        final int dimension;
        final long target;
        final CompletableFuture<Long> reached = new CompletableFuture<>();

        TickWaiter(int dimension, long target) {
            this.dimension = dimension;
            this.target = target;
        }
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new Events());
        registerWatch();
        registerWaitTick();
    }

    // ------------------------------------------------------------------
    // Sampling
    // ------------------------------------------------------------------

    /** On Forge's bus. */
    public static final class Events {

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            List<Watch> running = new ArrayList<>();
            synchronized (WATCHES) {
                for (Watch watch : WATCHES.values()) {
                    if (!watch.log.isDone()) {
                        running.add(watch);
                    }
                }
            }
            for (Watch watch : running) {
                sample(watch);
            }
            List<TickWaiter> waiting;
            synchronized (WAITERS) {
                waiting = new ArrayList<>(WAITERS);
            }
            for (TickWaiter waiter : waiting) {
                WorldServer world = DimensionManager.getWorld(waiter.dimension);
                if (world != null && world.getTotalWorldTime() >= waiter.target) {
                    waiter.reached.complete(world.getTotalWorldTime());
                    synchronized (WAITERS) {
                        WAITERS.remove(waiter);
                    }
                }
            }
        }
    }

    /** One tick's sample of one watch. Server thread. */
    private static void sample(Watch watch) {
        WorldServer world = DimensionManager.getWorld(watch.log.dimension());
        WorldServer clock = world != null ? world : DimensionManager.getWorld(0);
        if (clock == null) {
            return;
        }
        long tick = clock.getTotalWorldTime();
        for (int i = 0; i < watch.positions.length; i++) {
            IBlockState state = read(world, watch.positions[i], watch.actual);
            if (state != watch.last[i]) {
                watch.last[i] = state;
                watch.log.sample(tick, i, key(state, watch.properties));
            }
        }
        watch.log.sampled(tick);
        if (tick >= watch.log.endTick()) {
            watch.log.stop(tick, "done");
        }
        if (watch.log.isDone()) {
            watch.finishedAtMillis = System.currentTimeMillis();
        }
    }

    /** The state at {@code pos}, or null when its chunk is not loaded. Never loads. Server thread. */
    @Nullable
    private static IBlockState read(@Nullable WorldServer world, BlockPos pos, boolean actual) {
        if (world == null || !GameJson.isLoaded(world, pos)) {
            return null;
        }
        IBlockState state = world.getBlockState(pos);
        if (actual) {
            try {
                return state.getActualState(world, pos);
            }
            catch (RuntimeException e) {
                // A mod's getActualState that throws still has a stored state worth watching.
                return state;
            }
        }
        return state;
    }

    /** {@code modid:block[prop=value,...]}, with only {@code properties} when given. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static String key(@Nullable IBlockState state, @Nullable Set<String> properties) {
        if (state == null) {
            return UNLOADED;
        }
        ResourceLocation name = state.getBlock().getRegistryName();
        StringBuilder key = new StringBuilder(name == null ? "unknown" : name.toString());
        boolean any = false;
        for (Map.Entry<IProperty<?>, Comparable<?>> entry : state.getProperties().entrySet()) {
            String property = entry.getKey().getName();
            if (properties != null && !properties.contains(property)) {
                continue;
            }
            key.append(any ? ',' : '[').append(property).append('=')
                .append(((IProperty) entry.getKey()).getName((Comparable) entry.getValue()));
            any = true;
        }
        return any ? key.append(']').toString() : key.toString();
    }

    // ------------------------------------------------------------------
    // server_watch_blocks
    // ------------------------------------------------------------------

    private static void registerWatch() {
        McpRegistry.registerTool(McpTool.named("server_watch_blocks")
            .title("Watch blocks change over time")
            .description("Record every change to a set of blocks, tick by tick, over a span of ticks, "
                + "and get back only the changes: when each block changed and from what to what. For "
                + "timing anything that animates or cycles (signals, machines, redstone, doors) "
                + "instead of polling with server_get_blocks.\n\n"
                + "op 'start' (default) watches a box (x..toX, y..toY, z..toZ) or a list of "
                + "'positions' for 'ticks' ticks. It waits for the result when the span fits in "
                + "about 80 seconds, or when wait is true; otherwise it returns an 'id' at once. op "
                + "'read' returns a watch's changes so far (pass from_change = the last 'nextChange' "
                + "to get only new ones), 'stop' ends one early, 'list' shows them all.\n\n"
                + "Each change is [tick, x, y, z, from, to], indexes into 'palette', whose entries "
                + "read 'modid:block[prop=value]'. 'properties' limits what counts as a change to "
                + "those property names (the block id always counts). Never loads chunks: an "
                + "unloaded position reads '(unloaded)'. Tile-entity data is not watched.")
            .schema(JsonSchema.object()
                .enumeration("op", "What to do. Default 'start'.", "start", "read", "stop", "list")
                .integer("x", "start: X of one corner of the box.")
                .integer("y", "start: Y of one corner of the box.")
                .integer("z", "start: Z of one corner of the box.")
                .integer("toX", "start: X of the opposite corner. Defaults to x.")
                .integer("toY", "start: Y of the opposite corner. Defaults to y.")
                .integer("toZ", "start: Z of the opposite corner. Defaults to z.")
                .array("positions", "start: [x, y, z] positions to watch, instead of a box.",
                    Json.parse("{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"minItems\":3,"
                        + "\"maxItems\":3}").getAsJsonObject())
                .integer("dimension", "start: dimension id. Default 0.")
                .integer("ticks", "start: how many ticks to watch for (20 a second).", 1, MAX_TICKS)
                .stringArray("properties", "start: only these block-state properties count as a "
                    + "change, e.g. ['color']. Default: all of them.")
                .bool("actual", "start: watch the state as drawn (getActualState), which includes "
                    + "properties worked out from neighbours. Default true.")
                .bool("wait", "start: wait for the watch to finish before returning (at most 90 "
                    + "seconds). Default: wait when 'ticks' fits in about 80 seconds.")
                .string("id", "read, stop: the watch.")
                .integer("from_change", "read: the first change to return; pass the last "
                    + "'nextChange'. Default 0.", 0, MAX_CHANGES)
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> {
                String op = context.getString("op", "start");
                switch (op) {
                    case "start":
                        return start(context);
                    case "read":
                    case "stop": {
                        Watch watch = find(context);
                        if (watch == null) {
                            return ToolResult.error("No watch '" + context.getString("id", "")
                                + "'. op 'list' shows the watches kept; a finished one is kept for 30 "
                                + "minutes.");
                        }
                        if ("stop".equals(op)) {
                            watch.log.stop(currentTick(context, watch.log.dimension()), "stopped");
                            watch.finishedAtMillis = System.currentTimeMillis();
                        }
                        return result(watch, context.getBoundedInt("from_change", 0, 0, MAX_CHANGES));
                    }
                    case "list":
                        return list();
                    default:
                        return ToolResult.error("Unknown op '" + op + "'; use start, read, stop or list.");
                }
            })
            .build());
    }

    private static ToolResult start(ToolContext context) throws Exception {
        if (!context.has("ticks")) {
            return ToolResult.error("op 'start' needs 'ticks': how long to watch, 1 to " + MAX_TICKS + ".");
        }
        final int ticks = context.getBoundedInt("ticks", 200, 1, MAX_TICKS);
        final int dimension = context.getInt("dimension", 0);
        final boolean actual = context.getBoolean("actual", true);
        final Set<String> properties = context.has("properties")
            ? new HashSet<>(Json.getStringList(context.getArguments(), "properties")) : null;

        final List<BlockPos> positions = new ArrayList<>();
        JsonElement listed = context.getArguments().get("positions");
        if (listed != null && listed.isJsonArray() && listed.getAsJsonArray().size() > 0) {
            for (JsonElement element : listed.getAsJsonArray()) {
                if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
                    return ToolResult.error("Each of 'positions' is [x, y, z]; got " + Json.write(element) + ".");
                }
                JsonArray xyz = element.getAsJsonArray();
                positions.add(new BlockPos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt()));
                if (positions.size() > MAX_POSITIONS) {
                    break;
                }
            }
        }
        else {
            if (!context.has("x") || !context.has("y") || !context.has("z")) {
                return ToolResult.error("op 'start' needs a box (x, y, z, and optionally toX, toY, toZ) "
                    + "or 'positions'.");
            }
            int x1 = context.requireInt("x");
            int y1 = context.requireInt("y");
            int z1 = context.requireInt("z");
            int x2 = context.getInt("toX", x1);
            int y2 = context.getInt("toY", y1);
            int z2 = context.getInt("toZ", z1);
            long volume = (Math.abs((long) x2 - x1) + 1) * (Math.abs((long) y2 - y1) + 1)
                * (Math.abs((long) z2 - z1) + 1);
            if (volume > MAX_POSITIONS) {
                return ToolResult.error("That box is " + volume + " blocks; a watch samples every block "
                    + "every tick, so it takes at most " + MAX_POSITIONS + ". Watch a smaller box, or "
                    + "list the 'positions' you care about.");
            }
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                        positions.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        if (positions.size() > MAX_POSITIONS) {
            return ToolResult.error("A watch takes at most " + MAX_POSITIONS + " positions.");
        }

        final String id = "w" + LAST_ID.updateAndGet(last -> Math.max(last + 1, System.currentTimeMillis()));
        Watch watch = context.onGameThread(new Callable<Watch>() {
            @Override
            public Watch call() {
                WorldServer world = ServerWorldTools.requireWorld(dimension);
                long now = world.getTotalWorldTime();
                BlockPos[] at = positions.toArray(new BlockPos[0]);
                IBlockState[] states = new IBlockState[at.length];
                String[] keys = new String[at.length];
                int[] flat = new int[at.length * 3];
                for (int i = 0; i < at.length; i++) {
                    states[i] = read(world, at[i], actual);
                    keys[i] = key(states[i], properties);
                    flat[i * 3] = at[i].getX();
                    flat[i * 3 + 1] = at[i].getY();
                    flat[i * 3 + 2] = at[i].getZ();
                }
                BlockWatchLog log = new BlockWatchLog(id, dimension, flat, keys, now, now + ticks, MAX_CHANGES);
                return new Watch(log, at, states, properties, actual);
            }
        });
        String refused = add(watch);
        if (refused != null) {
            return ToolResult.error(refused);
        }

        // 20 ticks a second when the server keeps up; past about 80 seconds the call would outlive
        // the orchestrator's timeout, so the default is to hand back the id instead.
        boolean wait = context.getBoolean("wait", ticks <= 1600);
        if (!wait) {
            JsonObject json = watch.log.toJson(0, false);
            json.remove("changes");
            json.addProperty("note", "Watching. Read it with op 'read' and this id; it finishes at tick "
                + watch.log.endTick() + ".");
            return ToolResult.structured(json);
        }
        long deadline = System.currentTimeMillis() + MAX_WAIT_SECONDS * 1000L;
        while (!watch.log.isDone() && System.currentTimeMillis() < deadline) {
            context.getCancellation().throwIfCancelled();
            Thread.sleep(50L);
        }
        return result(watch, 0);
    }

    /** Adds a watch, making room by dropping finished ones. The refusal, or null. */
    @Nullable
    private static String add(Watch watch) {
        synchronized (WATCHES) {
            long now = System.currentTimeMillis();
            Iterator<Watch> old = WATCHES.values().iterator();
            while (old.hasNext()) {
                Watch candidate = old.next();
                if (candidate.log.isDone() && (WATCHES.size() >= MAX_WATCHES
                    || now - candidate.finishedAtMillis > KEEP_FINISHED_MILLIS)) {
                    old.remove();
                }
            }
            if (WATCHES.size() >= MAX_WATCHES) {
                return "There are already " + MAX_WATCHES + " watches running. Stop one with op 'stop', "
                    + "or wait for one to finish.";
            }
            WATCHES.put(watch.log.id(), watch);
            return null;
        }
    }

    @Nullable
    private static Watch find(ToolContext context) {
        String id = context.getString("id", "");
        synchronized (WATCHES) {
            return WATCHES.get(id);
        }
    }

    private static long currentTick(ToolContext context, final int dimension) throws Exception {
        return context.onGameThread(new Callable<Long>() {
            @Override
            public Long call() {
                WorldServer world = DimensionManager.getWorld(dimension);
                WorldServer clock = world != null ? world : DimensionManager.getWorld(0);
                return clock == null ? 0L : clock.getTotalWorldTime();
            }
        });
    }

    private static ToolResult result(Watch watch, int fromChange) {
        JsonObject json = watch.log.toJson(fromChange, watch.log.size() <= MAX_INITIAL_LISTED);
        String summary = json.get("changeCount").getAsInt() + " change(s) across " + watch.log.size()
            + " position(s), ticks " + json.get("startTick").getAsLong() + " to "
            + json.get("sampledTo").getAsLong()
            + (watch.log.isDone() ? " (" + json.get("stopReason").getAsString() + ")" : " so far; still watching");
        return ToolResult.text(summary).withStructured(json);
    }

    private static ToolResult list() {
        JsonArray rows = new JsonArray();
        synchronized (WATCHES) {
            for (Watch watch : WATCHES.values()) {
                JsonObject json = watch.log.toJson(Integer.MAX_VALUE, false);
                json.remove("changes");
                json.remove("palette");
                json.remove("fromChange");
                rows.add(json);
            }
        }
        JsonObject json = new JsonObject();
        json.add("watches", rows);
        return ToolResult.structured(json);
    }

    // ------------------------------------------------------------------
    // server_wait_tick
    // ------------------------------------------------------------------

    private static void registerWaitTick() {
        McpRegistry.registerTool(McpTool.named("server_wait_tick")
            .title("Wait for a game tick")
            .description("Wait until the world's total time (the tick counter that never resets, "
                + "unlike the time of day) reaches a tick, then return it. Either 'tick' for an exact "
                + "tick, or 'modulo' and 'remainder' for the next tick where totalTime % modulo == "
                + "remainder, to line an action up with something on a fixed cycle. Waits at most "
                + "90 seconds.")
            .schema(JsonSchema.object()
                .number("tick", "Wait until total time reaches this tick.")
                .integer("modulo", "Wait for the next tick where total time % modulo == remainder.", 2,
                    1_728_000)
                .integer("remainder", "With modulo. Default 0.")
                .integer("dimension", "Dimension id. Default 0.")
                .build())
            .serverOnly()
            .readOnly()
            .handler(context -> {
                final int dimension = context.getInt("dimension", 0);
                long now = context.onGameThread(new Callable<Long>() {
                    @Override
                    public Long call() {
                        return ServerWorldTools.requireWorld(dimension).getTotalWorldTime();
                    }
                });
                long target;
                if (context.has("tick")) {
                    target = (long) context.requireDouble("tick");
                }
                else if (context.has("modulo")) {
                    int modulo = context.getBoundedInt("modulo", 2, 2, 1_728_000);
                    long remainder = Math.floorMod(context.getInt("remainder", 0), modulo);
                    target = now - Math.floorMod(now, modulo) + remainder;
                    if (target <= now) {
                        target += modulo;
                    }
                }
                else {
                    return ToolResult.error("Give 'tick', or 'modulo' (and 'remainder').");
                }
                if (target - now > MAX_WAIT_SECONDS * 20L) {
                    return ToolResult.error("Tick " + target + " is " + (target - now) + " ticks away, "
                        + "about " + (target - now) / 20 + " seconds; this waits at most "
                        + MAX_WAIT_SECONDS + ". The current tick is " + now + ".");
                }
                JsonObject json = new JsonObject();
                json.addProperty("target", target);
                if (target <= now) {
                    json.addProperty("tick", now);
                    json.addProperty("alreadyPassed", true);
                    return ToolResult.structured(json);
                }
                TickWaiter waiter = new TickWaiter(dimension, target);
                synchronized (WAITERS) {
                    WAITERS.add(waiter);
                }
                long started = System.currentTimeMillis();
                try {
                    // Polled in short steps so a cancelled call stops waiting.
                    long deadline = started + (MAX_WAIT_SECONDS + 10) * 1000L;
                    while (true) {
                        context.getCancellation().throwIfCancelled();
                        try {
                            json.addProperty("tick", waiter.reached.get(100, TimeUnit.MILLISECONDS));
                            break;
                        }
                        catch (TimeoutException notYet) {
                            if (System.currentTimeMillis() > deadline) {
                                return ToolResult.error("Tick " + target + " did not arrive in time; the "
                                    + "server may be paused or running far behind.");
                            }
                        }
                    }
                }
                finally {
                    synchronized (WAITERS) {
                        WAITERS.remove(waiter);
                    }
                }
                json.addProperty("waitedMillis", System.currentTimeMillis() - started);
                return ToolResult.structured(json);
            })
            .build());
    }

    /** Ends every watch and wakes every waiter, when the server stops. */
    public static void serverStopping() {
        synchronized (WATCHES) {
            for (Watch watch : WATCHES.values()) {
                watch.log.stop(0, "the server stopped");
            }
            WATCHES.clear();
        }
        synchronized (WAITERS) {
            for (TickWaiter waiter : WAITERS) {
                waiter.reached.completeExceptionally(new IllegalStateException("The server stopped."));
            }
            WAITERS.clear();
        }
    }
}
