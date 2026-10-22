package com.micatechnologies.minecraft.mcmcp.jobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.game.Estop;
import com.micatechnologies.minecraft.mcmcp.game.GameThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpcException;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Runs background jobs on the server (#73): long writes and reads submitted in one call, worked
 * through part by part on a runner thread, polled with {@code server_job_status}, paused, resumed and
 * cancelled — instead of a script making an hour of blocking calls and dying halfway with no record.
 *
 * <p>Two jobs run at once; more wait their turn. Each part is an ordinary tool call in a
 * {@link JobContext}, so it is throttled like any call and also waits while the job is paused or the
 * server struggles. A part refused as busy (chunk loads, the write budget) is retried after the wait
 * it asks for, a few times, before the job fails.
 *
 * <p>The record of every job is written to the world's {@code mcmcp-jobs.json} as it changes. A job
 * running when the server stopped is listed afterwards as interrupted, with how far it got.
 *
 * <p>No Minecraft class: the server hands in its world folder and game-thread bridge.
 */
public final class JobManager {

    private static final int RUNNERS = 2;
    private static final int MAX_ACTIVE_PER_OWNER = 4;
    private static final int MAX_ACTIVE = 16;
    private static final int BUSY_RETRIES = 8;
    private static final long KEEP_SETTLED_MILLIS = 30L * 60L * 1000L;
    private static final long KEEP_RESTORED_MILLIS = 24L * 60L * 60L * 1000L;
    private static final long MAX_PAGE_BYTES = 256L << 20;
    private static final String FILE = "mcmcp-jobs.json";

    private static final Map<String, Job> JOBS = new LinkedHashMap<>();
    private static final List<Consumer<Job>> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Map<String, Long> NOTIFIED = new HashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger(1);

    @Nullable
    private static ExecutorService runners;
    @Nullable
    private static Thread watcher;
    @Nullable
    private static File folder;
    @Nullable
    private static GameThreadBridge bridge;
    private static long gameThreadTimeoutMillis = 5000L;
    private static volatile boolean dirty;

    private JobManager() {
    }

    static {
        Estop.addListener((now, before) -> {
            if (now != null) {
                pauseAll(Job.Hold.ESTOP);
            }
            else {
                // Lifting the stop leaves every job it paused paused, for an operator or owner to resume.
                for (Job job : jobs()) {
                    if (job.isPausedBy(Job.Hold.ESTOP)) {
                        job.pause(Job.Hold.OPERATOR);
                        job.resume(Job.Hold.ESTOP);
                    }
                }
            }
        });
    }

    /** Told of every job whose record changed, at most once a second each. */
    public static void addListener(Consumer<Job> listener) {
        LISTENERS.add(listener);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** The server started: read back the record and start the runners. */
    public static synchronized void serverStarting(File worldFolder, GameThreadBridge gameThread, long timeoutMillis) {
        folder = worldFolder;
        bridge = gameThread;
        gameThreadTimeoutMillis = timeoutMillis;
        JOBS.clear();
        NOTIFIED.clear();
        load();
        final AtomicInteger counter = new AtomicInteger(1);
        runners = Executors.newFixedThreadPool(RUNNERS, runnable -> {
            Thread thread = new Thread(runnable, "MCMCP-job-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        Thread thread = new Thread(JobManager::watch, "MCMCP-jobs-watch");
        thread.setDaemon(true);
        watcher = thread;
        thread.start();
    }

    /** The server is stopping: running jobs end as interrupted, and the record is written once more. */
    public static synchronized void serverStopping() {
        for (Job job : JOBS.values()) {
            if (!job.state().settled()) {
                job.cancel();
                job.finish(Job.State.INTERRUPTED, "The server stopped while this job ran. Resume from lastOp + 1 "
                    + "(or the next part) with a new job.");
            }
        }
        if (runners != null) {
            runners.shutdownNow();
            runners = null;
        }
        if (watcher != null) {
            watcher.interrupt();
            watcher = null;
        }
        save();
        folder = null;
        bridge = null;
    }

    // ------------------------------------------------------------------
    // Submitting and controlling
    // ------------------------------------------------------------------

    /**
     * Queues a job.
     *
     * @throws IllegalArgumentException with a message for the caller
     */
    public static synchronized Job submit(String kind, McpTool tool, JsonObject args, @Nullable String label,
                                          String owner, String ownerKey, McpSession session, JobParts.Limits limits) {
        if (runners == null || bridge == null) {
            throw new IllegalArgumentException("No server is running jobs.");
        }
        int active = 0;
        int mine = 0;
        for (Job job : JOBS.values()) {
            if (!job.state().settled()) {
                active++;
                if (job.ownerKey.equals(ownerKey)) {
                    mine++;
                }
            }
        }
        if (mine >= MAX_ACTIVE_PER_OWNER) {
            throw new IllegalArgumentException("You have " + mine + " jobs queued or running; " + MAX_ACTIVE_PER_OWNER
                + " is the most. Wait for one to finish, or cancel one.");
        }
        if (active >= MAX_ACTIVE) {
            throw new IllegalArgumentException("The server has " + active + " jobs queued or running; try again later.");
        }
        List<JobParts.Part> parts = JobParts.split(kind, args, limits);
        String id = "j" + Long.toString(System.currentTimeMillis() % 100_000_000L, 36) + NEXT.getAndIncrement();
        Job job = new Job(id, kind, tool.getName(), label, owner, ownerKey, parts, System.currentTimeMillis());
        JOBS.put(id, job);
        dirty = true;
        final GameThreadBridge gameThread = bridge;
        final long timeout = gameThreadTimeoutMillis;
        runners.submit(() -> run(job, tool, session, gameThread, timeout));
        return job;
    }

    @Nullable
    public static synchronized Job get(String id) {
        return JOBS.get(id);
    }

    public static synchronized List<Job> jobs() {
        return new ArrayList<>(JOBS.values());
    }

    public static void pauseAll(Job.Hold hold) {
        for (Job job : jobs()) {
            job.pause(hold);
        }
        dirty = true;
    }

    public static void resumeAll(Job.Hold hold) {
        for (Job job : jobs()) {
            job.resume(hold);
        }
        dirty = true;
    }

    public static void cancelAll() {
        for (Job job : jobs()) {
            job.cancel();
        }
        dirty = true;
    }

    // ------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------

    private static void run(Job job, McpTool tool, McpSession session, GameThreadBridge gameThread, long timeout) {
        if (job.state().settled()) {
            return;
        }
        job.started(System.currentTimeMillis());
        // Cancelling the job cancels the call it is running, not only its next game-thread step: a
        // test script waits between steps, and a wait that never asked would sleep through a cancel.
        McpSession.CancellationToken cancellation = new McpSession.CancellationToken() {
            @Override
            public boolean isCancelled() {
                return super.isCancelled() || job.cancelRequested();
            }

            @Override
            public void throwIfCancelled() {
                super.throwIfCancelled();
                if (job.cancelRequested()) {
                    throw new JsonRpcException(JsonRpcException.REQUEST_CANCELLED, "The job was cancelled.");
                }
            }
        };
        boolean pages = "get_blocks".equals(job.kind);
        int count = job.parts.size();
        try {
            for (JobParts.Part part : job.parts) {
                ToolResult result = null;
                for (int attempt = 0; ; attempt++) {
                    if (!job.awaitRunnable()) {
                        job.finish(Job.State.CANCELLED, "Cancelled after " + part.index + " of " + count + " parts.");
                        return;
                    }
                    JobContext context = new JobContext(job, part.index, count, session, part.args, gameThread,
                        cancellation, timeout);
                    try {
                        result = tool.call(context);
                    }
                    catch (com.micatechnologies.minecraft.mcmcp.mcp.ToolBusyException busy) {
                        // Too busy to start a step: retried below like any other busy refusal.
                        result = busy.toResult();
                    }
                    long retryAfter = retryAfter(result);
                    if (retryAfter < 0L || attempt >= BUSY_RETRIES) {
                        break;
                    }
                    job.progress(0.0D, "Busy; retrying part " + (part.index + 1) + " in " + retryAfter / 1000L + " s");
                    Thread.sleep(retryAfter);
                }
                if (result.isError()) {
                    job.finish(Job.State.FAILED, "Part " + (part.index + 1) + " of " + count + " failed: "
                        + result.getText());
                    return;
                }
                job.partDone(part, result.getStructuredContent(), pages, MAX_PAGE_BYTES);
                dirty = true;
                // A part that saw the cancel and stopped early still returns its result; the job was
                // cancelled all the same, and must not read as done.
                if (job.cancelRequested()) {
                    job.finish(Job.State.CANCELLED, "Cancelled during part " + (part.index + 1) + " of " + count + ".");
                    return;
                }
            }
            job.finish(Job.State.DONE, null);
        }
        catch (JsonRpcException e) {
            job.finish(e.getCode() == JsonRpcException.REQUEST_CANCELLED ? Job.State.CANCELLED : Job.State.FAILED,
                e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.finish(Job.State.INTERRUPTED, "The job's runner was stopped.");
        }
        catch (Exception e) {
            Mcmcp.LOGGER.error("MCMCP job " + job.id + " failed", e);
            job.finish(Job.State.FAILED, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        finally {
            dirty = true;
        }
    }

    /** How long a busy refusal asks to wait, or -1 if the result is not one. */
    private static long retryAfter(ToolResult result) {
        if (!result.isError()) {
            return -1L;
        }
        JsonObject structured = result.getStructuredContent();
        JsonObject busy = structured == null ? null : Json.getObject(structured, "busy");
        if (busy == null) {
            String text = String.valueOf(result.getText());
            return text.contains("chunks loaded") || text.contains("too busy") ? 5000L : -1L;
        }
        // Part of it ran, so running the part again would do that part twice.
        if (Json.getBoolean(busy, "earlierStepsRan", false)) {
            return -1L;
        }
        long wait = Json.getLong(busy, "retryAfterMs", 5000L);
        return Math.max(1000L, Math.min(60_000L, wait));
    }

    // ------------------------------------------------------------------
    // Watching: notifications, the record on disk, and forgetting old jobs
    // ------------------------------------------------------------------

    private static void watch() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1000L);
            }
            catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            List<Job> changed = new ArrayList<>();
            synchronized (JobManager.class) {
                for (java.util.Iterator<Job> it = JOBS.values().iterator(); it.hasNext(); ) {
                    Job job = it.next();
                    Long seen = NOTIFIED.get(job.id);
                    if (seen == null || seen != job.version()) {
                        NOTIFIED.put(job.id, job.version());
                        changed.add(job);
                    }
                    long ended = job.endedMillis();
                    long keep = job.parts.isEmpty() ? KEEP_RESTORED_MILLIS : KEEP_SETTLED_MILLIS;
                    if (job.state().settled() && ended > 0L && now - ended > keep) {
                        it.remove();
                        NOTIFIED.remove(job.id);
                        dirty = true;
                    }
                }
            }
            for (Job job : changed) {
                for (Consumer<Job> listener : LISTENERS) {
                    try {
                        listener.accept(job);
                    }
                    catch (RuntimeException e) {
                        Mcmcp.LOGGER.debug("MCMCP job listener failed", e);
                    }
                }
            }
            if (!changed.isEmpty() || dirty) {
                dirty = false;
                save();
            }
        }
    }

    private static synchronized void save() {
        if (folder == null) {
            return;
        }
        JsonArray saved = new JsonArray();
        for (Job job : JOBS.values()) {
            saved.add(job.toSaved());
        }
        File file = new File(folder, FILE);
        File temporary = new File(folder, FILE + ".tmp");
        try {
            Files.write(temporary.toPath(), Json.write(saved).getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        catch (Exception e) {
            Mcmcp.LOGGER.warn("MCMCP could not save the job record: " + e.getMessage());
        }
    }

    private static void load() {
        File file = folder == null ? null : new File(folder, FILE);
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            JsonElement parsed = Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (!parsed.isJsonArray()) {
                return;
            }
            long now = System.currentTimeMillis();
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                Job job = Job.interrupted(element.getAsJsonObject());
                if (job.endedMillis() == 0L || now - job.endedMillis() < KEEP_RESTORED_MILLIS) {
                    JOBS.put(job.id, job);
                }
            }
        }
        catch (Exception e) {
            Mcmcp.LOGGER.warn("MCMCP could not read the job record " + file + ": " + e.getMessage());
        }
    }
}
