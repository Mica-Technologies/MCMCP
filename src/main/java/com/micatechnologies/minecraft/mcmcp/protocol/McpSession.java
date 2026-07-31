package com.micatechnologies.minecraft.mcmcp.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * One MCP client's connection state.
 *
 * <p>A session outlives any single HTTP exchange. The streamable-HTTP transport is a sequence of
 * short POSTs plus (optionally) one long-lived GET carrying an SSE stream, and the client is
 * identified across all of them by the {@code Mcp-Session-Id} header this object's {@link #getId}
 * supplies. Everything that must persist between those exchanges — the negotiated protocol version,
 * the client's capabilities, its resource subscriptions, its log verbosity, and any not-yet-
 * delivered server-to-client traffic — lives here.
 *
 * <p>Thread safety is not optional: HTTP handler threads, the game thread (via resource-change
 * notifications) and the SSE writer thread all touch a session concurrently. Every field is either
 * volatile, atomic, or a concurrent collection, and nothing here ever blocks on the game thread.
 */
public class McpSession {

    /**
     * How many server-to-client messages may pile up before the oldest are dropped.
     *
     * <p>Bounded on purpose. A client that opens a session, subscribes to a fast-changing resource
     * and then never opens the SSE stream would otherwise grow this queue without limit for as long
     * as the session lives — inside the game process, which is exactly where an unbounded queue
     * must not be. Dropping the oldest keeps the newest world state, which is what a late-attaching
     * stream actually wants.
     */
    private static final int OUTBOUND_QUEUE_CAPACITY = 512;

    private final String id;
    private final long createdAtMillis;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicLong lastActivityMillis = new AtomicLong();
    private final AtomicLong nextOutboundRequestId = new AtomicLong(1L);

    private volatile String protocolVersion = McpProtocol.LATEST_VERSION;
    private volatile McpLogLevel logLevel = McpLogLevel.INFO;
    private volatile JsonObject clientInfo = new JsonObject();
    private volatile JsonObject clientCapabilities = new JsonObject();

