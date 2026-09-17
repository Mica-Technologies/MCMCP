package com.micatechnologies.minecraft.mcmcp.client.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ClientInputToolsTest {

    @Test
    void aModifierComboPressesTheModifierATickBeforeTheAction() {
        assertEquals(1, ClientInputTools.comboLeadTicks(Arrays.asList("sneak", "use")));
        assertEquals(1, ClientInputTools.comboLeadTicks(Arrays.asList("attack", "sprint", "forward")));
    }

    @Test
    void keysOfOneKindArePressedTogether() {
        assertEquals(0, ClientInputTools.comboLeadTicks(Arrays.asList("sprint", "jump", "forward")));
        assertEquals(0, ClientInputTools.comboLeadTicks(Arrays.asList("use")));
    }
}
