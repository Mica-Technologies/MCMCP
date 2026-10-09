package com.micatechnologies.minecraft.mcmcp.companion;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Splits a message into frames, and the frame header both sides agree on.
 *
 * <p>A frame is {@code type (1 byte) | stream (varint) | seq (varint) | flags (1 byte) | payload}.
 * A stream is one message; its frames are numbered from 0 and the last carries {@link
 * CompanionProtocol#FLAG_FIN}. A sender never reuses a stream id while a message on it is open, so a
 * receiver can interleave several messages from one peer — a large tool result does not hold up the
 * progress notification of another call.
 */
public final class CompanionFrames {

    /** Largest header: one type byte, two five-byte varints, one flags byte. */
    static final int MAX_HEADER = 12;

    private CompanionFrames() {
    }

    /** A decoded frame header and its payload. */
    public static final class Frame {

        public final byte type;
        public final int stream;
        public final int seq;
        public final int flags;
        public final byte[] payload;

        Frame(byte type, int stream, int seq, int flags, byte[] payload) {
            this.type = type;
            this.stream = stream;
            this.seq = seq;
            this.flags = flags;
            this.payload = payload;
        }

        public boolean isLast() {
            return (flags & CompanionProtocol.FLAG_FIN) != 0;
        }

        public boolean isDeflated() {
            return (flags & CompanionProtocol.FLAG_DEFLATE) != 0;
        }
    }

    /**
     * Splits one message into frames of at most {@code maxFrame} bytes each, header included.
     *
     * @throws IllegalArgumentException if {@code maxFrame} leaves no room for a payload
     */
    public static List<byte[]> encode(byte type, int stream, byte[] message, int maxFrame) {
        if (maxFrame <= MAX_HEADER) {
            throw new IllegalArgumentException("A frame of " + maxFrame + " bytes has no room for a payload");
        }
        if (stream < 0) {
            throw new IllegalArgumentException("Stream ids are not negative");
        }
        byte[] body = message;
        int flags = 0;
        if (message.length >= CompanionProtocol.DEFLATE_THRESHOLD) {
            byte[] deflated = deflate(message);
            if (deflated.length < message.length) {
                body = deflated;
                flags |= CompanionProtocol.FLAG_DEFLATE;
            }
        }

        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        int seq = 0;
        do {
            ByteArrayOutputStream header = new ByteArrayOutputStream(MAX_HEADER);
            header.write(type);
            writeVarInt(header, stream);
            writeVarInt(header, seq);
            int room = maxFrame - header.size() - 1;
            int length = Math.min(room, body.length - offset);
            boolean last = offset + length >= body.length;
            header.write(flags | (last ? CompanionProtocol.FLAG_FIN : 0));
            byte[] frame = new byte[header.size() + length];
            byte[] head = header.toByteArray();
            System.arraycopy(head, 0, frame, 0, head.length);
            System.arraycopy(body, offset, frame, head.length, length);
            frames.add(frame);
            offset += length;
            seq++;
        } while (offset < body.length);
        return frames;
    }

    /**
     * Reads one frame.
     *
     * @throws CompanionProtocolException if the bytes are not a frame
     */
    public static Frame decode(byte[] bytes) throws CompanionProtocolException {
        if (bytes.length < 4) {
            throw new CompanionProtocolException("A frame of " + bytes.length + " bytes is too short");
        }
        int[] at = {1};
        byte type = bytes[0];
        int stream = readVarInt(bytes, at);
        int seq = readVarInt(bytes, at);
        if (at[0] >= bytes.length) {
            throw new CompanionProtocolException("A frame ends before its flags");
        }
        int flags = bytes[at[0]++] & 0xFF;
        byte[] payload = new byte[bytes.length - at[0]];
        System.arraycopy(bytes, at[0], payload, 0, payload.length);
        return new Frame(type, stream, seq, flags, payload);
    }

    static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, input.length / 4));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
        finally {
            deflater.end();
        }
    }

    /**
     * Inflates a message, refusing to produce more than {@code limit} bytes: a small compressed
     * message must not be able to expand into the heap.
     */
    static byte[] inflate(byte[] input, int limit) throws CompanionProtocolException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, input.length * 4 + 64));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new CompanionProtocolException("A compressed message is truncated");
                }
                if (out.size() + n > limit) {
                    throw new CompanionProtocolException("A compressed message expands past " + limit + " bytes");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
        catch (DataFormatException e) {
            throw new CompanionProtocolException("A compressed message is corrupt: " + e.getMessage());
        }
        finally {
            inflater.end();
        }
    }

    static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    static int readVarInt(byte[] bytes, int[] at) throws CompanionProtocolException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (at[0] >= bytes.length) {
                throw new CompanionProtocolException("A frame ends inside a number");
            }
            int b = bytes[at[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                if (value < 0) {
                    throw new CompanionProtocolException("A frame carries a negative number");
                }
                return value;
            }
        }
        throw new CompanionProtocolException("A number in a frame is too long");
    }
}
