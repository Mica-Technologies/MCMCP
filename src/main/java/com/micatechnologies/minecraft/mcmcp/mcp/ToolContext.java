package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.game.BridgeTask;
import com.micatechnologies.minecraft.mcmcp.game.GameThreadBridge;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.game.ServerLoad;
import com.micatechnologies.minecraft.mcmcp.game.TaskCaller;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpcException;
import com.micatechnologies.minecraft.mcmcp.protocol.McpLogLevel;
import com.micatechnologies.minecraft.mcmcp.protocol.McpProtocol;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;

/**
 * Everything a tool handler is given when it runs: its arguments, the session that called it, the
 * bridge onto the game thread, and the channels for reporting progress and log output back.
 *
 * <p>Argument accessors come in two flavours and the distinction is deliberate. The
 * {@code require*} methods throw {@link JsonRpcException} with the parameter named, and are for
 * arguments the schema marked required — a missing one means the client ignored the schema, which
 * is a request-level fault. The plain {@code get*} methods take a default and never throw, and are
 * for optional arguments. Neither ever returns a half-valid value.
 */
public class ToolContext {

    /**
     * Told when a tool's game-thread task starts and ends, on the game thread, with who it runs for:
     * how the change tracker tells MCMCP's own writes from everyone else's without every tool having
     * to say so.
     */
    public interface GameTaskObserver {

        void enter(Principal principal);

        void exit();
    }

    @Nullable
    private static volatile GameTaskObserver gameTaskObserver;

    public static void setGameTaskObserver(@Nullable GameTaskObserver observer) {
        gameTaskObserver = observer;
    }

    /** Distinct step kinds {@link #noteTiming} keeps; the journal line must stay small. */
    private static final int MAX_TIMING_KINDS = 16;

    private final McpSession session;
    private final JsonObject arguments;
    private final GameThreadBridge gameThread;
    private final McpSession.CancellationToken cancellation;
    private final long gameThreadTimeoutMillis;
    /** Set for the length of one {@link #onGameThread(Callable, long)} call; 0 otherwise. */
    private long extendedTimeoutMillis;
    /** Set for the length of one {@link #onGameThreadWithCleanup} call; null otherwise. */
    @Nullable
    private java.util.function.Consumer<?> orphanCleanup;

    /**
     * How long a follow-up waits for the server thread before it is given up on. Long, because what
     * it finishes has already happened, and its caller waiting does not add to the queue: one task per
     * waiting call, at the front.
     */
    public static final long FOLLOW_UP_TIMEOUT_MILLIS = 60_000L;

    /** Every game-thread task of this context is a follow-up; see {@link #setFollowUp}. */
    private volatile boolean followUpContext;
    /** Set for the length of one {@link #onGameThreadFollowUp} call. */
    private boolean followUpHop;
    /** Whether a game-thread task of this context has run to its end: later ones are not the first. */
    private volatile boolean ranAHop;

    @Nullable
    private final JsonElement progressToken;

    /** The last progress value sent, so a smaller or equal one is dropped rather than sent. */
    private double lastProgress = Double.NEGATIVE_INFINITY;

    /** This call's entry in {@link ToolActivity}, or 0 when the call is not being tracked. */
    private volatile long activityCall;

    /** Whether this call can change the world: an emergency stop ends it at its next game-thread step. */
    private volatile boolean mutating;

    /** Which agent made this call. Set by the dispatcher; a part shares its whole's. */
    @Nullable
    private volatile CallerId caller;

    /** Who owns the chunk holds this call takes, when not the caller: a background job owns its own. */
    @Nullable
    private volatile String holdCaller;

    /**
     * Time spent per kind of step, for the request journal's end line. Shared with every
     * {@link #forPart} context, so a sequence's steps all add to the one call's totals.
     */
    private Map<String, long[]> timings = new LinkedHashMap<>();

