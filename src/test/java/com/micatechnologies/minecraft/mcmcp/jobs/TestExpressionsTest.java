package com.micatechnologies.minecraft.mcmcp.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The glue a building test script is written in. */
class TestExpressionsTest {

    private static Map<String, JsonElement> scope() {
        Map<String, JsonElement> scope = new HashMap<>();
        scope.put("c", Json.parse("{\"x\":171,\"y\":63,\"z\":-6227}"));
        scope.put("ctl", Json.parse("{\"blocks\":[{\"y\":63},{\"y\":75},{\"y\":210}]}"));
        scope.put("arrive", Json.parse("{\"met\":true,\"seconds\":8.25}"));
        scope.put("doors", Json.parse("{\"met\":false}"));
        return scope;
    }

    @Test
    void aWholeTemplateKeepsItsTypeAndAPartOfOneBecomesText() {
        assertEquals(Json.parse("64"), TestExpressions.resolve(Json.parse("\"${c.y + 1}\""), scope()));
        assertEquals(Json.parse("[63,75,210]"), TestExpressions.resolve(Json.parse("\"${ctl.blocks[*].y}\""), scope()));
        assertEquals(Json.parse("\"setblock 171 63 -6227 minecraft:redstone_block\""), TestExpressions.resolve(
            Json.parse("\"setblock ${c.x} ${c.y} ${c.z} minecraft:redstone_block\""), scope()));
        assertEquals(Json.parse("{\"args\":{\"y\":210,\"tag\":\"at 210\"}}"), TestExpressions.resolve(
            Json.parse("{\"args\":{\"y\":\"${ctl.blocks[-1].y}\",\"tag\":\"at ${max(ctl.blocks[*].y)}\"}}"), scope()));
    }

    @Test
    void expressionsHaveArithmeticComparisonsLogicAndFunctions() {
        assertEquals(true, TestExpressions.evaluate("arrive.met && !doors.met", scope()).getAsBoolean());
        assertEquals(false, TestExpressions.evaluate("arrive.seconds > 10 || doors.met", scope()).getAsBoolean());
        assertEquals(3, TestExpressions.evaluate("len(ctl.blocks)", scope()).getAsInt());
        assertEquals(8.3, TestExpressions.evaluate("round(arrive.seconds, 1)", scope()).getAsDouble(), 1e-9);
        assertEquals(-2, TestExpressions.evaluate("(c.y - 65) * 1", scope()).getAsInt());
        assertEquals("car A", TestExpressions.evaluate("'car ' + 'A'", scope()).getAsString());
    }

    @Test
    void aLookupTableTurnsAFacingIntoTheCellBehindIt() {
        Map<String, JsonElement> scope = scope();
        scope.put("face", Json.parse("\"e\""));
        scope.put("BACK", Json.parse("{\"n\":[0,1],\"s\":[0,-1],\"e\":[-1,0],\"w\":[1,0]}"));
        assertEquals(170, TestExpressions.evaluate("c.x + BACK[face][0]", scope).getAsInt());
        assertEquals(-1, TestExpressions.evaluate("{n: [0, 1], e: [-1, 0]}[face][0]", scope).getAsInt());
        assertEquals("east", TestExpressions.evaluate("['south', 'west', 'north', 'east'][7 % 4]", scope).getAsString());
        assertEquals(3, TestExpressions.evaluate("meta('csm:firealarmcontrolpanel:3')", scope).getAsInt());
        assertEquals(0, TestExpressions.evaluate("meta('minecraft:stone')", scope).getAsInt());
    }

    @Test
    void positionArraysIndexAfterAWildcardAndListsSortSliceAndRotate() {
        Map<String, JsonElement> scope = scope();
        scope.put("r", Json.parse("{\"positions\":[[1,210,2,0],[1,63,2,0],[1,75,2,0],[1,63,2,0]]}"));
        assertEquals(Json.parse("[63,75,210]"), TestExpressions.evaluate("sort(unique(r.positions[*][1]))", scope));
        assertEquals(63, TestExpressions.evaluate("min(r.positions[*][1])", scope).getAsInt());
        assertEquals(Json.parse("[75,210,63]"), TestExpressions.evaluate(
            "concat(slice(sort(unique(r.positions[*][1])), 1), slice(sort(unique(r.positions[*][1])), 0, 1))", scope));
        scope.put("pulls", Json.parse("[{\"x\":1,\"distance\":9.5},{\"x\":2,\"distance\":3.25}]"));
        assertEquals(2, TestExpressions.evaluate("minBy(pulls, 'distance').x", scope).getAsInt());
        assertEquals(1, TestExpressions.evaluate("maxBy(pulls, 'distance').x", scope).getAsInt());
    }

    @Test
    void anUnknownVariableIsAnErrorThatNamesTheKnownOnes() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> TestExpressions.evaluate("cc.y + 1", scope()));
        assertTrue(error.getMessage().contains("No variable 'cc'"), error.getMessage());
        assertTrue(error.getMessage().contains("ctl"), error.getMessage());
    }

    @Test
    void aCarIsThereWhenAnyPanelSitsOneOrTwoAboveTheLanding() {
        JsonElement panels = Json.parse("{\"blocks\":[{\"x\":171,\"y\":12,\"z\":0},{\"x\":171,\"y\":65,\"z\":0}]}");
        assertTrue(TestExpressions.test(Json.parse("{\"path\":\"blocks[*].y\",\"anyIn\":[\"${c.y+1}\",\"${c.y+2}\"]}"),
            panels, scope()));
        assertFalse(TestExpressions.test(Json.parse("{\"path\":\"blocks[*].y\",\"anyIn\":[\"${c.y+20}\"]}"),
            panels, scope()));
    }

    @Test
    void doorsAreOpenWhenATileEntityMatchesEveryFieldAndAByteFlagIsTrue() {
        JsonElement doors = Json.parse("{\"tileEntities\":[{\"nbt\":{\"controllerX\":171,\"controllerY\":75,\"open\":1}},"
            + "{\"nbt\":{\"controllerX\":171,\"controllerY\":63,\"open\":\"1b\"}}]}");
        assertTrue(TestExpressions.test(Json.parse("{\"path\":\"tileEntities[*]\",\"where\":{\"nbt.controllerX\":\"${c.x}\","
            + "\"nbt.controllerY\":\"${c.y}\",\"nbt.open\":true}}"), doors, scope()));
        assertFalse(TestExpressions.test(Json.parse("{\"path\":\"tileEntities[*]\",\"where\":{\"nbt.controllerY\":210}}"),
            doors, scope()));
    }

    @Test
    void aPanelIsResetWhenItsFlagEqualsZeroAndAStringConditionSeesTheResult() {
        JsonElement panel = Json.parse("{\"nbt\":{\"a\":0}}");
        assertTrue(TestExpressions.test(Json.parse("{\"path\":\"nbt.a\",\"equals\":0}"), panel, scope()));
        assertTrue(TestExpressions.test(Json.parse("\"${result.nbt.a == 0}\""), panel, scope()));
        assertFalse(TestExpressions.test(Json.parse("{\"path\":\"nbt.missing\"}"), panel, scope()));
        assertTrue(TestExpressions.test(Json.parse("{\"path\":\"nbt.missing\",\"none\":true}"), panel, scope()));
    }
}
