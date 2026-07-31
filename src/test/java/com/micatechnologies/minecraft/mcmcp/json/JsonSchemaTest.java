package com.micatechnologies.minecraft.mcmcp.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** The tool input-schema builder. */
class JsonSchemaTest {

    @Test
    void buildsAnObjectSchemaWithTypedProperties() {
        JsonObject schema = JsonSchema.object()
            .describe("Read a block.")
            .integer("x", "Block X")
            .string("dimension", "Dimension id")
            .bool("relative", "Relative to the player")
            .build();

        assertEquals("object", schema.get("type").getAsString());
        assertEquals("Read a block.", schema.get("description").getAsString());

        JsonObject properties = schema.getAsJsonObject("properties");
        assertEquals("integer", properties.getAsJsonObject("x").get("type").getAsString());
        assertEquals("string", properties.getAsJsonObject("dimension").get("type").getAsString());
        assertEquals("boolean", properties.getAsJsonObject("relative").get("type").getAsString());
    }

    @Test
    void recordsRequiredProperties() {
        JsonObject schema = JsonSchema.object()
            .integer("x", "Block X")
            .integer("y", "Block Y")
            .required("x", "y")
            .build();

        assertEquals(2, schema.getAsJsonArray("required").size());
        assertEquals("x", schema.getAsJsonArray("required").get(0).getAsString());
    }

    /**
     * Marking an undeclared property required is a registration bug and must fail loudly.
     *
     * <p>The alternative ships a schema whose {@code required} list names a property no client can
     * supply, which presents to a model as a tool that can never be called successfully — and does
     * so at runtime, in someone else's game.
     */
    @Test
    void refusesToRequireAnUndeclaredProperty() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> JsonSchema.object().integer("x", "Block X").required("y"));
        assertTrue(thrown.getMessage().contains("'y'"));
    }

    @Test
    void doesNotDuplicateRepeatedRequiredNames() {
        JsonObject schema = JsonSchema.object()
            .integer("x", "Block X")
            .required("x")
            .required("x")
            .build();
        assertEquals(1, schema.getAsJsonArray("required").size());
    }

    @Test
    void omitsTheRequiredArrayWhenNothingIsRequired() {
        assertFalse(JsonSchema.object().string("optional", "Optional").build().has("required"));
    }

    @Test
    void emitsBoundsForRangedNumbers() {
        JsonObject property = JsonSchema.object()
            .integer("radius", "Search radius", 1, 128)
            .build()
            .getAsJsonObject("properties")
            .getAsJsonObject("radius");

        assertEquals(1, property.get("minimum").getAsInt());
        assertEquals(128, property.get("maximum").getAsInt());
    }

    @Test
    void emitsEnumValues() {
        JsonObject property = JsonSchema.object()
            .enumeration("direction", "Which way", "forward", "back")
            .build()
            .getAsJsonObject("properties")
            .getAsJsonObject("direction");

        assertEquals("string", property.get("type").getAsString());
        assertEquals(2, property.getAsJsonArray("enum").size());
        assertEquals("forward", property.getAsJsonArray("enum").get(0).getAsString());
    }

    /**
     * A no-argument schema is still an object with an empty properties map.
     *
     * <p>Some clients refuse to call a tool that publishes no schema at all, so "takes no arguments"
     * has to be stated rather than omitted.
     */
    @Test
    void noArgumentSchemaIsAnEmptyObjectSchema() {
        JsonObject schema = JsonSchema.noArguments();
        assertEquals("object", schema.get("type").getAsString());
        assertTrue(schema.has("properties"));
        assertEquals(0, schema.getAsJsonObject("properties").size());
    }

    /** build() must be repeatable — the registry calls it once per list request in some paths. */
    @Test
    void buildIsRepeatableAndReturnsIndependentCopies() {
        JsonSchema builder = JsonSchema.object().integer("x", "Block X").required("x");
        JsonObject first = builder.build();
        JsonObject second = builder.build();

        assertEquals(Json.write(first), Json.write(second));
        first.addProperty("mutated", true);
        assertFalse(second.has("mutated"));
    }
}