    /** Queued server-to-client messages awaiting an attached SSE stream. */
    private final LinkedBlockingQueue<JsonObject> outbound = new LinkedBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);

    /** Resource URIs this client asked to be notified about via {@code resources/subscribe}. */
    private final Set<String> subscriptions = Collections.synchronizedSet(new HashSet<>());

    /** Requests we sent to the client (sampling, elicitation, roots) that are still awaiting a reply. */
    private final Map<String, CompletableFuture<JsonElement>> pendingOutboundRequests = new ConcurrentHashMap<>();

    /** In-flight inbound requests, keyed by the client's id, so {@code notifications/cancelled} can find them. */
    private final Map<String, CancellationToken> inflightRequests = new ConcurrentHashMap<>();

    public McpSession(String id, long nowMillis) {
        this.id = id;
        this.createdAtMillis = nowMillis;
        this.lastActivityMillis.set(nowMillis);
    }

    public String getId() {
        return id;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public long getLastActivityMillis() {
        return lastActivityMillis.get();
    }

    public void touch(long nowMillis) {
        lastActivityMillis.set(nowMillis);
    }

    // ------------------------------------------------------------------
    // Handshake state
    // ------------------------------------------------------------------

    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * Records the negotiated result of {@code initialize}.
     *
     * <p>Called from the {@code initialize} handler, before the response goes out. The session is
     * not marked initialized here — that happens on {@code notifications/initialized}, per spec —
     * but the version has to be stored now because it decides the shape of the very response we are
     * about to send.
     */
    public void applyInitialize(String negotiatedVersion, JsonObject info, JsonObject capabilities) {
        this.protocolVersion = negotiatedVersion;
        this.clientInfo = info == null ? new JsonObject() : info;
        this.clientCapabilities = capabilities == null ? new JsonObject() : capabilities;
    }

    /** Marks the handshake complete; triggered by {@code notifications/initialized}. */
    public void markInitialized() {
        initialized.set(true);
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public JsonObject getClientInfo() {
        return clientInfo;
    }

    public JsonObject getClientCapabilities() {
        return clientCapabilities;
    }

    /** Human-readable client identity for logs and the {@code /mcmcp status} command. */
    public String describeClient() {
        String name = Json.getString(clientInfo, "name", "unknown");
        String version = Json.getString(clientInfo, "version", "");
        return version.isEmpty() ? name : name + " " + version;
    }

    /** Whether the client declared the {@code sampling} capability — i.e. it can run LLM completions for us. */
    public boolean supportsSampling() {
        return Json.has(clientCapabilities, "sampling");
    }

    /** Whether the client declared {@code elicitation} — i.e. it can prompt its human for input. */
    public boolean supportsElicitation() {
        return Json.has(clientCapabilities, "elicitation")
            && McpProtocol.supportsElicitation(protocolVersion);
    }

    /** Whether the client declared {@code roots} — i.e. it can tell us which directories it is scoped to. */
    public boolean supportsRoots() {
        return Json.has(clientCapabilities, "roots");
    }

    // ------------------------------------------------------------------
    // Logging verbosity
    // ------------------------------------------------------------------

    public McpLogLevel getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(McpLogLevel level) {
        this.logLevel = level;
    }

    // ------------------------------------------------------------------
    // Resource subscriptions
    // ------------------------------------------------------------------

    public void subscribe(String uri) {
        subscriptions.add(uri);
    }

    public void unsubscribe(String uri) {
        subscriptions.remove(uri);
    }

    public boolean isSubscribedTo(String uri) {
        return subscriptions.contains(uri);
    }

    public Set<String> getSubscriptions() {
        synchronized (subscriptions) {
            return new HashSet<>(subscriptions);
        }
    }

    // ------------------------------------------------------------------
    // Outbound queue (server -> client)
    // ------------------------------------------------------------------

    /**
     * Queues a message for delivery over this session's SSE stream.
     *
     * @return false if the queue was full and the oldest message had to be discarded to make room
     */
    public boolean enqueue(JsonObject message) {
        if (outbound.offer(message)) {
            return true;
        }
        // Full: drop the head and retry once. Losing the oldest notification is the least-bad
        // outcome — see OUTBOUND_QUEUE_CAPACITY. The single retry cannot loop because only this
        // method adds to the queue when it is at capacity.
        outbound.poll();
        outbound.offer(message);
        return false;
    }

    /** Blocks up to {@code timeoutMillis} for the next outbound message; null on timeout. */
    @Nullable
    public JsonObject pollOutbound(long timeoutMillis) throws InterruptedException {
        return outbound.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public int outboundBacklog() {
        return outbound.size();
    }

    // ------------------------------------------------------------------
    // Server -> client requests (sampling / elicitation / roots)
    // ------------------------------------------------------------------

    /**
     * Sends a request to the client and returns a future for its reply.
     *
     * <p>These are the inverted-direction MCP calls: the server asking the client to run an LLM
     * completion ({@code sampling/createMessage}), to ask its human a question
     * ({@code elicitation/create}), or to list its filesystem roots. They only work if an SSE stream
     * is attached or attaches before the queue drops the message, and only if the client declared
     * the matching capability — callers must check {@link #supportsSampling} and friends first.
     */
    public CompletableFuture<JsonElement> sendRequest(String method, @Nullable JsonObject params) {
        String requestId = "mcmcp-" + nextOutboundRequestId.getAndIncrement();
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        pendingOutboundRequests.put(requestId, future);
        enqueue(JsonRpc.request(new JsonPrimitive(requestId), method, params));
        return future;
    }

    /**
     * Completes a pending outbound request from a client reply.
     *
     * @return true if the id matched something we were waiting for
     */
    public boolean completeOutboundRequest(JsonObject response) {
        JsonElement id = JsonRpc.getId(response);
        if (id == null || !id.isJsonPrimitive()) {
            return false;
        }
        CompletableFuture<JsonElement> future = pendingOutboundRequests.remove(id.getAsString());
        if (future == null) {
            return false;
        }
        JsonObject error = Json.getObject(response, "error");
        if (error != null) {
            future.completeExceptionally(new JsonRpcException(
                Json.getInt(error, "code", JsonRpcException.INTERNAL_ERROR),
                Json.getString(error, "message", "Client returned an error")));
        }
        else {
            JsonElement result = response.get("result");
            future.complete(result == null ? new JsonObject() : result);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Inbound request cancellation
    // ------------------------------------------------------------------

    /**
     * Registers an in-flight inbound request so a later {@code notifications/cancelled} can reach it.
     *
     * <p>Returns a token the handler polls. MCMCP never interrupts a running handler thread —
     * a half-applied world mutation is worse than a slightly late cancellation — so cancellation is
     * cooperative: long-running tools check {@link CancellationToken#isCancelled} between steps.
     */
    public CancellationToken beginRequest(@Nullable JsonElement requestId) {
        CancellationToken token = new CancellationToken();
        if (requestId != null) {
            inflightRequests.put(requestId.toString(), token);
        }
        return token;
    }

    public void endRequest(@Nullable JsonElement requestId) {
        if (requestId != null) {
            inflightRequests.remove(requestId.toString());
        }
    }

    /** Marks an in-flight request cancelled; no-op if it already finished. */
    public void cancelRequest(@Nullable JsonElement requestId, @Nullable String reason) {
        if (requestId == null) {
            return;
        }
        CancellationToken token = inflightRequests.get(requestId.toString());
        if (token != null) {
            token.cancel(reason);
        }
    }

    /**
     * Releases everything the session holds. Pending outbound requests are failed rather than left
     * hanging, so a tool blocked on a sampling round-trip unblocks when the client disconnects
     * instead of sitting on a worker thread until its timeout.
     */
    public void close() {
        for (Map.Entry<String, CompletableFuture<JsonElement>> entry : pendingOutboundRequests.entrySet()) {
            entry.getValue().completeExceptionally(
                new JsonRpcException(JsonRpcException.REQUEST_CANCELLED, "Session closed"));
        }
        pendingOutboundRequests.clear();
        for (CancellationToken token : inflightRequests.values()) {
            token.cancel("Session closed");
        }
        inflightRequests.clear();
        outbound.clear();
        subscriptions.clear();
    }

    /**
     * Cooperative cancellation flag for one in-flight request.
     */
    public static class CancellationToken {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private volatile String reason = "";

        public boolean isCancelled() {
            return cancelled.get();
        }

        public String getReason() {
            return reason;
        }

        void cancel(@Nullable String why) {
            this.reason = why == null ? "" : why;
            cancelled.set(true);
        }

        /** Throws if cancellation has been requested; the usual way a tool aborts between steps. */
        public void throwIfCancelled() {
            if (cancelled.get()) {
                throw new JsonRpcException(JsonRpcException.REQUEST_CANCELLED,
                    reason.isEmpty() ? "Request cancelled by client" : "Request cancelled: " + reason);
            }
        }
    }
}
