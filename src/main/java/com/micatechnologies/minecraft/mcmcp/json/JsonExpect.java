package com.micatechnologies.minecraft.mcmcp.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Checks a tool result against what a caller said it should contain: {@code client_sequence}'s
 * per-step {@code expect}.
 *
 * <p>A subset match, because a caller names the few fields that decide whether a step worked — the
 * block placed, a key in the held item's NBT — not the whole result. The rules:
 *
 * <ul>
 *   <li>A key may be a dotted path, {@code "mainHand.item"}, as well as a nested object.</li>
 *   <li>A string, number or boolean must be equal. Numbers compare by value, so {@code 1} matches
 *       {@code 1.0}.</li>
 *   <li>{@code "*"} means present with any value; {@code null} means absent.</li>
 *   <li>An object matches as a subset again; an array must match element by element.</li>
 *   <li>{@code {"$contains": "text"}} and {@code {"$notContains": "text"}} test a string by
 *       substring. They exist for the values that arrive as one string, held-item NBT above all:
 *       "has a {@code Linking} tag" is a substring question.</li>
 * </ul>
 *
 * <p>Every mismatch is reported, not just the first: a step that placed the wrong block facing the
 * wrong way should say both.
 */
public final class JsonExpect {

    /** The wildcard: present, with any value. */
    public static final String ANY = "*";

    private static final String CONTAINS = "$contains";

    private static final String NOT_CONTAINS = "$notContains";

    private JsonExpect() {
    }

    /** Every way {@code actual} fails {@code expected}, as readable lines; empty when it matches. */
    public static List<String> mismatches(JsonObject expected, @Nullable JsonObject actual) {
        List<String> found = new ArrayList<>();
        matchObject("", expected, actual == null ? new JsonObject() : actual, found);
        return found;
    }

    private static void matchObject(String prefix, JsonObject expected, JsonObject actual,
        List<String> found) {
        for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            match(path, entry.getValue(), resolve(actual, entry.getKey()), found);
        }
    }

    /** Follows a dotted key through nested objects; null when any step is missing. */
    @Nullable
    private static JsonElement resolve(JsonObject root, String dottedKey) {
        if (root.has(dottedKey)) {
            return root.get(dottedKey);
        }
        JsonElement current = root;
        for (String part : dottedKey.split("\\.")) {
            if (current == null || !current.isJsonObject() || !current.getAsJsonObject().has(part)) {
                return null;
            }
            current = current.getAsJsonObject().get(part);
        }
        return current;
    }

    private static void match(String path, JsonElement expected, @Nullable JsonElement actual,
        List<String> found) {
        boolean absent = actual == null || actual.isJsonNull();
        if (expected == null || expected.isJsonNull()) {
            if (!absent) {
                found.add(path + ": expected absent, got " + Json.write(actual));
            }
            return;
        }
        if (expected.isJsonPrimitive() && expected.getAsJsonPrimitive().isString()
            && ANY.equals(expected.getAsString())) {
            if (absent) {
                found.add(path + ": expected present, but it is absent");
            }
            return;
        }
        if (absent) {
            found.add(path + ": expected " + Json.write(expected) + ", but it is absent");
            return;
        }
        if (expected.isJsonObject() && isSubstringTest(expected.getAsJsonObject())) {
            matchSubstring(path, expected.getAsJsonObject(), actual, found);
            return;
        }
        if (expected.isJsonObject()) {
            if (!actual.isJsonObject()) {
                found.add(path + ": expected an object, got " + Json.write(actual));
                return;
            }
            matchObject(path, expected.getAsJsonObject(), actual.getAsJsonObject(), found);
            return;
        }
        if (expected.isJsonArray()) {
            JsonArray want = expected.getAsJsonArray();
            if (!actual.isJsonArray() || actual.getAsJsonArray().size() != want.size()) {
                found.add(path + ": expected " + Json.write(expected) + ", got " + Json.write(actual));
                return;
            }
            JsonArray got = actual.getAsJsonArray();
            for (int i = 0; i < want.size(); i++) {
                match(path + "[" + i + "]", want.get(i), got.get(i), found);
            }
            return;
        }
        if (!primitiveEquals(expected.getAsJsonPrimitive(), actual)) {
            found.add(path + ": expected " + Json.write(expected) + ", got " + Json.write(actual));
        }
    }

    private static boolean isSubstringTest(JsonObject expected) {
        return expected.size() == 1
            && (expected.has(CONTAINS) || expected.has(NOT_CONTAINS));
    }

    private static void matchSubstring(String path, JsonObject expected, JsonElement actual,
        List<String> found) {
        boolean wanted = expected.has(CONTAINS);
        String needle = Json.getString(expected, wanted ? CONTAINS : NOT_CONTAINS, "");
        String haystack = actual.isJsonPrimitive() ? actual.getAsString() : Json.write(actual);
        if (haystack.contains(needle) != wanted) {
            found.add(path + ": expected " + (wanted ? "to contain " : "not to contain ")
                + Json.write(new JsonPrimitive(needle)) + ", got " + Json.write(actual));
        }
    }

    private static boolean primitiveEquals(JsonPrimitive expected, JsonElement actual) {
        if (!actual.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive got = actual.getAsJsonPrimitive();
        if (expected.isNumber() && got.isNumber()) {
            return Double.compare(expected.getAsDouble(), got.getAsDouble()) == 0;
        }
        return expected.equals(got);
    }
}
