package com.micatechnologies.minecraft.mcmcp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClientIdentificationTest {

    @Test
    void putsTheInstanceNameInTheWindowTitle() {
        assertEquals("Minecraft 1.12.2 - MCMCP [builder-2]",
            ClientIdentification.windowTitle("builder-2"));
    }

    @Test
    void aLabelFromTheOrchestratorWinsOverTheConfiguredName() {
        assertEquals("mymod dev", ClientIdentification.displayName("mymod dev", "ATM9 - Copy (2)"));
    }

    @Test
    void theConfiguredNameIsShownUntilAnOrchestratorNamesTheInstance() {
        assertEquals("ATM9 - Copy (2)", ClientIdentification.displayName(null, "ATM9 - Copy (2)"));
        assertEquals("ATM9 - Copy (2)", ClientIdentification.displayName("   ", "ATM9 - Copy (2)"));
    }
}
