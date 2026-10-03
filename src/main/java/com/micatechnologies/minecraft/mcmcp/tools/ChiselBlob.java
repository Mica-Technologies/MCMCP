package com.micatechnologies.minecraft.mcmcp.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * The voxel blob a Chisels &amp; Bits block keeps in its tile entity's {@code X} byte array, read
 * and written without Chisels &amp; Bits on the classpath.
 *
 * <p>Written against C&amp;B 14.33 for 1.12 ({@code VoxelBlob}, {@code BlobSerializer},
 * {@code CrossWorldBlobSerializer}, {@code BitStream}) and checked against that jar's bytecode:
 *
 * <pre>
 * zlib( varint version
 *       varint paletteSize, then per entry:
 *           version 0: varint stateId                  (Block.getStateId: id | meta &lt;&lt; 12)
 *           version 1: string blockName, varint meta
 *           version 2: string "urlenc(name)?prop=val&amp;...", string ""
 *       varint byteOffset, varint byteCount
 *       byteCount bytes of bit stream )
 * </pre>
 *
 * The bit stream is 4096 palette indices, voxel {@code x | y << 4 | z << 8} in order, each written
 * most significant bit first in {@code max(1, ceil(log2(paletteSize)))} bits. Bits fill 32-bit ints
 * from bit 0 up, and the ints are stored big-endian. Leading and trailing all-zero ints are not
 * stored: {@code byteOffset} is where the first stored int would sit in the full stream.
 *
 * <p>No Minecraft class is imported, so this is unit-testable; turning state ids into names, and
 * the per-state facts the tile entity's other tags need, is {@code ChiselNbt}'s job.
 */
public final class ChiselBlob {

    public static final int VERSION_COMPACT = 0;
    public static final int VERSION_CROSSWORLD_LEGACY = 1;
    public static final int VERSION_CROSSWORLD = 2;

    public static final int DIM = 16;
    public static final int VOXELS = DIM * DIM * DIM;

    /** C&amp;B's default {@code bitLightPercentage}: how much of a block must glow to light fully. */
    static final float BIT_LIGHT_PERCENTAGE = 6.25f;

    /** Ceiling on the inflated size; a real blob is at most a few kilobytes. */
    private static final int MAX_INFLATED = 1 << 20;

    private ChiselBlob() {
    }

    public static int index(int x, int y, int z) {
        return x | y << 4 | z << 8;
    }

    /** One palette entry. Which fields mean something depends on the blob's version. */
    public static final class Entry {

        /** Version 0: the numeric block-state id; 0 is air. */
        public final int stateId;
        /** Version 1: the block's registry name. Version 2: C&amp;B's encoded state string. */
        public final String name;
        /** Version 1 only. */
        public final int meta;

        private Entry(int stateId, String name, int meta) {
            this.stateId = stateId;
            this.name = name;
            this.meta = meta;
        }

        public static Entry ofStateId(int stateId) {
            return new Entry(stateId, null, 0);
        }

        public static Entry ofName(String name) {
            return new Entry(0, name, 0);
        }

