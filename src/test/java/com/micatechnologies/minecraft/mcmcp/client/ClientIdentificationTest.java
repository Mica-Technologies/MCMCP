package com.micatechnologies.minecraft.mcmcp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClientIdentificationTest {

    @Test
    void putsTheConfiguredInstanceNameInTheWindowTitle() {
        assertEquals("Minecraft 1.12.2 - MCMCP [builder-2]",
            ClientIdentification.windowTitle("builder-2"));
    }
}
