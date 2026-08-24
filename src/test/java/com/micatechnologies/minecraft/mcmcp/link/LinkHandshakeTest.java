package com.micatechnologies.minecraft.mcmcp.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.McmcpIdentity;
import org.junit.jupiter.api.Test;

/** The opening exchange: what hello promises to carry, and how each reply is read. */
class LinkHandshakeTest {

    private static McmcpIdentity identity() {
        return new McmcpIdentity("modb-dev-3f2a1c", "0123456789abcdef", "mod B dev");
    }

    private static JsonObject welcome() {
        JsonObject frame = new JsonObject();
        frame.addProperty(LinkProtocol.FIELD_TYPE, LinkProtocol.TYPE_WELCOME);
        frame.addProperty(LinkProtocol.FIELD_LINK_PROTOCOL, LinkProtocol.VERSION);
        return frame;
    }

    private static JsonObject rejection(String reason) {
        JsonObject frame = new JsonObject();
        frame.addProperty(LinkProtocol.FIELD_TYPE, LinkProtocol.TYPE_REJECTED);
        frame.addProperty(LinkProtocol.FIELD_REASON, reason);
        return frame;
    }

    @Test
    void helloIdentifiesTheInstanceAndTheGameBehindIt() {
        JsonObject hello = LinkHandshake.hello(identity(), "client", "E:\\instances\\modB",
            "2026.08.24", "1.12.2", "http://127.0.0.1:25585/mcp");

        assertEquals(LinkProtocol.TYPE_HELLO, hello.get(LinkProtocol.FIELD_TYPE).getAsString());
        assertEquals(LinkProtocol.VERSION, hello.get(LinkProtocol.FIELD_LINK_PROTOCOL).getAsInt());
        assertEquals("modb-dev-3f2a1c", hello.get(LinkProtocol.FIELD_INSTANCE_ID).getAsString());
        assertEquals("0123456789abcdef", hello.get(LinkProtocol.FIELD_INSTANCE_SECRET).getAsString());

        // These four are what an approval dialog shows a human. Without them the prompt reads
        // "instance modb-dev-3f2a1c wants to connect", which nobody can answer.
        assertEquals("mod B dev", hello.get(LinkProtocol.FIELD_INSTANCE_NAME).getAsString());
        assertEquals("client", hello.get(LinkProtocol.FIELD_SIDE).getAsString());
        assertEquals("E:\\instances\\modB", hello.get(LinkProtocol.FIELD_GAME_DIRECTORY).getAsString());
        assertEquals("1.12.2", hello.get(LinkProtocol.FIELD_MINECRAFT_VERSION).getAsString());
    }

    @Test
    void helloOmitsOptionalFieldsRatherThanSendingEmptyStrings() {
        // A dedicated server has no client endpoint URL, and a game directory can fail to resolve.
        // Absent and empty mean different things to whoever reads the roster.
        JsonObject hello = LinkHandshake.hello(identity(), "server", null, "2026.08.24", "1.12.2", null);

        assertFalse(hello.has(LinkProtocol.FIELD_GAME_DIRECTORY));
        assertFalse(hello.has(LinkProtocol.FIELD_ENDPOINT_URL));
    }

    @Test
    void acceptsAWelcomeAndReportsWhoAnswered() throws Exception {
        JsonObject frame = welcome();
        JsonObject orchestrator = new JsonObject();
        orchestrator.addProperty("name", "MCMCP Orchestrator");
        orchestrator.addProperty("version", "0.1.0");
        frame.add(LinkProtocol.FIELD_ORCHESTRATOR, orchestrator);

        LinkHandshake.Result result = LinkHandshake.parseResponse(frame);

        assertTrue(result.isAccepted());
        assertEquals("MCMCP Orchestrator", result.getOrchestratorName());
        assertEquals("0.1.0", result.getOrchestratorVersion());
        assertTrue(result.describe().contains("MCMCP Orchestrator 0.1.0"));
    }

    @Test
    void aWelcomeNeedNotNameTheOrchestrator() throws Exception {
        LinkHandshake.Result result = LinkHandshake.parseResponse(welcome());

        assertTrue(result.isAccepted());
        assertEquals("orchestrator", result.getOrchestratorName());
    }

    @Test
    void aLabelTypedIntoTheOrchestratorComesBackOnTheWelcome() throws Exception {
        // The config value is a guess derived from a folder name; the roster value was typed by a
        // person on purpose, so it wins for display.
        JsonObject frame = welcome();
        frame.addProperty(LinkProtocol.FIELD_INSTANCE_NAME, "control instance");

        LinkHandshake.Result result = LinkHandshake.parseResponse(frame);

        assertEquals("control instance", result.getAssignedName());
    }

    @Test
    void anAbsentOrBlankAssignedNameIsNullRatherThanEmpty() throws Exception {
        assertNull(LinkHandshake.parseResponse(welcome()).getAssignedName());

        JsonObject blank = welcome();
        blank.addProperty(LinkProtocol.FIELD_INSTANCE_NAME, "   ");
        assertNull(LinkHandshake.parseResponse(blank).getAssignedName());
    }

    @Test
    void readsARejectionWithoutTreatingItAsAFailureToParse() throws Exception {
        // A rejection is a successful handshake with an unwelcome answer. Throwing here would
        // collapse "you are not approved yet" into the same bucket as a corrupt frame, and the two
        // want opposite responses.
        LinkHandshake.Result result = LinkHandshake.parseResponse(rejection(LinkProtocol.REASON_PENDING_APPROVAL));

        assertFalse(result.isAccepted());
        assertEquals(LinkProtocol.REASON_PENDING_APPROVAL, result.getReason());
    }

    @Test
    void waitingForApprovalIsRetryableAndAMismatchedSecretIsNot() throws Exception {
        assertTrue(LinkHandshake.parseResponse(rejection(LinkProtocol.REASON_PENDING_APPROVAL)).isRetryable(),
            "an approval dialog on screen is the normal case; the instance must keep waiting");
        assertFalse(LinkHandshake.parseResponse(rejection(LinkProtocol.REASON_SECRET_MISMATCH)).isRetryable(),
            "retrying a bad secret helps neither a rotated secret nor an impersonation attempt");
    }

    @Test
    void refusesToProceedWhenTheReplyIsNeitherWelcomeNorRejection() {
        // Serving tool calls to something that never completed a handshake means serving them to
        // something unauthenticated.
        JsonObject mcpMessage = new JsonObject();
        mcpMessage.addProperty(LinkProtocol.FIELD_JSONRPC, "2.0");
        assertThrows(LinkProtocolException.class, () -> LinkHandshake.parseResponse(mcpMessage));

        JsonObject unknownType = new JsonObject();
        unknownType.addProperty(LinkProtocol.FIELD_TYPE, "greetings");
        assertThrows(LinkProtocolException.class, () -> LinkHandshake.parseResponse(unknownType));
    }

    @Test
    void treatsAClosedLinkAsAFailedHandshake() {
        assertThrows(LinkProtocolException.class, () -> LinkHandshake.parseResponse(null));
    }
}
