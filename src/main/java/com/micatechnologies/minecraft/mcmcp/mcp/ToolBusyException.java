package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.game.ServerLoad;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpcException;

/**
 * A call the game was too busy for, thrown from wherever it is found out and answered as a tool error
 * with a structured {@code busy} block beside the message: {@code {"busy": {"reason": ...,
 * "retryAfterMs": ...}}}. Either it was refused, or a step of it was dropped before it ran, or a step
 * started and had not finished when its caller stopped waiting ({@link #startedNotFinished}).
 *
 * <p>A {@link JsonRpcException} so that every handler that already lets those through, or turns them
 * into its own error, still does the right thing. The dispatcher answers it as {@link #toResult()},
 * never as a JSON-RPC error: a protocol error is eaten by the client's plumbing and the model retries
 * the identical call at once, which is the last thing a busy server needs.
 */
public final class ToolBusyException extends JsonRpcException {

    private static final long serialVersionUID = 1L;

    private final transient JsonObject busy;
    private final boolean nothingRan;

    /**
     * @param nothingRan true when the step that was refused or dropped certainly did not run, so
     *                   trying that step again cannot do it twice
     */
    public ToolBusyException(String message, JsonObject busy, boolean nothingRan) {
        super(INTERNAL_ERROR, message);
        this.busy = busy;
        this.nothingRan = nothingRan;
    }

    /**
     * A step that had started on the server thread and had not finished when its caller stopped
     * waiting. It runs to its end, since nothing can stop it halfway, so what it changed is not known
     * yet. The block has {@code "reason": "started_not_finished"} and {@code "effectUnknown": true}, and
     * no {@code retryAfterMs}: resending the call is not safe until the world has been looked at, and a
     * job does not do it by itself.
     */
    public static ToolBusyException startedNotFinished(long timeoutMillis, long ranForMillis) {
        JsonObject busy = ServerLoad.busy("started_not_finished");
        busy.remove("retryAfterMs");
        busy.addProperty("effectUnknown", true);
        busy.addProperty("ranForMs", ranForMillis);
        busy.addProperty("timeoutMs", timeoutMillis);
        return new ToolBusyException("The server thread started this call's work but had not finished it after "
            + timeoutMillis + " ms, so this call stopped waiting. The work goes on to its end, and its effect is not "
            + "known: some, all or none of it may have happened. Check the world (read the blocks or state it "
            + "changes) before you retry, or it may be done twice. The server is " + ServerLoad.describe() + ".",
            busy, false);
    }

    public boolean nothingRan() {
        return nothingRan;
    }

    /** How long the refusal says to wait, or 0 when it does not say. */
    public long retryAfterMillis() {
        return busy.has("retryAfterMs") ? busy.get("retryAfterMs").getAsLong() : 0L;
    }

    public ToolResult toResult() {
        JsonObject structured = new JsonObject();
        structured.add("busy", busy);
        return ToolResult.error(getMessage()).withStructured(structured);
    }
}
