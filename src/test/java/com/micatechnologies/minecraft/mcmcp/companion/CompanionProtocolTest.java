package com.micatechnologies.minecraft.mcmcp.companion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.companion.CompanionHandshake.State;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The companion's framing, its reassembly limits, its send budget and its handshake. */
class CompanionProtocolTest {

    private static byte[] random(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static byte[] reassemble(List<byte[]> frames) throws CompanionProtocolException {
        CompanionReassembler reassembler = new CompanionReassembler(64 * 1024 * 1024, 8);
        CompanionReassembler.Message message = null;
        for (byte[] frame : frames) {
            assertNull(message, "nothing completes before the last frame");
            message = reassembler.accept(frame);
        }
        assertNotNull(message);
        return message.bytes;
    }

    @Test
    void noClientToServerFrameReachesTheSizeThatDisconnectsAPlayer() throws CompanionProtocolException {
        byte[] message = random(1_000_000, 1);
        List<byte[]> frames = CompanionFrames.encode(CompanionProtocol.TYPE_MCP, 7, message,
            CompanionProtocol.MAX_C2S_FRAME);
        for (byte[] frame : frames) {
            assertTrue(frame.length <= CompanionProtocol.MAX_C2S_FRAME);
            assertTrue(frame.length < 32_767, "vanilla refuses a client payload past 32,767 bytes");
        }
        assertArrayEquals(message, reassemble(frames));
    }

    @Test
    void aCompressibleMessageIsDeflatedAndComesBackIdentical() throws CompanionProtocolException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            text.append("{\"x\":").append(i).append(",\"block\":\"minecraft:stone\"},");
        }
        byte[] message = text.toString().getBytes(StandardCharsets.UTF_8);
        List<byte[]> frames = CompanionFrames.encode(CompanionProtocol.TYPE_MCP, 0, message,
            CompanionProtocol.S2C_FRAME);
        long sent = 0;
        for (byte[] frame : frames) {
            sent += frame.length;
        }
        assertTrue(sent < message.length / 4, "a repetitive block list compresses well: " + sent);
        assertArrayEquals(message, reassemble(frames));
    }

    @Test
    void anEmptyMessageIsStillOneFrame() throws CompanionProtocolException {
        List<byte[]> frames = CompanionFrames.encode(CompanionProtocol.TYPE_BYE, 3, new byte[0], 100);
        assertEquals(1, frames.size());
        assertEquals(0, reassemble(frames).length);
    }

    @Test
    void messagesFromOnePeerCanInterleave() throws CompanionProtocolException {
        byte[] first = random(5_000, 2);
        byte[] second = random(5_000, 3);
        List<byte[]> a = CompanionFrames.encode(CompanionProtocol.TYPE_MCP, 1, first, 1_000);
        List<byte[]> b = CompanionFrames.encode(CompanionProtocol.TYPE_MCP, 2, second, 1_000);
        CompanionReassembler reassembler = new CompanionReassembler(1_000_000, 4);
        List<CompanionReassembler.Message> done = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            for (List<byte[]> frames : Arrays.asList(a, b)) {
                if (i < frames.size()) {
                    CompanionReassembler.Message message = reassembler.accept(frames.get(i));
                    if (message != null) {
                        done.add(message);
                    }
                }
            }
        }
        assertEquals(2, done.size());
        assertArrayEquals(first, done.get(0).bytes);
        assertArrayEquals(second, done.get(1).bytes);
    }

    @Test
    void aMessagePastTheSizeLimitIsRefusedBeforeItIsHeld() {
        List<byte[]> frames = CompanionFrames.encode(CompanionProtocol.TYPE_MCP, 0, random(50_000, 4), 1_000);
        CompanionReassembler reassembler = new CompanionReassembler(10_000, 4);
        assertThrows(CompanionProtocolException.class, () -> {
            for (byte[] frame : frames) {
                reassembler.accept(frame);
            }
        });
        assertEquals(0, reassembler.openStreams(), "the refused message is not kept");
    }

    @Test
    void aSmallCompressedMessageCannotExpandPastTheLimit() {
        byte[] zeros = new byte[5_000_000];
        List<byte[]> frames = CompanionFrames.encode(CompanionProtocol.TYPE_MCP, 0, zeros, 32_000);
        assertEquals(1, frames.size(), "five megabytes of zeros deflate into one frame");
        CompanionReassembler reassembler = new CompanionReassembler(1_000_000, 4);
        assertThrows(CompanionProtocolException.class, () -> reassembler.accept(frames.get(0)));
    }

    @Test
    void tooManyMessagesAtOnceAreRefused() throws CompanionProtocolException {
        CompanionReassembler reassembler = new CompanionReassembler(1_000_000, 2);
        reassembler.accept(CompanionFrames.encode(CompanionProtocol.TYPE_MCP, 1, random(3_000, 5), 1_000).get(0));
        reassembler.accept(CompanionFrames.encode(CompanionProtocol.TYPE_MCP, 2, random(3_000, 6), 1_000).get(0));
        byte[] third = CompanionFrames.encode(CompanionProtocol.TYPE_MCP, 3, random(3_000, 7), 1_000).get(0);
        assertThrows(CompanionProtocolException.class, () -> reassembler.accept(third));
    }

    @Test
    void framesOutOfOrderOrMidStreamAreRefused() {
        List<byte[]> frames = CompanionFrames.encode(CompanionProtocol.TYPE_MCP, 9, random(5_000, 8), 1_000);
        CompanionReassembler skipped = new CompanionReassembler(1_000_000, 4);
        assertThrows(CompanionProtocolException.class, () -> {
            skipped.accept(frames.get(0));
            skipped.accept(frames.get(2));
        });
        CompanionReassembler midStream = new CompanionReassembler(1_000_000, 4);
        assertThrows(CompanionProtocolException.class, () -> midStream.accept(frames.get(1)));
    }

    @Test
    void garbageIsRefusedNotThrownAsSomethingElse() {
        CompanionReassembler reassembler = new CompanionReassembler(1_000, 4);
        assertThrows(CompanionProtocolException.class, () -> reassembler.accept(new byte[] {3}));
        assertThrows(CompanionProtocolException.class,
            () -> reassembler.accept(new byte[] {3, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
    }

    // ------------------------------------------------------------------
    // Outbox
    // ------------------------------------------------------------------

    @Test
    void theOutboxSendsABudgetPerTickAndAlwaysAtLeastOneFrame() {
        CompanionOutbox outbox = new CompanionOutbox(1_000);
        assertTrue(outbox.offer(CompanionProtocol.TYPE_MCP, random(10_000, 9), Long.MAX_VALUE));
        List<byte[]> sent = new ArrayList<>();
        long first = outbox.drain(2_500, sent::add);
        assertTrue(first <= 2_500);
        assertEquals(2, sent.size());
        long tiny = outbox.drain(10, sent::add);
        assertTrue(tiny > 0, "a budget smaller than a frame still sends one");
        outbox.drain(Long.MAX_VALUE, sent::add);
        assertEquals(0, outbox.queuedBytes());
    }

    @Test
    void theOutboxRefusesAMessageThatWouldOverfillIt() {
        CompanionOutbox outbox = new CompanionOutbox(1_000);
        assertTrue(outbox.offer(CompanionProtocol.TYPE_MCP, random(3_000, 10), 5_000));
        assertFalse(outbox.offer(CompanionProtocol.TYPE_MCP, random(3_000, 11), 5_000));
        assertTrue(outbox.queuedBytes() < 5_000, "nothing of the refused message was queued");
    }

    // ------------------------------------------------------------------
    // Handshake and access
    // ------------------------------------------------------------------

    @Test
    void aWelcomeSaysWhatTheClientMayDo() {
        JsonObject granted = CompanionHandshake.parse(CompanionHandshake.granted("v1", Collections.singletonList("read")));
        assertEquals(State.AVAILABLE, CompanionHandshake.stateOf(granted));
        JsonObject refused = CompanionHandshake.parse(CompanionHandshake.refused("v1", "not on the allowlist"));
        assertEquals(State.UNAUTHORISED, CompanionHandshake.stateOf(refused));
        JsonObject newer = CompanionHandshake.parse(CompanionHandshake.granted("v9", Collections.emptyList()));
        newer.addProperty("protocol", CompanionProtocol.VERSION + 1);
        assertEquals(State.INCOMPATIBLE, CompanionHandshake.stateOf(newer));
        assertEquals(State.ABSENT, CompanionHandshake.stateOf(null));
    }

    @Test
    void anEmptyAllowlistNamesNobodyAndEntriesMatchByUuidOrName() {
        UUID id = UUID.fromString("d04d5aaa-c6c9-386f-97c8-ff8571aa906a");
        assertFalse(CompanionAccess.isAllowlisted(id, "McmcpDev", Collections.emptyList()));
        assertFalse(CompanionAccess.isAllowlisted(id, "McmcpDev", Collections.singletonList("")));
        assertTrue(CompanionAccess.isAllowlisted(id, "McmcpDev",
            Collections.singletonList("D04D5AAA-C6C9-386F-97C8-FF8571AA906A")));
        assertTrue(CompanionAccess.isAllowlisted(id, "McmcpDev", Collections.singletonList(" mcmcpdev ")));
        assertFalse(CompanionAccess.isAllowlisted(id, "McmcpDev", Collections.singletonList("someone-else")));
    }
}