    public ToolContext(McpSession session,
                       JsonObject arguments,
                       GameThreadBridge gameThread,
                       McpSession.CancellationToken cancellation,
                       long gameThreadTimeoutMillis,
                       @Nullable JsonElement progressToken) {
        this.session = session;
        this.arguments = arguments == null ? new JsonObject() : arguments;
        this.gameThread = gameThread;
        this.cancellation = cancellation;
        this.gameThreadTimeoutMillis = gameThreadTimeoutMillis;
        this.progressToken = progressToken;
    }

    public McpSession getSession() {
        return session;
    }

    /** True when this call is a part of a background job rather than a call someone is waiting on. */
    public boolean isJob() {
        return false;
    }

    /**
     * True when a rate budget this call runs over (the writes-per-minute budget) should make it wait
     * rather than refuse it. A job part paces itself: nobody is waiting on it, and a refusal would fail
     * the whole job (#131). An interactive call is refused with {@code busy.retryAfterMs}.
     */
    public boolean pacesToBudgets() {
        return false;
    }

    /**
     * Waits up to {@code millis} for a budget to free up, showing {@code message} as progress. Only
     * called when {@link #pacesToBudgets()}. A job's context waits out its pauses here too and throws
     * a cancellation if the job is cancelled; this default only honours the call's own cancellation.
     */
    public void awaitBudget(long millis, String message) {
        cancellation.throwIfCancelled();
        try {
            Thread.sleep(Math.max(0L, millis));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JsonRpcException(JsonRpcException.REQUEST_CANCELLED, "Interrupted while waiting for a budget.");
        }
        cancellation.throwIfCancelled();
    }

    /** Marks this call as one an emergency stop must end. Set by the dispatcher for every tool that is not read-only. */
    public void setMutating(boolean value) {
        this.mutating = value;
    }

    /** Which agent made this call. Never null: a context nobody named is its session's. */
    public CallerId getCaller() {
        CallerId known = caller;
        return known != null ? known : CallerId.of(session, null);
    }

    public void setCaller(CallerId value) {
        this.caller = value;
    }

    /**
     * Whether this call must leave an undo point whatever the undo settings say: a test script's
     * {@code restore: true} step puts the world back from it.
     */
    private volatile boolean undoRequired;

    public boolean isUndoRequired() {
        return undoRequired;
    }

    public void setUndoRequired(boolean value) {
        this.undoRequired = value;
    }

    /**
     * Makes every game-thread task of this context a follow-up: for a context that puts back what an
     * earlier call did, such as a test script's restores. As {@link #onGameThreadFollowUp}, except that
     * an emergency stop still refuses its tasks, since they write.
     */
    public void setFollowUp(boolean value) {
        this.followUpContext = value;
    }

    /**
     * Whether the game-thread task being handed over now finishes work already done: from
     * {@link #setFollowUp}, or inside {@link #onGameThreadFollowUp}. A subclass that hands its tasks
     * to another context passes this on.
     */
    public boolean isFollowUp() {
        return followUpContext || followUpHop;
    }

    /**
     * Who this call is for chunk holds, budgets and the server's task queue (#121): one key per
     * player through the companion, whatever agents they run; one per agent on the endpoint; a
     * background job's own {@code job:<id>}.
     */
    public String callerKey() {
        if (holdCaller != null) {
            return holdCaller;
        }
        Principal principal = getPrincipal();
        return principal.isCompanion() ? "player:" + principal.getPlayerId() : "endpoint/" + getCaller().id();
    }

    /** {@link #callerKey()} for people to read: the player's or agent's name, or the job. */
    public String callerLabel() {
        if (holdCaller != null) {
            return holdCaller;
        }
        Principal principal = getPrincipal();
        return principal.isCompanion() ? principal.getPlayerName() : getCaller().label();
    }

    /** The key chunk holds are taken under, or null for the caller's own. */
    @Nullable
    public String getHoldCaller() {
        return holdCaller;
    }

    public void setHoldCaller(@Nullable String value) {
        this.holdCaller = value;
    }

