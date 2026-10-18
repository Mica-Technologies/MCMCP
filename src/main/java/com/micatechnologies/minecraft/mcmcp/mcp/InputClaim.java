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

    /** The one the game uses is {@link #get()}; tests make their own. */
    InputClaim() {
    }

    public static InputClaim get() {
        return INSTANCE;
    }

    /** Records a lock taken or extended by {@code holder}. */
    public synchronized void claimed(CallerId holder, String why, boolean exclusiveLock, long untilMillis) {
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
        if (!heldAt(nowMillis) || !exclusive || caller.id().equals(holderId)) {
            return null;
        }
        return "Busy: " + holderLabel + " holds this client's input exclusively"
            + (reason.isEmpty() ? "" : " (" + reason + ")") + " for another " + secondsLeft(nowMillis)
            + " s. Wait for it (client_input_lock op 'wait'), or take the lock over with client_input_lock "
            + "locked: true, force: true if you are sure it is abandoned.";
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
            json.addProperty("exclusive", exclusive);
            json.addProperty("secondsLeft", secondsLeft(nowMillis));
            if (!reason.isEmpty()) {
                json.addProperty("reason", reason);
            }
        }
        return json;
    }

    /** Whether {@code caller} would be refused for taking or releasing the lock without force. */
    public synchronized boolean heldExclusivelyByAnother(CallerId caller, long nowMillis) {
        return heldAt(nowMillis) && exclusive && !caller.id().equals(holderId);
    }

    private int secondsLeft(long nowMillis) {
        long remaining = expiresAtMillis - nowMillis;
        return remaining <= 0L ? 0 : (int) ((remaining + 999L) / 1000L);
    }
}
