package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonObject;
import java.util.Base64;
import javax.annotation.Nullable;

/**
 * Builders for MCP content blocks — the polymorphic payload shared by tool results, prompt
 * messages and sampling messages.
 *
 * <p>MCP defines five block types ({@code text}, {@code image}, {@code audio},
 * {@code resource_link}, {@code resource}), each an object with a {@code type} discriminator. They
 * are built rather than modelled as classes because they are write-only from MCMCP's side: nothing
 * in the mod ever parses a content block it produced, so a class hierarchy would be ceremony around
 * a JSON literal.
 *
 * <p>The choice between {@link #image} and {@link #resourceLink} is worth getting right for the
 * screenshot tools. An inline image costs ~1.4 MB of base64 for a 1080p PNG and is spent on every
 * single call whether or not the model needed to look at it; a resource link costs a few dozen
 * bytes and lets the client fetch the bytes only if it decides to. MCMCP's screenshot tool returns
 * the path plus a link by default and inlines the image only when explicitly asked.
 */
public final class McpContent {

    private McpContent() {
    }

    /** A plain-text block. The default and by far the most common result shape. */
    public static JsonObject text(String value) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", value == null ? "" : value);
        return block;
    }

    /**
     * An inline image block. {@code data} is raw bytes; this method does the base64 for you so
     * callers cannot accidentally double-encode.
     *
     * @param mimeType e.g. {@code image/png} — required by the spec, and clients do dispatch on it
     */
    public static JsonObject image(byte[] data, String mimeType) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "image");
        block.addProperty("data", Base64.getEncoder().encodeToString(data));
        block.addProperty("mimeType", mimeType);
        return block;
    }

    public static JsonObject audio(byte[] data, String mimeType) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "audio");
        block.addProperty("data", Base64.getEncoder().encodeToString(data));
        block.addProperty("mimeType", mimeType);
        return block;
    }

    /**
     * A pointer to a resource the client may read later via {@code resources/read}.
     *
     * <p>This is how a tool hands back something large — a screenshot, a chunk dump, a log
     * excerpt — without paying for it in the tool result itself.
     */
    public static JsonObject resourceLink(String uri, String name, @Nullable String description,
                                          @Nullable String mimeType) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "resource_link");
        block.addProperty("uri", uri);
        block.addProperty("name", name);
        if (description != null) {
            block.addProperty("description", description);
        }
        if (mimeType != null) {
            block.addProperty("mimeType", mimeType);
        }
        return block;
    }

    /** An embedded resource: the contents inline, rather than a link the client must follow. */
    public static JsonObject embeddedResource(JsonObject resourceContents) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "resource");
        block.add("resource", resourceContents);
        return block;
    }

    // ------------------------------------------------------------------
    // resources/read payloads
    // ------------------------------------------------------------------

    /** The {@code contents} entry for a text resource. */
    public static JsonObject textResource(String uri, String mimeType, String text) {
        JsonObject contents = new JsonObject();
        contents.addProperty("uri", uri);
        contents.addProperty("mimeType", mimeType);
        contents.addProperty("text", text);
        return contents;
    }

    /** The {@code contents} entry for a binary resource; {@code blob} is base64 per spec. */
    public static JsonObject binaryResource(String uri, String mimeType, byte[] data) {
        JsonObject contents = new JsonObject();
        contents.addProperty("uri", uri);
        contents.addProperty("mimeType", mimeType);
        contents.addProperty("blob", Base64.getEncoder().encodeToString(data));
        return contents;
    }
}