    /** Ties this call's progress to its {@link ToolActivity} entry. Set once, by the dispatcher. */
    public void trackActivity(long call) {
        this.activityCall = call;
    }

    public JsonObject getArguments() {
        return arguments;
    }

    public GameThreadBridge getGameThread() {
        return gameThread;
    }

    public McmcpSide getSide() {
        return gameThread.getSide();
    }

    public McpSession.CancellationToken getCancellation() {
        return cancellation;
    }

    /** Who this call runs as: the endpoint caller, or a player through the companion. */
    public Principal getPrincipal() {
        return session.getPrincipal();
    }

    /**
     * Why this call may not exercise {@code capability}, or null when it may.
     *
     * <p>An endpoint caller is answered from the config switch the tool passes in, with the tool's own
     * message naming it. A companion caller is answered from their grants, and told which permission
     * node they lack: the config switch is the endpoint's, and naming it would send them looking in the
     * wrong place.
     *
     * @param endpointAllowed the config switch for this capability, for an endpoint caller
     * @param endpointRefusal what to say to an endpoint caller when the switch is off
     */
    @Nullable
    public String refusal(Capability capability, boolean endpointAllowed, String endpointRefusal) {
        Principal principal = getPrincipal();
        if (!principal.isCompanion()) {
            return endpointAllowed ? null : endpointRefusal;
        }
        if (principal.allows(capability)) {
            return null;
        }
        String callClass = Principal.classFor(capability);
        return callClass == null
            ? "This is not available through the MCMCP companion."
            : "Your companion access on this server does not include '" + callClass + "' calls. An "
                + "operator can grant the mcmcp.companion." + callClass + " permission node.";
    }

    // ------------------------------------------------------------------
    // Game thread access
    // ------------------------------------------------------------------

