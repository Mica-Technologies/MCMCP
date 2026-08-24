package com.micatechnologies.minecraft.mcmcp.transport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpc;
import com.micatechnologies.minecraft.mcmcp.protocol.JsonRpcException;
import com.micatechnologies.minecraft.mcmcp.protocol.McpDispatcher;
import com.micatechnologies.minecraft.mcmcp.protocol.McpProtocol;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSession;
import com.micatechnologies.minecraft.mcmcp.protocol.McpSessionManager;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * MCP's Streamable HTTP transport, implemented on the JDK's built-in {@link HttpServer}.
 *
 * <h2>The shape of the transport</h2>
 *
 * One URL path serves everything:
 *
 * <ul>
 *   <li><b>POST</b> — the client sends one JSON-RPC message (or, on protocol 2025-03-26, an array
 *       of them). Requests get a JSON response; notifications and responses get {@code 202 Accepted}
 *       with no body, because there is nothing to answer.</li>
 *   <li><b>GET</b> with {@code Accept: text/event-stream} — opens the server-to-client stream.
 *       Everything the server originates travels here: resource-change notifications, log records,
 *       progress, and the inverted-direction requests (sampling, elicitation, roots).</li>
 *   <li><b>DELETE</b> — ends the session and releases its state.</li>
 *   <li><b>OPTIONS</b> — CORS preflight, for browser-based clients.</li>
 * </ul>
 *
 * <h2>Why the JDK server and not Netty</h2>
 *
 * Minecraft already has Netty on the classpath, but it is 4.0.23 from 2014 and it is <em>the game's
 * networking stack</em>. Sharing those event loops with an externally reachable control socket means
 * a slow MCP client can add latency to player packet handling, and it welds MCMCP to whatever
 * Netty version a given modpack ends up with. {@code com.sun.net.httpserver} is in the JDK — in
 * Java 8's {@code rt.jar}, and in the {@code jdk.httpserver} module (exported by default) on 9+, so
 * it works identically under the vanilla Java 8 launch and the lwjgl3ify Java 17/21 launches — has
 * its own threads, and costs nothing to ship.
 *
 * <h2>Threading</h2>
 *
 * The pool is sized {@code workerThreads + maxSessions}. That is not arbitrary: {@link HttpServer}
 * dispatches each exchange to the executor and holds the thread for the exchange's lifetime, and an
 * SSE stream is an exchange that stays open for as long as the client is connected. Sizing the pool
 * for POSTs alone means the first few clients to open streams consume every thread and all
 * subsequent requests hang — with no error, which makes it a genuinely nasty thing to diagnose.
 * Reserving one thread per permitted session removes the interaction entirely.
 */
public class HttpMcpTransport implements McpTransport {

    /**
     * Largest POST body accepted, in bytes.
     *
     * <p>Generous for JSON-RPC — the biggest legitimate message is a sampling response with a long
     * completion — while still bounding what an unauthenticated request can make the game process
     * allocate before the auth check has even run.
     */
    private static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;

    /**
     * How long the SSE writer waits for a message before emitting a keep-alive comment.
     *
     * <p>Keep-alives are what let a dead client be noticed: an idle TCP connection to a process that
     * has gone away looks identical to a healthy one until something is written to it. 15 s is
     * comfortably under the 30–60 s idle timeouts that intermediaries commonly impose.
     */
    private static final long SSE_POLL_MILLIS = 15_000L;

    private static final String HEADER_SESSION_ID = "Mcp-Session-Id";
    private static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";

    private final McpEndpointSettings settings;
    private final McpDispatcher dispatcher;
    private final McpSessionManager sessions;
    private final AtomicLong sseEventId = new AtomicLong(1L);

    @Nullable
    private HttpServer server;

    @Nullable
    private ExecutorService executor;

    public HttpMcpTransport(McpEndpointSettings settings, McpDispatcher dispatcher, McpSessionManager sessions) {
        this.settings = settings;
        this.dispatcher = dispatcher;
        this.sessions = sessions;
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    @Override
    public String describeKind() {
        return "http";
    }

    @Override
    public String describeTarget() {
        return settings.describeUrl();
    }

    /**
     * Binds the socket and begins serving.
     *
     * @throws IOException if the port is already in use — the common cause being a second dev launch
     *                     or a previous game instance that has not fully exited
     */
    @Override
    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }

