package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * One callable MCP tool.
 *
 * <p>Built through {@link Builder} rather than subclassed, because a tool is data plus one lambda
 * and the subclass-per-tool alternative produces a file per verb with nothing in it but a schema.
 * The registration sites read as declarations:
 *
 * <pre>{@code
 * McpTool.named("world_get_block")
 *     .title("Get block")
 *     .description("Read the block state at a world position.")
 *     .schema(JsonSchema.object()
 *         .integer("x", "Block X").integer("y", "Block Y").integer("z", "Block Z")
 *         .required("x", "y", "z")
 *         .build())
 *     .readOnly()
 *     .handler(context -> ...)
 *     .build();
 * }</pre>
 *
 * <h2>Annotations are not decoration</h2>
 *
 * MCP's tool annotations ({@code readOnlyHint}, {@code destructiveHint}, {@code idempotentHint},
 * {@code openWorldHint}) drive real client behaviour: many hosts auto-approve read-only tools and
 * require a human confirmation for destructive ones. Getting them wrong in the safe direction makes
 * the mod tedious to use; getting them wrong in the unsafe direction means a model silently
 * reshapes someone's world. Set them honestly — {@code readOnly()} means the call cannot change
 * game state, full stop.
 */
public final class McpTool {

    private final String name;
    private final String title;
    private final String description;
    private final JsonObject inputSchema;

    @Nullable
    private final JsonObject outputSchema;

    private final Set<McmcpSide> sides;
    private final boolean readOnly;
    private final boolean destructive;
    private final boolean idempotent;
    private final boolean openWorld;
    private final boolean requiresGameThread;
    private final Handler handler;

    private McpTool(Builder builder) {
        this.name = builder.name;
        this.title = builder.title == null ? builder.name : builder.title;
        this.description = builder.description == null ? "" : builder.description;
        this.inputSchema = builder.inputSchema;
        this.outputSchema = builder.outputSchema;
        this.sides = Collections.unmodifiableSet(builder.sides.isEmpty()
            ? EnumSet.allOf(McmcpSide.class)
            : EnumSet.copyOf(builder.sides));
        this.readOnly = builder.readOnly;
        this.destructive = builder.destructive;
        this.idempotent = builder.idempotent;
        this.openWorld = builder.openWorld;
        this.requiresGameThread = builder.requiresGameThread;
        this.handler = builder.handler;
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public String getName() {
        return name;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public boolean isAvailableOn(McmcpSide side) {
        return sides.contains(side);
    }

    public boolean requiresGameThread() {
        return requiresGameThread;
    }

    public ToolResult call(ToolContext context) throws Exception {
        return handler.handle(context);
    }

    /**
     * Renders the entry this tool contributes to {@code tools/list}.
     *
     * <p>{@code title} is emitted both at the top level and inside {@code annotations} — the field
     * moved between protocol revisions and clients read one or the other, so publishing both is the
     * cheapest way to have the tool show a readable name everywhere instead of its snake_case id.
     */
    public JsonObject toListEntry(String protocolVersion) {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("title", title);
        entry.addProperty("description", description);
        entry.add("inputSchema", inputSchema);
        if (outputSchema != null
            && com.micatechnologies.minecraft.mcmcp.protocol.McpProtocol
                .supportsToolStructuredContent(protocolVersion)) {
            entry.add("outputSchema", outputSchema);
        }

        JsonObject annotations = new JsonObject();
        annotations.addProperty("title", title);
        annotations.addProperty("readOnlyHint", readOnly);
        annotations.addProperty("destructiveHint", destructive);
        annotations.addProperty("idempotentHint", idempotent);
        annotations.addProperty("openWorldHint", openWorld);
        entry.add("annotations", annotations);
        return entry;
    }

    /** The body of a tool. Checked exceptions are allowed; the dispatcher converts them. */
    public interface Handler {
        ToolResult handle(ToolContext context) throws Exception;
    }

    public static final class Builder {

        private final String name;
        private final Set<McmcpSide> sides = new LinkedHashSet<>();

        private String title;
        private String description;
        private JsonObject inputSchema = com.micatechnologies.minecraft.mcmcp.json.JsonSchema.noArguments();
        private JsonObject outputSchema;
        private boolean readOnly;
        private boolean destructive;
        private boolean idempotent;

        /**
         * Defaults true, matching the MCP spec's own default. "Open world" means the tool touches
         * state outside the server's control — which for a Minecraft world, where other players are
         * acting concurrently, is nearly always the truth.
         */
        private boolean openWorld = true;

        /**
         * Defaults true because the overwhelming majority of these tools read or write world state,
         * and a tool that forgets to hop onto the game thread corrupts state in a way that surfaces
         * far from the cause. The few that genuinely do not need it (config reads, log tailing)
         * opt out explicitly via {@link #offGameThread()}.
         */
        private boolean requiresGameThread = true;

        private Handler handler;

        private Builder(String name) {
            this.name = name;
        }

        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder schema(JsonObject value) {
            this.inputSchema = value;
            return this;
        }

        public Builder outputSchema(JsonObject value) {
            this.outputSchema = value;
            return this;
        }

        /** Restricts the tool to specific endpoints; omit to expose it on both. */
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

        /** Cannot modify game state. Implies idempotent and non-destructive. */
        public Builder readOnly() {
            this.readOnly = true;
            this.idempotent = true;
            this.destructive = false;
            return this;
        }

        /** May perform irreversible updates — breaking blocks, killing entities, kicking players. */
        public Builder destructive() {
            this.destructive = true;
            this.readOnly = false;
            return this;
        }

        /** Repeat calls with the same arguments have no additional effect. */
        public Builder idempotent() {
            this.idempotent = true;
            return this;
        }

        public Builder closedWorld() {
            this.openWorld = false;
            return this;
        }

        /** For tools that touch no game state and can safely run on the HTTP worker thread. */
        public Builder offGameThread() {
            this.requiresGameThread = false;
            return this;
        }

        public Builder handler(Handler value) {
            this.handler = value;
            return this;
        }

        public McpTool build() {
            if (handler == null) {
                throw new IllegalStateException("Tool '" + name + "' has no handler");
            }
            if (description == null || description.isEmpty()) {
                // Enforced, not merely encouraged. The description is the entire basis on which a
                // model decides whether to call the tool; an undescribed tool is dead weight in
                // every request's context window.
                throw new IllegalStateException("Tool '" + name + "' has no description");
            }
            return new McpTool(this);
        }
    }
}
