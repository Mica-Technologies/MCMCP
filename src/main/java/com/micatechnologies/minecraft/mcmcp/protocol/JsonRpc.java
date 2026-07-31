package com.micatechnologies.minecraft.mcmcp.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import javax.annotation.Nullable;

/**
 * Construction and inspection of JSON-RPC 2.0 envelopes.
 *
 * <p>Kept free of Minecraft imports so the whole protocol layer is unit-testable without a game
 * instance — see {@code src/test/.../protocol}. Nothing here knows about tools, sessions or HTTP.
 *
 * <p>A note on ids, which is where JSON-RPC implementations usually go wrong: an id may be a string
 * or a number, and the two are distinct — {@code "1"} and {@code 1} are different requests. So ids
 * are carried around as the raw {@link JsonElement} and echoed back byte-for-byte, never parsed
 * into a Java type. A {@code null} id is only legal on a notification; a request with a null id is
 * malformed, and {@link #isRequest} treats it as such.
 */
public final class JsonRpc {

    public static final String VERSION = "2.0";

    private static final String JSONRPC = "jsonrpc";
    private static final String ID = "id";
    private static final String METHOD = "method";
    private static final String PARAMS = "params";
    private static final String RESULT = "result";
    private static final String ERROR = "error";

    private JsonRpc() {
    }

    // ------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------

    /** A message with a {@code method} and an {@code id}: expects exactly one response. */
    public static boolean isRequest(@Nullable JsonObject message) {
        return message != null && message.has(METHOD) && Json.has(message, ID);
    }

    /** A message with a {@code method} and no {@code id}: must never be answered. */
    public static boolean isNotification(@Nullable JsonObject message) {
        return message != null && message.has(METHOD) && !Json.has(message, ID);
    }

    /** A reply to something *we* sent — a sampling or elicitation request, or a ping. */
    public static boolean isResponse(@Nullable JsonObject message) {
        return message != null && !message.has(METHOD) && (message.has(RESULT) || message.has(ERROR));
    }

    @Nullable
    public static String getMethod(@Nullable JsonObject message) {
        return Json.getString(message, METHOD);
    }

    /** The raw id element, preserved exactly as received. */
    @Nullable
    public static JsonElement getId(@Nullable JsonObject message) {
        if (message == null) {
            return null;
        }
        JsonElement id = message.get(ID);
        return id == null || id.isJsonNull() ? null : id;
    }

    /**
     * The {@code params} object, or an empty object when absent.
     *
     * <p>Absent params is legal JSON-RPC and common for no-argument MCP methods like
     * {@code tools/list}, so handlers should not have to null-check. Params sent as an array
     * (positional, which JSON-RPC allows but MCP never uses) also come back empty rather than
     * throwing — the handler's own required-parameter check produces the better error message.
     */
    public static JsonObject getParams(@Nullable JsonObject message) {
        return Json.getObjectOrEmpty(message, PARAMS);
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    public static JsonObject request(JsonElement id, String method, @Nullable JsonObject params) {
        JsonObject message = new JsonObject();
        message.addProperty(JSONRPC, VERSION);
        message.add(ID, id);
        message.addProperty(METHOD, method);
        if (params != null) {
            message.add(PARAMS, params);
        }
        return message;
    }

    public static JsonObject notification(String method, @Nullable JsonObject params) {
        JsonObject message = new JsonObject();
        message.addProperty(JSONRPC, VERSION);
        message.addProperty(METHOD, method);
        if (params != null) {
            message.add(PARAMS, params);
        }
        return message;
    }

    public static JsonObject result(@Nullable JsonElement id, JsonElement result) {
        JsonObject message = new JsonObject();
        message.addProperty(JSONRPC, VERSION);
        message.add(ID, id);
        message.add(RESULT, result);
        return message;
    }

    public static JsonObject error(@Nullable JsonElement id, JsonRpcException error) {
        JsonObject message = new JsonObject();
        message.addProperty(JSONRPC, VERSION);
        message.add(ID, id);
        message.add(ERROR, error.toJson());
        return message;
    }

    public static JsonObject error(@Nullable JsonElement id, int code, String description) {
        return error(id, new JsonRpcException(code, description));
    }

    /**
     * Validates the JSON-RPC framing common to every inbound message.
     *
     * @throws JsonRpcException {@code -32600} if the envelope is unusable
     */
    public static void validateEnvelope(@Nullable JsonObject message) {
        if (message == null) {
            throw JsonRpcException.invalidRequest("Message is not a JSON object");
        }
        String version = Json.getString(message, JSONRPC);
        if (!VERSION.equals(version)) {
            // Strict on purpose. A message without "jsonrpc":"2.0" is either a different protocol
            // pointed at our port or a client bug, and both are better surfaced now than after a
            // handler has already acted on the game world.
            throw JsonRpcException.invalidRequest(
                "Expected \"jsonrpc\":\"2.0\" but found " + (version == null ? "nothing" : '"' + version + '"'));
        }
        if (!message.has(METHOD) && !message.has(RESULT) && !message.has(ERROR)) {
            throw JsonRpcException.invalidRequest("Message has neither \"method\", \"result\" nor \"error\"");
        }
        if (message.has(METHOD) && Json.getString(message, METHOD) == null) {
            throw JsonRpcException.invalidRequest("\"method\" must be a string");
        }
    }
}
