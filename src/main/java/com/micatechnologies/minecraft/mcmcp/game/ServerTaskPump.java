package com.micatechnologies.minecraft.mcmcp.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * MCMCP's own way onto the server thread, in place of {@code addScheduledTask} (#133).
 *
 * <p>Workers offer tasks to a {@link FairDrain} without a lock; at the start of every server tick the
 * server thread runs them, taking turns between callers, for at most the tick's {@link TaskBudget}:
 * 15 ms when the server is healthy, 5 ms while jobs are held, one task while writes are refused. A
 * task its caller gave up on is skipped.
 *
 * <p>At the start, not the end: vanilla times a tick from just before {@code ServerTickEvent}'s START
 * to just before its END, so work done in END is missing from {@code tickTimeArray}, and with it from
 * the mean tick time that health, admission and jobs go by. Drained in END, MCMCP spending 500 ms a
 * tick left the server reading 2 ms and OK.
 *
 * <h2>Stopping</h2>
 *
 * When a server stops, whatever is still queued is dropped: a caller waiting on it hears at once that
 * it never ran, instead of waiting out its timeout on a server that will never tick again. The same
 * happens when the next server starts, for anything offered in between.
 *
 * <h2>A paused singleplayer game</h2>
 *
 * An integrated server stops ticking while its player has the game paused, but still runs vanilla's
 * scheduled tasks. So when an integrated server has not ticked for a second, every offer also
 * schedules a vanilla task (one at a time) that drains the pump until it is empty: with no tick to
 * protect there is no budget to keep to. Only then does a worker take vanilla's lock; a dedicated
 * server never needs it. Only a tick's drain counts as the server ticking: a call offered just after
 * a paused game's drain must not wait for a tick that is not coming. A call offered in the second
 * after the game paused finds the server ticking still; its caller, while it waits, calls
 * {@link #kickIfPaused()} until one is scheduled.
 */
public final class ServerTaskPump {

    private static final FairDrain<Runnable> QUEUE = new FairDrain<>(System::nanoTime);

    /** Labels for the keys tasks were offered under, for people to read. Cleared when a server stops. */
    private static final Map<String, String> LABELS = new ConcurrentHashMap<>();

    private static final long KICK_AFTER_NANOS = 1_000_000_000L;

    private static final AtomicBoolean KICK_PENDING = new AtomicBoolean();

    /** Set from a server's stopping until the next one starts: offers then are dropped at once. */
    @Nullable
    private static volatile String closedBecause;
    /** When a server tick last drained the pump. Not moved by a paused game's drain. */
    private static volatile long lastTickNanos = System.nanoTime();

    /** Most drains one kick runs before leaving the rest to the next offer's kick. */
    private static final int KICK_MAX_DRAINS = 100;

    private static boolean registered;

    /** A task as it waits in the queue: with who offered it, so the task running now can be named. */
    private static final class Offered implements Runnable {

        final TaskCaller caller;
        final Runnable task;

        Offered(TaskCaller caller, Runnable task) {
            this.caller = caller;
            this.task = task;
        }

        @Override
        public void run() {
            task.run();
        }
    }

    /**
     * The task the server thread is inside now, for whoever looks from another thread (#219).
     *
     * <p>A call's own record ends when its caller stops waiting, which is exactly when a task that never
     * returns is most worth naming: the report of a server hung inside {@code server_set_blocks} said
     * "MCMCP calls running: 0". This is kept by the thread doing the work, so it lasts as long as the work.
     */
    public static final class Running {

        public final String caller;
        /** The tool call it is part of, with a short form of its arguments; null for housekeeping. */
        @Nullable
        public final String what;
        public final boolean followUp;
        public final long startedMillis;
        final long startedNanos;
        /** Set by {@link #requestAbort}: the task's own loops stop at their next {@link #checkpoint}. */
        volatile String abort;

        Running(TaskCaller caller) {
            this.caller = caller.label;
            this.what = caller.what;
            this.followUp = caller.followUp;
            this.startedMillis = System.currentTimeMillis();
            this.startedNanos = System.nanoTime();
        }

        public long runningMillis() {
            return (System.nanoTime() - startedNanos) / 1_000_000L;
        }

        /** "server_set_blocks {mode=palette, ...} for builder-2, running 14 s". */
        public String describe() {
            return (what == null ? "MCMCP housekeeping" : what) + " for " + caller + (followUp ? " (a follow-up step)" : "")
                + ", running " + runningMillis() / 1000L + " s";
        }
    }

    /** Thrown on the server thread by {@link #checkpoint} when the task running was asked to stop. */
    public static final class TaskAborted extends RuntimeException {

        private static final long serialVersionUID = 1L;

        TaskAborted(String why) {
            super(why);
        }
    }

    @Nullable
    private static volatile Running running;

    /** The task the server thread is running now, or null between tasks. Any thread. */
    @Nullable
    public static Running running() {
        return running;
    }

    /**
     * Any thread: asks the task running now to stop. It stops at the next {@link #checkpoint} its own
     * loop reaches, with {@code why} as its error; a task inside the game's own code (a vanilla lookup,
     * another mod's hook) reaches none until that returns, and nothing can interrupt it there.
     *
     * @return what was asked to stop, or null when no task is running
     */
    @Nullable
    public static Running requestAbort(String why) {
        Running current = running;
        if (current != null) {
            current.abort = why;
        }
        return current;
    }

    /**
     * Server thread, from a loop of MCMCP's own that reads the world and may run long: throws
     * {@link TaskAborted} if the task running was asked to stop. Costs one volatile read. Never called
     * from a loop that writes: a write stopped half way is worse than one that finishes late.
     */
    public static void checkpoint() {
        Running current = running;
        if (current != null && current.abort != null) {
            throw new TaskAborted(current.abort);
        }
    }

    private ServerTaskPump() {
    }

    public static synchronized void register() {
        if (!registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.register(new Events());
            ServerLoad.setQueueDepth(QUEUE::depth);
            ServerLoad.setRunningTask(() -> {
                Running current = running;
                return current == null ? null : current.describe();
            });
        }
    }

    /**
     * Any thread: queues {@code task} to run on the server thread for {@code caller}. Never blocks.
     * While no server is taking tasks, a {@link BridgeTask} is dropped at once and anything else is
     * discarded.
     */
    public static void offer(TaskCaller caller, Runnable task) {
        String closed = closedBecause;
        if (closed != null) {
            if (task instanceof BridgeTask) {
                ((BridgeTask<?>) task).drop(closed);
            }
            return;
        }
        LABELS.put(caller.key, caller.label);
        Offered offered = new Offered(caller, task);
        if (caller.followUp) {
            QUEUE.offerFirst(caller.key, offered);
        }
        else {
            QUEUE.offer(caller.key, offered);
        }
        kickIfPaused();
    }

    /**
     * Runs {@code task} at once when already on the server thread, as {@code addScheduledTask} did;
     * otherwise queues it. For side effects nobody waits on; a throw is logged, never propagated.
     */
    public static void runLater(TaskCaller caller, final Runnable task) {
        MinecraftServer server = ServerThreadBridge.server();
        Runnable guarded = () -> {
            try {
                task.run();
            }
            catch (Throwable t) {
                Mcmcp.LOGGER.error("MCMCP task failed on the server thread", t);
            }
        };
        if (server != null && server.isCallingFromMinecraftThread()) {
            guarded.run();
            return;
        }
        offer(caller, guarded);
    }

    /** Tasks waiting. Any thread. */
    public static int depth() {
        return QUEUE.depth();
    }

    /** The pump's figures, for {@code game_health}'s {@code serverHealth}. Any thread. */
    public static JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("queued", QUEUE.depth());
        json.addProperty("msPerTick", ServerLoad.round1(QUEUE.totalMsPerTick()));
        JsonArray callers = new JsonArray();
        for (FairDrain.Share share : QUEUE.shares()) {
            if (callers.size() >= 5) {
                break;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("caller", share.key);
            String label = LABELS.get(share.key);
            if (label != null && !label.equals(share.key)) {
                entry.addProperty("label", label);
            }
            entry.addProperty("msPerTick", Math.round(share.msPerTick * 100.0D) / 100.0D);
            callers.add(entry);
        }
        // "one task" when the budget is a count, not a time: a budgetMs of 0 read as no work at all.
        ServerLoad.Level level = ServerLoad.level();
        int configured = McmcpConfig.getHealthTickBudgetMs();
        json.addProperty("budget", TaskBudget.describe(level, configured));
        if (TaskBudget.maxTasks(level) > 1) {
            json.addProperty("budgetMs", TaskBudget.budgetNanos(level, configured) / 1_000_000L);
        }
        json.add("byCaller", callers);
        // Only once it has run long enough to be worth a look: most tasks are over in a millisecond.
        Running current = running;
        if (current != null && current.runningMillis() >= 1000L) {
            json.addProperty("runningNow", current.describe());
        }
        return json;
    }

    /** The caller spending most of MCMCP's share of the tick, as "label (7.2 ms/tick)", or null. */
    @Nullable
    public static String topCaller() {
        List<FairDrain.Share> shares = QUEUE.shares();
        if (shares.isEmpty() || shares.get(0).msPerTick < 0.05D) {
            return null;
        }
        FairDrain.Share top = shares.get(0);
        String label = LABELS.get(top.key);
        return (label == null ? top.key : label) + " (" + ServerLoad.round1(top.msPerTick) + " ms/tick)";
    }

    /** Server thread: a server is starting; anything offered since the last one stopped is dropped. */
    public static void open() {
        closedBecause = null;
        dropAll("it was queued for a server that has stopped");
    }

    /**
     * Server thread: the server is stopping. Whatever is queued is dropped, and so is anything offered
     * from now until the next server starts, so a caller waiting on it hears at once that it never ran.
     */
    public static void close(String why) {
        closedBecause = why;
        dropAll(why);
    }

    private static void dropAll(String why) {
        int dropped = 0;
        for (Runnable queued : QUEUE.clear()) {
            Runnable task = queued instanceof Offered ? ((Offered) queued).task : queued;
            if (task instanceof BridgeTask && ((BridgeTask<?>) task).drop(why)) {
                dropped++;
            }
        }
        LABELS.clear();
        if (dropped > 0) {
            Mcmcp.LOGGER.info("MCMCP dropped " + dropped + " queued server-thread task(s): " + why);
        }
    }

    /** Server thread: one tick's worth. */
    static void drain() {
        ServerLoad.Level level = ServerLoad.level();
        QUEUE.drain(TaskBudget.budgetNanos(level, McmcpConfig.getHealthTickBudgetMs()), TaskBudget.maxTasks(level),
            queued -> {
                Offered offered = (Offered) queued;
                Runnable task = offered.task;
                // Named for as long as it runs, whatever became of its caller (#219).
                running = new Running(offered.caller);
                try {
                    if (task instanceof BridgeTask) {
                        return ((BridgeTask<?>) task).tryRun();
                    }
                    task.run();
                    return true;
                }
                finally {
                    running = null;
                }
            });
    }

    /**
     * Any thread: when an integrated server has not ticked for a second, schedules one vanilla task
     * that drains the pump, unless one is already scheduled. Cheap, and a no-op on a dedicated server.
     */
    public static void kickIfPaused() {
        MinecraftServer server = ServerThreadBridge.server();
        if (server == null || server.isDedicatedServer() || System.nanoTime() - lastTickNanos < KICK_AFTER_NANOS
            || !KICK_PENDING.compareAndSet(false, true)) {
            return;
        }
        server.addScheduledTask(() -> {
            KICK_PENDING.set(false);
            try {
                for (int i = 0; i < KICK_MAX_DRAINS && QUEUE.depth() > 0; i++) {
                    drain();
                }
            }
            catch (Throwable t) {
                Mcmcp.LOGGER.error("MCMCP's task pump failed", t);
            }
        });
    }

    /** On Forge's bus. */
    public static final class Events {

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent event) {
            // START, inside the window vanilla times; see the class comment.
            if (event.phase != TickEvent.Phase.START) {
                return;
            }
            lastTickNanos = System.nanoTime();
            try {
                drain();
            }
            catch (Throwable t) {
                // Never into the tick loop: that would crash the server.
                Mcmcp.LOGGER.error("MCMCP's task pump failed", t);
            }
        }
    }
}
