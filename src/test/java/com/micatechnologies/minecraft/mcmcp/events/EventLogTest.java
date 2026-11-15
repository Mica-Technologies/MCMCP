package com.micatechnologies.minecraft.mcmcp.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EventLogTest {

    private static final EventLog.Waiter FOREVER = () -> true;

    private static GameEvent.Draft chat(String player, String text) {
        return GameEvent.of(GameEvent.CHAT).player(player, null).text(text);
    }

    @Test
    void twoIdenticalChatLinesAreTwoEvents() throws Exception {
        EventLog log = new EventLog();
        log.append(chat("Angel", "hi"));
        log.append(chat("Angel", "hi"));
        EventLog.Batch batch = log.await(0L, null, EventFilter.ALL, 50, 0, FOREVER);
        assertEquals(2, batch.events.size());
        assertEquals(2, batch.next);
    }

    @Test
    void aWaitWithNoCursorSeesOnlyWhatHappensAfterIt() throws Exception {
        EventLog log = new EventLog();
        log.append(chat("Angel", "before"));
        EventLog.Batch batch = log.await(null, null, EventFilter.ALL, 50, 0, FOREVER);
        assertTrue(batch.events.isEmpty());
        assertTrue(batch.timedOut);
        assertEquals(1, batch.next);
    }

    @Test
    void theCursorMovesPastEventsTheFilterDidNotMatch() throws Exception {
        EventLog log = new EventLog();
        log.append(chat("Angel", "nothing to see"));
        log.append(chat("Akselhok", "still nothing"));
        EventFilter tagged = new EventFilter(null, null, null, "@claude");
        EventLog.Batch batch = log.await(0L, null, tagged, 50, 0, FOREVER);
        assertTrue(batch.events.isEmpty());
        assertEquals(2, batch.next);
    }

    @Test
    void aFullBatchResumesAtTheFirstEventItLeftOut() throws Exception {
        EventLog log = new EventLog();
        for (int i = 0; i < 5; i++) {
            log.append(chat("Angel", "line " + i));
        }
        EventLog.Batch first = log.await(0L, null, EventFilter.ALL, 2, 0, FOREVER);
        assertEquals(2, first.events.size());
        assertEquals(2, first.next);
        EventLog.Batch second = log.await(first.next, first.stream, EventFilter.ALL, 50, 0, FOREVER);
        assertEquals(3, second.events.size());
        assertEquals(3, second.events.get(0).getSeq());
    }

    @Test
    void eventsThatFellOffTheRingAreCountedAsMissed() throws Exception {
        EventLog log = new EventLog(3, System::currentTimeMillis);
        for (int i = 0; i < 6; i++) {
            log.append(chat("Angel", "line " + i));
        }
        EventLog.Batch batch = log.await(1L, null, EventFilter.ALL, 50, 0, FOREVER);
        assertEquals(2, batch.missed);
        assertEquals(3, batch.events.size());
    }

    @Test
    void aCursorFromAnotherStreamIsAResetFromTheStartOfTheRing() throws Exception {
        EventLog log = new EventLog();
        log.append(chat("Angel", "after the restart"));
        EventLog.Batch batch = log.await(900L, "deadbeef", EventFilter.ALL, 50, 0, FOREVER);
        assertTrue(batch.reset);
        assertEquals(1, batch.events.size());
        assertEquals(0, batch.missed);
    }

    @Test
    void aCursorAheadOfTheLogIsTreatedAsAnEarlierGame() throws Exception {
        EventLog log = new EventLog();
        log.append(chat("Angel", "after the restart"));
        EventLog.Batch batch = log.await(900L, null, EventFilter.ALL, 50, 0, FOREVER);
        assertTrue(batch.reset);
        assertEquals(1, batch.events.size());
    }

    @Test
    void aWaiterWakesWhenAMatchingEventArrives() throws Exception {
        final EventLog log = new EventLog();
        Thread appender = new Thread(() -> {
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException ignored) {
                return;
            }
            log.append(chat("Angel", "ignored"));
            log.append(GameEvent.of(GameEvent.JOIN).player("Akselhok", null));
        });
        appender.start();
        long start = System.currentTimeMillis();
        EventLog.Batch batch = log.await(null, null,
            new EventFilter(Collections.singleton("join"), null, null, null), 50, 10_000, FOREVER);
        appender.join();
        assertFalse(batch.timedOut);
        assertEquals(1, batch.events.size());
        assertEquals("Akselhok", batch.events.get(0).getPlayer());
        assertTrue(System.currentTimeMillis() - start < 5_000);
    }

    @Test
    void aCancelledWaiterStopsWithoutWaitingOutItsTimeout() throws Exception {
        EventLog log = new EventLog();
        AtomicInteger asked = new AtomicInteger();
        long start = System.currentTimeMillis();
        EventLog.Batch batch = log.await(null, null, EventFilter.ALL, 50, 60_000,
            () -> asked.incrementAndGet() < 2);
        assertTrue(batch.timedOut);
        assertTrue(System.currentTimeMillis() - start < 5_000);
    }

    @Test
    void theWireFormLeavesOutWhatAnEventDoesNotHave() {
        EventLog log = new EventLog(10, () -> 1_763_215_331_000L);
        JsonObject join = log.append(GameEvent.of(GameEvent.JOIN).player("Angel", "u-1")
            .online(Arrays.asList("Mica", "Angel"))).toJson();
        assertEquals("{\"seq\":1,\"t\":\"2025-11-15T14:02:11Z\",\"type\":\"join\",\"player\":\"Angel\","
            + "\"uuid\":\"u-1\",\"online\":[\"Mica\",\"Angel\"]}", join.toString());
    }

    @Test
    void aPlayerFilterMatchesNamesWithoutRegardToCase() {
        EventFilter filter = new EventFilter(null, Collections.singleton("angelwingspanda"), null, null);
        assertTrue(filter.matches(new EventLog().append(chat("AngelWingsPanda", "hi"))));
        assertFalse(filter.matches(new EventLog().append(GameEvent.of(GameEvent.CHAT).text("server says"))));
    }

    @Test
    void anExcludedPlayerIsDroppedEvenWhenEverythingElseMatches() {
        EventFilter filter = new EventFilter(null, null, Collections.singleton("claude"), "@");
        assertFalse(filter.matches(new EventLog().append(chat("Claude", "@Angel done"))));
        assertTrue(filter.matches(new EventLog().append(chat("Angel", "@Claude go"))));
    }

    @Test
    void aPlayerLimitedToTheirOwnCommandsDoesNotSeeAnOperators() {
        EventFilter filter = EventFilter.ALL.withCommandsOnlyFrom("Builder");
        EventLog log = new EventLog();
        assertFalse(filter.matches(log.append(GameEvent.of(GameEvent.COMMAND).player("Op", null).text("/ban x"))));
        assertTrue(filter.matches(log.append(GameEvent.of(GameEvent.COMMAND).player("builder", null).text("/home"))));
        assertTrue(filter.matches(log.append(chat("Op", "hello"))));
    }

    @Test
    void aStreamQueryReadsTheSameNamesAsTheTool() {
        JsonObject arguments = EventQuery.parse("types=chat,join&contains=%40Claude&players=a&players=b&since=12");
        assertEquals("chat,join", arguments.get("types").getAsString());
        assertEquals("@Claude", arguments.get("contains").getAsString());
        assertEquals("a,b", arguments.get("players").getAsString());
        assertEquals(12L, arguments.get("since").getAsLong());
        assertFalse(EventQuery.parse("since=soon").has("since"));
    }

    @Test
    void aMisspelledTypeIsReportedRatherThanWaitedOnForever() {
        EventFilter filter = new EventFilter(Arrays.asList("chat", "joins"), null, null, null);
        assertEquals(Collections.singleton("joins"), filter.unknownTypes());
    }
}
