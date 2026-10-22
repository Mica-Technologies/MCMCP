package com.micatechnologies.minecraft.mcmcp.jobs;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.chunkload.ChunkLoadGovernor;
import com.micatechnologies.minecraft.mcmcp.game.GameThreadBridge;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpcException;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;

/**
 * The context one part of a job runs in: the submitter's session and identity, but the job's own
 * cancellation, progress and chunk holds.
 *
 * <p>Every game-thread step first waits while the job is paused (by its owner, an operator or an
 * emergency stop) or the server is struggling, so a pause lands within one step, and a job never adds
 * work to a server already behind. Holds are taken as {@code job:<id>}, so neither the owner's
 * {@code server_release_loaded all} nor their logout cuts a job's chunks from under it.
 */
final class JobContext extends ToolContext {

    private final Job job;
    private final int part;
    private final int parts;

    JobContext(Job job, int part, int parts, McpSession session, JsonObject arguments, GameThreadBridge bridge,
               McpSession.CancellationToken cancellation, long gameThreadTimeoutMillis) {
        super(session, arguments, bridge, cancellation, gameThreadTimeoutMillis, null);
        this.job = job;
        this.part = part;
        this.parts = parts;
        setHoldCaller("job:" + job.id);
    }

    @Override
    public boolean isJob() {
        return true;
    }

    @Override
    public <T> T onGameThread(Callable<T> task) {
        try {
            if (!job.awaitRunnable()) {
                throw new JsonRpcException(JsonRpcException.REQUEST_CANCELLED, "The job was cancelled.");
            }
            while (ChunkLoadGovernor.struggling() && !job.cancelRequested()) {
                job.progress(0.0D, "Waiting: the server is struggling");
                Thread.sleep(1000L);
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JsonRpcException(JsonRpcException.REQUEST_CANCELLED, "The job's runner was stopped.");
        }
        return super.onGameThread(task);
    }

    /** A job part waits for the writes-per-minute budget rather than failing the job on it (#131). */
    @Override
    public boolean pacesToBudgets() {
        return true;
    }

    /** Waits out pauses, shows the wait in the job's status, and stops within one step of a cancel. */
    @Override
    public void awaitBudget(long millis, String message) {
        try {
            if (!job.awaitRunnable()) {
                throw new JsonRpcException(JsonRpcException.REQUEST_CANCELLED, "The job was cancelled.");
            }
            reportProgress(0.0D, 1.0D, message);
            Thread.sleep(Math.max(0L, millis));
            if (job.cancelRequested()) {
                throw new JsonRpcException(JsonRpcException.REQUEST_CANCELLED, "The job was cancelled.");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JsonRpcException(JsonRpcException.REQUEST_CANCELLED, "The job's runner was stopped.");
        }
    }

    @Override
    public void reportProgress(double progress, double total, @Nullable String message) {
        double fraction = total > 0 ? progress / total : 0.5D;
        job.progress(fraction, (parts > 1 ? "Part " + (part + 1) + " of " + parts + (message == null ? "" : " · ") : "")
            + (message == null ? "" : message));
    }
}
