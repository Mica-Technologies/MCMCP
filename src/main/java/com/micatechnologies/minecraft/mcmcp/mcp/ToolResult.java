package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.protocol.McpProtocol;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The result of a {@code tools/call}.
 *
 * <p>The central thing to understand about MCP tool errors, and the reason this class has an
 * {@link #error} factory that produces a <em>successful</em> JSON-RPC response: a tool that ran and
 * failed is not a protocol error. "There is no player logged in", "that block is out of range",
 * "movement control is disabled in the config" — all of these are results the model needs to read
 * and react to, so they come back as {@code isError: true} with the explanation in the content.
 * A JSON-RPC error, by contrast, is consumed by the client's plumbing and frequently never reaches
 * the model, which then retries the identical call forever. Reserve
 * {@link com.micatechnologies.minecraft.mcmcp.protocol.JsonRpcException} for genuinely malformed
 * requests.
 *
 * <p>{@link #structured} attaches machine-readable output alongside the human-readable content.
 * It is only emitted to clients on protocol 2025-06-18 or newer — see
 * {@link McpProtocol#supportsToolStructuredContent} — but callers do not need to care: they always
 * set it, and {@link #toJson} decides.
 */
public final class ToolResult {

    private final List<JsonObject> content = new ArrayList<>();
    private final boolean isError;

    @Nullable
    private JsonObject structuredContent;

    private ToolResult(boolean isError) {
        this.isError = isError;
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /** An empty successful result; add content with the {@code with*} methods. */
    public static ToolResult ok() {
        return new ToolResult(false);
    }

    /** The common case: a successful result whose entire payload is one line of text. */
    public static ToolResult text(String message) {
        return ok().withText(message);
    }

    /**
     * A failed tool call. Comes back as a successful JSON-RPC response with {@code isError: true},
     * per the MCP spec — see the class javadoc for why that is not a mistake.
     */
    public static ToolResult error(String message) {
        return new ToolResult(true).withText(message);
    }

    /**
     * A successful result carrying both prose and structured data.
     *
     * <p>The text is generated from the structured form rather than written separately so the two
     * cannot disagree — a result whose prose says one thing and whose JSON says another is worse
     * than having only one of them.
     */
    public static ToolResult structured(JsonObject data) {
        ToolResult result = ok();
        result.structuredContent = data;
        result.withText(Json.writePretty(data));
        return result;
    }

    // ------------------------------------------------------------------
    // Builders
    // ------------------------------------------------------------------

    public ToolResult withText(String value) {
        content.add(McpContent.text(value));
        return this;
    }

    public ToolResult withContent(JsonObject contentBlock) {
        content.add(contentBlock);
        return this;
    }

    public ToolResult withStructured(JsonObject data) {
        this.structuredContent = data;
        return this;
    }

    public boolean isError() {
        return isError;
    }

    // ------------------------------------------------------------------
    // Wire form
    // ------------------------------------------------------------------

    /**
     * Renders the {@code tools/call} result for a client on {@code protocolVersion}.
     *
     * <p>An empty content list is still emitted as an empty array rather than omitted: {@code
     * content} is a required field, and clients that validate strictly reject a result without it.
     */
    public JsonObject toJson(String protocolVersion) {
        JsonObject result = new JsonObject();
        JsonArray blocks = new JsonArray();
        for (JsonObject block : content) {
            blocks.add(block);
        }
        result.add("content", blocks);
        result.addProperty("isError", isError);
        if (structuredContent != null && McpProtocol.supportsToolStructuredContent(protocolVersion)) {
            result.add("structuredContent", structuredContent);
        }
        return result;
    }
}
