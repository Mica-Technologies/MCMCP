package com.micatechnologies.minecraft.mcmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Protocol version negotiation and the pre-initialize method gate. */
class McpProtocolTest {

    @Test
    void echoesASupportedVersionBack() {
        assertEquals(McpProtocol.VERSION_2025_03_26,
            McpProtocol.negotiate(McpProtocol.VERSION_2025_03_26));
        assertEquals(McpProtocol.VERSION_2024_11_05,
            McpProtocol.negotiate(McpProtocol.VERSION_2024_11_05));
    }

    /**
     * An unknown version is answered with our latest, not rejected.
     *
     * <p>Per spec this is a negotiation outcome, not an error: the client then decides whether to
     * proceed. Failing the request here would break clients that would happily have downgraded, and
     * would break every future client whose version we have not heard of.
     */
    @Test
    void answersUnknownVersionsWithTheLatestSupported() {
        assertEquals(McpProtocol.LATEST_VERSION, McpProtocol.negotiate("2099-01-01"));
        assertEquals(McpProtocol.LATEST_VERSION, McpProtocol.negotiate(null));
        assertEquals(McpProtocol.LATEST_VERSION, McpProtocol.negotiate("nonsense"));
    }

    @Test
    void gatesStructuredContentOnTheNegotiatedVersion() {
        assertTrue(McpProtocol.supportsToolStructuredContent(McpProtocol.VERSION_2025_06_18));
        assertFalse(McpProtocol.supportsToolStructuredContent(McpProtocol.VERSION_2025_03_26));
        assertFalse(McpProtocol.supportsToolStructuredContent(McpProtocol.VERSION_2024_11_05));
    }

    @Test
    void gatesElicitationOnTheNegotiatedVersion() {
        assertTrue(McpProtocol.supportsElicitation(McpProtocol.VERSION_2025_06_18));
        assertFalse(McpProtocol.supportsElicitation(McpProtocol.VERSION_2025_03_26));
    }

    /** Batching existed only in 2025-03-26; it was added after 2024-11-05 and removed in 2025-06-18. */
    @Test
    void allowsBatchingOnlyOnTheOneVersionThatHadIt() {
        assertTrue(McpProtocol.supportsBatching(McpProtocol.VERSION_2025_03_26));
        assertFalse(McpProtocol.supportsBatching(McpProtocol.VERSION_2025_06_18));
        assertFalse(McpProtocol.supportsBatching(McpProtocol.VERSION_2024_11_05));
    }

    @Test
    void permitsOnlyInitializeAndPingBeforeTheHandshakeCompletes() {
        assertTrue(McpProtocol.isAllowedBeforeInitialize(McpProtocol.METHOD_INITIALIZE));
        assertTrue(McpProtocol.isAllowedBeforeInitialize(McpProtocol.METHOD_PING));
        assertFalse(McpProtocol.isAllowedBeforeInitialize(McpProtocol.METHOD_TOOLS_CALL));
        assertFalse(McpProtocol.isAllowedBeforeInitialize(McpProtocol.METHOD_RESOURCES_READ));
        assertFalse(McpProtocol.isAllowedBeforeInitialize(null));
    }

    @Test
    void identifiesTheNotificationNamespace() {
        assertTrue(McpProtocol.isNotification(McpProtocol.NOTIFICATION_INITIALIZED));
        assertTrue(McpProtocol.isNotification(McpProtocol.NOTIFICATION_RESOURCES_UPDATED));
        assertFalse(McpProtocol.isNotification(McpProtocol.METHOD_TOOLS_LIST));
    }

    @Test
    void listsTheLatestVersionFirst() {
        assertEquals(McpProtocol.LATEST_VERSION, McpProtocol.supportedVersions().get(0));
    }
}
