package com.micatechnologies.minecraft.mcmcp.chunkload;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import com.micatechnologies.minecraft.mcmcp.game.ServerLoad;
import com.micatechnologies.minecraft.mcmcp.game.ServerTaskPump;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.game.TaskCaller;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.mcp.Principal;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.perf.TickClock;
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
import java.util.concurrent.atomic.AtomicLong;
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
 * <p>The one exception is {@link ChunkPregen}, which generates on purpose for
 * {@code server_pregen_chunks} and is paced by its caller in chunks per tick.
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

    /** A read inside a hold whose lease ends within this says so. */
    private static final long LEASE_WARNING_MILLIS = 120_000L;

    private static final HoldLedger LEDGER = new HoldLedger();
    private static final LoadBudget BUDGET = new LoadBudget();
    private static final ServerHealth HEALTH = new ServerHealth();
    /**
     * The same readings against health.holdJobsMspt and health.refuseWritesMspt: what jobs, calls that
     * change the world and the ops' alerts go by ({@link ServerLoad}). Looser than chunk loading's,
     * since refusing an agent's write costs far more than loading a chunk a little later.
     */
    private static final ServerHealth CALLS = new ServerHealth();
    private static final Deque<Request> QUEUE = new ArrayDeque<>();
    private static final Map<Integer, List<ForgeChunkManager.Ticket>> TICKETS = new HashMap<>();
    private static final Map<HoldLedger.Place, ForgeChunkManager.Ticket> FORCED = new HashMap<>();
    private static final List<String> SHED_LOG = new ArrayList<>();

    private static int ticks;

    /** Server thread: when the health was last measured. */
    private static long lastMeasuredMillis;

    /** Server thread: when this server's first tick was seen, by nanoTime; 0 before it. */
    private static long firstTickNanos;

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

    /**
     * Who a call's chunk holds belong to, for the per-caller cap and ownership (#121). A player is
     * one caller however many agents they run, since their access is theirs. On the endpoint each
     * agent is its own — through the orchestrator every agent shares one link session, and keyed on
     * the endpoint alone five agents shared one caller's cap and starved each other. A background
     * job holds under {@code job:<id>}, a caller of its own.
     */
    public static String holdCallerOf(ToolContext context) {
        return context.callerKey();
    }

    /** How long a call refused by a hold cap waits for calls in progress to give chunks back. */
    private static final long CAP_WAIT_MILLIS = 10_000L;

    /** How long a call that gave up waiting for loads waits for the server thread to hear of it. */
    private static final long GIVE_UP_MILLIS = 15_000L;

    // ------------------------------------------------------------------
    // Asking for chunks
    // ------------------------------------------------------------------

    /** Why loading was refused or given up on, as the structured {@code busy} block. */
    public static final class Busy extends Exception {

        private static final long serialVersionUID = 1L;

        private final transient JsonObject busy;

        /** A hold cap that calls in progress count against: it may clear in a moment. */
        private boolean capMayClear;

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
        /** When the call was done with the hold, or 0 while it is not; see {@link HoldLedger.Hold#callEndedAt}. */
        final AtomicLong callEnded;

        Outcome(String holdId, int inBox, AtomicLong callEnded) {
            this.holdId = holdId;
            this.inBox = inBox;
            this.callEnded = callEnded;
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
        // It waits for server ticks to load the chunks, and on the server thread those never come.
        MinecraftServer running = ServerThreadBridge.server();
        if (running != null && running.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("ChunkLoadGovernor.acquire was called on the server thread, where "
                + "waiting for chunk loads would wait forever. Call it from the tool's worker.");
        }
        final String caller = holdCallerOf(context);
        final String label = context.getHoldCaller() != null || context.getPrincipal().isCompanion()
            ? null : context.getCaller().label();
        final long started = System.currentTimeMillis();
        // A call's own hold has no idle timer, only a lease longer than any call runs: what ends it is the
        // call, through release(Outcome), and callEnded says when that has happened.
        final long lease = temporary ? temporaryLeaseMillis() : maxMillis;
        final AtomicLong callEnded = new AtomicLong();
        try {
            return acquire(context, caller, label, dimension, chunks, temporary, idleMillis, lease, started, callEnded);
        }
        catch (Busy | RuntimeException e) {
            endCall(callEnded);
            throw e;
        }
    }

    /** Marks a call as done with its hold, the first time only. */
    private static void endCall(AtomicLong callEnded) {
        callEnded.compareAndSet(0L, System.currentTimeMillis());
    }

    /**
     * A temporary hold's lease: the longest a hold may ask for, and never under an hour, so it outlives
     * any call — a test run as a job lasts up to 30 minutes — and still ends if its release was lost.
     */
    static long temporaryLeaseMillis() {
        return Math.max(3_600L, McmcpConfig.getChunkHoldLongestLeaseSeconds()) * 1000L;
    }

    private static Outcome acquire(ToolContext context, final String caller, @Nullable final String label,
                                   final int dimension, final Set<Long> chunks, final boolean temporary,
                                   final long idleMillis, final long maxMillis, final long started,
                                   final AtomicLong callEnded) throws Busy {
        Object admitted;
        while (true) {
            // If this call stops waiting while admit runs, the hold admit takes is nobody's: give it back
            // as soon as admit ends (#132). If it stops waiting before admit starts, admit never runs.
            admitted = context.onGameThreadWithCleanup(() -> admit(caller, label, dimension, chunks, temporary,
                idleMillis, maxMillis, System.currentTimeMillis(), callEnded), orphan -> {
                    endCall(callEnded);
                    if (orphan instanceof Request) {
                        releaseNow(((Request) orphan).hold.id, "its call stopped waiting for the server thread");
                    }
                });
            // A cap that calls in progress count against clears when they end, usually within seconds:
            // a short wait then saves the caller a retry round. A lasting hold does not end that soon,
            // so a cap made of those is refused at once.
            if (!(admitted instanceof Busy) || !((Busy) admitted).capMayClear
                || System.currentTimeMillis() - started >= CAP_WAIT_MILLIS || context.getCancellation().isCancelled()) {
                break;
            }
            try {
                Thread.sleep(250L);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (admitted instanceof Busy) {
            throw (Busy) admitted;
        }
        Request request = (Request) admitted;
        // The wait is measured from the last chunk this request got, not from when it asked: several
        // calls sharing one player's budget each move slowly, and a read that is still moving is not
        // stuck (#77).
        long patience = McmcpConfig.getChunkMaxWaitSeconds() * 1000L;
        long lastProgress = started;
        int total = request.pending.size();
        int lastLeft = total;
        boolean gaveUp = false;
        long gaveUpAt = 0L;
        boolean wasInterrupted = false;
        try {
            while (true) {
                try {
                    Outcome outcome = request.done.get(250L, TimeUnit.MILLISECONDS);
                    outcome.waitedMillis = System.currentTimeMillis() - started;
                    return outcome;
                }
                catch (TimeoutException waiting) {
                    long now = System.currentTimeMillis();
                    int leftNow = request.pending.size();
                    if (leftNow < lastLeft) {
                        lastLeft = leftNow;
                        lastProgress = now;
                    }
                    if (context.getCancellation().isCancelled() || now - lastProgress > patience) {
                        // Once: on a server too far behind to run it soon, one more task every 250 ms for the
                        // same request only adds to the queue it is stuck behind.
                        if (!gaveUp) {
                            gaveUp = true;
                            gaveUpAt = now;
                            giveUp(request, context.getCancellation().isCancelled() ? "cancelled" : null);
                        }
                        else if (now - gaveUpAt > GIVE_UP_MILLIS) {
                            // The server thread has not even run the give-up: it is stalled. Stop waiting
                            // for it; the give-up still releases the hold when it runs, and if it never
                            // does, the hold reads as its call's gone and is expired (#132).
                            throw new Busy("The server thread did not respond while this call waited for its "
                                + "chunks; it is " + ServerLoad.describe() + ". Retry after busy.retryAfterMs.",
                                ServerLoad.busy("server_overloaded"));
                        }
                    }
                    else if (total > 0) {
                        int left = request.pending.size();
                        context.reportProgress(total - left, total, "Loading chunks: " + (total - left) + " of " + total);
                    }
                }
                catch (InterruptedException interrupted) {
                    // Not re-flagged until this returns: with the flag set, every get() would throw at once and
                    // this loop would spin until the server thread got round to the give-up.
                    wasInterrupted = true;
                    if (!gaveUp) {
                        gaveUp = true;
                        giveUp(request, "cancelled");
                    }
                }
                catch (ExecutionException failed) {
                    if (failed.getCause() instanceof Busy) {
                        // Whoever failed it released the hold.
                        throw (Busy) failed.getCause();
                    }
                    release(request.hold.id, "its call failed while its chunks loaded");
                    throw new IllegalStateException(failed.getCause());
                }
            }
        }
        finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
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
        ServerTaskPump.runLater(TaskCaller.MCMCP, () -> {
            if (request.done.isDone()) {
                return;
            }
            QUEUE.remove(request);
            releaseNow(request.hold.id, "it gave up waiting");
            request.done.completeExceptionally(new Busy("cancelled".equals(why)
                ? "The call was cancelled while waiting for chunks to load."
                : "No chunk of this call's " + request.pending.size() + " still to load came in "
                    + "chunks.maxWaitSeconds (" + McmcpConfig.getChunkMaxWaitSeconds() + " s); " + QUEUE.size()
                    + " call(s) are waiting for loads. Retry after busy.retryAfterMs, or run long reads as a job, "
                    + "which waits as long as it needs.", busy(HEALTH.state() == ServerHealth.State.OK ? "throttled"
                    : HEALTH.state().name().toLowerCase(java.util.Locale.ROOT), null,
                    retryAfter(request.hold.caller))));
        });
    }

    /** Server thread: limits, the hold itself, and what is already loaded. */
    private static Object admit(String caller, @Nullable String label, int dimension, Set<Long> chunks,
                                boolean temporary, long idleMillis, long maxMillis, long nowMillis,
                                AtomicLong callEnded) {
        if (callEnded.get() != 0L) {
            // The call left while this waited its turn; take nothing for it.
            return new Busy("The call ended before its chunks were admitted.", busy("released", null, 0L));
        }
        WorldServer world = DimensionManager.getWorld(dimension);
        if (world == null) {
            return new Busy("Dimension " + dimension + " is not loaded.", busy("limit", "dimension", 0L));
        }
        HoldLedger.CapRefusal cap = LEDGER.checkCaps(caller, dimension, chunks, McmcpConfig.getChunkMaxHeld(),
            McmcpConfig.getChunkMaxHeldPerCaller());
        if (cap != null) {
            return capBusy(cap);
        }
        if (QUEUE.size() >= MAX_QUEUED_REQUESTS) {
            return new Busy("MCMCP chunk loading is saturated: " + QUEUE.size() + " requests are waiting.",
                busy("saturated", null, retryAfter(caller)));
        }

        HoldLedger.Hold hold = LEDGER.create(caller, dimension, chunks, temporary, nowMillis, idleMillis, maxMillis);
        hold.label = label;
        if (temporary) {
            hold.callEndedAt = callEnded::get;
        }
        LEDGER.add(hold);
        Outcome outcome = new Outcome(hold.id, chunks.size(), callEnded);
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

    /**
     * Ends the hold a call took, from its worker, when the call is done with it. Says so at once, so the
     * hold reads as nobody's even while the release waits its turn on a busy server thread.
     */
    public static void release(Outcome outcome, String why) {
        endCall(outcome.callEnded);
        release(outcome.holdId, why);
    }

    /** Ends a hold, from any thread. Chunks no hold needs are unforced and, if MCMCP loaded them, unloaded. */
    public static void release(String holdId, String why) {
        MinecraftServer server = ServerThreadBridge.server();
        if (server != null) {
            ServerTaskPump.runLater(TaskCaller.MCMCP, () -> releaseNow(holdId, why));
        }
    }

    /**
     * Ends one caller's holds. With {@code callsToo} false only the holds the caller asked for are ended
     * — never a hold another call of theirs is still loading or using, because one account can run
     * several scripts at once and one cleaning up must not fail the others' reads. Logout and lost
     * companion access pass true: nothing of theirs should outlive them.
     */
    public static void releaseCaller(String caller, boolean callsToo, String why) {
        MinecraftServer server = ServerThreadBridge.server();
        if (server != null) {
            ServerTaskPump.runLater(TaskCaller.MCMCP, () -> {
                for (String id : LEDGER.ownedBy(caller, callsToo)) {
                    releaseNow(id, why);
                }
            });
        }
    }

    /**
     * Ends one caller's own holds now, never the temporary holds of their calls still running. Server
     * thread.
     *
     * @return the ids ended
     */
    public static List<String> releaseCallerNow(String caller, String why) {
        List<String> ids = LEDGER.ownedBy(caller, false);
        for (String id : ids) {
            releaseNow(id, why);
        }
        return ids;
    }

    /** Marks a hold to be shed only after every other lasting hold. Server thread. */
    public static void pin(String holdId) {
        HoldLedger.Hold hold = LEDGER.get(holdId);
        if (hold != null) {
            hold.pinned = true;
        }
    }

    /** Who owns a hold, or null if there is no such hold. Server thread. */
    @Nullable
    public static String ownerOf(String holdId) {
        HoldLedger.Hold hold = LEDGER.get(holdId);
        return hold == null ? null : hold.caller;
    }

    /** Distinct chunks one caller holds. Server thread. */
    public static int heldChunks(String caller) {
        return LEDGER.heldChunks(caller);
    }

    /**
     * Server thread.
     *
     * @param why what released it, finishing "The hold was released before its chunks loaded: …", so a
     *            call that fails because of it says which path did
     */
    public static void releaseNow(String holdId, String why) {
        HoldLedger.Hold hold = LEDGER.get(holdId);
        if (hold == null) {
            return;
        }
        for (Iterator<Request> it = QUEUE.iterator(); it.hasNext(); ) {
            Request request = it.next();
            if (request.hold.id.equals(holdId)) {
                it.remove();
                Mcmcp.LOGGER.info("MCMCP released hold " + holdId + " (" + hold.caller + ") while it was still "
                    + "loading: " + why + ".");
                request.done.completeExceptionally(new Busy("The hold was released before its chunks loaded: "
                    + why + ".", busy("released", null, 0L)));
            }
        }
        WorldServer world = DimensionManager.getWorld(hold.dimension);
        for (HoldLedger.Released released : LEDGER.remove(holdId)) {
            unforce(world, released);
        }
    }

    /** Ends every hold and returns every ticket. Server stopping. */
    public static void releaseAll(String why) {
        for (HoldLedger.Hold hold : LEDGER.holds()) {
            releaseNow(hold.id, why);
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
     * @param idleMillis a new idle timeout, or a negative number to keep the hold's own
     * @return null, or why it could not
     */
    @Nullable
    public static String renew(String holdId, String caller, long maxMillis, long idleMillis) {
        HoldLedger.Hold hold = LEDGER.get(holdId);
        if (hold == null || hold.temporary) {
            return "There is no hold '" + holdId + "'.";
        }
        // The endpoint's agents may renew any hold, as before each had its own (#121); a player only theirs.
        if (!hold.caller.equals(caller) && !caller.startsWith("endpoint")) {
            return "Hold '" + holdId + "' is not yours.";
        }
        long now = System.currentTimeMillis();
        hold.lastUsedMillis = now;
        hold.maxMillis = (now - hold.createdMillis) + maxMillis;
        if (idleMillis >= 0L) {
            hold.idleMillis = idleMillis;
        }
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
        if (firstTickNanos == 0L) {
            firstTickNanos = System.nanoTime();
        }
        BUDGET.newTick();
        // Every 20 ticks, or every second when ticks are slower than that: counted in ticks alone, a
        // server at 1 s a tick was measured every 20 s, and kept reading OK for that long.
        if (++ticks % 20 == 0 || now - lastMeasuredMillis >= 1_000L) {
            lastMeasuredMillis = now;
            measure(server, now);
        }
        int perTick = ServerHealth.perTick(HEALTH.state(), McmcpConfig.getChunkLoadsPerTick());
        // Round robin: each waiting call gets one load in turn until the tick's share is spent, so a
        // call that asked first cannot starve the ones behind it (#77). A chunk already in memory
        // costs nothing and does not use a turn.
        final int tickLimit = perTick;
        boolean progressed = true;
        while (perTick > 0 && progressed && !QUEUE.isEmpty()) {
            progressed = false;
            for (Iterator<Request> it = QUEUE.iterator(); it.hasNext() && perTick > 0; ) {
                Request request = it.next();
                int loads = serve(request, now, 1, tickLimit);
                if (loads < 0) {
                    it.remove();
                    continue;
                }
                perTick -= loads;
                progressed |= loads > 0;
                if (request.pending.isEmpty()) {
                    it.remove();
                    request.done.complete(request.outcome);
                    progressed = true;
                }
            }
        }
        // Whoever went first this tick goes last next tick.
        if (QUEUE.size() > 1) {
            QUEUE.addLast(QUEUE.pollFirst());
        }
        if (ticks % 200 == 0) {
            housekeeping(now);
        }
    }

    /**
     * Gives one request up to {@code share} loads, plus any of its chunks already in memory. The
     * budget still counts every load this tick against {@code tickLimit}.
     *
     * @return the loads made, or -1 if the request ended (its dimension unloaded) and left the queue
     */
    private static int serve(Request request, long now, int share, int tickLimit) {
        WorldServer world = DimensionManager.getWorld(request.hold.dimension);
        if (world == null) {
            releaseNow(request.hold.id, "its dimension unloaded");
            request.done.completeExceptionally(new Busy("Dimension " + request.hold.dimension
                + " unloaded.", busy("limit", "dimension", 0L)));
            return -1;
        }
        int allowed = BUDGET.allowance(request.hold.caller, Math.min(share, request.pending.size()), now, tickLimit,
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
        // Chunks already in memory cost no load: take them now, whatever the share.
        while (!request.pending.isEmpty() && provider.chunkExists(ChunkKeys.x(request.pending.peekFirst()),
            ChunkKeys.z(request.pending.peekFirst()))) {
            long chunk = request.pending.pollFirst();
            request.outcome.alreadyLoaded++;
            force(world, new HoldLedger.Place(request.hold.dimension, chunk));
        }
        BUDGET.record(request.hold.caller, loads, now);
        return loads;
    }

    private static void measure(MinecraftServer server, long now) {
        long total = 0L;
        for (long nanos : server.tickTimeArray) {
            total += nanos;
        }
        double meanMspt = total / (double) server.tickTimeArray.length / 1.0e6D;
        double heap = heapPercentAfterGc();
        ServerHealth.State before = HEALTH.state();
        ServerHealth.State after = HEALTH.update(meanMspt, heap, now,
            McmcpConfig.getChunkThrottleMspt(), McmcpConfig.getChunkPauseMspt(),
            McmcpConfig.getChunkPauseHeapPercent());
        // Ticks finished a second by the wall clock: the mean tick time leaves out the gaps between ticks,
        // so a server thread that stops for seconds between quick ticks reads fast by it. Not on an
        // integrated server, which stops ticking whenever its player pauses the game.
        double realTps = server.isDedicatedServer()
            ? ServerLoad.realTps(TickClock.SERVER, System.nanoTime(), firstTickNanos) : -1.0D;
        ServerLoad.measured(ServerLoad.fromHealth(CALLS.update(meanMspt, heap, realTps, now,
            McmcpConfig.getHealthHoldJobsMspt(), McmcpConfig.getHealthRefuseWritesMspt(),
            McmcpConfig.getChunkPauseHeapPercent(), McmcpConfig.getHealthHoldJobsBelowTps(),
            McmcpConfig.getHealthRefuseWritesBelowTps())), meanMspt, heap, realTps, CALLS.millisUntilRecovery(now));
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

    /**
     * Gives back one hold while the server stays paused: a call's hold its call is done with first, then
     * the longest idle. Never the hold of a call still running.
     */
    private static void shedOne(long now) {
        for (String id : LEDGER.shedOrder(now)) {
            HoldLedger.Hold hold = LEDGER.get(id);
            if (hold == null || hold.temporary && !hold.callGone(now)) {
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
            releaseNow(id, "the server has been struggling and shed it");
            return;
        }
    }

    private static void housekeeping(long now) {
        for (String id : LEDGER.expired(now)) {
            HoldLedger.Hold hold = LEDGER.get(id);
            if (hold != null && hold.temporary) {
                // A call's hold ends with its call. One still here was never given back (#132).
                Mcmcp.LOGGER.warn("MCMCP released hold " + id + " (" + hold.chunks.size() + " chunks, " + hold.caller
                    + "): " + (hold.callGone(now) ? "its call had ended without releasing it" : "its lease ran out") + ".");
            }
            releaseNow(id, "it expired");
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

    /** A hold cap's refusal: which cap, who holds what, and the caller's own share. */
    private static Busy capBusy(HoldLedger.CapRefusal cap) {
        JsonObject json = busy("limit", cap.limit, cap.callsInProgress ? 2_000L : 0L);
        json.addProperty("cap", cap.cap);
        json.addProperty("adding", cap.adding);
        json.addProperty("heldChunks", cap.heldChunks);
        json.addProperty("callers", cap.callers);
        json.addProperty("yourHeldChunks", cap.callerHeldChunks);
        Busy busy = new Busy(cap.message(), json);
        busy.capMayClear = cap.callsInProgress;
        return busy;
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
        // Reading in a hold renews its idle timer, never its lease, so a long test is told before its
        // region unloads under it rather than after (#91).
        long leaseLeft = LEDGER.leaseEndsAtMillis(dimension, chunks) - System.currentTimeMillis();
        if (leaseLeft < LEASE_WARNING_MILLIS) {
            json.addProperty("holdLeaseEndsInSeconds", Math.max(0L, leaseLeft / 1000L));
            json.addProperty("holdNote", "A hold over these chunks ends soon. Extend it with server_keep_loaded "
                + "op 'renew' if you still need them.");
        }
        return json;
    }

    /** For {@code /mcmcp chunks}, {@code server_tick_stats} and {@code game_health}. Server thread. */
    public static JsonObject status(@Nullable String onlyCaller) {
        return status(onlyCaller, null);
    }

    /**
     * As above, with {@code you} the asker's hold caller, to report their own count beside the caps.
     * With {@code onlyCaller} null every caller's share is listed: who holds what, so a stale hold
     * can be found and released when a cap is in the way (#121).
     */
    public static JsonObject status(@Nullable String onlyCaller, @Nullable String you) {
        long now = System.currentTimeMillis();
        JsonObject json = new JsonObject();
        json.addProperty("state", HEALTH.state().name().toLowerCase(java.util.Locale.ROOT));
        json.addProperty("meanTickMs", Math.round(HEALTH.lastMspt() * 10.0D) / 10.0D);
        json.addProperty("heapPercentAfterGc", Math.round(HEALTH.lastHeapPercent()));
        json.addProperty("heldChunks", LEDGER.heldChunks());
        json.addProperty("maxHeldChunks", McmcpConfig.getChunkMaxHeld());
        json.addProperty("maxHeldChunksPerCaller", McmcpConfig.getChunkMaxHeldPerCaller());
        json.addProperty("callers", LEDGER.callers());
        if (you != null) {
            json.addProperty("you", you);
            json.addProperty("yourHeldChunks", LEDGER.heldChunks(you));
        }
        json.addProperty("loadsLastMinute", BUDGET.lastMinute(now));
        json.addProperty("waitingRequests", QUEUE.size());
        Map<String, String> labels = new HashMap<>();
        JsonArray holds = new JsonArray();
        for (HoldLedger.Hold hold : LEDGER.holds()) {
            if (hold.label != null) {
                labels.put(hold.caller, hold.label);
            }
            if (onlyCaller != null && !HoldLedger.covers(onlyCaller, hold.caller)) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", hold.id);
            entry.addProperty("owner", hold.caller);
            if (hold.label != null) {
                entry.addProperty("ownerLabel", hold.label);
            }
            entry.addProperty("dimension", hold.dimension);
            entry.addProperty("chunks", hold.chunks.size());
            entry.addProperty("temporary", hold.temporary);
            entry.addProperty("expiresInSeconds", Math.max(0L, (hold.expiresAtMillis() - now) / 1000L));
            if (!hold.temporary) {
                entry.addProperty("leaseEndsInSeconds",
                    Math.max(0L, (hold.createdMillis + hold.maxMillis - now) / 1000L));
            }
            holds.add(entry);
        }
        json.add("holds", holds);
        if (onlyCaller == null) {
            JsonArray byCaller = new JsonArray();
            for (HoldLedger.Share share : LEDGER.shares()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("caller", share.caller);
                if (labels.containsKey(share.caller)) {
                    entry.addProperty("label", labels.get(share.caller));
                }
                entry.addProperty("chunks", share.chunks);
                entry.addProperty("holds", share.holds);
                entry.addProperty("calls", share.calls);
                if (share.nextExpiryMillis != Long.MAX_VALUE) {
                    entry.addProperty("nextExpiresInSeconds", Math.max(0L, (share.nextExpiryMillis - now) / 1000L));
                }
                byCaller.add(entry);
            }
            json.add("byCaller", byCaller);
        }
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
        releaseAll("the server is stopping");
        ServerLoad.reset();
        firstTickNanos = 0L;
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
            for (String id : LEDGER.ownedBy(caller, true)) {
                releaseNow(id, "its player logged out");
            }
        }
    }
}