        public static Entry ofLegacy(String name, int meta) {
            return new Entry(0, name, meta);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Entry)) {
                return false;
            }
            Entry entry = (Entry) other;
            return stateId == entry.stateId && meta == entry.meta
                && (name == null ? entry.name == null : name.equals(entry.name));
        }

        @Override
        public int hashCode() {
            return 31 * (31 * stateId + meta) + (name == null ? 0 : name.hashCode());
        }

        @Override
        public String toString() {
            return name == null ? "#" + stateId : meta == 0 ? name : name + "@" + meta;
        }
    }

    /** A whole blob: its palette and one palette index per voxel. */
    public static final class Blob {

        public final int version;
        public final List<Entry> palette;
        /** Palette index per voxel, {@link #VOXELS} long, indexed by {@link ChiselBlob#index}. */
        public final int[] cells;

        public Blob(int version, List<Entry> palette, int[] cells) {
            if (cells.length != VOXELS) {
                throw new IllegalArgumentException("A blob has " + VOXELS + " voxels, not "
                    + cells.length + ".");
            }
            this.version = version;
            this.palette = Collections.unmodifiableList(new ArrayList<>(palette));
            this.cells = cells;
        }

        /** How many voxels use each palette index. */
        public int[] counts() {
            int[] counts = new int[palette.size()];
            for (int cell : cells) {
                counts[cell]++;
            }
            return counts;
        }
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    /**
     * @throws IllegalArgumentException when the bytes are not a blob this understands
     */
    public static Blob decode(byte[] bytes) {
        byte[] raw = inflate(bytes);
        Reader in = new Reader(raw);
        int version = in.varint();
        if (version < VERSION_COMPACT || version > VERSION_CROSSWORLD) {
            throw new IllegalArgumentException("Unknown voxel blob version " + version + ".");
        }
        int size = in.varint();
        if (size < 1 || size > VOXELS) {
            throw new IllegalArgumentException("A palette of " + size + " entries is not valid.");
        }
        List<Entry> palette = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            if (version == VERSION_COMPACT) {
                palette.add(Entry.ofStateId(in.varint()));
            } else if (version == VERSION_CROSSWORLD_LEGACY) {
                String name = in.string();
                palette.add(Entry.ofLegacy(name, in.varint()));
            } else {
                String name = in.string();
                in.string(); // "extra data for later use", always empty
                palette.add(Entry.ofName(name));
            }
        }
        int byteOffset = in.varint();
        int byteCount = in.varint();
        if (byteOffset < 0 || byteCount < 0 || byteOffset % 4 != 0
            || byteCount > raw.length - in.position) {
            throw new IllegalArgumentException("The bit stream's bounds (offset " + byteOffset
                + ", " + byteCount + " bytes) do not fit the blob.");
        }

        int bits = bitsPer(size);
        int firstInt = byteOffset / 4;
        int storedInts = byteCount / 4;
        int start = in.position;
        int[] cells = new int[VOXELS];
        long bit = 0;
        for (int v = 0; v < VOXELS; v++) {
            int value = 0;
            for (int b = bits - 1; b >= 0; b--, bit++) {
                int word = (int) (bit >>> 5) - firstInt;
                if (word >= 0 && word < storedInts) {
                    int at = start + word * 4;
                    int packed = (raw[at] & 0xFF) << 24 | (raw[at + 1] & 0xFF) << 16
                        | (raw[at + 2] & 0xFF) << 8 | raw[at + 3] & 0xFF;
                    if ((packed >>> (int) (bit & 31) & 1) != 0) {
                        value |= 1 << b;
                    }
                }
            }
            if (value >= size) {
                throw new IllegalArgumentException("Voxel " + v + " names palette entry " + value
                    + " of " + size + ".");
            }
            cells[v] = value;
        }
        return new Blob(version, palette, cells);
    }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    /**
     * Writes a blob, dropping palette entries no voxel uses and merging duplicates first, as C&amp;B's
     * own writer (which builds its palette from the voxels) would.
     */
    public static byte[] encode(Blob blob) {
        Blob compact = compact(blob);
        int size = compact.palette.size();
        int bits = bitsPer(size);

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        writeVarint(header, compact.version);
        writeVarint(header, size);
        for (Entry entry : compact.palette) {
            if (compact.version == VERSION_COMPACT) {
                writeVarint(header, entry.stateId);
            } else if (compact.version == VERSION_CROSSWORLD_LEGACY) {
                writeString(header, entry.name);
                writeVarint(header, entry.meta);
            } else {
                writeString(header, entry.name);
                writeString(header, "");
            }
        }

        int[] words = new int[VOXELS * bits / 32];
        int bit = 0;
        for (int cell : compact.cells) {
            for (int b = bits - 1; b >= 0; b--, bit++) {
                if ((cell >>> b & 1) != 0) {
                    words[bit >>> 5] |= 1 << (bit & 31);
                }
            }
        }
        int first = -1;
        int last = -1;
        for (int i = 0; i < words.length; i++) {
            if (words[i] != 0) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        int byteOffset = Math.max(first * 4, 0);
        int byteCount = (last + 1) * 4 - byteOffset;
        writeVarint(header, byteOffset);
        writeVarint(header, byteCount);
        for (int i = byteOffset / 4; i <= last; i++) {
            header.write(words[i] >>> 24);
            header.write(words[i] >>> 16);
            header.write(words[i] >>> 8);
            header.write(words[i]);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream zip = new DeflaterOutputStream(out, deflater)) {
            header.writeTo(zip);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    /** The same blob with only the palette entries in use, each once, in first-use order. */
    static Blob compact(Blob blob) {
        Map<Entry, Integer> remap = new LinkedHashMap<>();
        int[] from = new int[blob.palette.size()];
        boolean[] used = new boolean[blob.palette.size()];
        for (int cell : blob.cells) {
            used[cell] = true;
        }
        for (int cell : blob.cells) {
            if (used[cell]) {
                used[cell] = false;
                Entry entry = blob.palette.get(cell);
                Integer index = remap.get(entry);
                if (index == null) {
                    index = remap.size();
                    remap.put(entry, index);
                }
                from[cell] = index;
            }
        }
        int[] cells = new int[VOXELS];
        for (int i = 0; i < VOXELS; i++) {
            cells[i] = from[blob.cells[i]];
        }
        return new Blob(blob.version, new ArrayList<>(remap.keySet()), cells);
    }

    static int bitsPer(int paletteSize) {
        return Math.max(Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1), 1);
    }

    // ------------------------------------------------------------------
    // The tile entity's other tags, which C&B trusts rather than recomputes
    // ------------------------------------------------------------------

    /**
     * {@code s}: one bit per face (EnumFacing ordinal: down, up, north, south, west, east) set when
     * the face's centre 7×7 square is all solid — C&amp;B's {@code getSideFlags(5, 11, 16)}, which
     * really asks for 16 of those 49. Neighbours use it to cull the faces they share.
     *
     * @param solid per palette index: not air and not a fluid
     */
    public static int sideFlags(int[] cells, boolean[] solid) {
        int flags = 0;
        for (int face = 0; face < 6; face++) {
            int edge = face % 2 == 1 ? DIM - 1 : 0;
            int required = 16;
            for (int a = 5; a <= 11; a++) {
                for (int b = 5; b <= 11; b++) {
                    int cell;
                    if (face < 2) {
                        cell = cells[index(b, edge, a)];
                    } else if (face < 4) {
                        cell = cells[index(b, a, edge)];
                    } else {
                        cell = cells[index(edge, b, a)];
                    }
                    if (solid[cell]) {
                        required--;
                    }
                }
            }
            if (required <= 0) {
                flags |= 1 << face;
            }
        }
        return flags;
    }

    /**
     * {@code lv}: the light the block gives off, from each bit's own light value, scaled so that
     * {@link #BIT_LIGHT_PERCENTAGE} percent of glowstone lights fully.
     */
    public static int lightValue(int[] counts, int[] light) {
        float total = 0;
        for (int i = 0; i < counts.length; i++) {
            total += counts[i] * (float) light[i];
        }
        float scale = BIT_LIGHT_PERCENTAGE * VOXELS * 15.0f / 100.0f;
        return Math.max(0, Math.min(15, (int) (total / scale * 15)));
    }

    /**
     * {@code b}: the palette index of the most common non-air entry, or -1 when there is none.
     *
     * @param air per palette index
     */
    public static int primary(int[] counts, boolean[] air) {
        int best = -1;
        for (int i = 0; i < counts.length; i++) {
            if (!air[i] && counts[i] > 0 && (best < 0 || counts[i] > counts[best])) {
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Wire primitives: Minecraft's PacketBuffer varint and string
    // ------------------------------------------------------------------

    private static byte[] inflate(byte[] bytes) {
        try (InputStream in = new InflaterInputStream(new java.io.ByteArrayInputStream(bytes))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int read; (read = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_INFLATED) {
                    throw new IllegalArgumentException("The blob inflates past " + MAX_INFLATED
                        + " bytes; it is not a voxel blob.");
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a zlib stream: " + e.getMessage(), e);
        }
    }

    static void writeVarint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, utf8.length);
        out.write(utf8, 0, utf8.length);
    }

    private static final class Reader {

        private final byte[] data;
        int position;

        Reader(byte[] data) {
            this.data = data;
        }

        int varint() {
            int value = 0;
            for (int shift = 0; ; shift += 7) {
                if (position >= data.length) {
                    throw new IllegalArgumentException("The blob ends inside a number.");
                }
                if (shift > 28) {
                    throw new IllegalArgumentException("A number in the blob is too long.");
                }
                byte b = data[position++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
        }

        String string() {
            int length = varint();
            if (length < 0 || length > data.length - position) {
                throw new IllegalArgumentException("A name in the blob runs past its end.");
            }
            String value = new String(data, position, length, StandardCharsets.UTF_8);
            position += length;
            return value;
        }
    }
}
