package com.micatechnologies.minecraft.mcmcp.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tolerant JSON accessors.
 *
 * <p>The leniency tested here is deliberate and load-bearing: MCP arguments are frequently produced
 * by a language model, which sends {@code {"count": "5"}} where the schema said integer roughly as
 * often as the reverse. Coercing rather than rejecting turns a failed call into a working one, at no
 * cost to correctness — the values are still validated where it matters.
 */
class JsonTest {

    private static JsonObject fixture() {
        return Json.parse("{"
            + "\"name\":\"steve\","
            + "\"count\":5,"
            + "\"countAsString\":\"7\","
            + "\"ratio\":0.25,"
            + "\"flag\":true,"
            + "\"flagAsString\":\"false\","
            + "\"nested\":{\"a\":1},"
            + "\"list\":[\"a\",\"b\"],"
            + "\"nothing\":null"
            + "}").getAsJsonObject();
    }

    @Test
    void readsStringsNumbersAndBooleans() {
        JsonObject json = fixture();
        assertEquals("steve", Json.getString(json, "name"));
        assertEquals(5, Json.getInt(json, "count", 0));
        assertEquals(0.25D, Json.getDouble(json, "ratio", 0.0D), 1e-9D);
        assertTrue(Json.getBoolean(json, "flag", false));
    }

    @Test
    void coercesNumericStringsToNumbers() {
        assertEquals(7, Json.getInt(fixture(), "countAsString", 0));
    }

    @Test
    void coercesBooleanStrings() {
        assertFalse(Json.getBoolean(fixture(), "flagAsString", true));
    }

    @Test
    void coercesNumbersToStrings() {
        assertEquals("5", Json.getString(fixture(), "count"));
    }

    @Test
    void returnsTheFallbackForMissingOrUnusableFields() {
        JsonObject json = fixture();
        assertEquals("fallback", Json.getString(json, "absent", "fallback"));
        assertEquals(99, Json.getInt(json, "absent", 99));
        assertEquals(99, Json.getInt(json, "name", 99), "a non-numeric string is not a number");
        assertEquals(99, Json.getInt(json, "nested", 99), "an object is not a number");
        assertTrue(Json.getBoolean(json, "absent", true));
    }

    @Test
    void treatsJsonNullAsAbsent() {
        assertFalse(Json.has(fixture(), "nothing"));
        assertNull(Json.getString(fixture(), "nothing"));
    }

    @Test
    void readsNestedObjectsAndArrays() {
        JsonObject json = fixture();
        assertNotNull(Json.getObject(json, "nested"));
        assertEquals(1, Json.getInt(Json.getObject(json, "nested"), "a", 0));
        assertNotNull(Json.getArray(json, "list"));

        List<String> list = Json.getStringList(json, "list");
        assertEquals(2, list.size());
        assertEquals("a", list.get(0));
    }

    @Test
    void getObjectOrEmptyNeverReturnsNull() {
        assertEquals(0, Json.getObjectOrEmpty(fixture(), "absent").size());
        assertEquals(0, Json.getObjectOrEmpty(null, "absent").size());
    }

    @Test
    void parseReturnsNullForInvalidJsonRatherThanThrowing() {
        assertNull(Json.parse("{not json"));
        assertNull(Json.parse(""));
        assertNull(Json.parse(null));
        assertNull(Json.parse("null"));
    }

    /**
     * HTML escaping stays off.
     *
     * <p>Gson escapes {@code < > & = '} by default. Valid JSON, but it mangles the file paths, chat
     * text and tool descriptions that make up most of what MCMCP sends, and this payload goes to an
     * MCP client over HTTP — never into an HTML document.
     */
    @Test
    void doesNotHtmlEscapeOutput() {
        JsonObject json = new JsonObject();
        json.addProperty("text", "a < b && c > d");
        assertEquals("{\"text\":\"a < b && c > d\"}", Json.write(json));
    }

    @Test
    void nullSerialisesAsJsonNull() {
        assertEquals("null", Json.write(null));
    }
}