        final String sideId = dispatcher.getSide().id();
        final AtomicInteger threadCounter = new AtomicInteger(1);
        ExecutorService pool = Executors.newFixedThreadPool(
            settings.getWorkerThreads() + settings.getMaxSessions(),
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable,
                        "MCMCP-" + sideId + "-http-" + threadCounter.getAndIncrement());
                    // Daemon threads so a stuck SSE stream can never keep the JVM alive after the
                    // game has decided to exit. Game shutdown is not negotiable with a control port.
                    thread.setDaemon(true);
                    return thread;
                }
            });

        HttpServer created = HttpServer.create(
            new InetSocketAddress(settings.getBindAddress(), settings.getPort()),
            /* backlog */ 0);
        created.createContext(settings.getPath(), new McpHandler());
        created.createContext(settings.getPath() + "/health", new HealthHandler());
        created.setExecutor(pool);
        created.start();

        this.executor = pool;
        this.server = created;

        Mcmcp.LOGGER.info("MCMCP " + sideId + " endpoint listening on " + settings.describeUrl());
        if (settings.isExposedBeyondLoopback()) {
            Mcmcp.LOGGER.warn("MCMCP " + sideId + " endpoint is bound to " + settings.getBindAddress()
                + ", which is reachable from outside this machine. Anyone who can reach it and holds "
                + "the token can control this game instance. Bind 127.0.0.1 and use an SSH tunnel "
                + "unless you specifically intend otherwise.");
        }
        if (!settings.isRequireAuth()) {
            Mcmcp.LOGGER.warn("MCMCP " + sideId + " endpoint has authentication DISABLED. Any process "
                + "that can reach " + settings.describeUrl() + " can control this game instance.");
        }
    }

    /** Stops serving and releases the port. Safe to call when not running. */
    @Override
    public synchronized void stop() {
        HttpServer running = this.server;
        if (running == null) {
            return;
        }
        this.server = null;

        // Zero delay: in-flight exchanges are MCP calls whose clients are already gone or about to
        // be, and the alternative is delaying game shutdown behind a long-poll SSE stream.
        running.stop(0);
        sessions.closeAll();

        ExecutorService pool = this.executor;
        this.executor = null;
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Mcmcp.LOGGER.info("MCMCP " + dispatcher.getSide().id() + " endpoint stopped");
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private class McpHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();

                if ("OPTIONS".equalsIgnoreCase(method)) {
                    handlePreflight(exchange);
                    return;
                }
                if (!checkOrigin(exchange) || !checkAuth(exchange)) {
                    return;
                }

                if ("POST".equalsIgnoreCase(method)) {
                    handlePost(exchange);
                }
                else if ("GET".equalsIgnoreCase(method)) {
                    handleGet(exchange);
                }
                else if ("DELETE".equalsIgnoreCase(method)) {
                    handleDelete(exchange);
                }
                else {
                    exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE, OPTIONS");
                    sendText(exchange, 405, "text/plain; charset=utf-8",
                        "MCMCP accepts GET, POST, DELETE and OPTIONS on this path.");
                }
            }
            catch (IOException e) {
                // A broken pipe when the client vanishes mid-response is routine, not an error
                // worth a stack trace in the game log.
                Mcmcp.LOGGER.debug("MCMCP HTTP exchange ended early: " + e.getMessage());
            }
            catch (Exception e) {
                Mcmcp.LOGGER.error("MCMCP HTTP handler failed", e);
                trySendError(exchange, 500, "Internal MCMCP error");
            }
            finally {
                exchange.close();
            }
        }
    }

    /**
     * An unauthenticated liveness probe.
     *
     * <p>Deliberately says nothing beyond "MCMCP is here and which side it is". Session counts,
     * versions and tool names would help an operator, but this is the one endpoint reachable
     * without a token, so it gets the minimum needed to answer "is the port I am connecting to
     * actually MCMCP, or something else that happens to be on 25585".
     */
    private class HealthHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("service", McmcpConstants.MOD_NAMESPACE);
                body.addProperty("side", dispatcher.getSide().id());
                body.addProperty("status", "ok");
                sendJson(exchange, 200, body);
            }
            finally {
                exchange.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // Request handling
    // ------------------------------------------------------------------

    private void handlePost(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body == null) {
            sendJsonRpcError(exchange, 413, null, JsonRpcException.invalidRequest(
                "Request body exceeds " + MAX_REQUEST_BYTES + " bytes"));
            return;
        }

        JsonElement parsed = Json.parse(body);
        if (parsed == null) {
            sendJsonRpcError(exchange, 400, null, JsonRpcException.parseError("Request body is not valid JSON"));
            return;
        }

        warnOnProtocolVersionMismatch(exchange);

        if (parsed.isJsonArray()) {
            handleBatch(exchange, parsed.getAsJsonArray());
            return;
        }
        if (!parsed.isJsonObject()) {
            sendJsonRpcError(exchange, 400, null,
                JsonRpcException.invalidRequest("Request body must be a JSON object or array"));
            return;
        }

        JsonObject message = parsed.getAsJsonObject();
        boolean isInitialize = McpProtocol.METHOD_INITIALIZE.equals(JsonRpc.getMethod(message));

        McpSession session;
        if (isInitialize) {
            // A fresh session per initialize, even if the client sent an id. Re-initializing an
            // existing session would leave its negotiated version, subscriptions and log level from
            // the previous handshake in place, which is a subtle and confusing state to debug.
            try {
                session = sessions.create();
            }
            catch (JsonRpcException e) {
                sendJsonRpcError(exchange, 503, JsonRpc.getId(message), e);
                return;
            }
            exchange.getResponseHeaders().set(HEADER_SESSION_ID, session.getId());
        }
        else {
            session = resolveSession(exchange);
            if (session == null) {
                return;
            }
        }

        JsonObject response = dispatcher.dispatch(session, message);
        if (response == null) {
            // Notification or a response to one of our own requests: nothing to send back. 202 is
            // what the spec asks for and what tells the client its message was accepted.
            sendEmpty(exchange, 202);
            return;
        }
        sendJson(exchange, 200, response);
    }

    /**
     * Handles a JSON-RPC batch.
     *
     * <p>Accepted regardless of negotiated version: batching was legal in 2025-03-26 and removed in
     * 2025-06-18, and rejecting a batch a client has already sent helps nobody. MCMCP never
     * originates one.
     */
    private void handleBatch(HttpExchange exchange, JsonArray batch) throws IOException {
        if (batch.size() == 0) {
            sendJsonRpcError(exchange, 400, null, JsonRpcException.invalidRequest("Batch is empty"));
            return;
        }

        McpSession session = resolveSession(exchange);
        if (session == null) {
            return;
        }

        JsonArray responses = new JsonArray();
        for (JsonElement element : batch) {
            if (!element.isJsonObject()) {
                responses.add(JsonRpc.error(null,
                    JsonRpcException.invalidRequest("Batch entries must be JSON objects")));
                continue;
            }
            JsonObject response = dispatcher.dispatch(session, element.getAsJsonObject());
            if (response != null) {
                responses.add(response);
            }
        }

        if (responses.size() == 0) {
            sendEmpty(exchange, 202);
            return;
        }
        sendJson(exchange, 200, responses);
    }

    /**
     * Opens the server-to-client SSE stream.
     *
     * <p>Holds this thread for the life of the stream — see the class javadoc on pool sizing.
     */
    private void handleGet(HttpExchange exchange) throws IOException {
        String accept = firstHeader(exchange, "Accept");
        if (accept == null || !accept.contains("text/event-stream")) {
            exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE, OPTIONS");
            sendText(exchange, 405, "text/plain; charset=utf-8",
                "GET on the MCP endpoint opens the server-to-client event stream and requires "
                    + "'Accept: text/event-stream'. Send MCP requests with POST instead.");
            return;
        }

        McpSession session = resolveSession(exchange);
        if (session == null) {
            return;
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        // No buffering anywhere in the path: an SSE stream that is buffered is an SSE stream that
        // delivers nothing until it closes.
        headers.set("Cache-Control", "no-cache, no-store");
        headers.set("Connection", "keep-alive");
        headers.set("X-Accel-Buffering", "no");
        applyCorsHeaders(exchange);
        // Content-length 0 selects chunked encoding for a streaming response.
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream out = exchange.getResponseBody()) {
            while (isRunning()) {
                JsonObject message = session.pollOutbound(SSE_POLL_MILLIS);
                if (message == null) {
                    // Comment frame. SSE ignores it, but writing it is what surfaces a dead peer.
                    out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                StringBuilder frame = new StringBuilder();
                frame.append("id: ").append(sseEventId.getAndIncrement()).append('\n');
                frame.append("event: message\n");
                frame.append("data: ").append(Json.write(message)).append("\n\n");
                out.write(frame.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                session.touch(System.currentTimeMillis());
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        catch (IOException e) {
            Mcmcp.LOGGER.debug("MCMCP SSE stream for session " + session.getId() + " closed: " + e.getMessage());
        }
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        String sessionId = firstHeader(exchange, HEADER_SESSION_ID);
        sessions.remove(sessionId);
        sendEmpty(exchange, 204);
    }

    private void handlePreflight(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        headers.set("Access-Control-Allow-Headers",
            "Content-Type, Authorization, " + HEADER_SESSION_ID + ", " + HEADER_PROTOCOL_VERSION + ", Last-Event-ID");
        headers.set("Access-Control-Max-Age", "600");
        applyCorsHeaders(exchange);
        sendEmpty(exchange, 204);
    }

    // ------------------------------------------------------------------
    // Session, auth and origin
    // ------------------------------------------------------------------

    /**
     * Finds the session named by {@code Mcp-Session-Id}, writing the error response if it cannot.
     *
     * @return the session, or null if a response has already been sent
     */
    @Nullable
    private McpSession resolveSession(HttpExchange exchange) throws IOException {
        String sessionId = firstHeader(exchange, HEADER_SESSION_ID);
        if (sessionId == null || sessionId.isEmpty()) {
            sendJsonRpcError(exchange, 400, null, JsonRpcException.invalidRequest(
                "Missing " + HEADER_SESSION_ID + " header. Call 'initialize' first and send back the "
                    + HEADER_SESSION_ID + " returned with its response."));
            return null;
        }

        McpSession session = sessions.get(sessionId);
        if (session == null) {
            // 404 specifically: the spec makes this the signal for "your session is gone, start a
            // new one", and compliant clients re-initialize automatically instead of failing.
            sendJsonRpcError(exchange, 404, null, JsonRpcException.invalidRequest(
                "Unknown or expired session. Re-initialize to obtain a new " + HEADER_SESSION_ID + "."));
            return null;
        }
        return session;
    }

    /**
     * Enforces the bearer token.
     *
     * @return true if the request may proceed; false if a 401 has been sent
     */
    private boolean checkAuth(HttpExchange exchange) throws IOException {
        if (!settings.isRequireAuth()) {
            return true;
        }

        String header = firstHeader(exchange, "Authorization");
        String presented = null;
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            presented = header.substring(7).trim();
        }

        if (presented == null || !constantTimeEquals(presented, settings.getAuthToken())) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"MCMCP\"");
            applyCorsHeaders(exchange);
            sendJsonRpcError(exchange, 401, null, JsonRpcException.invalidRequest(
                "Missing or invalid bearer token. The token for this endpoint is in the MCMCP config "
                    + "file, and is printed to the game log at startup."));
            return false;
        }
        return true;
    }

    /**
     * Rejects cross-origin requests from origins that were not allow-listed.
     *
     * <p>This is the DNS-rebinding defence the MCP transport guidance calls for, and it is not
     * theoretical: without it, any web page the user visits can have their browser POST to
     * {@code http://127.0.0.1:25585} and drive the game, because the browser attaches no
     * credentials the server would miss. Requests with no {@code Origin} header — every non-browser
     * MCP client — are unaffected.
     */
    private boolean checkOrigin(HttpExchange exchange) throws IOException {
        String origin = firstHeader(exchange, "Origin");
        if (origin == null || origin.isEmpty()) {
            return true;
        }
        if (isOriginAllowed(origin)) {
            return true;
        }
        sendText(exchange, 403, "text/plain; charset=utf-8",
            "Origin '" + origin + "' is not allowed by this MCMCP endpoint. Add it to the "
                + "allowedOrigins list in the MCMCP config if this is intended.");
        return false;
    }

    private boolean isOriginAllowed(String origin) {
        for (String allowed : settings.getAllowedOrigins()) {
            if ("*".equals(allowed)) {
                return true;
            }
            // Port-insensitive prefix match: a browser client on http://localhost:5173 should be
            // covered by an allow-list entry of http://localhost without the operator having to
            // guess their dev server's port.
            if (origin.equals(allowed) || origin.startsWith(allowed + ":")) {
                return true;
            }
        }
        return false;
    }

    private void applyCorsHeaders(HttpExchange exchange) {
        String origin = firstHeader(exchange, "Origin");
        if (origin == null || !isOriginAllowed(origin)) {
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", origin);
        // Without this the browser can read the body but not the session id, so a browser-based
        // client can complete initialize and then never make a second call.
        headers.set("Access-Control-Expose-Headers", HEADER_SESSION_ID);
        headers.set("Vary", "Origin");
    }

    private void warnOnProtocolVersionMismatch(HttpExchange exchange) {
        String declared = firstHeader(exchange, HEADER_PROTOCOL_VERSION);
        if (declared != null && !McpProtocol.isSupported(declared)) {
            // A warning rather than a rejection. The negotiated version from `initialize` is
            // authoritative; this header is advisory, and failing requests over it breaks clients
            // that send a version string we simply have not heard of yet.
            Mcmcp.LOGGER.warn("MCMCP client declared unsupported " + HEADER_PROTOCOL_VERSION + ": " + declared);
        }
    }

    /**
     * Compares two tokens without leaking their contents through timing.
     *
     * <p>{@code String.equals} returns as soon as it finds a differing byte, which over enough
     * requests reveals the token one character at a time. The cost of not caring is a remotely
     * discoverable credential; the cost of caring is this method.
     */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        int difference = left.length ^ right.length;
        for (int i = 0; i < left.length && i < right.length; i++) {
            difference |= left[i] ^ right[i];
        }
        return difference == 0 && left.length > 0;
    }

    // ------------------------------------------------------------------
    // Low-level IO
    // ------------------------------------------------------------------

    @Nullable
    private static String firstHeader(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    /** Reads the request body, or returns null if it exceeds {@link #MAX_REQUEST_BYTES}. */
    @Nullable
    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (buffer.size() + read > MAX_REQUEST_BYTES) {
                    return null;
                }
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void sendJson(HttpExchange exchange, int status, JsonElement body) throws IOException {
        applyCorsHeaders(exchange);
        sendText(exchange, status, "application/json; charset=utf-8", Json.write(body));
    }

    private void sendJsonRpcError(HttpExchange exchange, int status, @Nullable JsonElement id,
                                  JsonRpcException error) throws IOException {
        applyCorsHeaders(exchange);
        sendText(exchange, status, "application/json; charset=utf-8", Json.write(JsonRpc.error(id, error)));
    }

    private static void sendText(HttpExchange exchange, int status, String contentType, String body)
        throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        // -1 rather than 0: com.sun's HttpServer reads 0 as "chunked, body follows" and the client
        // then waits for a body that never arrives.
        exchange.sendResponseHeaders(status, -1);
    }

    private static void trySendError(HttpExchange exchange, int status, String message) {
        try {
            sendText(exchange, status, "text/plain; charset=utf-8", message);
        }
        catch (IOException e) {
            Mcmcp.LOGGER.debug("MCMCP could not send an error response: " + e.getMessage());
        }
    }
}
