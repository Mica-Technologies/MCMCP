package com.micatechnologies.minecraft.mcmcp.link;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

/**
 * Newline-delimited JSON framing for the orchestrator link.
 *
 * <h2>Why bytes rather than a Reader</h2>
 *
 * This works directly on streams and decodes UTF-8 itself, instead of wrapping the socket in a
 * {@code BufferedReader} and calling {@code readLine}. Two reasons, and the second is the real one:
 *
 * <ol>
 *   <li>{@code readLine} has no size bound. A peer that opens a connection and sends bytes without
 *       ever sending a newline would grow a {@code StringBuilder} until the game runs out of heap —
 *       inside the game process, which is where an unbounded buffer must never be.</li>
 *   <li>A character cap is not a byte cap. UTF-8 encodes a character in up to four bytes, so a
 *       limit counted in characters permits four times as much memory as it appears to. Counting
 *       the bytes as they arrive is the only version of this guard that means what it says.</li>
 * </ol>
 *
 * <p>Line endings are tolerated in both forms — a trailing {@code \r} is stripped — and blank lines
 * are skipped rather than treated as frames. Neither costs anything, and both make the link
 * drivable by hand from a terminal when something has gone wrong enough to need that.
 *
 * <p>No Minecraft imports, by the same rule that governs {@code protocol/}: framing is exactly the
 * sort of thing that should be provable in a unit test rather than by launching a game.
 */
public final class LinkFraming {

    /**
     * Largest single frame accepted, in bytes.
     *
     * <p>Matches the HTTP transport's request cap. A bulk block read is the biggest thing that
     * legitimately crosses this link, and the same limit already bounds it over HTTP.
     */
    public static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;

    private LinkFraming() {
    }

    /**
     * Reads one frame.
     *
     * @return the frame's raw text, or null at end of stream
     * @throws LinkProtocolException if a single line exceeds {@link #MAX_FRAME_BYTES}
     */
    @Nullable
    public static String readLine(InputStream in) throws IOException, LinkProtocolException {
        return readLine(in, MAX_FRAME_BYTES);
    }

    @Nullable
    static String readLine(InputStream in, int maxBytes) throws IOException, LinkProtocolException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        while (true) {
            int read = in.read();
            if (read < 0) {
                // End of stream. A partial line here is a peer that vanished mid-frame; there is
                // nothing useful to hand back, and returning it would present a truncated frame as
                // a complete one.
                return null;
            }
            if (read == '\n') {
                String line = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (line.trim().isEmpty()) {
                    // Blank line: not a frame. Reset and keep reading rather than handing back
                    // something the caller would have to special-case.
                    buffer.reset();
                    continue;
                }
                return line;
            }
            if (buffer.size() >= maxBytes) {
                throw new LinkProtocolException("Link frame exceeds " + maxBytes
                    + " bytes without a newline; refusing to buffer more");
            }
            buffer.write(read);
        }
    }

    /**
     * Reads one frame and parses it as a JSON object.
     *
     * @return the parsed frame, or null at end of stream
     * @throws LinkProtocolException if the frame is oversized, is not valid JSON, or is not an object
     */
    @Nullable
    public static JsonObject readFrame(InputStream in) throws IOException, LinkProtocolException {
        String line = readLine(in);
        if (line == null) {
            return null;
        }
        JsonElement parsed = Json.parse(line);
        if (parsed == null) {
            throw new LinkProtocolException("Link frame is not valid JSON");
        }
        if (!parsed.isJsonObject()) {
            // Arrays are valid JSON-RPC batches over HTTP, but the link has no reason to carry one:
            // the orchestrator generates every request itself and can simply send them as frames.
            // Refusing here keeps the reader loop's contract to one object per frame.
            throw new LinkProtocolException("Link frame must be a JSON object, not a "
                + (parsed.isJsonArray() ? "array" : "primitive"));
        }
        return parsed.getAsJsonObject();
    }

    /**
     * Writes one frame and flushes.
     *
     * <p>Synchronized on the stream because two threads legitimately write to a live link — the
     * reader loop replying to a request, and the writer draining queued notifications. An
     * interleaved write would corrupt both frames, and the failure would look like a protocol bug
     * rather than a locking one.
     */
    public static void writeFrame(OutputStream out, JsonObject frame) throws IOException {
        byte[] bytes = (Json.write(frame) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME_BYTES) {
            throw new IOException("Refusing to send a " + bytes.length
                + " byte link frame; the limit is " + MAX_FRAME_BYTES);
        }
        synchronized (out) {
            out.write(bytes);
            out.flush();
        }
    }

    /** Whether this frame is an MCP message rather than a link control frame. */
    public static boolean isMcpMessage(@Nullable JsonObject frame) {
        return frame != null && frame.has(LinkProtocol.FIELD_JSONRPC);
    }

    /** Whether this frame is a link control frame rather than an MCP message. */
    public static boolean isControlFrame(@Nullable JsonObject frame) {
        return frame != null && frame.has(LinkProtocol.FIELD_TYPE);
    }
}
