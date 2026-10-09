package com.micatechnologies.minecraft.mcmcp.chunkload;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.mcp.Principal;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * The one way MCMCP loads a chunk that is not already in memory.
 *
 * <h2>Why one gatekeeper</h2>
 *
 * A chunk load on a server is not free: from disk it costs about half a millisecond of the tick
 * for a vanilla chunk, more for a modded one; a chunk that was never generated costs twenty, and
 * Forge's force-loading generates without asking — 25 at once froze one tick for 519 ms in testing.
 * And a loaded chunk stays in memory until the next autosave unless someone gives it back. An agent
 * surveying a district, unrationed, would raise the server's memory and lower its tick rate for as
 * long as it worked. So every load MCMCP causes goes through here, and here:
 *
 * <ul>
 *   <li>only the chunks a call needs are loaded, and never one that was not generated;</li>
 *   <li>loads are paced per tick, and budgeted per minute for the server and for each caller;</li>
 *   <li>the server's tick time and memory are watched, and loading slows, pauses, and — if the server
 *       stays in trouble — gives back what it holds;</li>
 *   <li>a chunk MCMCP loaded is handed back to the server to unload as soon as nothing of MCMCP's
 *       needs it, instead of waiting for the autosave; a chunk a player can see is never touched.</li>
 * </ul>
 *
 * <h2>Threads</h2>
 *
 * The ledger, the tickets and the queue are the server thread's alone. A tool asks from its worker
 * through {@link #acquire}, which schedules the admission onto the server thread and then waits for
 * the server ticks to load the chunks.
 */
public final class ChunkLoadGovernor {

    /** At most this many requests may wait for loads at once; more are refused as saturated. */
    private static final int MAX_QUEUED_REQUESTS = 64;

    /** At most this many ungenerated or unloaded chunk coordinates are listed in a result. */
    private static final int MAX_LISTED = 16;

    private static final HoldLedger LEDGER = new HoldLedger();
    private static final LoadBudget BUDGET = new LoadBudget();
    private static final ServerHealth HEALTH = new ServerHealth();
    private static final Deque<Request> QUEUE = new ArrayDeque<>();
    private static final Map<Integer, List<ForgeChunkManager.Ticket>> TICKETS = new HashMap<>();
    private static final Map<HoldLedger.Place, ForgeChunkManager.Ticket> FORCED = new HashMap<>();
    private static final List<String> SHED_LOG = new ArrayList<>();

    private static int ticks;

    private ChunkLoadGovernor() {
    }

    /** Registers the ticket callback (required before any ticket) and the event handlers. Init. */
    public static void init() {
        ForgeChunkManager.setForcedChunkLoadingCallback(Mcmcp.instance, (tickets, world) -> {
            // MCMCP keeps nothing loaded across a restart: whatever was held is released, and an
            // agent that still needs it asks again.
            for (ForgeChunkManager.Ticket ticket : tickets) {
                ForgeChunkManager.releaseTicket(ticket);
            }
            if (!tickets.isEmpty()) {
                Mcmcp.LOGGER.info("MCMCP released " + tickets.size() + " chunk ticket(s) left from before the "
                    + "restart in dimension " + world.provider.getDimension() + ".");
            }
        });
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    /** Who a caller is, for budgets and ownership: one key per player, one for the endpoint. */
    public static String callerOf(Principal principal) {
        return principal.isCompanion() ? "player:" + principal.getPlayerId() : "endpoint";
    }

    // ------------------------------------------------------------------
    // Asking for chunks
    // ------------------------------------------------------------------

    /** Why loading was refused or given up on, as the structured {@code busy} block. */
    public static final class Busy extends Exception {

        private static final long serialVersionUID = 1L;

        private final transient JsonObject busy;

        Busy(String message, JsonObject busy) {
            super(message);
            this.busy = busy;
        }

        /** The tool error a caller returns: the message, with {@code {"busy": {...}}} beside it. */
        public ToolResult toResult() {
            JsonObject structured = new JsonObject();
            structured.add("busy", busy);
            return ToolResult.error(getMessage()).withStructured(structured);
        }
    }

    /** What getting the chunks cost; becomes a result's {@code chunks} block. */
    public static final class Outcome {

        public final String holdId;
        public final int inBox;
        int loaded;
        int alreadyLoaded;
        int held;
        int ungenerated;
        long waitedMillis;
        final List<Long> ungeneratedChunks = new ArrayList<>();

        Outcome(String holdId, int inBox) {
            this.holdId = holdId;
            this.inBox = inBox;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("inBox", inBox);
            json.addProperty("loaded", loaded);
            json.addProperty("alreadyLoaded", alreadyLoaded);
            json.addProperty("held", held);
            json.addProperty("unloaded", 0);
            json.addProperty("ungenerated", ungenerated);
            json.addProperty("waitedMs", waitedMillis);
            if (!ungeneratedChunks.isEmpty()) {
                json.add("ungeneratedChunks", chunkList(ungeneratedChunks));
            }
            return json;
        }
    }

    private static final class Request {

        final HoldLedger.Hold hold;
        final Outcome outcome;
        final Deque<Long> pending;
        final CompletableFuture<Outcome> done = new CompletableFuture<>();
        final long startedMillis;

        Request(HoldLedger.Hold hold, Outcome outcome, Deque<Long> pending, long startedMillis) {
            this.hold = hold;
            this.outcome = outcome;
            this.pending = pending;
            this.startedMillis = startedMillis;
        }
    }

    /**
     * Holds {@code chunks} loaded for the caller, loading what is not loaded at the governor's pace,
     * and returns once they are in memory. Blocks the calling worker; never call it on the server
     * thread.
     *
     * @param temporary true for a single call's {@code load: true}, released by {@link #release} when
     *                  the call ends; false for a hold that lasts until it idles out or is released
     * @throws Busy when a limit refuses it, the queue is full, or the server stayed too busy to load
     *              within {@code chunks.maxWaitSeconds}; the hold is released in each case
     */
    public static Outcome acquire(ToolContext context, final int dimension, final Set<Long> chunks,
                                  final boolean temporary, final long idleMillis, final long maxMillis) throws Busy {
        final String caller = callerOf(context.getPrincipal());
        final long started = System.currentTimeMillis();
        Object admitted = context.onGameThread(() -> admit(caller, dimension, chunks, temporary, idleMillis,
            maxMillis, started));
        if (admitted instanceof Busy) {
            throw (Busy) admitted;
        }
        Request request = (Request) admitted;
        long deadline = started + McmcpConfig.getChunkMaxWaitSeconds() * 1000L;
        int total = request.pending.size();
        while (true) {
            try {
                Outcome outcome = request.done.get(250L, TimeUnit.MILLISECONDS);
                outcome.waitedMillis = System.currentTimeMillis() - started;
                return outcome;
            }
            catch (TimeoutException waiting) {
                if (context.getCancellation().isCancelled() || System.currentTimeMillis() > deadline) {
                    giveUp(request, context.getCancellation().isCancelled() ? "cancelled" : null);
                }
                else if (total > 0) {
                    int left = request.pending.size();
                    context.reportProgress(total - left, total, "Loading chunks: " + (total - left) + " of " + total);
                }
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                giveUp(request, "cancelled");
            }
            catch (ExecutionException failed) {
                if (failed.getCause() instanceof Busy) {
                    throw (Busy) failed.getCause();
                }
                throw new IllegalStateException(failed.getCause());
            }
        }
    }

    /** Fails a request still waiting, from its worker; the server thread releases its hold. */
    private static void giveUp(final Request request, @Nullable final String why) {
        MinecraftServer server = ServerThreadBridge.server();
        if (server == null) {
            request.done.completeExceptionally(new Busy("The server stopped.", busy("stopped", null, 0L)));
            return;
        }
        server.addScheduledTask(() -> {
            if (request.done.isDone()) {
                return;
            }
            QUEUE.remove(request);
            releaseNow(request.hold.id);
            request.done.completeExceptionally(new Busy("cancelled".equals(why)
                ? "The call was cancelled while waiting for chunks to load."
                : "The server stayed too busy to load " + request.pending.size() + " more chunk(s) within "
                    + "chunks.maxWaitSeconds (" + McmcpConfig.getChunkMaxWaitSeconds() + " s). Retry after "
                    + "busy.retryAfterMs.", busy(HEALTH.state() == ServerHealth.State.OK ? "throttled"
                    : HEALTH.state().name().toLowerCase(java.util.Locale.ROOT), null,
                    retryAfter(request.hold.caller))));
        });
    }

    /** Server thread: limits, the hold itself, and what is already loaded. */
    private static Object admit(String caller, int dimension, Set<Long> chunks, boolean temporary,
                                long idleMillis, long maxMillis, long nowMillis) {
        WorldServer world = DimensionManager.getWorld(dimension);
        if (world == null) {
            return new Busy("Dimension " + dimension + " is not loaded.", busy("limit", "dimension", 0L));
        }
        int adding = LEDGER.added(dimension, chunks);
        int maxTotal = McmcpConfig.getChunkMaxHeld();
        int maxCaller = McmcpConfig.getChunkMaxHeldPerCaller();
        if (LEDGER.heldChunks() + adding > maxTotal) {
            return new Busy("That needs " + adding + " more chunk(s) held, and MCMCP already holds "
                + LEDGER.heldChunks() + " of the " + maxTotal + " chunks.maxHeldChunks allows. Release a hold "
                + "or ask for less.", busy("limit", "chunks.maxHeldChunks", 0L));
        }
        if (LEDGER.heldChunks(caller) + adding > maxCaller) {
            return new Busy("That needs " + adding + " more chunk(s) held, and you already hold "
                + LEDGER.heldChunks(caller) + " of the " + maxCaller + " chunks.maxHeldChunksPerCaller allows. "
                + "Release a hold (server_release_loaded) or work in smaller pieces.",
                busy("limit", "chunks.maxHeldChunksPerCaller", 0L));
        }
        if (QUEUE.size() >= MAX_QUEUED_REQUESTS) {
            return new Busy("MCMCP chunk loading is saturated: " + QUEUE.size() + " requests are waiting.",
                busy("saturated", null, retryAfter(caller)));
        }

        HoldLedger.Hold hold = LEDGER.create(caller, dimension, chunks, temporary, nowMillis, idleMillis, maxMillis);
        LEDGER.add(hold);
        Outcome outcome = new Outcome(hold.id, chunks.size());
        Deque<Long> pending = new ArrayDeque<>();
        ChunkProviderServer provider = world.getChunkProvider();
        for (long chunk : chunks) {
            int cx = ChunkKeys.x(chunk);
            int cz = ChunkKeys.z(chunk);
            HoldLedger.Place place = new HoldLedger.Place(dimension, chunk);
            if (FORCED.containsKey(place)) {
                outcome.held++;
                outcome.alreadyLoaded++;
            }
            else if (provider.chunkExists(cx, cz)) {
                outcome.alreadyLoaded++;
                force(world, place);
            }
            else if (!provider.isChunkGeneratedAt(cx, cz)) {
                ungenerated(outcome, chunk);
            }
            else {
                pending.add(chunk);
            }
        }
        Request request = new Request(hold, outcome, pending, nowMillis);
        if (pending.isEmpty()) {
            request.done.complete(outcome);
        }
        else {
            QUEUE.add(request);
        }
        return request;
    }

    /** Ends a hold, from any thread. Chunks no hold needs are unforced and, if MCMCP loaded them, unloaded. */
    public static void release(String holdId) {
        MinecraftServer server = ServerThreadBridge.server();
        if (server != null) {
            server.addScheduledTask(() -> releaseNow(holdId));
        }
    }

    /** Ends one caller's holds: on logout, when their companion access goes. */
    public static void releaseCaller(String caller) {
        MinecraftServer server = ServerThreadBridge.server();
        if (server != null) {
            server.addScheduledTask(() -> {
                for (String id : LEDGER.ownedBy(caller)) {
                    releaseNow(id);
                }
            });
        }
    }

    /** Server thread. */
    static void releaseNow(String holdId) {
        HoldLedger.Hold hold = LEDGER.get(holdId);
        if (hold == null) {
            return;
        }
        for (Iterator<Request> it = QUEUE.iterator(); it.hasNext(); ) {
            Request request = it.next();
            if (request.hold.id.equals(holdId)) {
                it.remove();
                request.done.completeExceptionally(new Busy("The hold was released before its chunks loaded.",
                    busy("released", null, 0L)));
            }
        }
        WorldServer world = DimensionManager.getWorld(hold.dimension);
        for (HoldLedger.Released released : LEDGER.remove(holdId)) {
            unforce(world, released);
        }
    }

    /** Ends every hold and returns every ticket. Server stopping. */
    public static void releaseAll() {
        for (HoldLedger.Hold hold : LEDGER.holds()) {
            releaseNow(hold.id);
        }
        for (List<ForgeChunkManager.Ticket> tickets : TICKETS.values()) {
            for (ForgeChunkManager.Ticket ticket : tickets) {
                ForgeChunkManager.releaseTicket(ticket);
            }
        }
        TICKETS.clear();
        FORCED.clear();
        QUEUE.clear();
    }

    /**
     * Extends a hold's idle timer and lease; {@code maxMillis} counts from now. Server thread.
     *
     * @return null, or why it could not
     */
    @Nullable
    public static String renew(String holdId, String caller, long maxMillis) {
        HoldLedger.Hold hold = LEDGER.get(holdId);
        if (hold == null || hold.temporary) {
            return "There is no hold '" + holdId + "'.";
        }
        if (!hold.caller.equals(caller) && !"endpoint".equals(caller)) {
            return "Hold '" + holdId + "' is not yours.";
        }
        long now = System.currentTimeMillis();
        hold.lastUsedMillis = now;
        hold.maxMillis = (now - hold.createdMillis) + maxMillis;
        return null;
    }

    /** Using chunks a hold contains keeps it alive. Server thread. */
    public static void touch(int dimension, Set<Long> chunks) {
        LEDGER.touch(dimension, chunks, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // Forcing and unloading
    // ------------------------------------------------------------------

    private static void force(WorldServer world, HoldLedger.Place place) {
        if (FORCED.containsKey(place)) {
            return;
        }
        ForgeChunkManager.Ticket ticket = ticketWithRoom(world);
        if (ticket == null) {
            // Every Forge ticket MCMCP may have is full. The chunk stays loaded for now and is simply
            // not protected from unloading; the hold says so through the log rather than failing.
            Mcmcp.LOGGER.warn("MCMCP is out of Forge chunk tickets (forgeChunkLoading.cfg); a held chunk "
                + "is not force-loaded.");
            return;
        }
        ForgeChunkManager.forceChunk(ticket, new ChunkPos(ChunkKeys.x(place.chunk), ChunkKeys.z(place.chunk)));
        FORCED.put(place, ticket);
    }

    /** A ticket for this dimension with room for one more chunk; a new one when all are full. */
    @Nullable
    private static ForgeChunkManager.Ticket ticketWithRoom(WorldServer world) {
        int dimension = world.provider.getDimension();
        List<ForgeChunkManager.Ticket> tickets = TICKETS.computeIfAbsent(dimension, key -> new ArrayList<>());
        for (ForgeChunkManager.Ticket ticket : tickets) {
            // Forge silently evicts the oldest chunk past this depth, so never go past it.
            if (ticket.getChunkList().size() < ticket.getMaxChunkListDepth()) {
                return ticket;
            }
        }
        ForgeChunkManager.Ticket fresh = ForgeChunkManager.requestTicket(Mcmcp.instance, world,
            ForgeChunkManager.Type.NORMAL);
        if (fresh != null) {
            tickets.add(fresh);
        }
        return fresh;
    }

    /**
     * Gives a chunk back. Unforced always; unloaded only if MCMCP loaded it, no player can see it and
     * nothing else forces it. Queued for the server to unload this tick or the next, rather than at
     * the next autosave.
     */
    private static void unforce(@Nullable WorldServer world, HoldLedger.Released released) {
        ForgeChunkManager.Ticket ticket = FORCED.remove(released.place);
        int cx = ChunkKeys.x(released.place.chunk);
        int cz = ChunkKeys.z(released.place.chunk);
        if (ticket != null) {
            ForgeChunkManager.unforceChunk(ticket, new ChunkPos(cx, cz));
            if (ticket.getChunkList().isEmpty()) {
                ForgeChunkManager.releaseTicket(ticket);
                List<ForgeChunkManager.Ticket> tickets = TICKETS.get(released.place.dimension);
                if (tickets != null) {
                    tickets.remove(ticket);
                }
            }
        }
        if (world == null || !released.loadedByUs) {
            return;
        }
        if (world.getPlayerChunkMap().contains(cx, cz)) {
            return;
        }
        if (world.getPersistentChunks().containsKey(new ChunkPos(cx, cz))) {
            return;
        }
        Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
        if (chunk != null) {
            world.getChunkProvider().queueUnload(chunk);
        }
    }

    // ------------------------------------------------------------------
    // The tick
    // ------------------------------------------------------------------

    private static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        BUDGET.newTick();
        if (++ticks % 20 == 0) {
            measure(server, now);
        }
        int perTick = ServerHealth.perTick(HEALTH.state(), McmcpConfig.getChunkLoadsPerTick());
        for (Iterator<Request> it = QUEUE.iterator(); it.hasNext() && perTick > 0; ) {
            Request request = it.next();
            WorldServer world = DimensionManager.getWorld(request.hold.dimension);
            if (world == null) {
                it.remove();
                releaseNow(request.hold.id);
                request.done.completeExceptionally(new Busy("Dimension " + request.hold.dimension
                    + " unloaded.", busy("limit", "dimension", 0L)));
                continue;
            }
            int allowed = BUDGET.allowance(request.hold.caller, request.pending.size(), now, perTick,
                McmcpConfig.getChunkLoadsPerMinute(), McmcpConfig.getChunkLoadsPerMinutePerCaller());
            int loads = 0;
            ChunkProviderServer provider = world.getChunkProvider();
            while (!request.pending.isEmpty() && loads < allowed) {
                long chunk = request.pending.pollFirst();
                int cx = ChunkKeys.x(chunk);
                int cz = ChunkKeys.z(chunk);
                HoldLedger.Place place = new HoldLedger.Place(request.hold.dimension, chunk);
                if (provider.chunkExists(cx, cz)) {
                    request.outcome.alreadyLoaded++;
                    force(world, place);
                    continue;
                }
                // loadChunk reads a chunk from disk and returns null for one that is not there; it
                // never generates. provideChunk would.
                Chunk loaded = provider.loadChunk(cx, cz);
                loads++;
                if (loaded == null) {
                    ungenerated(request.outcome, chunk);
                    continue;
                }
                request.outcome.loaded++;
                LEDGER.markLoadedByUs(request.hold.dimension, chunk);
                force(world, place);
            }
            BUDGET.record(request.hold.caller, loads, now);
            perTick -= loads;
            if (request.pending.isEmpty()) {
                it.remove();
                request.done.complete(request.outcome);
            }
        }
        if (ticks % 200 == 0) {
            housekeeping(now);
        }
    }

    private static void measure(MinecraftServer server, long now) {
        long total = 0L;
        for (long nanos : server.tickTimeArray) {
            total += nanos;
        }
        double meanMspt = total / (double) server.tickTimeArray.length / 1.0e6D;
        ServerHealth.State before = HEALTH.state();
        ServerHealth.State after = HEALTH.update(meanMspt, heapPercentAfterGc(), now,
            McmcpConfig.getChunkThrottleMspt(), McmcpConfig.getChunkPauseMspt(),
            McmcpConfig.getChunkPauseHeapPercent());
        if (after != before) {
            Mcmcp.LOGGER.warn("MCMCP chunk loading is now " + after.name().toLowerCase(java.util.Locale.ROOT)
                + ": mean tick " + Math.round(meanMspt * 10.0D) / 10.0D + " ms, heap after GC "
                + Math.round(HEALTH.lastHeapPercent()) + "%, MCMCP holds " + LEDGER.heldChunks() + " chunk(s).");
        }
        if (after == ServerHealth.State.PAUSED
            && HEALTH.pausedForMillis(now) > McmcpConfig.getChunkShedAfterSeconds() * 1000L) {
            shedOne(now);
        }
    }

    /** Gives back one hold while the server stays paused: the longest idle first. */
    private static void shedOne(long now) {
        for (String id : LEDGER.shedOrder()) {
            HoldLedger.Hold hold = LEDGER.get(id);
            if (hold == null || hold.temporary) {
                continue;
            }
            Mcmcp.LOGGER.warn("MCMCP released hold " + id + " (" + hold.chunks.size() + " chunks, " + hold.caller
                + ") because the server has been struggling for " + HEALTH.pausedForMillis(now) / 1000L + " s.");
            synchronized (SHED_LOG) {
                SHED_LOG.add(id + " released: server under load");
                if (SHED_LOG.size() > 20) {
                    SHED_LOG.remove(0);
                }
            }
            releaseNow(id);
            return;
        }
    }

    private static void housekeeping(long now) {
        for (String id : LEDGER.expired(now)) {
            releaseNow(id);
        }
        // Reconcile: a forced chunk no hold references is a leak; give it back.
        for (HoldLedger.Place place : new ArrayList<>(FORCED.keySet())) {
            if (!LEDGER.isHeld(place.dimension, place.chunk)) {
                Mcmcp.LOGGER.warn("MCMCP found a forced chunk no hold uses, " + ChunkKeys.describe(place.chunk)
                    + " in dimension " + place.dimension + "; releasing it.");
                unforce(DimensionManager.getWorld(place.dimension), new HoldLedger.Released(place, false));
            }
        }
    }

    /** Old-generation heap in use after the last collection, as a percentage of its maximum. */
    static double heapPercentAfterGc() {
        long used = 0L;
        long max = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) {
                continue;
            }
            String name = pool.getName();
            if (!name.contains("Old") && !name.contains("Tenured")) {
                continue;
            }
            MemoryUsage afterGc = pool.getCollectionUsage();
            if (afterGc != null && afterGc.getMax() > 0L) {
                used += afterGc.getUsed();
                max += afterGc.getMax();
            }
        }
        if (max <= 0L) {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            return heap.getMax() > 0L ? heap.getUsed() * 100.0D / heap.getMax() : 0.0D;
        }
        return used * 100.0D / max;
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    private static long retryAfter(String caller) {
        long now = System.currentTimeMillis();
        long budget = BUDGET.millisUntilRoom(caller, now, McmcpConfig.getChunkLoadsPerMinute(),
            McmcpConfig.getChunkLoadsPerMinutePerCaller());
        long health = HEALTH.millisUntilRecovery(now);
        long queue = QUEUE.isEmpty() ? 0L : 1_000L;
        return Math.max(1_000L, Math.max(budget, Math.max(health, queue)));
    }

    private static JsonObject busy(String reason, @Nullable String limit, long retryAfterMillis) {
        JsonObject json = new JsonObject();
        json.addProperty("reason", reason);
        if (limit != null) {
            json.addProperty("limit", limit);
        }
        json.addProperty("mspt", Math.round(HEALTH.lastMspt() * 10.0D) / 10.0D);
        json.addProperty("heapPercent", Math.round(HEALTH.lastHeapPercent()));
        if (retryAfterMillis > 0L) {
            json.addProperty("retryAfterMs", retryAfterMillis);
        }
        return json;
    }

    private static void ungenerated(Outcome outcome, long chunk) {
        outcome.ungenerated++;
        if (outcome.ungeneratedChunks.size() < MAX_LISTED) {
            outcome.ungeneratedChunks.add(chunk);
        }
    }

    static JsonArray chunkList(List<Long> chunks) {
        JsonArray array = new JsonArray();
        for (long chunk : chunks) {
            JsonArray pair = new JsonArray();
            pair.add(ChunkKeys.x(chunk));
            pair.add(ChunkKeys.z(chunk));
            array.add(pair);
        }
        return array;
    }

    /**
     * The {@code chunks} block for a call that did not load: what the box contains, loaded or not.
     * Server thread. An empty read over chunks that are not loaded must never look like empty ground.
     */
    public static JsonObject describeUnloaded(WorldServer world, Set<Long> chunks) {
        ChunkProviderServer provider = world.getChunkProvider();
        int loaded = 0;
        int held = 0;
        int unloaded = 0;
        int ungenerated = 0;
        List<Long> missing = new ArrayList<>();
        List<Long> neverGenerated = new ArrayList<>();
        int dimension = world.provider.getDimension();
        for (long chunk : chunks) {
            int cx = ChunkKeys.x(chunk);
            int cz = ChunkKeys.z(chunk);
            if (provider.chunkExists(cx, cz)) {
                loaded++;
                if (LEDGER.isHeld(dimension, chunk)) {
                    held++;
                }
            }
            else if (provider.isChunkGeneratedAt(cx, cz)) {
                unloaded++;
                if (missing.size() < MAX_LISTED) {
                    missing.add(chunk);
                }
            }
            else {
                ungenerated++;
                if (neverGenerated.size() < MAX_LISTED) {
                    neverGenerated.add(chunk);
                }
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("inBox", chunks.size());
        json.addProperty("loaded", 0);
        json.addProperty("alreadyLoaded", loaded);
        json.addProperty("held", held);
        json.addProperty("unloaded", unloaded);
        json.addProperty("ungenerated", ungenerated);
        if (!missing.isEmpty()) {
            json.add("unloadedChunks", chunkList(missing));
        }
        if (!neverGenerated.isEmpty()) {
            json.add("ungeneratedChunks", chunkList(neverGenerated));
        }
        if (unloaded > 0) {
            json.addProperty("note", "Cells in unloaded chunks were not read. Pass load: true, or hold the "
                + "region with server_keep_loaded, to read them.");
        }
        return json;
    }

    /** For {@code /mcmcp chunks}, {@code server_tick_stats} and {@code game_health}. Server thread. */
    public static JsonObject status(@Nullable String onlyCaller) {
        long now = System.currentTimeMillis();
        JsonObject json = new JsonObject();
        json.addProperty("state", HEALTH.state().name().toLowerCase(java.util.Locale.ROOT));
        json.addProperty("meanTickMs", Math.round(HEALTH.lastMspt() * 10.0D) / 10.0D);
        json.addProperty("heapPercentAfterGc", Math.round(HEALTH.lastHeapPercent()));
        json.addProperty("heldChunks", LEDGER.heldChunks());
        json.addProperty("maxHeldChunks", McmcpConfig.getChunkMaxHeld());
        json.addProperty("loadsLastMinute", BUDGET.lastMinute(now));
        json.addProperty("waitingRequests", QUEUE.size());
        JsonArray holds = new JsonArray();
        for (HoldLedger.Hold hold : LEDGER.holds()) {
            if (onlyCaller != null && !onlyCaller.equals(hold.caller)) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", hold.id);
            entry.addProperty("owner", hold.caller);
            entry.addProperty("dimension", hold.dimension);
            entry.addProperty("chunks", hold.chunks.size());
            entry.addProperty("temporary", hold.temporary);
            entry.addProperty("expiresInSeconds", Math.max(0L, (hold.expiresAtMillis() - now) / 1000L));
            holds.add(entry);
        }
        json.add("holds", holds);
        synchronized (SHED_LOG) {
            if (!SHED_LOG.isEmpty()) {
                json.add("recentlyShed", Json.arrayOfStrings(new ArrayList<>(SHED_LOG)));
            }
        }
        return json;
    }

    /** The chunk set a list of block positions touches, for {@code load: true} on a list write. */
    public static Set<Long> chunksOf(List<int[]> xz) {
        Set<Long> keys = new LinkedHashSet<>();
        for (int[] pair : xz) {
            keys.add(ChunkKeys.ofBlock(pair[0], pair[1]));
        }
        return keys;
    }

    /** Releases what a server's MCMCP held when the server stops. */
    public static void serverStopping() {
        releaseAll();
    }

    /** On Forge's bus. */
    public static final class Events {

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            MinecraftServer server = ServerThreadBridge.server();
            if (server != null) {
                tick(server);
            }
        }

        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            String caller = "player:" + event.player.getUniqueID();
            for (String id : LEDGER.ownedBy(caller)) {
                releaseNow(id);
            }
        }
    }
}
