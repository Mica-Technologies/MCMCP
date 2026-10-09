package com.micatechnologies.minecraft.mcmcp.client.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionHandshake;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionProtocol;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.game.ServerThreadBridge;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolActivity;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpc;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpcException;
import com.micatechnologies.minecraft.mcmcp.protocol.McpDispatcher;
import com.micatechnologies.minecraft.mcmcp.protocol.McpProtocol;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import com.micatechnologies.minecraft.mcmcp.transport.McpEndpoint;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * The virtual server endpoint's dispatcher: every message goes to the server the player is
 * connected to, over the companion channel, and the server's reply comes back as this dispatcher's.
 *
 * <p>It parses nothing past the envelope and the request id. The server runs the real dispatcher, so
 * {@code initialize}, the tool list, every tool's schema and every result are the server's own —
 * which is what lets a client and a server running different MCMCP builds work together.
 *
 * <p>Each local MCP session (an orchestrator link, an HTTP client) is a separate session on the
 * server, keyed by the local session's id, so their request ids never collide.
 */
final class CompanionForwarder extends McpDispatcher {

    /**
     * The longest a forwarded request waits. The server applies its own tool timeouts; this is the
     * backstop against a reply that will never come, and it is long because a server-side survey
     * can legitimately run for minutes.
     */
    private static final long MAX_WAIT_MINUTES = 30L;

    private final Consumer<byte[]> sender;
    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * @param sender sends one TYPE_MCP message's bytes to the server (framing is the sender's job)
     */
    CompanionForwarder(Consumer<byte[]> sender) {
        super(McmcpSide.SERVER, new ServerThreadBridge(), 0L, McpEndpoint.instructionsFor(McmcpSide.SERVER));
        this.sender = sender;
    }

    @Override
    @Nullable
    public JsonObject dispatch(McpSession session, JsonObject message) {
        if (closed) {
            return unavailable(message);
        }
        sessions.put(session.getId(), session);
        stripOwnInstance(message);

        JsonElement id = JsonRpc.getId(message);
        boolean request = JsonRpc.isRequest(message);
        CompletableFuture<JsonObject> reply = null;
        String key = null;
        if (request && id != null) {
            key = pendingKey(session.getId(), id);
            reply = new CompletableFuture<>();
            pending.put(key, reply);
        }
        String method = JsonRpc.getMethod(message);
        if (McpProtocol.NOTIFICATION_INITIALIZED.equals(method)) {
            // The local session tracks the handshake too: the transports read its state.
            session.markInitialized();
        }
        // A companion call is an agent driving this game too: keep it unthrottled, and show the call on
        // the in-game activity line, as the client's own dispatcher does for its calls.
        long activity = 0L;
        if (McpProtocol.METHOD_TOOLS_CALL.equals(method)) {
            ToolActivity.noteCall(McmcpSide.CLIENT);
            activity = ToolActivity.callStarted(McmcpSide.SERVER,
                Json.getString(Json.getObjectOrEmpty(message, "params"), "name", "server tool"));
        }
        sender.accept(CompanionHandshake.bytes(envelope(session.getId(), message)));
        if (reply == null) {
            ToolActivity.callEnded(activity);
            return null;
        }
        try {
            JsonObject response = reply.get(MAX_WAIT_MINUTES, TimeUnit.MINUTES);
            if (McpProtocol.METHOD_INITIALIZE.equals(method)) {
                JsonObject params = Json.getObjectOrEmpty(message, "params");
                String negotiated = Json.getString(Json.getObject(response, "result"), "protocolVersion");
                if (negotiated != null) {
                    session.applyInitialize(negotiated, Json.getObjectOrEmpty(params, "clientInfo"),
                        Json.getObjectOrEmpty(params, "capabilities"));
                }
            }
            return response;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable(message);
        }
        catch (ExecutionException | TimeoutException e) {
            return unavailable(message);
        }
        finally {
            pending.remove(key);
            ToolActivity.callEnded(activity);
        }
    }

    /**
     * Takes one message from the server: a reply completes the call waiting for it, anything else
     * (a progress notification, a log line, a request to the client) goes to its local session.
     */
    void deliver(JsonObject envelope) {
        String sessionId = Json.getString(envelope, CompanionProtocol.FIELD_SESSION);
        JsonObject message = Json.getObject(envelope, CompanionProtocol.FIELD_MESSAGE);
        if (sessionId == null || message == null) {
            return;
        }
        JsonElement id = JsonRpc.getId(message);
        if (JsonRpc.isResponse(message) && id != null) {
            CompletableFuture<JsonObject> reply = pending.get(pendingKey(sessionId, id));
            if (reply != null) {
                reply.complete(message);
                return;
            }
        }
        McpSession session = sessions.get(sessionId);
        if (session != null) {
            session.enqueue(message);
        }
    }

    /** Fails every waiting call: the connection to the server is gone. */
    void close() {
        closed = true;
        for (CompletableFuture<JsonObject> reply : pending.values()) {
            reply.completeExceptionally(new IllegalStateException("disconnected"));
        }
        pending.clear();
        sessions.clear();
    }

    /**
     * Removes an {@code instance} argument that names this virtual endpoint. The server cannot do
     * it: it does not know which client instance it is being reached through.
     */
    private void stripOwnInstance(JsonObject message) {
        if (!McpProtocol.METHOD_TOOLS_CALL.equals(JsonRpc.getMethod(message))) {
            return;
        }
        JsonObject arguments = Json.getObject(Json.getObject(message, "params"), "arguments");
        if (arguments == null || !arguments.has("instance")) {
            return;
        }
        JsonElement named = arguments.get("instance");
        if (named.isJsonPrimitive() && getAddress().answersTo(named.getAsString())) {
            arguments.remove("instance");
        }
    }

    /**
     * The reply to a request that cannot reach the server. A tool call gets a tool error the model can
     * read; anything else a JSON-RPC error, which is what the client's plumbing expects for those.
     */
    @Nullable
    private static JsonObject unavailable(JsonObject message) {
        JsonElement id = JsonRpc.getId(message);
        if (!JsonRpc.isRequest(message) || id == null) {
            return null;
        }
        String text = "The connection to the server's MCMCP companion is gone: the player left the "
            + "server or was disconnected. The server's tools come back when the player rejoins.";
        if (McpProtocol.METHOD_TOOLS_CALL.equals(JsonRpc.getMethod(message))) {
            return JsonRpc.result(id, ToolResult.error(text).toJson(McpProtocol.LATEST_VERSION));
        }
        return JsonRpc.error(id, JsonRpcException.INTERNAL_ERROR, text);
    }

    private static JsonObject envelope(String sessionId, JsonObject message) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty(CompanionProtocol.FIELD_SESSION, sessionId);
        envelope.add(CompanionProtocol.FIELD_MESSAGE, message);
        return envelope;
    }

    private static String pendingKey(String sessionId, JsonElement id) {
        return sessionId + "|" + Json.write(id);
    }
}