    /**
     * Runs {@code task} on the game thread and returns its value, using the configured timeout.
     *
     * <p>The one correct way for a tool to touch world state. Failures are translated into
     * {@link JsonRpcException}s here so handlers do not each reimplement the same try/catch, but
     * note that a tool which wants to report a *game-level* failure to the model should catch this
     * and return {@link ToolResult#error} instead of letting it propagate.
     */
    public <T> T onGameThread(Callable<T> task) {
        // A follow-up finishes what an earlier task of this call already did to the world, so it is not
        // dropped for a cancellation or the usual timeout, and goes to the front of the server's queue.
        final boolean followUp = isFollowUp();
        final long timeoutMillis = Math.max(followUp ? FOLLOW_UP_TIMEOUT_MILLIS : gameThreadTimeoutMillis,
            extendedTimeoutMillis);
        if (!followUp) {
            cancellation.throwIfCancelled();
        }
        if (mutating && !followUpHop && com.micatechnologies.minecraft.mcmcp.game.Estop.active()) {
            throw new JsonRpcException(JsonRpcException.INTERNAL_ERROR,
                com.micatechnologies.minecraft.mcmcp.game.Estop.refusal());
        }
        if (!gameThread.isAvailable()) {
            throw new JsonRpcException(JsonRpcException.INTERNAL_ERROR,
                getSide().isClient()
                    ? "The Minecraft client is not ready to accept commands"
                    : "No Minecraft server is currently running");
        }
        final GameTaskObserver observer = gameTaskObserver;
        final Principal principal = getPrincipal();
        Callable<T> observed = observer == null ? task : () -> {
            observer.enter(principal);
            try {
                return task.call();
            }
            finally {
                observer.exit();
            }
        };
        @SuppressWarnings("unchecked")
        java.util.function.Consumer<? super T> cleanup = (java.util.function.Consumer<? super T>) orphanCleanup;
        TaskCaller who = new TaskCaller(callerKey(), callerLabel());
        try {
            T value = gameThread.callOnGameThread(observed, timeoutMillis, cleanup,
                followUp ? null : cancellation::isCancelled, followUp ? who.asFollowUp() : who);
            ranAHop = true;
            return value;
        }
        catch (JsonRpcException e) {
            throw e;
        }
        catch (BridgeTask.NotStarted e) {
            // This step certainly did not run, so a job may try it again. The agent is told so as a tool
            // error with a wait in it, not a protocol timeout it would resend at once (#132) — and told
            // when earlier steps of the same call had run, since then the call as a whole is not
            // "nothing changed", and resending it would do those again.
            JsonObject busy = ServerLoad.notStarted();
            boolean earlier = followUp || ranAHop && mutating;
            if (earlier) {
                busy.addProperty("earlierStepsRan", true);
            }
            String message = earlier
                ? "The game thread did not start a step of this call within " + e.waitedMillis() + " ms, so that "
                    + "step was dropped before it ran; but earlier steps of the call had already run, so it may have "
                    + "changed the world in part: look before you retry it. " + ServerLoad.notStartedCause()
                : e.getMessage() + " " + ServerLoad.notStartedCause() + " Retry after busy.retryAfterMs.";
            throw new ToolBusyException(message, busy, true);
        }
        catch (BridgeTask.Dropped e) {
            throw new JsonRpcException(JsonRpcException.INTERNAL_ERROR, e.getMessage());
        }
        catch (java.util.concurrent.CancellationException e) {
            throw new JsonRpcException(JsonRpcException.REQUEST_CANCELLED, e.getMessage());
        }
        catch (BridgeTask.StartedNotFinished e) {
            // It started, so it is not safe to resend, and the agent must hear that. As a JSON-RPC timeout
            // the client's plumbing ate it and the model resent the identical call, which is how #132
            // piled up: a tool error says plainly that the effect is unknown and to look first.
            throw ToolBusyException.startedNotFinished(e.timeoutMillis(), e.ranForMillis());
        }
        catch (java.util.concurrent.TimeoutException e) {
            // The client bridge, which cannot tell a task that started from one that did not.
            throw new JsonRpcException(JsonRpcException.REQUEST_TIMED_OUT, e.getMessage());
        }
        catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new JsonRpcException(JsonRpcException.INTERNAL_ERROR,
                "Game thread task failed: " + message);
        }
    }

    /**
     * As {@link #onGameThread(Callable)}, waiting at least {@code minTimeoutMillis} for the result:
     * for a task the caller has asked to measure, which is likeliest to be the slow one. Goes through
     * {@link #onGameThread(Callable)}, so a subclass's gate on it still applies.
     */
    public <T> T onGameThread(Callable<T> task, long minTimeoutMillis) {
        long previous = extendedTimeoutMillis;
        extendedTimeoutMillis = Math.max(previous, minTimeoutMillis);
        try {
            return onGameThread(task);
        }
        finally {
            extendedTimeoutMillis = previous;
        }
    }

    /**
     * As {@link #onGameThread(Callable)}, with {@code ifOrphaned} run on the game thread with the
     * task's value if the task finishes after this call stopped waiting for it: where a task that takes
     * something — a chunk hold — gives it back when nobody is left to use it. A task that never started
     * took nothing and runs no cleanup. Goes through {@link #onGameThread(Callable)}, so a subclass's
     * gate on it still applies.
     */
    public <T> T onGameThreadWithCleanup(Callable<T> task, java.util.function.Consumer<? super T> ifOrphaned) {
        java.util.function.Consumer<?> previous = orphanCleanup;
        orphanCleanup = ifOrphaned;
        try {
            return onGameThread(task);
        }
        finally {
            orphanCleanup = previous;
        }
    }

    /**
     * As {@link #onGameThread(Callable)}, for a task that finishes a write this call has already made
     * and does not itself change the world: the read that saves its undo point, its after-picture, the
     * look for what its held physics would move and the hand-back of those ticks, the touch of its
     * chunks. Dropping one of these leaves the write without its undo point, or the world's ticks held,
     * so it is not treated like a new step:
     *
     * <ul>
     *   <li>the server's pump runs it before anyone's turn ({@link TaskCaller#asFollowUp});</li>
     *   <li>it waits up to {@link #FOLLOW_UP_TIMEOUT_MILLIS}, not the usual timeout, before it is given up on;</li>
     *   <li>a cancellation does not drop it, and nor does an emergency stop, since it changes nothing.</li>
     * </ul>
     *
     * Never for a task that writes: that would let one call's writes jump the queue and the stop.
     */
    public <T> T onGameThreadFollowUp(Callable<T> task) {
        boolean previous = followUpHop;
        followUpHop = true;
        try {
            return onGameThread(task);
        }
        finally {
            followUpHop = previous;
        }
    }

    /** Schedules a side effect on the game thread without waiting for it. */
    public void onGameThreadAsync(Runnable task) {
        gameThread.runOnGameThread(task);
    }

    // ------------------------------------------------------------------
    // Arguments
    // ------------------------------------------------------------------

    public String requireString(String name) {
        String value = Json.getString(arguments, name);
        if (value == null || value.isEmpty()) {
            throw JsonRpcException.missingParam(name);
        }
        return value;
    }

    @Nullable
    public String getString(String name, @Nullable String fallback) {
        return Json.getString(arguments, name, fallback);
    }

    public int requireInt(String name) {
        if (!Json.has(arguments, name)) {
            throw JsonRpcException.missingParam(name);
        }
        return Json.getInt(arguments, name, 0);
    }

    public int getInt(String name, int fallback) {
        return Json.getInt(arguments, name, fallback);
    }

    /**
     * An integer argument clamped into {@code [min, max]}.
     *
     * <p>Clamps rather than rejects. These bounds guard things like scan radius and tick duration,
     * where the schema already advertises the range; a caller that exceeds it gets the nearest legal
     * value and a working tool, instead of an error round-trip that teaches the model nothing the
     * schema had not already said.
     */
    public int getBoundedInt(String name, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, Json.getInt(arguments, name, fallback)));
    }

    public double requireDouble(String name) {
        if (!Json.has(arguments, name)) {
            throw JsonRpcException.missingParam(name);
        }
        return Json.getDouble(arguments, name, 0.0D);
    }

    public double getDouble(String name, double fallback) {
        return Json.getDouble(arguments, name, fallback);
    }

    public boolean getBoolean(String name, boolean fallback) {
        return Json.getBoolean(arguments, name, fallback);
    }

    public boolean has(String name) {
        return Json.has(arguments, name);
    }

    // ------------------------------------------------------------------
    // Progress and logging
    // ------------------------------------------------------------------

    /**
     * Reports progress on a long-running call.
     *
     * <p>Silently does nothing when the client did not send a {@code progressToken} in
     * {@code _meta} — per spec, progress notifications are only legal for requests that asked for
     * them, and sending unsolicited ones makes strict clients drop the connection.
     *
     * @param progress how far along, in the same units as {@code total}
     * @param total    the expected end value, or a negative number if unknown
     */
    public void reportProgress(double progress, double total, @Nullable String message) {
        // Recorded whether or not the client asked for progress: the game shows what it is doing to
        // whoever is watching it, and that is not the client's choice.
        if (activityCall != 0) {
            ToolActivity.progress(activityCall, progress, total, message);
        }
        if (progressToken == null) {
            return;
        }
        // The spec has progress increase with every notification. A call made of several parts —
        // client_sequence's steps, each reporting on its own scale — would otherwise repeat a value
        // at each boundary, and a strict client may treat that as a protocol fault.
        synchronized (this) {
            if (progress <= lastProgress) {
                return;
            }
            lastProgress = progress;
        }
        JsonObject params = new JsonObject();
        params.add("progressToken", progressToken);
        params.addProperty("progress", progress);
        if (total >= 0) {
            params.addProperty("total", total);
        }
        if (message != null) {
            params.addProperty("message", message);
        }
        session.enqueue(com.micatechnologies.minecraft.mcmcp.protocol.JsonRpc
            .notification(McpProtocol.NOTIFICATION_PROGRESS, params));
    }

    /**
     * A context for running one part of this call as a tool call of its own: same session,
     * cancellation and game thread, its own arguments.
     *
     * <p>Progress the part reports is mapped onto this call's scale, as {@code base} plus the part's
     * own fraction (held below 1), out of {@code total}. So a step that heartbeats through a long
     * wait keeps the whole call alive through the orchestrator's no-progress limit, and the numbers
     * still only go up.
     */
    public ToolContext forPart(JsonObject partArguments, final double base, final double total) {
        return forPart(partArguments, base, total, null);
    }

    /**
     * As {@link #forPart(JsonObject, double, double)}, with each of the part's progress messages
     * prefixed by {@code messagePrefix} — which part is speaking, for someone watching the whole.
     */
    public ToolContext forPart(JsonObject partArguments, final double base, final double total,
        @Nullable final String messagePrefix) {
        final ToolContext whole = this;
        ToolContext part = new ToolContext(session, partArguments, gameThread, cancellation,
            gameThreadTimeoutMillis, null) {
            @Override
            public void reportProgress(double progress, double partTotal, @Nullable String message) {
                double fraction = partTotal > 0 ? Math.max(0.0D, Math.min(progress / partTotal, 0.99D))
                    : 0.5D;
                String labelled = messagePrefix == null ? message
                    : message == null ? messagePrefix : messagePrefix + " · " + message;
                whole.reportProgress(base + fraction, total, labelled);
            }

            @Override
            public boolean pacesToBudgets() {
                return whole.pacesToBudgets();
            }

            @Override
            public void awaitBudget(long millis, String message) {
                whole.awaitBudget(millis, message);
            }
        };
        part.timings = timings;
        part.caller = caller;
        return part;
    }

    // ------------------------------------------------------------------
    // Journal notes
    // ------------------------------------------------------------------

    /**
     * Adds {@code millis} to the running total for one kind of step, which the request journal
     * writes on the call's end line ({@code "steps": {"client_wait:chunksLoaded": {"n": 12, "ms":
     * 9400}}}). How a long call spent its time — teleports, chunk waits, reply waits — is what tells
     * whether it is worth moving server-side.
     */
    public void noteTiming(String kind, long millis) {
        synchronized (timings) {
            long[] total = timings.get(kind);
            if (total == null) {
                if (timings.size() >= MAX_TIMING_KINDS) {
                    return;
                }
                total = new long[2];
                timings.put(kind, total);
            }
            total[0]++;
            total[1] += millis;
        }
    }

    /** The noted timings, or null when nothing was noted. */
    @Nullable
    public JsonObject timingNotes() {
        synchronized (timings) {
            if (timings.isEmpty()) {
                return null;
            }
            JsonObject json = new JsonObject();
            for (Map.Entry<String, long[]> entry : timings.entrySet()) {
                JsonObject kind = new JsonObject();
                kind.addProperty("n", entry.getValue()[0]);
                kind.addProperty("ms", entry.getValue()[1]);
                json.add(entry.getKey(), kind);
            }
            return json;
        }
    }

    /**
     * Sends a log record to the client, subject to the level it set via {@code logging/setLevel}.
     *
     * <p>Distinct from the game log on purpose: this is diagnostic output *for the model*, not for
     * the player watching the console. Use it for the reasoning a model needs to recover from a
     * partial failure ("moved 3 of 5 blocks, path blocked at x=12"), not for mod-internal noise.
     */
    public void log(McpLogLevel level, String message) {
        if (!level.isAtLeast(session.getLogLevel())) {
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("level", level.wireName());
        params.addProperty("logger", "mcmcp." + getSide().id());
        params.add("data", Json.obj("message", message));
        session.enqueue(com.micatechnologies.minecraft.mcmcp.protocol.JsonRpc
            .notification(McpProtocol.NOTIFICATION_MESSAGE, params));
    }
}
