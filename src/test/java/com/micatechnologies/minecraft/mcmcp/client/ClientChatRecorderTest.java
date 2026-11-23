package com.micatechnologies.minecraft.mcmcp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientChatRecorderTest {

    private static List<ClientChatRecorder.Entry> buffer(String... texts) {
        List<ClientChatRecorder.Entry> entries = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            entries.add(new ClientChatRecorder.Entry(1000L * i, texts[i], "CHAT", false, null, new String[0]));
        }
        return entries;
    }

    private static List<String> texts(ClientChatRecorder.Selection selection) {
        List<String> texts = new ArrayList<>();
        for (ClientChatRecorder.Entry entry : selection.lines) {
            texts.add(entry.getText());
        }
        return texts;
    }

    @Test
    void aReadWithoutAFilterReturnsTheNewestLinesOldestOfThemFirst() {
        ClientChatRecorder.Selection selection =
            ClientChatRecorder.select(buffer("one", "two", "three", "four"), null, 2);

        assertEquals(java.util.Arrays.asList("three", "four"), texts(selection));
        assertEquals(4, selection.matched);
    }

    @Test
    void aFilterIsAppliedBeforeTheCutSoTheNewestMatchesComeBack() {
        ClientChatRecorder.Selection selection = ClientChatRecorder.select(
            buffer("Set block A", "hello", "set block B", "noise", "Set block C", "noise"), "set block", 2);

        assertEquals(java.util.Arrays.asList("set block B", "Set block C"), texts(selection));
        assertEquals(3, selection.matched);
    }

    @Test
    void askingForMoreThanIsBufferedReturnsEverythingInOrder() {
        ClientChatRecorder.Selection selection = ClientChatRecorder.select(buffer("one", "two"), null, 300);

        assertEquals(java.util.Arrays.asList("one", "two"), texts(selection));
    }

    @Test
    void aFilterNothingMatchesReturnsNothing() {
        ClientChatRecorder.Selection selection = ClientChatRecorder.select(buffer("one", "two"), "three", 5);

        assertEquals(0, selection.lines.size());
        assertEquals(0, selection.matched);
    }
}
