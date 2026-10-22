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
 * <p>Workers offer tasks to a {@link FairDrain} without a lock; at the end of every server tick the
 * server thread runs them, taking turns between callers, for at most the tick's {@link TaskBudget}:
 * 15 ms when the server is healthy, 5 ms while jobs are held, one task while writes are refused. A
 * task its caller gave up on is skipped.
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
 * scheduled tasks. So when an integrated server's pump has not drained for a second, an offer also
 * schedules one vanilla task that drains it. Only then does a worker take vanilla's lock; a dedicated
 * server never needs it.
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
    private static volatile long lastDrainNanos = System.nanoTime();

    private static boolean registered;

    private ServerTaskPump() {
    }

    public static synchronized void register() {
        if (!registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.register(new Events());
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
        QUEUE.offer(caller.key, task);
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
        json.addProperty("budgetMs", TaskBudget.budgetNanos(ServerLoad.level(), McmcpConfig.getHealthTickBudgetMs())
            / 1_000_000L);
        json.add("byCaller", callers);
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
        for (Runnable task : QUEUE.clear()) {
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
        lastDrainNanos = System.nanoTime();
        ServerLoad.Level level = ServerLoad.level();
        QUEUE.drain(TaskBudget.budgetNanos(level, McmcpConfig.getHealthTickBudgetMs()), TaskBudget.maxTasks(level),
            task -> {
                if (task instanceof BridgeTask) {
                    return ((BridgeTask<?>) task).tryRun();
                }
                task.run();
                return true;
            });
        lastDrainNanos = System.nanoTime();
    }

    private static void kickIfPaused() {
        MinecraftServer server = ServerThreadBridge.server();
        if (server == null || server.isDedicatedServer() || System.nanoTime() - lastDrainNanos < KICK_AFTER_NANOS
            || !KICK_PENDING.compareAndSet(false, true)) {
            return;
        }
        server.addScheduledTask(() -> {
            KICK_PENDING.set(false);
            try {
                drain();
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
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
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
