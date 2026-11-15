package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpConstants;
import com.micatechnologies.minecraft.mcmcp.events.EventFilter;
import com.micatechnologies.minecraft.mcmcp.events.EventLog;
import com.micatechnologies.minecraft.mcmcp.events.GameEvent;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpContent;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpResource;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.Principal;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolContext;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Event subscriptions (#201): {@code game_events_wait}, and a subscribable resource per side.
 *
 * <p>Watching for a teammate or a chat line that tags the agent used to be a script polling
 * {@code client_read_chat} every few seconds all day, de-duplicating by text and grepping
 * "joined the game". This replaces it with a long-poll on a cursor: one call blocks until something
 * matching happens, and its answer says where the next call starts, so nothing is lost or repeated.
 *
 * <p>The same wait backs the streams outside MCP: the {@code /events} HTTP stream on each endpoint,
 * and the orchestrator's WebSocket and {@code events} command, which call this tool in a loop.
 */
public final class GameEventTools {

    public static final String TOOL = "game_events_wait";

    static final int MAX_TIMEOUT_SECONDS = 300;
    static final int MAX_EVENTS = 200;

    /** How often a long wait reports progress, which keeps an orchestrator from abandoning it. */
    private static final long HEARTBEAT_MILLIS = 10_000L;

    private GameEventTools() {
    }

    /** The resource listing a side's recent events; subscribe to be told when one is added. */
    public static String resourceUri(McmcpSide side) {
        return McmcpConstants.RESOURCE_SCHEME + "://" + side.id() + "/events/recent";
    }

    public static EventLog log(McmcpSide side) {
        return EventLog.forSide(side.id());
    }

    public static void register() {
        for (final McmcpSide side : McmcpSide.values()) {
            final String uri = resourceUri(side);
            log(side).addListener(() -> McpRegistry.notifyResourceUpdated(uri));
            McpRegistry.registerResource(McpResource
                .at(uri)
                .name(side.id() + "-events")
                .title("Recent game events (" + side.id() + ")")
                .description("The newest chat lines, joins, leaves, deaths, advancements and commands this "
                    + "endpoint has seen, oldest first, with the cursor to wait from. Subscribe to be told "
                    + "when one is added; game_events_wait waits for specific ones.")
                .mimeType("application/json")
                .sides(side)
                .subscribable()
                .reader((context, requested) -> {
                    EventLog log = log(side);
                    JsonObject payload = new JsonObject();
                    payload.addProperty("stream", log.getStream());
                    payload.addProperty("next", log.lastSeq());
                    JsonArray events = new JsonArray();
                    for (GameEvent event : log.recent(50)) {
                        events.add(event.toJson());
                    }
                    payload.add("events", events);
                    return Collections.singletonList(
                        McpContent.textResource(requested, "application/json", Json.write(payload)));
                })
                .build());
        }

        McpRegistry.registerTool(McpTool.named(TOOL)
            .title("Wait for game events")
            .description("Wait until something happens in the game that you want to react to: a chat "
                + "line, a player joining or leaving, a death, an advancement, a command someone ran. "
                + "Returns as soon as one matching event arrives, or empty when 'timeout_s' passes.\n\n"
                + "Use this instead of polling client_read_chat. Pass the result's 'next' as 'since' and "
                + "its 'stream' as 'stream' on the next call: nothing is skipped or repeated between "
                + "calls, and 'missed' counts events that fell off the buffer while you were away. "
                + "'reset' means the game restarted since your cursor. Omit 'since' to wait only for "
                + "what happens from now on; since 0 also returns what is already buffered.\n\n"
                + "On a server endpoint, events come from the server (exact sender and uuid). On a "
                + "client, chat is what this client received and joins/leaves come from the tab list. "
                + "For a stream outside MCP, the orchestrator serves these over a WebSocket and its "
                + "'events' command.")
            .schema(JsonSchema.object()
                .stringArray("types", "Event types to wait for: chat, join, leave, death, advancement, "
                    + "command. Omit for all.")
                .stringArray("players", "Only events about these players (names, any case).")
                .stringArray("exclude_players", "Ignore events about these players, e.g. your own name "
                    + "so your own chat does not wake you.")
                .string("contains", "Only events whose text contains this, any case, e.g. '@Claude'.")
                .integer("since", "The 'next' of your previous call. Omit to wait for new events only.")
                .string("stream", "The 'stream' of the result your 'since' came from.")
                .integer("timeout_s", "Longest to wait, in seconds. 0 answers at once. Default 60.",
                    0, MAX_TIMEOUT_SECONDS)
                .integer("max", "Most events to return in one answer. Default 50.", 1, MAX_EVENTS)
                .build())
            .readOnly()
            .offGameThread()
            .passive()
            .handler(GameEventTools::waitForEvents)
            .build());
    }

    private static ToolResult waitForEvents(final ToolContext context) throws Exception {
        JsonObject arguments = context.getArguments();
        EventFilter filter = filterFrom(arguments);
        Set<String> unknown = filter.unknownTypes();
        if (!unknown.isEmpty()) {
            return ToolResult.error("Unknown event type(s) " + unknown + ". The types are "
                + GameEvent.TYPES + ".");
        }
        Principal principal = context.getPrincipal();
        if (principal.isCompanion() && !principal.has(Principal.CLASS_OTHERS)
            && !principal.has(Principal.CLASS_CONSOLE)) {
            filter = filter.withCommandsOnlyFrom(principal.getPlayerName());
        }

        Long since = Json.has(arguments, "since") ? Json.getLong(arguments, "since", 0L) : null;
        String stream = context.getString("stream", null);
        final int timeoutSeconds = context.getBoundedInt("timeout_s", 60, 0, MAX_TIMEOUT_SECONDS);
        int max = context.getBoundedInt("max", 50, 1, MAX_EVENTS);
        final long start = System.currentTimeMillis();
        final long[] lastBeat = {start};

        EventLog.Batch batch = log(context.getSide()).await(since, stream, filter, max,
            timeoutSeconds * 1000L, () -> {
                if (context.getCancellation().isCancelled()) {
                    return false;
                }
                long now = System.currentTimeMillis();
                if (now - lastBeat[0] >= HEARTBEAT_MILLIS) {
                    lastBeat[0] = now;
                    context.reportProgress((now - start) / 1000.0D, timeoutSeconds, "waiting for events");
                }
                return true;
            });
        return ToolResult.structured(toJson(batch));
    }

    /** The filter the tool's arguments (or an {@code /events} stream's query) describe. */
    public static EventFilter filterFrom(JsonObject arguments) {
        return new EventFilter(strings(arguments, "types"), strings(arguments, "players"),
            strings(arguments, "exclude_players"), Json.getString(arguments, "contains", null));
    }

    /** The answer's wire form: shared with the HTTP event stream, so the two cannot drift. */
    public static JsonObject toJson(EventLog.Batch batch) {
        JsonObject json = new JsonObject();
        json.addProperty("stream", batch.stream);
        json.addProperty("next", batch.next);
        JsonArray events = new JsonArray();
        for (GameEvent event : batch.events) {
            events.add(event.toJson());
        }
        json.add("events", events);
        if (batch.timedOut) {
            json.addProperty("timedOut", true);
        }
        if (batch.missed > 0) {
            json.addProperty("missed", batch.missed);
        }
        if (batch.reset) {
            json.addProperty("reset", true);
        }
        return json;
    }

    /**
     * A string list from an array or, since models and query strings both send them, a
     * comma-separated string.
     */
    @Nullable
    public static List<String> strings(JsonObject arguments, String key) {
        JsonElement element = arguments.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            return Json.getStringList(arguments, key);
        }
        List<String> values = new ArrayList<>();
        for (String part : element.getAsString().split(",")) {
            if (!part.trim().isEmpty()) {
                values.add(part.trim());
            }
        }
        return values;
    }
}
