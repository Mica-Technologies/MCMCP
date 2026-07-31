package com.micatechnologies.minecraft.mcmcp.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javax.annotation.Nullable;

/**
 * A JSON-RPC 2.0 error, thrown by request handlers and turned into an error response by
 * {@link McpDispatcher}.
 *
 * <p>The distinction that matters throughout MCMCP: a <em>protocol</em> error (this class) means
 * the request itself was malformed or could not be routed, and is reported as a JSON-RPC
 * {@code error} object. A <em>tool</em> error — the tool ran fine but the action failed, e.g. "no
 * player is logged in" — is <strong>not</strong> this class. Per the MCP spec that is a successful
 * response carrying {@code isError: true}, because the model needs to see the failure text and
 * decide what to do, whereas a JSON-RPC error is handled by the client's plumbing and often never
 * reaches the model at all. Throwing this from inside a tool handler is almost always a bug; see
 * {@code ToolResult.error} for the other path.
 */
public class JsonRpcException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // Standard JSON-RPC 2.0 codes.
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    /**
     * MCP does not define codes of its own beyond the JSON-RPC set, so these live in the
     * implementation-defined server range ({@code -32099..-32000}) that JSON-RPC reserves. They
     * exist so a client can distinguish "the thing you named does not exist" from "your request was
     * shaped wrong", which both otherwise collapse into {@code -32602}.
     */
    public static final int RESOURCE_NOT_FOUND = -32002;
    public static final int TOOL_NOT_FOUND = -32003;
    public static final int PROMPT_NOT_FOUND = -32004;
    public static final int REQUEST_CANCELLED = -32005;
    public static final int REQUEST_TIMED_OUT = -32006;

    private final int code;

    @Nullable
    private final JsonElement data;

    public JsonRpcException(int code, String message) {
        this(code, message, null);
    }

    public JsonRpcException(int code, String message, @Nullable JsonElement data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    @Nullable
    public JsonElement getData() {
        return data;
    }

    /** Renders the {@code error} member of a JSON-RPC error response. */
    public JsonObject toJson() {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", getMessage() == null ? "Unknown error" : getMessage());
        if (data != null) {
            error.add("data", data);
        }
        return error;
    }

    public static JsonRpcException parseError(String message) {
        return new JsonRpcException(PARSE_ERROR, message);
    }

    public static JsonRpcException invalidRequest(String message) {
        return new JsonRpcException(INVALID_REQUEST, message);
    }

    public static JsonRpcException methodNotFound(String method) {
        return new JsonRpcException(METHOD_NOT_FOUND, "Unknown method: " + method);
    }

    public static JsonRpcException invalidParams(String message) {
        return new JsonRpcException(INVALID_PARAMS, message);
    }

    public static JsonRpcException internalError(String message) {
        return new JsonRpcException(INTERNAL_ERROR, message);
    }

    /**
     * Thrown when a required parameter is missing or unusable.
     *
     * <p>Names the parameter in the message on purpose: the caller is usually a model, and
     * "Missing required parameter 'uri'" gets corrected on the next attempt where "Invalid params"
     * gets retried verbatim.
     */
    public static JsonRpcException missingParam(String name) {
        return invalidParams("Missing required parameter '" + name + "'");
    }
}
