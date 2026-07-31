package com.micatechnologies.minecraft.mcmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import org.junit.jupiter.api.Test;

/**
 * JSON-RPC envelope handling.
 *
 * <p>These run headless. Nothing in {@code protocol} imports a Minecraft class, which is what makes
 * the wire format testable at all — the alternative would be asserting protocol behaviour through a
 * running game, which is slow enough that it would not get done.
 */
class JsonRpcTest {

    private static JsonObject parse(String text) {
        JsonElement element = Json.parse(text);
        assertNotNull(element, "expected the fixture to be valid JSON");
        return element.getAsJsonObject();
    }

    @Test
    void classifiesRequestsNotificationsAndResponses() {
        JsonObject request = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertTrue(JsonRpc.isRequest(request));
        assertFalse(JsonRpc.isNotification(request));
        assertFalse(JsonRpc.isResponse(request));

        JsonObject notification =
            parse("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertFalse(JsonRpc.isRequest(notification));
        assertTrue(JsonRpc.isNotification(notification));

        JsonObject response = parse("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}");
        assertTrue(JsonRpc.isResponse(response));
        assertFalse(JsonRpc.isRequest(response));
    }

    /**
     * A request whose id is explicitly null is malformed, not a notification.
     *
     * <p>Worth pinning down: treating it as a notification would silently swallow a real request and
     * leave the client waiting forever for a response that was never going to come.
     */
    @Test
    void treatsExplicitNullIdAsNotARequest() {
        JsonObject message = parse("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}");
        assertFalse(JsonRpc.isRequest(message));
        assertTrue(JsonRpc.isNotification(message));
        assertNull(JsonRpc.getId(message));
    }

    /**
     * String and numeric ids are distinct and must round-trip unchanged.
     *
     * <p>JSON-RPC allows either, and {@code "1"} and {@code 1} are different requests. Normalising
     * them into a Java type is the classic way to mismatch a response to its request.
     */
    @Test
    void preservesIdTypeExactly() {
        JsonObject numeric = parse("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"ping\"}");
        JsonObject stringly = parse("{\"jsonrpc\":\"2.0\",\"id\":\"42\",\"method\":\"ping\"}");

        JsonElement numericId = JsonRpc.getId(numeric);
        JsonElement stringId = JsonRpc.getId(stringly);
        assertNotNull(numericId);
        assertNotNull(stringId);
        assertTrue(numericId.getAsJsonPrimitive().isNumber());
        assertTrue(stringId.getAsJsonPrimitive().isString());

        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":42,\"result\":{}}",
            Json.write(JsonRpc.result(numericId, new JsonObject())));
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"42\",\"result\":{}}",
            Json.write(JsonRpc.result(stringId, new JsonObject())));
    }

    @Test
    void absentParamsReadsAsAnEmptyObject() {
        JsonObject message = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        JsonObject params = JsonRpc.getParams(message);
        assertNotNull(params);
        assertEquals(0, params.size());
    }

    /** Positional params are legal JSON-RPC but never used by MCP; they must not blow up a handler. */
    @Test
    void arrayParamsReadAsEmptyRatherThanThrowing() {
        JsonObject message = parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\",\"params\":[1,2]}");
        assertEquals(0, JsonRpc.getParams(message).size());
    }

    @Test
    void rejectsEnvelopesWithoutTheVersionTag() {
        JsonRpcException error = assertThrows(JsonRpcException.class,
            () -> JsonRpc.validateEnvelope(parse("{\"id\":1,\"method\":\"ping\"}")));
        assertEquals(JsonRpcException.INVALID_REQUEST, error.getCode());
    }

    @Test
    void rejectsEnvelopesWithTheWrongVersion() {
        JsonRpcException error = assertThrows(JsonRpcException.class,
            () -> JsonRpc.validateEnvelope(parse("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}")));
        assertEquals(JsonRpcException.INVALID_REQUEST, error.getCode());
    }

    @Test
    void rejectsEnvelopesWithNoMethodResultOrError() {
        assertThrows(JsonRpcException.class,
            () -> JsonRpc.validateEnvelope(parse("{\"jsonrpc\":\"2.0\",\"id\":1}")));
    }

    @Test
    void acceptsAWellFormedResponse() {
        JsonRpc.validateEnvelope(parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"));
    }

    @Test
    void buildsErrorResponsesWithCodeAndMessage() {
        JsonObject error = JsonRpc.error(new JsonPrimitive(3),
            JsonRpcException.methodNotFound("nope/nope"));
        JsonObject body = error.getAsJsonObject("error");
        assertEquals(JsonRpcException.METHOD_NOT_FOUND, body.get("code").getAsInt());
        assertTrue(body.get("message").getAsString().contains("nope/nope"));
    }

    /** Notifications carry no id at all — not an id of null. */
    @Test
    void notificationsOmitTheIdField() {
        JsonObject notification = JsonRpc.notification("notifications/progress", new JsonObject());
        assertFalse(notification.has("id"));
        assertEquals("2.0", notification.get("jsonrpc").getAsString());
    }
}
