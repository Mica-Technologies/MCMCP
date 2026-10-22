package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpcException;

/**
 * A call refused because the game is too busy, thrown from wherever it is found out and answered as
 * a tool error with a structured {@code busy} block beside the message: {@code {"busy": {"reason":
 * ..., "retryAfterMs": ...}}}.
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
     * @param nothingRan true when the call's work certainly did not run, so retrying it cannot do it
     *                   twice
     */
    public ToolBusyException(String message, JsonObject busy, boolean nothingRan) {
        super(INTERNAL_ERROR, message);
        this.busy = busy;
        this.nothingRan = nothingRan;
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
