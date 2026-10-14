package com.micatechnologies.minecraft.mcmcp.game;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;

/**
 * MCMCP's emergency stop: while it is set, nothing MCMCP does may change the world or move a player.
 *
 * <p>An operator sets it with {@code /mcmcp estop} on a server, and only an operator lifts it; an agent
 * may set it with {@code server_estop} but never lift it, so a model cannot undo its own stop. While
 * it is set every call that is not read-only is refused, a call already running stops at its next
 * game-thread step, and every background job pauses. Lifting it leaves the jobs paused until they are
 * resumed one by one.
 *
 * <p>A client learns of its server's stop over the companion channel and holds its own copy, so the
 * agent driving a player through the client endpoint stops too. In singleplayer the server and the
 * client share this one JVM-wide state.
 *
 * <p>No Minecraft class: state and listeners only. Persisting it is the server's job.
 */
public final class Estop {

    /** Who set a stop: this process's own server, or the server a client is connected to. */
    public enum Source {
        LOCAL, REMOTE
    }

    /** A stop, as set. */
    public static final class Stop {
        public final String by;
        public final String reason;
        public final long atMillis;
        public final Source source;

        public Stop(String by, String reason, long atMillis, Source source) {
            this.by = by;
            this.reason = reason;
            this.atMillis = atMillis;
            this.source = source;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("by", by);
            json.addProperty("reason", reason);
            json.addProperty("at", atMillis);
            return json;
        }

        public static Stop fromJson(JsonObject json, Source source) {
            return new Stop(json.has("by") ? json.get("by").getAsString() : "?",
                json.has("reason") ? json.get("reason").getAsString() : "",
                json.has("at") ? json.get("at").getAsLong() : System.currentTimeMillis(), source);
        }
    }

    /** Told when the stop is set or lifted. */
    public interface Listener {
        void changed(@Nullable Stop now, @Nullable Stop before);
    }

    private static volatile Stop local;
    private static volatile Stop remote;
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private Estop() {
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    /** The stop in force, local first, or null. */
    @Nullable
    public static Stop current() {
        Stop stop = local;
        return stop != null ? stop : remote;
    }

    public static boolean active() {
        return current() != null;
    }

    /** Sets this server's stop. A second stop while one is set keeps the first's who and why. */
    public static synchronized void set(String by, String reason) {
        if (local != null) {
            return;
        }
        Stop before = current();
        local = new Stop(by, reason == null || reason.trim().isEmpty() ? "no reason given" : reason.trim(),
            System.currentTimeMillis(), Source.LOCAL);
        fire(before);
    }

    /** Restores a stop read back after a restart, without changing who set it or when. */
    public static synchronized void restore(Stop stop) {
        Stop before = current();
        local = stop;
        fire(before);
    }

    /** Lifts this server's stop. @return false if none was set */
    public static synchronized boolean lift() {
        if (local == null) {
            return false;
        }
        Stop before = current();
        local = null;
        fire(before);
        return true;
    }

    /** What the connected server says, on a client: a stop, or null when lifted or disconnected. */
    public static synchronized void setRemote(@Nullable Stop stop) {
        Stop before = current();
        remote = stop;
        fire(before);
    }

    /** Forgets this server's stop without telling anyone: the server stopped, and the next world loads its own. */
    public static synchronized void clearLocal() {
        local = null;
    }

    private static void fire(@Nullable Stop before) {
        Stop now = current();
        if (now == before) {
            return;
        }
        for (Listener listener : LISTENERS) {
            try {
                listener.changed(now, before);
            }
            catch (RuntimeException e) {
                // A listener must never stop the stop.
            }
        }
    }

    /** The refusal a call gets while stopped. */
    public static String refusal() {
        Stop stop = current();
        if (stop == null) {
            return "";
        }
        return "MCMCP is emergency-stopped" + (stop.source == Source.REMOTE ? " by the server" : "") + ": "
            + stop.by + " stopped it (" + stop.reason + "). Nothing that changes the world or moves a player "
            + "runs until an operator lifts it with /mcmcp estop reset. Read-only calls still work.";
    }
}
