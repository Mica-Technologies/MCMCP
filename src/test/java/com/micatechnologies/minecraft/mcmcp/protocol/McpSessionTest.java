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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Session state: handshake, subscriptions, the outbound queue, and cancellation. */
class McpSessionTest {

    private static McpSession session() {
        return new McpSession("test-session", 1_000L);
    }

    @Test
    void isNotInitializedUntilTheNotificationArrives() {
        McpSession session = session();
        assertFalse(session.isInitialized());

        // applyInitialize stores the negotiated result but must NOT flip the flag: per spec the
        // handshake completes on notifications/initialized, and a client is allowed to hang up
        // between the response and the notification.
        session.applyInitialize(McpProtocol.VERSION_2025_06_18, new JsonObject(), new JsonObject());
        assertFalse(session.isInitialized());
        assertEquals(McpProtocol.VERSION_2025_06_18, session.getProtocolVersion());

        session.markInitialized();
        assertTrue(session.isInitialized());
    }

    @Test
    void describesTheClientFromItsInfoBlock() {
        McpSession session = session();
        JsonObject info = new JsonObject();
        info.addProperty("name", "example-client");
        info.addProperty("version", "1.4.0");
        session.applyInitialize(McpProtocol.LATEST_VERSION, info, new JsonObject());
        assertEquals("example-client 1.4.0", session.describeClient());
    }

    @Test
    void reportsUnknownForAClientThatSentNoInfo() {
        assertEquals("unknown", session().describeClient());
    }

    /** Elicitation needs both the client capability and a protocol version that has the method. */
    @Test
    void requiresBothCapabilityAndVersionForElicitation() {
        McpSession session = session();
        JsonObject capabilities = new JsonObject();
        capabilities.add("elicitation", new JsonObject());

        session.applyInitialize(McpProtocol.VERSION_2025_03_26, new JsonObject(), capabilities);
        assertFalse(session.supportsElicitation(), "2025-03-26 has no elicitation method");

        session.applyInitialize(McpProtocol.VERSION_2025_06_18, new JsonObject(), capabilities);
        assertTrue(session.supportsElicitation());

        session.applyInitialize(McpProtocol.VERSION_2025_06_18, new JsonObject(), new JsonObject());
        assertFalse(session.supportsElicitation(), "capability was not declared");
    }

    @Test
    void tracksSubscriptions() {
        McpSession session = session();
        assertFalse(session.isSubscribedTo("minecraft://client/chat/recent"));

        session.subscribe("minecraft://client/chat/recent");
        assertTrue(session.isSubscribedTo("minecraft://client/chat/recent"));
        assertEquals(1, session.getSubscriptions().size());

        session.unsubscribe("minecraft://client/chat/recent");
        assertFalse(session.isSubscribedTo("minecraft://client/chat/recent"));
    }

    @Test
    void queuesAndDrainsOutboundMessages() throws InterruptedException {
        McpSession session = session();
        assertTrue(session.enqueue(JsonRpc.notification("notifications/message", new JsonObject())));
        assertEquals(1, session.outboundBacklog());

        JsonObject drained = session.pollOutbound(100L);
        assertNotNull(drained);
        assertEquals("notifications/message", JsonRpc.getMethod(drained));
        assertEquals(0, session.outboundBacklog());
    }

    @Test
    void pollReturnsNullOnTimeoutRatherThanBlockingForever() throws InterruptedException {
        assertNull(session().pollOutbound(10L));
    }

    /**
     * A full queue drops the oldest message and keeps the newest.
     *
     * <p>This is the behaviour a client with no attached SSE stream depends on. Growing without
     * bound inside the game process is not an option, and when something has to go, the stale
     * notification is worth less than the fresh one.
     */
    @Test
    void dropsTheOldestMessageWhenTheQueueIsFull() throws InterruptedException {
        McpSession session = session();
        int capacity = 512;

        for (int i = 0; i < capacity; i++) {
            JsonObject params = new JsonObject();
            params.addProperty("seq", i);
            assertTrue(session.enqueue(JsonRpc.notification("notifications/message", params)),
                "message " + i + " should fit");
        }

        JsonObject params = new JsonObject();
        params.addProperty("seq", capacity);
        assertFalse(session.enqueue(JsonRpc.notification("notifications/message", params)),
            "the overflowing message should report that something was dropped");

        JsonObject head = session.pollOutbound(100L);
        assertNotNull(head);
        assertEquals(1, JsonRpc.getParams(head).get("seq").getAsInt(),
            "seq 0 should have been dropped, leaving seq 1 at the head");
    }

    @Test
    void completesOutboundRequestsFromMatchingResponses() throws Exception {
        McpSession session = session();
        CompletableFuture<JsonElement> future =
            session.sendRequest(McpProtocol.METHOD_ROOTS_LIST, null);

        JsonObject sent = session.pollOutbound(100L);
        assertNotNull(sent);
        JsonElement id = JsonRpc.getId(sent);
        assertNotNull(id);

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        assertTrue(session.completeOutboundRequest(JsonRpc.result(id, result)));
        assertTrue(future.get(1, TimeUnit.SECONDS).getAsJsonObject().get("ok").getAsBoolean());
    }

    @Test
    void ignoresResponsesWithUnrecognisedIds() {
        McpSession session = session();
        assertFalse(session.completeOutboundRequest(
            JsonRpc.result(new JsonPrimitive("not-ours"), new JsonObject())));
    }

    @Test
    void failsPendingOutboundRequestsWhenTheSessionCloses() {
        McpSession session = session();
        CompletableFuture<JsonElement> future =
            session.sendRequest(McpProtocol.METHOD_SAMPLING_CREATE_MESSAGE, null);

        session.close();

        ExecutionException thrown = assertThrows(ExecutionException.class,
            () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(thrown.getCause() instanceof JsonRpcException);
    }

    @Test
    void cancellationIsCooperativeAndCarriesItsReason() {
        McpSession session = session();
        JsonElement requestId = new JsonPrimitive(9);
        McpSession.CancellationToken token = session.beginRequest(requestId);

        assertFalse(token.isCancelled());
        token.throwIfCancelled();

        session.cancelRequest(requestId, "user pressed stop");
        assertTrue(token.isCancelled());

        JsonRpcException thrown = assertThrows(JsonRpcException.class, token::throwIfCancelled);
        assertEquals(JsonRpcException.REQUEST_CANCELLED, thrown.getCode());
        assertTrue(thrown.getMessage().contains("user pressed stop"));
    }

    @Test
    void cancellingAFinishedRequestIsSilentlyIgnored() {
        McpSession session = session();
        JsonElement requestId = new JsonPrimitive(9);
        McpSession.CancellationToken token = session.beginRequest(requestId);
        session.endRequest(requestId);

        session.cancelRequest(requestId, "too late");
        assertFalse(token.isCancelled());
    }
}
