package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.chunkload.HoldLedger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Who is borrowing a shared player, and who is waiting to (#144).
 *
 * <p>Parallel agents drive one player, and linking devices means holding a linker in a hotbar slot
 * and clicking blocks with it over several calls. Two agents doing that at once broke each other's
 * pairing and cleared the player's own item from the slot. A lease names one agent as the player's
 * borrower: the player's item-use and teleport tools refuse every other agent until it ends, and the
 * slots it names are put back as they were when it does.
 *
 * <p>Waiting is first come, first served: an agent that asked first is granted first, as long as it is
 * still asking. One that stops polling for {@link #WAITER_GONE_MILLIS} loses its place.
 *
 * <p>Pure and synchronized, so it is unit-tested directly. The slot contents are opaque strings (NBT,
 * written and read on the game thread by the tool), so nothing here names a Minecraft class.
 */
public final class PlayerLeases {

    /** A waiter that has not asked again in this long has given up. */
    public static final long WAITER_GONE_MILLIS = 5_000L;

    private static final PlayerLeases INSTANCE = new PlayerLeases();

    /** One agent's lease of one player. */
    public static final class Lease {
        public final String player;
        public final String holderKey;
        public final String holderLabel;
        public final String purpose;
        /** Hotbar or inventory slot to its contents when the lease was taken, to put back on release. */
        public final Map<Integer, String> slots;
        final long leaseMillis;
        long expiresAtMillis;

        Lease(String player, String holderKey, String holderLabel, String purpose, Map<Integer, String> slots,
              long leaseMillis, long nowMillis) {
            this.player = player;
            this.holderKey = holderKey;
            this.holderLabel = holderLabel;
            this.purpose = purpose;
            this.slots = Collections.unmodifiableMap(new LinkedHashMap<>(slots));
            this.leaseMillis = leaseMillis;
            this.expiresAtMillis = nowMillis + leaseMillis;
        }

        public int secondsLeft(long nowMillis) {
            long remaining = expiresAtMillis - nowMillis;
            return remaining <= 0L ? 0 : (int) ((remaining + 999L) / 1000L);
        }
    }

    /** The outcome of asking for a lease. */
    public static final class Ask {
        @Nullable
        public final Lease granted;
        @Nullable
        public final Lease heldBy;
        /** 1 when next in line. */
        public final int place;

        Ask(@Nullable Lease granted, @Nullable Lease heldBy, int place) {
            this.granted = granted;
            this.heldBy = heldBy;
            this.place = place;
        }
    }

    private final Map<String, Lease> leases = new HashMap<>();
    /** Per player, the waiting callers in the order they first asked, with when each last asked. */
    private final Map<String, LinkedHashMap<String, Long>> waiting = new HashMap<>();

    /** The one the game uses is {@link #get()}; tests make their own. */
    PlayerLeases() {
    }

    public static PlayerLeases get() {
        return INSTANCE;
    }

    private static String keyOf(String player) {
        return player.toLowerCase(Locale.ROOT);
    }

    /**
     * Asks for {@code player}'s lease. Granted when nobody holds it and nobody asked before this
     * caller, or when the caller already holds it (then it is renewed, and its slots stay as first
     * recorded). Otherwise the caller is queued, or keeps its place.
     *
     * @param slots what to restore on release, read when the lease is granted; ignored on a renewal
     */
    public synchronized Ask acquire(String player, String holderKey, String holderLabel, String purpose,
                                    Map<Integer, String> slots, long leaseMillis, long nowMillis) {
        String key = keyOf(player);
        Lease held = live(key, nowMillis);
        if (held != null && held.holderKey.equals(holderKey)) {
            held.expiresAtMillis = nowMillis + leaseMillis;
            return new Ask(held, null, 0);
        }
        LinkedHashMap<String, Long> line = waiting.computeIfAbsent(key, k -> new LinkedHashMap<>());
        line.entrySet().removeIf(entry -> nowMillis - entry.getValue() > WAITER_GONE_MILLIS);
        if (held == null && (line.isEmpty() || line.keySet().iterator().next().equals(holderKey))) {
            line.remove(holderKey);
            Lease lease = new Lease(player, holderKey, holderLabel, purpose, slots, leaseMillis, nowMillis);
            leases.put(key, lease);
            return new Ask(lease, null, 0);
        }
        line.put(holderKey, nowMillis);
        int place = 1;
        for (String waiter : line.keySet()) {
            if (waiter.equals(holderKey)) {
                break;
            }
            place++;
        }
        return new Ask(null, held, place);
    }

    /** Leaves the queue for {@code player}: an agent that stopped waiting. */
    public synchronized void stopWaiting(String player, String holderKey) {
        LinkedHashMap<String, Long> line = waiting.get(keyOf(player));
        if (line != null) {
            line.remove(holderKey);
        }
    }

    /**
     * Ends {@code player}'s lease if {@code holderKey} holds it, or always with {@code force}.
     *
     * @return the lease ended, whose slots the caller puts back; null when there was none to end
     */
    @Nullable
    public synchronized Lease release(String player, String holderKey, boolean force) {
        String key = keyOf(player);
        Lease held = leases.get(key);
        if (held == null || (!force && !held.holderKey.equals(holderKey))) {
            return null;
        }
        leases.remove(key);
        return held;
    }

    /**
     * Ends every lease {@code key} covers, as {@link HoldLedger#covers} reads it: an agent's own, or
     * all of a player's agents' when the player's companion access ends.
     */
    public synchronized List<Lease> releaseHolder(String key) {
        List<Lease> ended = new ArrayList<>();
        Iterator<Lease> it = leases.values().iterator();
        while (it.hasNext()) {
            Lease lease = it.next();
            if (HoldLedger.covers(key, lease.holderKey)) {
                ended.add(lease);
                it.remove();
            }
        }
        return ended;
    }

    /** Ends {@code player}'s lease whoever holds it: the player left. */
    @Nullable
    public synchronized Lease releasePlayer(String player) {
        waiting.remove(keyOf(player));
        return leases.remove(keyOf(player));
    }

    /** Ends and returns every lease past its time, for their slots to be put back. */
    public synchronized List<Lease> expire(long nowMillis) {
        List<Lease> ended = new ArrayList<>();
        Iterator<Lease> it = leases.values().iterator();
        while (it.hasNext()) {
            Lease lease = it.next();
            if (nowMillis >= lease.expiresAtMillis) {
                ended.add(lease);
                it.remove();
            }
        }
        return ended;
    }

    /**
     * Why {@code callerKey} may not borrow or move {@code player} now, or null if it may: another agent
     * holds the lease. A use by the holder renews the lease, so a script working steadily keeps it.
     */
    @Nullable
    public synchronized String refusalFor(String player, String callerKey, long nowMillis) {
        Lease held = live(keyOf(player), nowMillis);
        if (held == null) {
            return null;
        }
        if (held.holderKey.equals(callerKey)) {
            held.expiresAtMillis = Math.max(held.expiresAtMillis, nowMillis + held.leaseMillis);
            return null;
        }
        return "Busy: " + held.holderLabel + " has leased " + held.player
            + (held.purpose.isEmpty() ? "" : " (" + held.purpose + ")") + " for another " + held.secondsLeft(nowMillis)
            + " s, so its hotbar and position are theirs. Wait with server_player_lease op 'acquire' and "
            + "waitSeconds, which queues you, or take it over with force: true if you are sure it is abandoned.";
    }

    /** The structured {@code busy} block a refusal carries. */
    public synchronized JsonObject busy(String player, long nowMillis) {
        JsonObject json = new JsonObject();
        json.addProperty("reason", "playerLeased");
        Lease held = live(keyOf(player), nowMillis);
        if (held != null) {
            json.addProperty("holder", held.holderLabel);
            if (!held.purpose.isEmpty()) {
                json.addProperty("purpose", held.purpose);
            }
            json.addProperty("retryAfterMs", Math.max(1000L, held.expiresAtMillis - nowMillis));
        }
        return json;
    }

    /** The lease on {@code player} as {@code op: status} reports it, from {@code callerKey}'s side. */
    public synchronized JsonObject status(String player, String callerKey, long nowMillis) {
        JsonObject json = new JsonObject();
        Lease held = live(keyOf(player), nowMillis);
        json.addProperty("leased", held != null);
        if (held != null) {
            json.addProperty("holder", held.holderLabel);
            json.addProperty("yours", held.holderKey.equals(callerKey));
            json.addProperty("secondsLeft", held.secondsLeft(nowMillis));
            if (!held.purpose.isEmpty()) {
                json.addProperty("purpose", held.purpose);
            }
            if (!held.slots.isEmpty()) {
                json.addProperty("restoresSlots", held.slots.size());
            }
        }
        LinkedHashMap<String, Long> line = waiting.get(keyOf(player));
        int queued = 0;
        if (line != null) {
            for (Long asked : line.values()) {
                if (nowMillis - asked <= WAITER_GONE_MILLIS) {
                    queued++;
                }
            }
        }
        json.addProperty("waiting", queued);
        return json;
    }

    /** The lease on {@code player} if it has not run out. An expired one is left for {@link #expire}. */
    @Nullable
    private Lease live(String key, long nowMillis) {
        Lease held = leases.get(key);
        return held != null && nowMillis < held.expiresAtMillis ? held : null;
    }
}
