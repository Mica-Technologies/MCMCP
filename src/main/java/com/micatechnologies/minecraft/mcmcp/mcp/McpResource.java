package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * One MCP resource: a named, URI-addressed blob of state a client can read on demand.
 *
 * <p>Resources and tools solve different problems and mixing them up makes an MCP server unpleasant
 * to use. A <em>tool</em> is a verb the model chooses to invoke and pays context for on every call.
 * A <em>resource</em> is a noun the client can attach, cache, re-read and subscribe to without the
 * model spending a turn on it. So "the player's current inventory" is a resource — the client can
 * keep it in context and refresh it when it changes — while "drop 3 items" is a tool.
 *
 * <p>MCMCP addresses resources under the {@code minecraft:} scheme, e.g.
 * {@code minecraft://client/screenshot/latest}. Templated resources ({@link #getUriTemplate}) cover
 * families with a parameter, such as a specific chunk or a specific player, and are published
 * separately via {@code resources/templates/list}.
 */
public final class McpResource {

    private final String uri;

    @Nullable
    private final String uriTemplate;

    private final String name;
    private final String title;
    private final String description;
    private final String mimeType;
    private final Set<McmcpSide> sides;
    private final boolean subscribable;
    private final Reader reader;

    private McpResource(Builder builder) {
        this.uri = builder.uri;
        this.uriTemplate = builder.uriTemplate;
        this.name = builder.name;
        this.title = builder.title == null ? builder.name : builder.title;
        this.description = builder.description == null ? "" : builder.description;
        this.mimeType = builder.mimeType;
        this.sides = Collections.unmodifiableSet(builder.sides.isEmpty()
            ? EnumSet.allOf(McmcpSide.class)
            : EnumSet.copyOf(builder.sides));
        this.subscribable = builder.subscribable;
        this.reader = builder.reader;
    }

    public static Builder at(String uri) {
        return new Builder(uri, null);
    }

    /** Declares a templated resource family, e.g. {@code minecraft://world/chunk/{x}/{z}}. */
    public static Builder template(String uriTemplate) {
        return new Builder(null, uriTemplate);
    }

    public String getUri() {
        return uri;
    }

    @Nullable
    public String getUriTemplate() {
        return uriTemplate;
    }

    public boolean isTemplate() {
        return uriTemplate != null;
    }

    public String getName() {
        return name;
    }

    public String getMimeType() {
        return mimeType;
    }

    public boolean isSubscribable() {
        return subscribable;
    }

    public boolean isAvailableOn(McmcpSide side) {
        return sides.contains(side);
    }

    /**
     * Reads the resource.
     *
     * @param context     the reading session's context, for game-thread access
     * @param resolvedUri the concrete URI requested — differs from {@link #getUri} for templates
     * @return one or more {@code contents} entries, built with {@link McpContent#textResource} or
     *         {@link McpContent#binaryResource}
     */
    public List<JsonObject> read(ToolContext context, String resolvedUri) throws Exception {
        return reader.read(context, resolvedUri);
    }

    /** The entry this resource contributes to {@code resources/list} or {@code .../templates/list}. */
    public JsonObject toListEntry() {
        JsonObject entry = new JsonObject();
        if (isTemplate()) {
            entry.addProperty("uriTemplate", uriTemplate);
        }
        else {
            entry.addProperty("uri", uri);
        }
        entry.addProperty("name", name);
        entry.addProperty("title", title);
        entry.addProperty("description", description);
        entry.addProperty("mimeType", mimeType);
        return entry;
    }

    public interface Reader {
        List<JsonObject> read(ToolContext context, String resolvedUri) throws Exception;
    }

    public static final class Builder {

        private final String uri;
        private final String uriTemplate;
        private final Set<McmcpSide> sides = new LinkedHashSet<>();

        private String name;
        private String title;
        private String description;
        private String mimeType = "application/json";
        private boolean subscribable;
        private Reader reader;

        private Builder(@Nullable String uri, @Nullable String uriTemplate) {
            this.uri = uri;
            this.uriTemplate = uriTemplate;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder mimeType(String value) {
            this.mimeType = value;
            return this;
        }

        public Builder sides(McmcpSide... values) {
            this.sides.addAll(Arrays.asList(values));
            return this;
        }

        public Builder clientOnly() {
            return sides(McmcpSide.CLIENT);
        }

        public Builder serverOnly() {
            return sides(McmcpSide.SERVER);
        }

        /**
         * Allows clients to {@code resources/subscribe} to this URI.
         *
         * <p>Only worth setting where something actually fires
         * {@code ResourceRegistry.notifyUpdated} for it. A resource advertised as subscribable that
         * never emits an update looks to the client exactly like a resource that never changes,
         * which is a lie that costs a poll loop.
         */
        public Builder subscribable() {
            this.subscribable = true;
            return this;
        }

        public Builder reader(Reader value) {
            this.reader = value;
            return this;
        }

        public McpResource build() {
            if (name == null || name.isEmpty()) {
                throw new IllegalStateException("Resource " + (uri == null ? uriTemplate : uri) + " has no name");
            }
            if (reader == null) {
                throw new IllegalStateException("Resource '" + name + "' has no reader");
            }
            return new McpResource(this);
        }
    }
}
