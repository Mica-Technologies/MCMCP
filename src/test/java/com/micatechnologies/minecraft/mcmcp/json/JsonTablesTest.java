package com.micatechnologies.minecraft.mcmcp.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.micatechnologies.minecraft.mcmcp.jobs.TestExpressions;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Big lists written as a header and rows. */
class JsonTablesTest {

    private static JsonArray hits(int n) {
        JsonArray list = new JsonArray();
        for (int i = 0; i < n; i++) {
            list.add(Json.parse("{\"x\":" + i + ",\"y\":64,\"z\":-" + i + ",\"metadata\":" + (i % 4)
                + ",\"distance\":" + (i * 1.5) + (i == 2 ? ",\"block\":\"csm:panel\"" : "") + "}"));
        }
        return list;
    }

    @Test
    void aListOfObjectsBecomesColumnsAndRowsAndComesBackTheSame() {
        JsonArray list = hits(40);
        JsonElement table = JsonTables.table(list);
        assertTrue(JsonTables.isTable(table));
        assertEquals(Json.parse("[\"x\",\"y\",\"z\",\"metadata\",\"distance\",\"block\"]"),
            table.getAsJsonObject().get("columns"));
        assertEquals(list, JsonTables.rows(table), "a key an item lacks is null in its row and absent again after");
        int before = Json.write(list).length();
        int after = Json.write(table).length();
        assertTrue(after < before * 0.6, "40 hits went from " + before + " to " + after + " bytes");
    }

    @Test
    void shortListsAndListsOfNonObjectsStayAsTheyAre() {
        JsonArray two = hits(2);
        assertSame(two, JsonTables.table(two));
        JsonArray numbers = (JsonArray) Json.parse("[1,2,3,4]");
        assertSame(numbers, JsonTables.table(numbers));
    }

    @Test
    void aScriptReadsATableAsItsRows() {
        Map<String, JsonElement> scope = new HashMap<>();
        JsonElement result = Json.parse("{\"tileEntities\":" + Json.write(JsonTables.table((JsonArray) Json.parse(
            "[{\"x\":1,\"nbt\":{\"data\":{\"open\":0}}},{\"x\":2,\"nbt\":{\"data\":{\"open\":1}}},"
                + "{\"x\":3,\"nbt\":{\"data\":{\"open\":0}}}]"))) + "}");
        scope.put("r", result);
        assertEquals(Json.parse("[1,2,3]"), TestExpressions.evaluate("r.tileEntities[*].x", scope));
        assertEquals(2, TestExpressions.evaluate("r.tileEntities[1].x", scope).getAsInt());
        assertEquals(3, TestExpressions.evaluate("len(r.tileEntities)", scope).getAsInt());
        assertTrue(TestExpressions.test(Json.parse("{\"path\":\"tileEntities[*]\",\"where\":{\"nbt.data.open\":true}}"),
            result, scope));
        assertFalse(TestExpressions.test(Json.parse("{\"path\":\"tileEntities[*]\",\"where\":{\"x\":9}}"),
            result, scope));
    }
}
