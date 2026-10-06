package com.micatechnologies.minecraft.mcmcp.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonExpectTest {

    private static JsonObject parse(String json) {
        return Json.parse(json).getAsJsonObject();
    }

    private static List<String> check(String expected, String actual) {
        return JsonExpect.mismatches(parse(expected), parse(actual));
    }

    @Test
    void only_the_named_fields_are_checked() {
        assertTrue(check("{\"block\":\"minecraft:stone\"}",
            "{\"block\":\"minecraft:stone\",\"loaded\":true,\"state\":{}}").isEmpty());
    }

    @Test
    void a_wrong_value_names_the_field_and_both_values() {
        List<String> found = check("{\"block\":\"minecraft:stone\"}", "{\"block\":\"minecraft:dirt\"}");

        assertEquals(1, found.size());
        assertEquals("block: expected \"minecraft:stone\", got \"minecraft:dirt\"", found.get(0));
    }

    @Test
    void every_mismatch_is_reported_not_just_the_first() {
        List<String> found = check("{\"block\":\"a:b\",\"state\":{\"facing\":\"north\"}}",
            "{\"block\":\"a:c\",\"state\":{\"facing\":\"south\"}}");

        assertEquals(2, found.size());
        assertEquals("state.facing: expected \"north\", got \"south\"", found.get(1));
    }

    @Test
    void a_dotted_key_reaches_into_nested_objects() {
        assertTrue(check("{\"mainHand.item\":\"minecraft:stick\"}",
            "{\"mainHand\":{\"item\":\"minecraft:stick\",\"count\":1}}").isEmpty());
        assertEquals(1, check("{\"mainHand.item\":\"minecraft:stick\"}", "{\"mainHand\":{}}").size());
    }

    @Test
    void numbers_compare_by_value_so_an_integer_matches_its_decimal_form() {
        assertTrue(check("{\"count\":1}", "{\"count\":1.0}").isEmpty());
    }

    @Test
    void a_wildcard_requires_presence_and_null_requires_absence() {
        assertTrue(check("{\"nbt\":\"*\"}", "{\"nbt\":\"{a:1}\"}").isEmpty());
        assertEquals(1, check("{\"nbt\":\"*\"}", "{}").size());
        assertTrue(check("{\"nbt\":null}", "{}").isEmpty());
        assertEquals(1, check("{\"nbt\":null}", "{\"nbt\":\"{a:1}\"}").size());
    }

    @Test
    void substring_tests_answer_whether_a_tag_is_in_a_flattened_nbt_string() {
        String held = "{\"mainHand\":{\"nbt\":\"{Linking:{x:1}}\"}}";

        assertTrue(check("{\"mainHand.nbt\":{\"$contains\":\"Linking\"}}", held).isEmpty());
        assertEquals(1, check("{\"mainHand.nbt\":{\"$notContains\":\"Linking\"}}", held).size());
    }

    @Test
    void an_array_must_match_element_by_element() {
        assertTrue(check("{\"a\":[1,\"*\"]}", "{\"a\":[1,2]}").isEmpty());
        assertEquals(1, check("{\"a\":[1,2]}", "{\"a\":[1]}").size());
    }
}
