package com.micatechnologies.minecraft.mcmcp.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ChatAttributionTest {

    private static final Set<String> ONLINE = new HashSet<>(Arrays.asList("AngelWingsPanda", "MicaAlex"));
    private static final Predicate<String> IS_ONLINE = ONLINE::contains;

    @Test
    void vanillaPlayerChatNamesItsSenderExactly() {
        ChatAttribution.Line line = ChatAttribution.attribute("chat.type.text",
            new String[] {"Stranger", "@Claude hello"}, "<Stranger> @Claude hello", IS_ONLINE);
        assertEquals("Stranger", line.player);
        assertEquals("@Claude hello", line.text);
    }

    @Test
    void aWhisperIsAttributedToItsSender() {
        ChatAttribution.Line line = ChatAttribution.attribute("commands.message.display.incoming",
            new String[] {"MicaAlex", "psst"}, "MicaAlex whispers to you: psst", IS_ONLINE);
        assertEquals("MicaAlex", line.player);
        assertEquals("psst", line.text);
    }

    @Test
    void aChatPluginsRankPrefixedLineIsAttributedToTheOnlinePlayerItNames() {
        ChatAttribution.Line line = ChatAttribution.attribute(null, new String[0],
            "§7[§bMod§7] §fAngelWingsPanda§7: the door is backwards", IS_ONLINE);
        assertEquals("AngelWingsPanda", line.player);
        assertEquals("the door is backwards", line.text);
    }

    @Test
    void angledPlainTextAndArrowSeparatorsAreBothRead() {
        assertEquals("MicaAlex", ChatAttribution.attribute(null, new String[0], "<MicaAlex> hi", IS_ONLINE).player);
        assertEquals("MicaAlex", ChatAttribution.attribute(null, new String[0], "MicaAlex » hi", IS_ONLINE).player);
    }

    @Test
    void aNameThatIsNotOnlineIsNotTakenForASender() {
        ChatAttribution.Line line = ChatAttribution.attribute(null, new String[0],
            "Server: restarting in 5 minutes", IS_ONLINE);
        assertNull(line.player);
        assertEquals("Server: restarting in 5 minutes", line.text);
    }

    @Test
    void theFirstLookAtTheTabListIsABaselineNotAFloodOfJoins() {
        Roster roster = new Roster();
        Map<String, String> first = new LinkedHashMap<>();
        first.put("MicaAlex", "u1");
        first.put("AngelWingsPanda", "u2");
        assertTrue(roster.update(first).isEmpty());

        Map<String, String> second = new LinkedHashMap<>();
        second.put("MicaAlex", "u1");
        second.put("Akselhok", "u3");
        List<Roster.Change> changes = roster.update(second);
        assertEquals(2, changes.size());
        assertEquals("AngelWingsPanda", changes.get(0).player);
        assertEquals(false, changes.get(0).joined);
        assertEquals("Akselhok", changes.get(1).player);
        assertEquals(true, changes.get(1).joined);
        assertEquals(Arrays.asList("MicaAlex", "Akselhok"), roster.online());
    }

    @Test
    void aResetRosterTakesItsNextLookAsANewBaseline() {
        Roster roster = new Roster();
        Map<String, String> one = new LinkedHashMap<>();
        one.put("MicaAlex", null);
        roster.update(one);
        roster.reset();
        Map<String, String> other = new LinkedHashMap<>();
        other.put("Akselhok", null);
        assertTrue(roster.update(other).isEmpty());
    }

    @Test
    void tabListFillerEntriesAreNotPlayerNames() {
        assertTrue(ChatAttribution.isPlayerName("Akselhok"));
        assertEquals(false, ChatAttribution.isPlayerName("§a ┃ slot 01"));
        assertEquals(false, ChatAttribution.isPlayerName(""));
    }
}
