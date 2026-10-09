package com.micatechnologies.minecraft.mcmcp.regions;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * An array of small integers — palette indices, column heights — as a short string.
 *
 * <p>Run-length encoded as varint pairs ({@code value, run}), deflated, then base64. A region of
 * terrain is long runs of the same few blocks, so a 128,000-cell read that is a 500 KB JSON array of
 * numbers comes out a few kilobytes here. Every byte of a reply is paid for in a model's context.
 *
 * <p>To decode: base64-decode, inflate (zlib), then read varint pairs until the bytes run out, each
 * pair being a value and how many times it repeats. A varint is 7 bits per byte, low bits first,
 * high bit set on every byte but the last.
 *
 * <p>Pure, and round-trip tested.
 */
public final class PackedIndices {

    /** What a reply calls this encoding, so a reader knows how to undo it. */
    public static final String ENCODING = "rle-varint+zlib+base64";

    private PackedIndices() {
    }

    public static String encode(int[] values) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(Math.max(16, values.length / 8));
        int i = 0;
        while (i < values.length) {
            int value = values[i];
            int run = 1;
            while (i + run < values.length && values[i + run] == value) {
                run++;
            }
            writeVarInt(raw, value);
            writeVarInt(raw, run);
            i += run;
        }
        byte[] bytes = raw.toByteArray();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(bytes);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, bytes.length / 2));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return Base64.getEncoder().encodeToString(out.toByteArray());
        }
        finally {
            deflater.end();
        }
    }

    /**
     * The inverse, for tests and for anyone reading MCMCP's output in Java.
     *
     * @param count how many values were encoded (the reply says)
     */
    public static int[] decode(String text, int count) {
        if (count == 0) {
            return new int[0];
        }
        byte[] deflated = Base64.getDecoder().decode(text);
        Inflater inflater = new Inflater();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            inflater.setInput(deflated);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && inflater.needsInput()) {
                    throw new IllegalArgumentException("truncated");
                }
                raw.write(buffer, 0, n);
            }
        }
        catch (DataFormatException e) {
            throw new IllegalArgumentException(e);
        }
        finally {
            inflater.end();
        }
        byte[] bytes = raw.toByteArray();
        int[] values = new int[count];
        int[] at = {0};
        int filled = 0;
        while (at[0] < bytes.length) {
            int value = readVarInt(bytes, at);
            int run = readVarInt(bytes, at);
            for (int k = 0; k < run && filled < count; k++) {
                values[filled++] = value;
            }
        }
        if (filled != count) {
            throw new IllegalArgumentException("expected " + count + " values, decoded " + filled);
        }
        return values;
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static int readVarInt(byte[] bytes, int[] at) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = bytes[at[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("varint too long");
    }
}
