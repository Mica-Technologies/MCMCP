package com.micatechnologies.minecraft.mcmcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.game.GameThreadBridge;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.game.ServerLoad;
import com.micatechnologies.minecraft.mcmcp.json.ArgumentNames;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.mcp.CallFilter;
import com.micatechnologies.minecraft.mcmcp.mcp.McpPrompt;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpResource;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.RequestJournal;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolActivity;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolBusyException;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Routes an inbound MCP message to the handler for its method and produces the reply.
 *
 * <p>The dispatcher is transport-agnostic on purpose. It takes a {@link McpSession} and a parsed
 * {@link JsonObject} and returns a {@link JsonObject} response or null; it knows nothing about
 * HTTP, SSE, headers or sockets. That boundary is what makes the protocol layer testable without a
 * socket and what would let a second transport — a Unix socket, a stdio bridge, an in-process pipe
 * for another mod — be added without touching any of this.
 *
 * <p>Threading: {@code dispatch} runs on a transport worker thread, never the game thread. Handlers
 * that need world state hop across via {@link ToolContext#onGameThread}. Nothing here may block on
 * the game thread outside of that call, because the game thread routinely takes a full tick to get
 * around to a scheduled task and holding a worker for that long starves the request pool.
 */
public class McpDispatcher {

    /** The orchestrator's routing argument; see {@link #stripOwnInstance}. */
    private static final String INSTANCE_ARGUMENT = "instance";

    private final McmcpSide side;
    private final GameThreadBridge gameThread;
    private final long gameThreadTimeoutMillis;
    private final String instructions;
    private volatile EndpointAddress address = EndpointAddress.NONE;

    /**
     * Tools that are not read-only but still run during an emergency stop, because each only makes
     * things stop or let go (stopping a job, setting the stop again, releasing chunk holds, leaving),
     * or only looks and notes (a screenshot, a mark, a save, a logger's level) — what someone needs to see
     * what happened.
     */
    static final java.util.Set<String> ESTOP_EXEMPT = new java.util.HashSet<>(java.util.Arrays.asList(
        "server_job_cancel", "server_job_pause", "server_estop", "server_release_loaded", "server_stop",
        "server_save_world", "client_screenshot", "client_marks", "client_world_leave", "client_quit",
        "game_set_log_level"));

    /**
     * Tools that are not read-only but still run while the server is paused or stalled, beside
     * {@link #ESTOP_EXEMPT} and every {@code server_job_*}: what someone needs to see what the server
     * is doing and to make MCMCP stop or let go.
     */
    static final java.util.Set<String> ADMISSION_EXEMPT = new java.util.HashSet<>(java.util.Arrays.asList(
        "game_health", "server_tick_stats", "server_release_loaded", "server_estop", "mcmcp_endpoint_info",
        "game_set_log_level"));

    /** Which tools each session may list and call; null lists and runs everything for the side. */
    @Nullable
    private volatile CallFilter filter;

    /** Where each tool call is recorded as it starts and ends; null when nothing records. */
    @Nullable
    private volatile RequestJournal journal;

    public McpDispatcher(McmcpSide side, GameThreadBridge gameThread, long gameThreadTimeoutMillis,
                         String instructions) {
        this.side = side;
        this.gameThread = gameThread;
        this.gameThreadTimeoutMillis = gameThreadTimeoutMillis;
        this.instructions = instructions;
    }

    public McmcpSide getSide() {
        return side;
    }

    /**
     * Gives this dispatcher its orchestrator-facing identity. Set once during wiring, before any
     * transport starts; until then {@link EndpointAddress#NONE} accepts only {@code instance: "*"}.
     */
    public void setAddress(EndpointAddress address) {
        this.address = address;
    }

    /** This dispatcher's orchestrator-facing identity; see {@link #setAddress}. */
    public EndpointAddress getAddress() {
        return address;
    }

    /** Gives this dispatcher a per-session tool filter. Set once during wiring. */
    public void setFilter(@Nullable CallFilter filter) {
        this.filter = filter;
    }

    /** Gives this dispatcher its request journal. Set once during wiring, like {@link #setAddress}. */
    public void setJournal(@Nullable RequestJournal journal) {
        this.journal = journal;
    }

    @Nullable
    public RequestJournal getJournal() {
        return journal;
    }

    /**
     * Handles one message.
     *
     * @return the response to send back, or null for messages that must not be answered
     *         (notifications, and replies to requests we ourselves sent)
     */
    @Nullable
    public JsonObject dispatch(McpSession session, JsonObject message) {
        JsonElement id = null;
        try {
            JsonRpc.validateEnvelope(message);
            id = JsonRpc.getId(message);

            // A reply to something we sent — sampling, elicitation, roots, or our own ping.
            // Consumed by the session's pending-request table; there is nothing to respond to.
            if (JsonRpc.isResponse(message)) {
                if (!session.completeOutboundRequest(message)) {
                    Mcmcp.LOGGER.debug("MCMCP received a response with an unknown id: " + Json.write(message));
                }
                return null;
            }

            String method = JsonRpc.getMethod(message);
            if (method == null) {
                throw JsonRpcException.invalidRequest("Message has no method");
            }

            if (JsonRpc.isNotification(message)) {
                handleNotification(session, method, JsonRpc.getParams(message));
                return null;
            }

            if (!session.isInitialized() && !McpProtocol.isAllowedBeforeInitialize(method)) {
                throw JsonRpcException.invalidRequest(
                    "Session is not initialized; call 'initialize' before '" + method + "'");
            }

            McpSession.CancellationToken cancellation = session.beginRequest(id);
            try {
                JsonElement result = handleRequest(session, method, JsonRpc.getParams(message), id, cancellation);
                return JsonRpc.result(id, result);
            }
            finally {
                session.endRequest(id);
            }
        }
        catch (JsonRpcException e) {
            return JsonRpc.error(id, e);
        }
        catch (Exception e) {
            // Any handler exception that is not already a JsonRpcException is a bug in MCMCP, not a
            // client fault. Log it with the stack trace — the client only gets the message, and a
            // report of "internal error" with nothing in the game log is unfixable.
            Mcmcp.LOGGER.error("MCMCP dispatcher failed handling " + JsonRpc.getMethod(message), e);
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return JsonRpc.error(id, JsonRpcException.internalError(detail));
        }
    }

    // ------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------

    private JsonElement handleRequest(McpSession session, String method, JsonObject params,
                                      @Nullable JsonElement requestId,
                                      McpSession.CancellationToken cancellation) throws Exception {
        switch (method) {
            case McpProtocol.METHOD_INITIALIZE:
                return handleInitialize(session, params);

            case McpProtocol.METHOD_PING:
                // Deliberately an empty object, not null: the spec's ping result is `{}`, and some
                // clients treat a null result as a malformed response.
                return Json.obj();

            case McpProtocol.METHOD_TOOLS_LIST:
                return handleToolsList(session);

            case McpProtocol.METHOD_TOOLS_CALL:
                return handleToolsCall(session, params, requestId, cancellation);

            case McpProtocol.METHOD_RESOURCES_LIST:
                return withholds(session) ? Json.obj("resources", new JsonArray()) : handleResourcesList();

            case McpProtocol.METHOD_RESOURCES_TEMPLATES_LIST:
                return withholds(session) ? Json.obj("resourceTemplates", new JsonArray())
                    : handleResourceTemplatesList();

            case McpProtocol.METHOD_RESOURCES_READ:
                if (withholds(session)) {
                    throw new JsonRpcException(JsonRpcException.RESOURCE_NOT_FOUND,
                        "Resources are not served to this session");
                }
                return handleResourcesRead(session, params, cancellation);

            case McpProtocol.METHOD_RESOURCES_SUBSCRIBE:
                return handleSubscribe(session, params, true);

            case McpProtocol.METHOD_RESOURCES_UNSUBSCRIBE:
                return handleSubscribe(session, params, false);

            case McpProtocol.METHOD_PROMPTS_LIST:
                return withholds(session) ? Json.obj("prompts", new JsonArray()) : handlePromptsList();

            case McpProtocol.METHOD_PROMPTS_GET:
                if (withholds(session)) {
                    throw new JsonRpcException(JsonRpcException.PROMPT_NOT_FOUND,
                        "Prompts are not served to this session");
                }
                return handlePromptsGet(session, params, cancellation);

            case McpProtocol.METHOD_LOGGING_SET_LEVEL:
                return handleSetLevel(session, params);

            case McpProtocol.METHOD_COMPLETION_COMPLETE:
                return handleComplete(params);

            default:
                throw JsonRpcException.methodNotFound(method);
        }
    }

    /** Whether resources and prompts are withheld from this session by the filter. */
    private boolean withholds(McpSession session) {
        CallFilter current = filter;
        return current != null && !current.servesResourcesAndPrompts(session);
    }

    private JsonElement handleInitialize(McpSession session, JsonObject params) {
        String requested = Json.getString(params, "protocolVersion");
        String negotiated = McpProtocol.negotiate(requested);
        session.applyInitialize(negotiated,
            Json.getObjectOrEmpty(params, "clientInfo"),
            Json.getObjectOrEmpty(params, "capabilities"));

        Mcmcp.LOGGER.info("MCMCP " + side.id() + " endpoint: client '" + session.describeClient()
            + "' initialized on protocol " + negotiated
            + (negotiated.equals(requested) ? "" : " (requested " + requested + ")"));

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", negotiated);
        result.add("capabilities", buildServerCapabilities());
        result.add("serverInfo", buildServerInfo());
        // `instructions` is the server's chance to tell the model how to use it before it has read
        // a single tool description. Worth its context cost here: the client/server split and the
        // fact that block coordinates are integers while entity positions are not are both things
        // models get wrong on the first try otherwise.
        String steering = session.isOrchestratorLink() ? null : address.steeringNote();
        result.addProperty("instructions", steering == null ? instructions : instructions + "\n" + steering + "\n");
        return result;
    }

    private JsonObject buildServerCapabilities() {
        JsonObject capabilities = new JsonObject();

        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", true);
        capabilities.add("tools", tools);

        JsonObject resources = new JsonObject();
        resources.addProperty("subscribe", true);
        resources.addProperty("listChanged", true);
        capabilities.add("resources", resources);

        JsonObject prompts = new JsonObject();
        prompts.addProperty("listChanged", true);
        capabilities.add("prompts", prompts);

        // Empty objects are the correct declaration for capabilities with no sub-options; omitting
        // them means "not supported", which would stop clients ever calling logging/setLevel or
        // completion/complete.
        capabilities.add("logging", new JsonObject());
        capabilities.add("completions", new JsonObject());
        return capabilities;
    }

    private JsonObject buildServerInfo() {
        JsonObject info = new JsonObject();
        info.addProperty("name", McmcpConstants.MOD_NAMESPACE + "-" + side.id());
        info.addProperty("title", McmcpConstants.MOD_NAME + " (" + side.id() + ")");
        info.addProperty("version", McmcpConstants.MOD_VERSION);
        String instance = address.addressableId();
        if (instance != null) {
            // The same as the Mcmcp-Instance header, for a client that sees only the body.
            info.addProperty("instance", instance);
        }
        return info;
    }

    private JsonElement handleToolsList(McpSession session) {
        JsonArray tools = new JsonArray();
        CallFilter current = filter;
        for (McpTool tool : McpRegistry.tools(side)) {
            if (current != null && !current.lists(session, tool)) {
                continue;
            }
            tools.add(tool.toListEntry(session.getProtocolVersion()));
        }
        // No nextCursor: the catalogue is tens of entries, not thousands, and pagination that never
        // triggers is pagination that never gets tested. Add it when a registry grows past the
        // point where one response is reasonable.
        return Json.obj("tools", tools);
    }

    private JsonElement handleToolsCall(McpSession session, JsonObject params,
                                        @Nullable JsonElement requestId,
                                        McpSession.CancellationToken cancellation) {
        String name = Json.getString(params, "name");
        if (name == null || name.isEmpty()) {
            throw JsonRpcException.missingParam("name");
        }

        McpTool tool = McpRegistry.tool(name, side);
        if (tool == null) {
            throw new JsonRpcException(JsonRpcException.TOOL_NOT_FOUND,
                "No tool named '" + name + "' is available on the " + side.id() + " endpoint");
        }

        // Refused before the handler runs, as a tool error the model reads. An argument the schema
        // does not name is almost always a misspelling of one it does, and ignoring it runs the tool
        // on a default the caller never chose — see ArgumentNames.
        JsonObject arguments = Json.getObjectOrEmpty(params, "arguments");
        String instanceRefusal = stripOwnInstance(tool, arguments);
        if (instanceRefusal != null) {
            return ToolResult.error(instanceRefusal).toJson(session.getProtocolVersion());
        }
        String unknownArguments = ArgumentNames.describeUnknown(name, tool.getInputSchema(), arguments);
        if (unknownArguments != null) {
            return ToolResult.error(unknownArguments).toJson(session.getProtocolVersion());
        }
        // After the argument-name check, so a pinned argument the filter fills in is never mistaken
        // for one the caller misspelled.
        CallFilter current = filter;
        if (current != null) {
            String refusal = current.admit(session, tool, arguments);
            if (refusal != null) {
                return ToolResult.error(refusal).toJson(session.getProtocolVersion());
            }
        }

        if (!tool.isReadOnly() && com.micatechnologies.minecraft.mcmcp.game.Estop.active()
            && !ESTOP_EXEMPT.contains(name)) {
            return ToolResult.error(com.micatechnologies.minecraft.mcmcp.game.Estop.refusal())
                .toJson(session.getProtocolVersion());
        }

        // A server paused below 20 TPS, or with its thread stalled, gets no new work that changes the
        // world (#133). Read-only calls still run, and so does what someone needs to see and stop it.
        String overloaded = admissionRefusal(name, tool.isReadOnly());
        if (overloaded != null) {
            return ToolResult.error(overloaded).withStructured(
                Json.obj("busy", ServerLoad.busy("server_overloaded"))).toJson(session.getProtocolVersion());
        }

        // Another agent holds the client's input exclusively (#94). Read-only tools still answer, and
        // client_input_lock itself decides for its own ops, so an agent can ask or wait.
        com.micatechnologies.minecraft.mcmcp.mcp.CallerId caller =
            com.micatechnologies.minecraft.mcmcp.mcp.CallerId.of(session, params);
        if (side.isClient() && !tool.isReadOnly() && !"client_input_lock".equals(name)) {
            long now = System.currentTimeMillis();
            com.micatechnologies.minecraft.mcmcp.mcp.InputClaim claim =
                com.micatechnologies.minecraft.mcmcp.mcp.InputClaim.get();
            String busy = claim.refusalFor(caller, now);
            if (busy != null) {
                JsonObject structured = new JsonObject();
                structured.add("busy", claim.busy(now));
                return ToolResult.error(busy).withStructured(structured).toJson(session.getProtocolVersion());
            }
        }

        ToolActivity.noteCall(side);

        ToolContext context = new ToolContext(
            session,
            arguments,
            gameThread,
            cancellation,
            gameThreadTimeoutMillis,
            extractProgressToken(params));

        context.setCaller(caller);
        long activity = ToolActivity.callStarted(side, name);
        context.trackActivity(activity);
        context.setMutating(!tool.isReadOnly() && !ESTOP_EXEMPT.contains(name));
        ToolActivity.describe(activity, session.getPrincipal().isCompanion()
            ? session.getPrincipal().getPlayerName() + " (companion)"
            : session.isOrchestratorLink() ? "orchestrator" : session.describeClient(), session.getId());
        RequestJournal recorder = journal;
        long started = System.currentTimeMillis();
        if (recorder != null) {
            recorder.start(activity, name, arguments,
                session.isOrchestratorLink() ? "orchestrator" : session.describeClient(),
                session.getPrincipal().toJournal(), started);
        }
        String status = "exception";
        JsonObject reply = null;
        ToolResult result = null;
        try {
            result = tool.call(context);
            status = result.isError() ? "error" : "ok";
            reply = withSteeringNote(session, result.toJson(session.getProtocolVersion()));
            return reply;
        }
        catch (JsonRpcException e) {
            // Too busy to run it, or a step that started and did not finish in time: a tool error with its
            // structured busy block, so the agent waits retryAfterMs, or looks at the world first, instead
            // of resending at once (#132).
            if (e instanceof ToolBusyException) {
                status = ((ToolBusyException) e).nothingRan() ? "busy" : "timeout";
                result = ((ToolBusyException) e).toResult();
                reply = result.toJson(session.getProtocolVersion());
                return reply;
            }
            // Cancellation stays a protocol error, and so does a client-thread timeout, where the bridge
            // cannot say whether the task started. Everything else that a handler threw describes a
            // failed *action*, which per spec belongs in the result so the model can read it and adapt.
            if (e.getCode() == JsonRpcException.REQUEST_CANCELLED) {
                status = "cancelled";
                throw e;
            }
            if (e.getCode() == JsonRpcException.REQUEST_TIMED_OUT) {
                status = "timeout";
                throw e;
            }
            status = "error";
            reply = ToolResult.error(e.getMessage()).toJson(session.getProtocolVersion());
            return reply;
        }
        catch (Exception e) {
            Mcmcp.LOGGER.error("MCMCP tool '" + name + "' threw", e);
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            reply = ToolResult.error("Tool '" + name + "' failed: " + detail)
                .toJson(session.getProtocolVersion());
            return reply;
        }
        finally {
            ToolActivity.callEnded(activity);
            if (recorder != null && recorder.isEnabled()) {
                long now = System.currentTimeMillis();
                // A second serialisation, paid only while the journal is on: the transports write
                // the reply themselves and threading a byte count back from each is not worth it.
                long bytes = reply == null ? -1L : Json.write(reply).length();
                recorder.end(activity, status, now - started, bytes,
                    result == null ? null : result.getStructuredContent(), context.timingNotes(), now);
            }
        }
    }

    /**
     * Why a call is refused for the server's load, or null when it may run: on the server endpoint,
     * while {@link ServerLoad} is paused or stalled, a tool that is not read-only and not exempt.
     */
    @Nullable
    String admissionRefusal(String name, boolean readOnly) {
        if (side.isClient() || readOnly || !ServerLoad.refusesWrites() || ESTOP_EXEMPT.contains(name)
            || ADMISSION_EXEMPT.contains(name) || name.startsWith("server_job_")) {
            return null;
        }
        return "Refused before it ran: the server is " + ServerLoad.describe() + ", so MCMCP is not starting work "
            + "that changes the world. Nothing changed. Read-only calls still run. Retry after busy.retryAfterMs; "
            + "game_health shows the server's state.";
    }

    /**
     * Removes an {@code instance} argument that names this endpoint, so a call written for the
     * orchestrator runs unchanged against the game's own endpoint.
     *
     * <p>Left alone when the tool declares an {@code instance} of its own. The orchestrator strips
     * the argument before forwarding, so on a link session it is never present.
     *
     * @return the refusal when {@code instance} names some other game, else null
     */
    @Nullable
    private String stripOwnInstance(McpTool tool, JsonObject arguments) {
        if (!arguments.has(INSTANCE_ARGUMENT)
            || Json.getObjectOrEmpty(tool.getInputSchema(), "properties").has(INSTANCE_ARGUMENT)) {
            return null;
        }
        JsonElement requested = arguments.get(INSTANCE_ARGUMENT);
        String named = requested.isJsonPrimitive() ? requested.getAsString() : String.valueOf(requested);
        if (!address.answersTo(named)) {
            return address.describeMismatch(named);
        }
        arguments.remove(INSTANCE_ARGUMENT);
        return null;
    }

    /**
     * Appends the orchestrator reminder to the first tool result of a direct session while a link
     * is up. Appended rather than prepended: scripts read {@code content[0]} or
     * {@code structuredContent}, and neither moves.
     */
    private JsonObject withSteeringNote(McpSession session, JsonObject result) {
        String note = address.steeringNote();
        if (note == null || !session.claimSteeringNote()) {
            return result;
        }
        JsonArray content = result.getAsJsonArray("content");
        if (content == null) {
            content = new JsonArray();
            result.add("content", content);
        }
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", note);
        content.add(block);
        return result;
    }

    /**
     * Pulls the caller's progress token out of {@code params._meta.progressToken}.
     *
     * <p>Absent means the client does not want progress notifications, and sending them anyway is a
     * protocol violation — hence null rather than a generated token.
     */
    @Nullable
    private JsonElement extractProgressToken(JsonObject params) {
        JsonObject meta = Json.getObject(params, "_meta");
        if (meta == null) {
            return null;
        }
        JsonElement token = meta.get("progressToken");
        return token == null || token.isJsonNull() ? null : token;
    }

    private JsonElement handleResourcesList() {
        JsonArray resources = new JsonArray();
        for (McpResource resource : McpRegistry.resources(side)) {
            resources.add(resource.toListEntry());
        }
        return Json.obj("resources", resources);
    }

    private JsonElement handleResourceTemplatesList() {
        JsonArray templates = new JsonArray();
        for (McpResource resource : McpRegistry.resourceTemplates(side)) {
            templates.add(resource.toListEntry());
        }
        return Json.obj("resourceTemplates", templates);
    }

    private JsonElement handleResourcesRead(McpSession session, JsonObject params,
                                            McpSession.CancellationToken cancellation) throws Exception {
        String uri = Json.getString(params, "uri");
        if (uri == null || uri.isEmpty()) {
            throw JsonRpcException.missingParam("uri");
        }

        McpResource resource = McpRegistry.resolveResource(uri, side);
        if (resource == null) {
            throw new JsonRpcException(JsonRpcException.RESOURCE_NOT_FOUND,
                "No resource is registered at '" + uri + "'");
        }

        ToolContext context = new ToolContext(session, new JsonObject(), gameThread, cancellation,
            gameThreadTimeoutMillis, null);
        JsonArray contents = new JsonArray();
        for (JsonObject entry : resource.read(context, uri)) {
            contents.add(entry);
        }
        return Json.obj("contents", contents);
    }

    private JsonElement handleSubscribe(McpSession session, JsonObject params, boolean subscribing) {
        String uri = Json.getString(params, "uri");
        if (uri == null || uri.isEmpty()) {
            throw JsonRpcException.missingParam("uri");
        }
        if (subscribing) {
            McpResource resource = McpRegistry.resolveResource(uri, side);
            if (resource == null) {
                throw new JsonRpcException(JsonRpcException.RESOURCE_NOT_FOUND,
                    "No resource is registered at '" + uri + "'");
            }
            if (!resource.isSubscribable()) {
                throw JsonRpcException.invalidParams(
                    "Resource '" + uri + "' does not emit change notifications");
            }
            session.subscribe(uri);
        }
        else {
            session.unsubscribe(uri);
        }
        return Json.obj();
    }

    private JsonElement handlePromptsList() {
        JsonArray prompts = new JsonArray();
        for (McpPrompt prompt : McpRegistry.prompts(side)) {
            prompts.add(prompt.toListEntry());
        }
        return Json.obj("prompts", prompts);
    }

    private JsonElement handlePromptsGet(McpSession session, JsonObject params,
                                         McpSession.CancellationToken cancellation) throws Exception {
        String name = Json.getString(params, "name");
        if (name == null || name.isEmpty()) {
            throw JsonRpcException.missingParam("name");
        }

        McpPrompt prompt = McpRegistry.prompt(name, side);
        if (prompt == null) {
            throw new JsonRpcException(JsonRpcException.PROMPT_NOT_FOUND,
                "No prompt named '" + name + "' is available on the " + side.id() + " endpoint");
        }

        JsonObject arguments = Json.getObjectOrEmpty(params, "arguments");
        for (McpPrompt.Argument argument : prompt.getArguments()) {
            if (argument.isRequired() && !Json.has(arguments, argument.getName())) {
                throw JsonRpcException.missingParam(argument.getName());
            }
        }

        ToolContext context = new ToolContext(session, arguments, gameThread, cancellation,
            gameThreadTimeoutMillis, null);
        JsonArray messages = new JsonArray();
        for (JsonObject message : prompt.generate(context)) {
            messages.add(message);
        }

        JsonObject result = new JsonObject();
        result.addProperty("description", prompt.getDescription());
        result.add("messages", messages);
        return result;
    }

    private JsonElement handleSetLevel(McpSession session, JsonObject params) {
        McpLogLevel level = McpLogLevel.fromWireName(Json.getString(params, "level"), session.getLogLevel());
        session.setLogLevel(level);
        return Json.obj();
    }

    /**
     * Argument autocompletion for prompts and resource templates.
     *
     * <p>Currently completes resource URIs by prefix and returns nothing for prompt arguments, whose
     * legal values are world state that would have to be read on the game thread — a cost this
     * method, which fires on every keystroke in the client's UI, must not pay. Prompt-argument
     * completion belongs behind a cached snapshot, not a synchronous world read.
     */
    private JsonElement handleComplete(JsonObject params) {
        JsonObject ref = Json.getObjectOrEmpty(params, "ref");
        JsonObject argument = Json.getObjectOrEmpty(params, "argument");
        String refType = Json.getString(ref, "type", "");
        String typed = Json.getString(argument, "value", "");

        JsonArray values = new JsonArray();
        if ("ref/resource".equals(refType)) {
            for (McpResource resource : McpRegistry.resources(side)) {
                if (resource.getUri().startsWith(typed)) {
                    values.add(resource.getUri());
                }
            }
        }

        JsonObject completion = new JsonObject();
        completion.add("values", values);
        completion.addProperty("total", values.size());
        completion.addProperty("hasMore", false);
        return Json.obj("completion", completion);
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private void handleNotification(McpSession session, String method, JsonObject params) {
        switch (method) {
            case McpProtocol.NOTIFICATION_INITIALIZED:
                session.markInitialized();
                break;

            case McpProtocol.NOTIFICATION_CANCELLED:
                session.cancelRequest(params.get("requestId"), Json.getString(params, "reason"));
                break;

            case McpProtocol.NOTIFICATION_PROGRESS:
                // Progress on a request *we* sent (sampling, elicitation). Nothing in MCMCP acts on
                // it yet; swallow rather than log, since a chatty client would otherwise flood the
                // game log with notifications that are working exactly as intended.
                break;

            case McpProtocol.NOTIFICATION_ROOTS_LIST_CHANGED:
                Mcmcp.LOGGER.debug("MCMCP client '" + session.describeClient() + "' changed its roots");
                break;

            default:
                // Unknown notifications are ignored by design: JSON-RPC forbids replying to them,
                // and future protocol revisions add notifications that older servers must tolerate.
                Mcmcp.LOGGER.debug("MCMCP ignoring unknown notification '" + method + "'");
                break;
        }
    }

    /** Builds the {@code notifications/resources/updated} payload for a changed resource. */
    public static JsonObject resourceUpdatedNotification(String uri) {
        return JsonRpc.notification(McpProtocol.NOTIFICATION_RESOURCES_UPDATED, Json.obj("uri", uri));
    }

    /** The list of protocol versions this dispatcher will negotiate, for diagnostics. */
    public static List<String> supportedProtocolVersions() {
        return McpProtocol.supportedVersions();
    }
}
