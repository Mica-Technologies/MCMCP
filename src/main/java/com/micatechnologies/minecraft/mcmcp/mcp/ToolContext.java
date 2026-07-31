package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.game.GameThreadBridge;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpcException;
import com.micatechnologies.minecraft.mcmcp.protocol.McpLogLevel;
import com.micatechnologies.minecraft.mcmcp.protocol.McpProtocol;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;

/**
 * Everything a tool handler is given when it runs: its arguments, the session that called it, the
 * bridge onto the game thread, and the channels for reporting progress and log output back.
 *
 * <p>Argument accessors come in two flavours and the distinction is deliberate. The
 * {@code require*} methods throw {@link JsonRpcException} with the parameter named, and are for
 * arguments the schema marked required — a missing one means the client ignored the schema, which
 * is a request-level fault. The plain {@code get*} methods take a default and never throw, and are
 * for optional arguments. Neither ever returns a half-valid value.
 */
public class ToolContext {

    private final McpSession session;
    private final JsonObject arguments;
    private final GameThreadBridge gameThread;
    private final McpSession.CancellationToken cancellation;
    private final long gameThreadTimeoutMillis;

    @Nullable
    private final JsonElement progressToken;

    public ToolContext(McpSession session,
                       JsonObject arguments,
                       GameThreadBridge gameThread,
                       McpSession.CancellationToken cancellation,
                       long gameThreadTimeoutMillis,
                       @Nullable JsonElement progressToken) {
        this.session = session;
        this.arguments = arguments == null ? new JsonObject() : arguments;
        this.gameThread = gameThread;
        this.cancellation = cancellation;
        this.gameThreadTimeoutMillis = gameThreadTimeoutMillis;
        this.progressToken = progressToken;
    }

    public McpSession getSession() {
        return session;
    }

    public JsonObject getArguments() {
        return arguments;
    }

    public GameThreadBridge getGameThread() {
        return gameThread;
    }

    public McmcpSide getSide() {
        return gameThread.getSide();
    }

    public McpSession.CancellationToken getCancellation() {
        return cancellation;
    }

    // ------------------------------------------------------------------
    // Game thread access
    // ------------------------------------------------------------------

    /**
     * Runs {@code task} on the game thread and returns its value, using the configured timeout.
     *
     * <p>The one correct way for a tool to touch world state. Failures are translated into
     * {@link JsonRpcException}s here so handlers do not each reimplement the same try/catch, but
     * note that a tool which wants to report a *game-level* failure to the model should catch this
     * and return {@link ToolResult#error} instead of letting it propagate.
     */
    public <T> T onGameThread(Callable<T> task) {
        cancellation.throwIfCancelled();
        if (!gameThread.isAvailable()) {
            throw new JsonRpcException(JsonRpcException.INTERNAL_ERROR,
                getSide().isClient()
                    ? "The Minecraft client is not ready to accept commands"
                    : "No Minecraft server is currently running");
        }
        try {
            return gameThread.callOnGameThread(task, gameThreadTimeoutMillis);
        }
        catch (JsonRpcException e) {
            throw e;
        }
        catch (java.util.concurrent.TimeoutException e) {
            throw new JsonRpcException(JsonRpcException.REQUEST_TIMED_OUT, e.getMessage());
        }
        catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new JsonRpcException(JsonRpcException.INTERNAL_ERROR,
                "Game thread task failed: " + message);
        }
    }

    /** Schedules a side effect on the game thread without waiting for it. */
    public void onGameThreadAsync(Runnable task) {
        gameThread.runOnGameThread(task);
    }

    // ------------------------------------------------------------------
    // Arguments
    // ------------------------------------------------------------------

    public String requireString(String name) {
        String value = Json.getString(arguments, name);
        if (value == null || value.isEmpty()) {
            throw JsonRpcException.missingParam(name);
        }
        return value;
    }

    @Nullable
    public String getString(String name, @Nullable String fallback) {
        return Json.getString(arguments, name, fallback);
    }

    public int requireInt(String name) {
        if (!Json.has(arguments, name)) {
            throw JsonRpcException.missingParam(name);
        }
        return Json.getInt(arguments, name, 0);
    }

    public int getInt(String name, int fallback) {
        return Json.getInt(arguments, name, fallback);
    }

    /**
     * An integer argument clamped into {@code [min, max]}.
     *
     * <p>Clamps rather than rejects. These bounds guard things like scan radius and tick duration,
     * where the schema already advertises the range; a caller that exceeds it gets the nearest legal
     * value and a working tool, instead of an error round-trip that teaches the model nothing the
     * schema had not already said.
     */
    public int getBoundedInt(String name, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, Json.getInt(arguments, name, fallback)));
    }

    public double requireDouble(String name) {
        if (!Json.has(arguments, name)) {
            throw JsonRpcException.missingParam(name);
        }
        return Json.getDouble(arguments, name, 0.0D);
    }

    public double getDouble(String name, double fallback) {
        return Json.getDouble(arguments, name, fallback);
    }

    public boolean getBoolean(String name, boolean fallback) {
        return Json.getBoolean(arguments, name, fallback);
    }

    public boolean has(String name) {
        return Json.has(arguments, name);
    }

    // ------------------------------------------------------------------
    // Progress and logging
    // ------------------------------------------------------------------

    /**
     * Reports progress on a long-running call.
     *
     * <p>Silently does nothing when the client did not send a {@code progressToken} in
     * {@code _meta} — per spec, progress notifications are only legal for requests that asked for
     * them, and sending unsolicited ones makes strict clients drop the connection.
     *
     * @param progress how far along, in the same units as {@code total}
     * @param total    the expected end value, or a negative number if unknown
     */
    public void reportProgress(double progress, double total, @Nullable String message) {
        if (progressToken == null) {
            return;
        }
        JsonObject params = new JsonObject();
        params.add("progressToken", progressToken);
        params.addProperty("progress", progress);
        if (total >= 0) {
            params.addProperty("total", total);
        }
        if (message != null) {
            params.addProperty("message", message);
        }
        session.enqueue(com.micatechnologies.minecraft.mcmcp.protocol.JsonRpc
            .notification(McpProtocol.NOTIFICATION_PROGRESS, params));
    }

    /**
     * Sends a log record to the client, subject to the level it set via {@code logging/setLevel}.
     *
     * <p>Distinct from the game log on purpose: this is diagnostic output *for the model*, not for
     * the player watching the console. Use it for the reasoning a model needs to recover from a
     * partial failure ("moved 3 of 5 blocks, path blocked at x=12"), not for mod-internal noise.
     */
    public void log(McpLogLevel level, String message) {
        if (!level.isAtLeast(session.getLogLevel())) {
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("level", level.wireName());
        params.addProperty("logger", "mcmcp." + getSide().id());
        params.add("data", Json.obj("message", message));
        session.enqueue(com.micatechnologies.minecraft.mcmcp.protocol.JsonRpc
            .notification(McpProtocol.NOTIFICATION_MESSAGE, params));
    }
}
