package com.micatechnologies.minecraft.mcmcp.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Newline-delimited framing: what counts as a frame, and what is refused before it costs memory. */
class LinkFramingTest {

    private static InputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsOneFramePerLine() throws Exception {
        InputStream in = bytes("{\"a\":1}\n{\"b\":2}\n");

        JsonObject first = LinkFraming.readFrame(in);
        JsonObject second = LinkFraming.readFrame(in);

        assertEquals(1, first.get("a").getAsInt());
        assertEquals(2, second.get("b").getAsInt());
    }

    @Test
    void returnsNullAtEndOfStream() throws Exception {
        InputStream in = bytes("{\"a\":1}\n");
        LinkFraming.readFrame(in);

        assertNull(LinkFraming.readFrame(in), "a closed link must read as end of stream, not as a frame");
    }

    @Test
    void treatsAPartialFinalLineAsEndOfStream() throws Exception {
        // A peer that vanishes mid-frame leaves bytes with no newline. Handing those back would
        // present a truncated frame as a complete one, and the JSON would parse or not by luck.
        InputStream in = bytes("{\"a\":1}\n{\"b\":");

        LinkFraming.readFrame(in);

        assertNull(LinkFraming.readFrame(in));
    }

    @Test
    void toleratesCarriageReturnsAndSkipsBlankLines() throws Exception {
        InputStream in = bytes("{\"a\":1}\r\n\n   \n{\"b\":2}\r\n");

        JsonObject first = LinkFraming.readFrame(in);
        JsonObject second = LinkFraming.readFrame(in);

        assertEquals(1, first.get("a").getAsInt());
        assertEquals(2, second.get("b").getAsInt());
    }

    @Test
    void preservesMultiByteCharacters() throws Exception {
        // Instance names come from directory names, which are whatever the filesystem allows.
        InputStream in = bytes("{\"name\":\"módB — tëst\"}\n");

        JsonObject frame = LinkFraming.readFrame(in);

        assertEquals("módB — tëst", frame.get("name").getAsString());
    }

    @Test
    void refusesALineThatExceedsTheByteLimitBeforeBufferingIt() {
        // The guard exists so a peer that never sends a newline cannot grow a buffer inside the
        // game process until the heap is gone. It has to fire on the bytes read so far, not on a
        // completed line — by then the memory is already spent.
        StringBuilder oversized = new StringBuilder("{\"a\":\"");
        for (int i = 0; i < 200; i++) {
            oversized.append("xxxxxxxxxx");
        }
        InputStream in = bytes(oversized.toString());

        assertThrows(LinkProtocolException.class, () -> LinkFraming.readLine(in, 64));
    }

    @Test
    void countsTheLimitInBytesRatherThanCharacters() {
        // Each of these is two bytes in UTF-8. Forty characters is eighty bytes, so a sixty-four
        // byte cap must reject it — a character-counted cap would let it through and permit up to
        // four times the memory the limit appears to allow.
        StringBuilder multiByte = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            multiByte.append('é');
        }
        InputStream in = bytes(multiByte.toString());

        assertThrows(LinkProtocolException.class, () -> LinkFraming.readLine(in, 64));
    }

    @Test
    void rejectsAFrameThatIsNotAJsonObject() {
        // Arrays are valid JSON-RPC batches over HTTP but have no place here: the orchestrator
        // generates every request itself and can send them as separate frames.
        assertThrows(LinkProtocolException.class, () -> LinkFraming.readFrame(bytes("[{\"a\":1}]\n")));
        assertThrows(LinkProtocolException.class, () -> LinkFraming.readFrame(bytes("\"hello\"\n")));
    }

    @Test
    void rejectsAFrameThatIsNotValidJson() {
        assertThrows(LinkProtocolException.class, () -> LinkFraming.readFrame(bytes("{not json}\n")));
    }

    @Test
    void writesOneNewlineTerminatedLinePerFrame() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonObject frame = new JsonObject();
        frame.addProperty("a", 1);

        LinkFraming.writeFrame(out, frame);
        LinkFraming.writeFrame(out, frame);

        String written = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(2, written.split("\n", -1).length - 1, "each frame ends with exactly one newline");
        assertFalse(written.contains("\n\n"), "no blank lines between frames");
    }

    @Test
    void roundTripsAFrameThroughWriteAndRead() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "hello");
        frame.addProperty("instanceName", "módB — tëst");

        LinkFraming.writeFrame(out, frame);
        JsonObject read = LinkFraming.readFrame(new ByteArrayInputStream(out.toByteArray()));

        assertEquals("hello", read.get("type").getAsString());
        assertEquals("módB — tëst", read.get("instanceName").getAsString());
    }

    @Test
    void refusesToSendAFrameLargerThanItWouldAccept() {
        // Symmetry matters: sending something the far end is required to reject produces a dropped
        // link with the cause a whole process away from the code that caused it.
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < LinkFraming.MAX_FRAME_BYTES / 8; i++) {
            huge.append("xxxxxxxxx");
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("payload", huge.toString());

        assertThrows(IOException.class, () -> LinkFraming.writeFrame(new ByteArrayOutputStream(), frame));
    }

    @Test
    void tellsControlFramesApartFromMcpMessages() {
        // The link carries both, and they are distinguished by which field is present rather than
        // by position — that is what leaves room for a control frame after the handshake.
        JsonObject control = new JsonObject();
        control.addProperty(LinkProtocol.FIELD_TYPE, LinkProtocol.TYPE_HELLO);
        JsonObject mcp = new JsonObject();
        mcp.addProperty(LinkProtocol.FIELD_JSONRPC, "2.0");

        assertTrue(LinkFraming.isControlFrame(control));
        assertFalse(LinkFraming.isMcpMessage(control));
        assertTrue(LinkFraming.isMcpMessage(mcp));
        assertFalse(LinkFraming.isControlFrame(mcp));
    }
}
