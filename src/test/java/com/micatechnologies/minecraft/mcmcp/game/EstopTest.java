package com.micatechnologies.minecraft.mcmcp.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The emergency stop's state: who set it, which one wins, and who is told. */
class EstopTest {

    @AfterEach
    void clear() {
        Estop.lift();
        Estop.setRemote(null);
    }

    @Test
    void aSecondStopKeepsTheFirstStopsWhoAndWhy() {
        Estop.set("Alice", "runaway fill");
        Estop.set("agent", "me too");
        assertEquals("Alice", Estop.current().by);
        assertTrue(Estop.refusal().contains("runaway fill"));
    }

    @Test
    void theServersOwnStopWinsOverOneHeardFromAnotherServerAndLiftingItLeavesThatOne() {
        Estop.setRemote(new Estop.Stop("Bob", "remote", 1L, Estop.Source.REMOTE));
        Estop.set("Alice", "local");
        assertEquals(Estop.Source.LOCAL, Estop.current().source);
        assertTrue(Estop.lift());
        assertEquals("Bob", Estop.current().by, "a client stays stopped while its server says so");
        Estop.setRemote(null);
        assertFalse(Estop.active());
        assertFalse(Estop.lift(), "nothing left to lift");
    }

    @Test
    void listenersHearASetAndALiftButNotARepeat() {
        List<String> heard = new ArrayList<>();
        Estop.addListener((now, before) -> heard.add(now == null ? "lifted" : "set by " + now.by));
        Estop.set("Alice", "");
        Estop.set("Alice", "");
        Estop.lift();
        assertEquals(2, heard.size());
        assertEquals("set by Alice", heard.get(0));
        assertEquals("lifted", heard.get(1));
        assertNull(Estop.current());
    }
}
