package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonObject;
import javax.annotation.Nullable;

/**
 * Who holds the client's input lock, and whether other agents must keep off (#94).
 *
 * <p>Several agents drive one client at once. One took {@code client_input_lock} for a lift ride
 * test while another, with no way to know, teleported the shared player and clicked through GUIs in
 * the middle of it. This records the holder, so any agent can ask, and an <em>exclusive</em> lock
 * makes the client's other input tools refuse other agents until it ends.
 *
 * <p>{@code ClientInputLock} does the locking itself and keeps this in step; this holds only who and
 * until when, so the dispatcher can consult it without naming a client class. Pure and
 * synchronized, so it is unit-tested directly.
 */
public final class InputClaim {

    private static final InputClaim INSTANCE = new InputClaim();

    @Nullable
    private String holderId;
    private String holderLabel = "";
    private String reason = "";
    private boolean exclusive;
    private long expiresAtMillis;
    /**
     * What a second connection of the holder's presents to be let through an exclusive lock (#190):
     * the holder's own build script, driving the client over a connection of its own, was refused by
     * the lock its agent had taken to keep everyone else out. Made when an exclusive lock is taken, and
     * handed only to the caller that took it.
     */
    @Nullable
    private String token;
    /** The callers that joined the lock with its token; they drive the client as the holder does. */
    private final java.util.Set<String> guests = new java.util.HashSet<>();
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /** The one the game uses is {@link #get()}; tests make their own. */
    InputClaim() {
    }

    public static InputClaim get() {
        return INSTANCE;
    }

    /** Records a lock taken or extended by {@code holder}. */
    public synchronized void claimed(CallerId holder, String why, boolean exclusiveLock, long untilMillis) {
        // The same holder locking again extends its lock, and whoever it let in stays in. A new holder,
        // or a lock no longer exclusive, starts clean.
        boolean sameHolder = holder.id().equals(holderId);
        if (!sameHolder || !exclusiveLock) {
            token = null;
            guests.clear();
        }
        if (exclusiveLock && token == null) {
            byte[] entropy = new byte[8];
            RANDOM.nextBytes(entropy);
            StringBuilder hex = new StringBuilder(16);
            for (byte b : entropy) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            token = hex.toString();
        }
        holderId = holder.id();
        holderLabel = holder.label();
        reason = why == null ? "" : why;
        exclusive = exclusiveLock;
        expiresAtMillis = untilMillis;
    }

    /** Records that the lock ended, however it ended. */
    public synchronized void released() {
        holderId = null;
        holderLabel = "";
        reason = "";
        exclusive = false;
        expiresAtMillis = 0L;
        token = null;
        guests.clear();
    }

    /** Whether {@code caller} holds the lock or joined it: whose input calls an exclusive lock lets through. */
    private boolean inside(CallerId caller) {
        return caller.id().equals(holderId) || guests.contains(caller.id());
    }

    /**
     * The token of the exclusive lock {@code caller} holds, for it to give its other connections; null
     * when it holds none. Never for anyone but the holder: with it, any caller is let through.
     */
    @Nullable
    public synchronized String tokenFor(CallerId caller, long nowMillis) {
        return heldAt(nowMillis) && exclusive && caller.id().equals(holderId) ? token : null;
    }

    /**
     * Lets {@code caller} through the exclusive lock whose token this is, until the lock ends.
     *
     * @return null when it is in, or why not
     */
    @Nullable
    public synchronized String join(CallerId caller, @Nullable String presented, long nowMillis) {
        if (!heldAt(nowMillis) || !exclusive) {
            return "No exclusive lock is held, so there is nothing to join: the client's input tools are not "
                + "refusing you.";
        }
        if (inside(caller)) {
            return null;
        }
        if (presented == null || token == null || !token.equals(presented.trim())) {
            return "That is not the token of the lock " + holderLabel + " holds. The holder gets it from "
                + "client_input_lock locked: true, exclusive: true, or op 'status'.";
        }
        guests.add(caller.id());
        return null;
    }

    private boolean heldAt(long nowMillis) {
        return holderId != null && nowMillis < expiresAtMillis;
    }

    /**
     * Why {@code caller} may not drive the client now, or null if it may: another agent holds an
     * exclusive lock.
     */
    @Nullable
    public synchronized String refusalFor(CallerId caller, long nowMillis) {
        if (!heldAt(nowMillis) || !exclusive || inside(caller)) {
            return null;
        }
        return "Busy: " + holderLabel + " holds this client's input exclusively"
            + (reason.isEmpty() ? "" : " (" + reason + ")") + " for another " + secondsLeft(nowMillis)
            + " s. Wait for it (client_input_lock op 'wait'), or take the lock over with client_input_lock "
            + "locked: true, force: true if you are sure it is abandoned. If you are that agent's own script "
            + "on another connection, join it: client_input_lock op 'join' with the lock's token.";
    }

    /** The structured {@code busy} block a refusal carries: who, and when to retry. */
    public synchronized JsonObject busy(long nowMillis) {
        JsonObject json = new JsonObject();
        json.addProperty("reason", "inputLocked");
        json.addProperty("holder", holderLabel);
        json.addProperty("retryAfterMs", Math.max(1000L, expiresAtMillis - nowMillis));
        return json;
    }

    /** The lock as {@code op: status} reports it, from {@code asker}'s point of view. */
    public synchronized JsonObject status(CallerId asker, long nowMillis) {
        JsonObject json = new JsonObject();
        boolean held = heldAt(nowMillis);
        json.addProperty("locked", held);
        if (held) {
            json.addProperty("holder", holderLabel);
            json.addProperty("yours", asker.id().equals(holderId));
            if (guests.contains(asker.id())) {
                json.addProperty("joined", true);
            }
            json.addProperty("exclusive", exclusive);
            if (exclusive && asker.id().equals(holderId)) {
                // To the holder only: its other connections present this to be let through (#190).
                json.addProperty("token", token);
                if (!guests.isEmpty()) {
                    json.addProperty("joinedConnections", guests.size());
                }
            }
            json.addProperty("secondsLeft", secondsLeft(nowMillis));
            if (!reason.isEmpty()) {
                json.addProperty("reason", reason);
            }
        }
        return json;
    }

    /** Whether {@code caller} would be refused for taking or releasing the lock without force. */
    public synchronized boolean heldExclusivelyByAnother(CallerId caller, long nowMillis) {
        // A connection that joined drives the client; the lock itself stays the holder's to set.
        return heldAt(nowMillis) && exclusive && !caller.id().equals(holderId);
    }

    private int secondsLeft(long nowMillis) {
        long remaining = expiresAtMillis - nowMillis;
        return remaining <= 0L ? 0 : (int) ((remaining + 999L) / 1000L);
    }
}
