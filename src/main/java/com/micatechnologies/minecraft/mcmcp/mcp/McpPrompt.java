package com.micatechnologies.minecraft.mcmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.game.McmcpSide;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One MCP prompt: a named, parameterised conversation starter the user picks from their client's UI.
 *
 * <p>Prompts are user-initiated, which is the property that makes them useful here. Tools are
 * chosen by the model and resources are attached by the client, but a prompt is something a person
 * deliberately runs — "survey the area around me", "diagnose why this redstone contraption is not
 * firing" — and it arrives pre-loaded with the game state the task needs. That saves the model
 * three or four discovery tool calls before it can start on the actual question.
 *
 * <p>A prompt returns a list of messages, each with a {@code role} and one content block. MCMCP's
 * built-ins generate their messages from live world state at {@code prompts/get} time, so the model
 * sees the world as it is when the user runs the prompt, not as it was when the server started.
 */
public final class McpPrompt {

    private final String name;
    private final String title;
    private final String description;
    private final List<Argument> arguments;
    private final Set<McmcpSide> sides;
    private final Generator generator;

    private McpPrompt(Builder builder) {
        this.name = builder.name;
        this.title = builder.title == null ? builder.name : builder.title;
        this.description = builder.description == null ? "" : builder.description;
        this.arguments = Collections.unmodifiableList(new ArrayList<>(builder.arguments));
        this.sides = Collections.unmodifiableSet(builder.sides.isEmpty()
            ? EnumSet.allOf(McmcpSide.class)
            : EnumSet.copyOf(builder.sides));
        this.generator = builder.generator;
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<Argument> getArguments() {
        return arguments;
    }

    public boolean isAvailableOn(McmcpSide side) {
        return sides.contains(side);
    }

    public List<JsonObject> generate(ToolContext context) throws Exception {
        return generator.generate(context);
    }

    public JsonObject toListEntry() {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("title", title);
        entry.addProperty("description", description);
        JsonArray args = new JsonArray();
        for (Argument argument : arguments) {
            args.add(argument.toJson());
        }
        entry.add("arguments", args);
        return entry;
    }

    /** Builds one entry of a prompt's {@code messages} array. */
    public static JsonObject message(String role, JsonObject content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.add("content", content);
        return message;
    }

    public static JsonObject userMessage(String text) {
        return message("user", McpContent.text(text));
    }

    public static JsonObject assistantMessage(String text) {
        return message("assistant", McpContent.text(text));
    }

    public interface Generator {
        List<JsonObject> generate(ToolContext context) throws Exception;
    }

    /** One declared prompt argument. Clients render these as form fields before running the prompt. */
    public static final class Argument {

        private final String name;
        private final String description;
        private final boolean required;

        public Argument(String name, String description, boolean required) {
            this.name = name;
            this.description = description;
            this.required = required;
        }

        public String getName() {
            return name;
        }

        public boolean isRequired() {
            return required;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("name", name);
            json.addProperty("description", description);
            json.addProperty("required", required);
            return json;
        }
    }

    public static final class Builder {

        private final String name;
        private final List<Argument> arguments = new ArrayList<>();
        private final Set<McmcpSide> sides = new LinkedHashSet<>();

        private String title;
        private String description;
        private Generator generator;

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

        public Builder argument(String argName, String argDescription, boolean required) {
            this.arguments.add(new Argument(argName, argDescription, required));
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

        public Builder generator(Generator value) {
            this.generator = value;
            return this;
        }
        public McpPrompt build() {
            if (generator == null) {
                throw new IllegalStateException("Prompt '" + name + "' has no generator");
            }
            return new McpPrompt(this);
        }
    }
}
