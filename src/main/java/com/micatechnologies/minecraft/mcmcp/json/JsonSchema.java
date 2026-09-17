package com.micatechnologies.minecraft.mcmcp.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Fluent builder for the JSON Schema documents that describe MCP tool inputs and outputs.
 *
 * <p>Every tool must publish an {@code inputSchema}, and that schema is the only thing a model sees
 * before deciding how to call the tool — a vague schema produces wrong calls far more often than a
 * vague description does. Hand-writing them as string literals was tried first and is how the
 * property/required lists drift apart, so the builder owns both: {@link #required} adds to the
 * {@code required} array *and* asserts the property exists.
 *
 * <p>The output is a plain {@link JsonObject} of {@code {"type":"object","properties":{...},
 * "required":[...]}}, which is the subset of JSON Schema draft 2020-12 that MCP clients actually
 * consume. Nested object and array shapes are expressed by passing a nested builder.
 *
 * <pre>{@code
 * JsonSchema.object()
 *     .describe("Teleport the controlled player to a position.")
 *     .number("x", "Target X coordinate, in blocks.").required("x")
 *     .string("dimension", "Dimension id; defaults to the player's current dimension.")
 *     .build();
 * }</pre>
 */
public final class JsonSchema {

    private static final String TYPE = "type";
    private static final String DESCRIPTION = "description";
    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";

    private final JsonObject root = new JsonObject();
    private final JsonObject properties = new JsonObject();
    private final List<String> requiredNames = new ArrayList<>();

    private JsonSchema(String type) {
        root.addProperty(TYPE, type);
    }

    /**
     * Starts an object schema. This is what tool input schemas always are — MCP passes tool
     * arguments as a JSON object, so a top-level array or scalar schema is unusable.
     */
    public static JsonSchema object() {
        JsonSchema schema = new JsonSchema("object");
        schema.root.add(PROPERTIES, schema.properties);
        return schema;
    }

    /**
     * An object schema that takes no arguments.
     *
     * <p>Still an object with an empty {@code properties} map rather than an omitted schema:
     * clients validate arguments against whatever is published, and an absent schema makes some of
     * them refuse to call the tool at all.
     */
    public static JsonObject noArguments() {
        return object().build();
    }

    public JsonSchema describe(String description) {
        root.addProperty(DESCRIPTION, description);
        return this;
    }

    /**
     * Rejects arguments not named in the schema.
     *
     * <p>Off by default, matching JSON Schema. Worth turning on for destructive tools, where a
     * silently ignored misspelled argument ({@code "blocks"} vs {@code "block"}) means the tool
     * runs with a default the caller never intended.
     */
    public JsonSchema strict() {
        root.addProperty("additionalProperties", false);
        return this;
    }

    public JsonSchema string(String name, String description) {
        return property(name, primitive("string", description));
    }

    /** A string constrained to {@code values}; renders as a JSON Schema {@code enum}. */
    public JsonSchema enumeration(String name, String description, String... values) {
        JsonObject property = primitive("string", description);
        property.add("enum", Json.arrayOfStrings(Arrays.asList(values)));
        return property(name, property);
    }

    public JsonSchema number(String name, String description) {
        return property(name, primitive("number", description));
    }

    public JsonSchema integer(String name, String description) {
        return property(name, primitive("integer", description));
    }

    /**
     * An integer bounded to {@code [min, max]}.
     *
     * <p>Bounds are load-bearing rather than decorative here: several MCMCP tools take a scan
     * radius or a tick duration that a model will happily set to 100000, which would stall the game
     * thread. Publishing the ceiling makes the model pick a sane value instead of discovering the
     * clamp through an error.
     */
    public JsonSchema integer(String name, String description, int min, int max) {
        JsonObject property = primitive("integer", description);
        property.addProperty("minimum", min);
        property.addProperty("maximum", max);
        return property(name, property);
    }

    public JsonSchema number(String name, String description, double min, double max) {
        JsonObject property = primitive("number", description);
        property.addProperty("minimum", min);
        property.addProperty("maximum", max);
        return property(name, property);
    }

    public JsonSchema bool(String name, String description) {
        return property(name, primitive("boolean", description));
    }

    /** An array whose items are described by {@code itemSchema}. */
    public JsonSchema array(String name, String description, JsonObject itemSchema) {
        JsonObject property = primitive("array", description);
        property.add("items", itemSchema);
        return property(name, property);
    }

    /** An item schema for {@link #array}: a string constrained to {@code values}. */
    public static JsonObject enumItems(String... values) {
        JsonObject item = primitive("string", null);
        item.add("enum", Json.arrayOfStrings(Arrays.asList(values)));
        return item;
    }

    public JsonSchema stringArray(String name, String description) {
        return array(name, description, primitive("string", null));
    }

    /** A nested object property, described by its own builder. */
    public JsonSchema object(String name, String description, JsonSchema nested) {
        JsonObject property = nested.build();
        if (description != null) {
            property.addProperty(DESCRIPTION, description);
        }
        return property(name, property);
    }

    /** Adds a pre-built property schema; the escape hatch for shapes this builder does not model. */
    public JsonSchema property(String name, JsonObject schema) {
        properties.add(name, schema);
        return this;
    }

    /**
     * Marks previously declared properties as required.
     *
     * <p>Throws if a name was never declared. That is a programming error in tool registration, and
     * catching it at class-init time is far better than shipping a schema whose {@code required}
     * list names a property no client can supply — which presents to the model as a tool that can
     * never be called successfully.
     */
    public JsonSchema required(String... names) {
        for (String name : names) {
            if (!properties.has(name)) {
                throw new IllegalArgumentException(
                    "Cannot mark unknown property '" + name + "' as required; declare it first.");
            }
            if (!requiredNames.contains(name)) {
                requiredNames.add(name);
            }
        }
        return this;
    }

    /**
     * Builds the schema document. Safe to call more than once; each call returns a fresh copy.
     *
     * <p>The copy is a JSON round-trip rather than {@code JsonObject.deepCopy()}: that method was
     * package-private until Gson 2.8.2, and Minecraft 1.12.2 ships 2.8.0. Schemas are built once at
     * registration and are a few hundred bytes, so the serialise/parse cost is irrelevant next to
     * pinning a newer Gson.
     */
    public JsonObject build() {
        JsonObject built = Json.parse(Json.write(root)).getAsJsonObject();
        if (!requiredNames.isEmpty()) {
            JsonArray required = new JsonArray();
            for (String name : requiredNames) {
                required.add(name);
            }
            built.add(REQUIRED, required);
        }
        return built;
    }

    private static JsonObject primitive(String type, @Nullable String description) {
        JsonObject property = new JsonObject();
        property.addProperty(TYPE, type);
        if (description != null) {
            property.addProperty(DESCRIPTION, description);
        }
        return property;
    }
}
