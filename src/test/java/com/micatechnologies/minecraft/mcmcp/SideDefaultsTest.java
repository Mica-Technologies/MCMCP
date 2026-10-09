package com.micatechnologies.minecraft.mcmcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What a freshly generated config allows, on a dedicated server and everywhere else. */
class SideDefaultsTest {

    @Test
    void aDedicatedServerOpensNothingAndDialsNothingByDefault() {
        SideDefaults server = new SideDefaults(true, false);
        assertTrue(server.secureServer());
        assertFalse(server.serverEndpoint(), "no MCP port");
        assertFalse(server.orchestratorLink(), "no outbound link");
        assertFalse(server.endpointCommands());
        assertFalse(server.endpointProcessControl());
        assertTrue(server.companion(), "on, but its empty allowlist lets nobody in");
        assertTrue(server.journal(), "the server keeps its audit trail");
    }

    @Test
    void aClientKeepsItsDefaultsAndHasNoCompanionToOffer() {
        SideDefaults client = new SideDefaults(false, false);
        assertFalse(client.secureServer());
        assertFalse(client.serverEndpoint());
        assertTrue(client.orchestratorLink());
        assertTrue(client.endpointCommands());
        assertFalse(client.companion());
        assertFalse(client.journal(), "recording is opt-in on a client");
    }

    @Test
    void aDevServerKeepsTheEndpointItIsDrivenThrough() {
        SideDefaults dev = new SideDefaults(true, true);
        assertFalse(dev.secureServer());
        assertTrue(dev.serverEndpoint());
        assertTrue(dev.orchestratorLink());
        assertTrue(dev.endpointCommands());
    }
}
