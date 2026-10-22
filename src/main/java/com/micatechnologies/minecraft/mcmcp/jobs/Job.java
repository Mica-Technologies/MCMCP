package com.micatechnologies.minecraft.mcmcp.jobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * One background job: a long write or read split into parts, each run as an ordinary tool call (#73).
 *
 * <p>The record is what {@code server_job_status} shows and what survives a restart, so it says how
 * far the job got in the caller's own terms: parts, and for an op replay, the index of the last op
 * finished, which is where to resume with the rest.
 *
 * <p>No Minecraft class. Thread-safe: the runner writes it, any caller reads it.
 */
public final class Job {

    public enum State {
        QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED, INTERRUPTED;

        public boolean settled() {
            return this == DONE || this == FAILED || this == CANCELLED || this == INTERRUPTED;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Why a job is paused; it runs only once none remain. */
    public enum Hold {
        OWNER, OPERATOR, ESTOP
    }

    /** What a page of a read job holds, and the part that produced it. */
    public static final class Page {
        public final int index;
        public final JsonObject content;

        Page(int index, JsonObject content) {
            this.index = index;
            this.content = content;
        }
    }

    public final String id;
    public final String kind;
    public final String tool;
    @Nullable
    public final String label;
    /** Who submitted it, as shown to people: a player's name, or "endpoint". */
    public final String owner;
    /** The ownership key, as the chunk governor keys callers. */
    public final String ownerKey;
    public final long createdMillis;
    final List<JobParts.Part> parts;

    private State state = State.QUEUED;
    private final java.util.EnumSet<Hold> holds = java.util.EnumSet.noneOf(Hold.class);
    private boolean cancelRequested;
    private int partsDone;
    private double partFraction;
    @Nullable
    private String message;
    private long startedMillis;
    private long endedMillis;
    @Nullable
    private String error;
    private final JsonObject totals = new JsonObject();
    /** The latest part's word on undo history it had to delete (#131), shown in every status. */
    @Nullable
    private String undoWarning;
    /** A test job's verdict, once its run has ended. */
    @Nullable
    private JsonObject verdict;
    private final JsonArray lists = new JsonArray();
    private final List<Page> pages = new ArrayList<>();
    private long pageBytes;
    /** For an op replay: the last op of the last finished part. -1 before any. */
    private int lastOp = -1;
    /** Bumped on every change, so listeners can skip what they have seen. */
    private long version;

    Job(String id, String kind, String tool, @Nullable String label, String owner, String ownerKey,
        List<JobParts.Part> parts, long createdMillis) {
        this.id = id;
        this.kind = kind;
        this.tool = tool;
        this.label = label;
        this.owner = owner;
        this.ownerKey = ownerKey;
        this.parts = parts;
        this.createdMillis = createdMillis;
    }

    /** A job read back after a restart: its record only, never runnable again. */
    static Job interrupted(JsonObject saved) {
        Job job = new Job(saved.get("id").getAsString(), saved.get("kind").getAsString(),
            saved.has("tool") ? saved.get("tool").getAsString() : "", saved.has("label") ? saved.get("label").getAsString() : null,
            saved.has("owner") ? saved.get("owner").getAsString() : "?",
            saved.has("ownerKey") ? saved.get("ownerKey").getAsString() : "?", new ArrayList<>(),
            saved.has("created") ? saved.get("created").getAsLong() : 0L);
        State was = State.valueOf(saved.get("state").getAsString().toUpperCase(Locale.ROOT));
        job.state = was.settled() ? was : State.INTERRUPTED;
        job.partsDone = saved.has("partsDone") ? saved.get("partsDone").getAsInt() : 0;
        job.restoredPartCount = saved.has("parts") ? saved.get("parts").getAsInt() : 0;
        job.lastOp = saved.has("lastOp") ? saved.get("lastOp").getAsInt() : -1;
        job.startedMillis = saved.has("started") ? saved.get("started").getAsLong() : 0L;
        job.endedMillis = saved.has("ended") ? saved.get("ended").getAsLong() : 0L;
        job.error = saved.has("error") ? saved.get("error").getAsString()
            : was.settled() ? null : "The server stopped while this job ran. Resume from lastOp + 1 with a new job.";
        if (saved.has("undoWarning")) {
            job.undoWarning = saved.get("undoWarning").getAsString();
        }
        if (saved.has("totals") && saved.get("totals").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : saved.getAsJsonObject("totals").entrySet()) {
                job.totals.add(entry.getKey(), entry.getValue());
            }
        }
        return job;
    }

    private int restoredPartCount = -1;

    public synchronized State state() {
        return state;
    }

    public synchronized long version() {
        return version;
    }

    /**
     * Waits until the job's state is no longer {@code from}, or {@code millis} pass. Progress alone
     * does not end the wait: a caller waiting on a job wants the next thing that changes what it
     * does next, not every percent.
     */
    public synchronized void awaitStateChange(State from, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        long left = millis;
        while (state == from && left > 0L) {
            wait(Math.min(left, 1000L));
            left = end - System.currentTimeMillis();
        }
    }

    public int partCount() {
        return restoredPartCount >= 0 ? restoredPartCount : parts.size();
    }

    // ------------------------------------------------------------------
    // Control, from any thread
    // ------------------------------------------------------------------

    /** @return false if the job had already finished */
    public synchronized boolean pause(Hold hold) {
        if (state.settled()) {
            return false;
        }
        holds.add(hold);
        if (state == State.RUNNING || state == State.QUEUED) {
            state = State.PAUSED;
        }
        changed();
        return true;
    }

    /** Lifts one reason to pause. @return false if the job had already finished */
    public synchronized boolean resume(Hold hold) {
        if (state.settled()) {
            return false;
        }
        holds.remove(hold);
        if (holds.isEmpty() && state == State.PAUSED) {
            state = startedMillis == 0L ? State.QUEUED : State.RUNNING;
        }
        changed();
        return true;
    }

    public synchronized boolean isPausedBy(Hold hold) {
        return holds.contains(hold);
    }

    /** @return false if the job had already finished */
    public synchronized boolean cancel() {
        if (state.settled()) {
            return false;
        }
        cancelRequested = true;
        if (startedMillis == 0L) {
            finish(State.CANCELLED, "Cancelled before it started.");
        }
        changed();
        return true;
    }

    public synchronized boolean cancelRequested() {
        return cancelRequested;
    }

    /**
     * Blocks the runner while the job is paused. Called before every game-thread step of every part,
     * so a pause takes effect within one step.
     *
     * @return false if the job was cancelled and the runner must stop
     */
    public synchronized boolean awaitRunnable() throws InterruptedException {
        while (!cancelRequested && !holds.isEmpty()) {
            wait(1000L);
        }
        return !cancelRequested;
    }

    // ------------------------------------------------------------------
    // Progress, from the runner
    // ------------------------------------------------------------------

    synchronized void started(long nowMillis) {
        startedMillis = nowMillis;
        if (state == State.QUEUED) {
            state = holds.isEmpty() ? State.RUNNING : State.PAUSED;
        }
        changed();
    }

    synchronized void progress(double fraction, @Nullable String message) {
        this.partFraction = Math.max(0.0D, Math.min(0.999D, fraction));
        if (message != null) {
            this.message = message;
        }
        changed();
    }

    /** One part finished: its result folded into the totals, and for reads kept as a page. */
    synchronized void partDone(JobParts.Part part, @Nullable JsonObject result, boolean keepAsPage,
                               long maxPageBytes) {
        partsDone++;
        partFraction = 0.0D;
        if (part.lastOp >= 0) {
            lastOp = part.lastOp;
        }
        if (result != null && "test".equals(kind)) {
            // A test's result is its verdict, not counts to add up.
            verdict = result;
        }
        else if (result != null) {
            JobParts.fold(totals, lists, result, part.opOffset);
            JsonElement warning = result.get("undoWarning");
            if (warning != null && warning.isJsonPrimitive()) {
                undoWarning = warning.getAsString();
            }
            if (keepAsPage) {
                JsonObject page = new JsonObject();
                if (part.region != null) {
                    page.add("region", part.region);
                }
                page.add("result", result);
                pageBytes += page.toString().length();
                pages.add(new Page(pages.size(), page));
                if (pageBytes > maxPageBytes) {
                    throw new IllegalStateException("This read has produced over " + (maxPageBytes >> 20)
                        + " MB of pages; read it in smaller jobs, or fetch and drop pages as they come.");
                }
            }
        }
        changed();
    }

    synchronized void finish(State end, @Nullable String why) {
        if (state.settled()) {
            return;
        }
        state = end;
        error = why;
        endedMillis = System.currentTimeMillis();
        notifyAll();
        changed();
    }

    private void changed() {
        version++;
        notifyAll();
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    @Nullable
    public synchronized Page page(int index) {
        return index >= 0 && index < pages.size() ? pages.get(index) : null;
    }

    public synchronized int pageCount() {
        return pages.size();
    }

    /** The status a caller sees. {@code full} adds the lists of refusals and the like. */
    public synchronized JsonObject toJson(boolean full) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("kind", kind);
        if (label != null) {
            json.addProperty("label", label);
        }
        json.addProperty("owner", owner);
        json.addProperty("state", state.id());
        if (!holds.isEmpty()) {
            JsonArray why = new JsonArray();
            for (Hold hold : holds) {
                why.add(hold.name().toLowerCase(Locale.ROOT));
            }
            json.add("pausedBy", why);
        }
        int count = partCount();
        json.addProperty("partsDone", partsDone);
        json.addProperty("parts", count);
        double done = count == 0 ? 0.0D : (partsDone + partFraction) / count;
        json.addProperty("percent", Math.round(done * 1000.0D) / 10.0D);
        if (lastOp >= 0) {
            json.addProperty("lastOp", lastOp);
        }
        if (message != null && !state.settled()) {
            json.addProperty("message", message);
        }
        long now = System.currentTimeMillis();
        if (startedMillis > 0L) {
            long elapsed = (endedMillis > 0L ? endedMillis : now) - startedMillis;
            json.addProperty("elapsedMs", elapsed);
            if (!state.settled() && done > 0.02D) {
                json.addProperty("etaMs", Math.round(elapsed / done - elapsed));
            }
        }
        if (totals.size() > 0) {
            json.add("totals", totals);
        }
        if (undoWarning != null) {
            json.addProperty("undoWarning", undoWarning);
        }
        if (verdict != null) {
            json.add("verdict", verdict);
        }
        if (full && lists.size() > 0) {
            json.add("refused", lists);
        }
        if (!pages.isEmpty()) {
            json.addProperty("pages", pages.size());
        }
        if (error != null) {
            json.addProperty(state == State.DONE ? "note" : "error", error);
        }
        return json;
    }

    /** What is written to disk: the status plus what a restart needs to rebuild it. */
    synchronized JsonObject toSaved() {
        JsonObject json = toJson(false);
        json.remove("percent");
        json.remove("etaMs");
        json.remove("pages");
        json.addProperty("tool", tool);
        json.addProperty("ownerKey", ownerKey);
        json.addProperty("created", createdMillis);
        json.addProperty("started", startedMillis);
        json.addProperty("ended", endedMillis);
        return json;
    }

    synchronized long endedMillis() {
        return endedMillis;
    }
}
