package com.micatechnologies.minecraft.mcmcp.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** How a direct endpoint recognises an orchestrator-style 'instance', and when it steers callers to the orchestrator. */
class EndpointAddressTest {

    private static EndpointAddress address(AtomicBoolean linked) {
        return new EndpointAddress("altotest-c1d0ee", "client", () -> "AltoTEST", linked::get);
    }

    @Test
    void anInstanceNamingThisEndpointIsAccepted() {
        EndpointAddress address = address(new AtomicBoolean(false));
        assertTrue(address.answersTo("altotest-c1d0ee.client"));
        assertTrue(address.answersTo("*"));
        assertTrue(address.answersTo("altotest-c1d0ee"));
        assertTrue(address.answersTo("alTOtest"), "the human-assigned label, case-insensitively");
    }

    @Test
    void anInstanceNamingAnotherGameOrTheOtherSideIsRefused() {
        EndpointAddress address = address(new AtomicBoolean(false));
        assertFalse(address.answersTo("atm9-3f2a1c.client"));
        assertFalse(address.answersTo("altotest-c1d0ee.server"));
        assertTrue(address.describeMismatch("atm9-3f2a1c.client").contains("Nothing was done"));
    }

    @Test
    void anEndpointWithNoIdentityStillAcceptsEveryInstance() {
        assertTrue(EndpointAddress.NONE.answersTo("*"));
        assertFalse(EndpointAddress.NONE.answersTo("anything.client"));
    }

    @Test
    void theSteeringNoteAppearsOnlyWhileTheOrchestratorLinkIsUp() {
        AtomicBoolean linked = new AtomicBoolean(false);
        EndpointAddress address = address(linked);
        assertNull(address.steeringNote());

        linked.set(true);
        String note = address.steeringNote();
        assertNotNull(note);
        assertTrue(note.contains("instance: 'altotest-c1d0ee.client'"), note);
    }

    @Test
    void aDirectSessionIsSteeredOnceAndTheLinkSessionNever() {
        McpSession direct = new McpSession("direct", 0L, false);
        assertTrue(direct.claimSteeringNote());
        assertFalse(direct.claimSteeringNote(), "the note costs tokens; once is enough");

        McpSession link = new McpSession("link", 0L, true);
        assertFalse(link.claimSteeringNote());
    }
}
