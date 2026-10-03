package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.Test;

/**
 * The Chisels &amp; Bits voxel blob, byte for byte. The expected streams are worked out by hand from
 * C&amp;B 14.33's {@code VoxelBlob.write} and {@code BitStream}, so a round trip that agrees with
 * itself but not with C&amp;B fails here rather than in a player's world.
 */
class ChiselBlobTest {

    private static final int STONE = 1;
    private static final int GRANITE = 1 | 1 << 12;

    @Test
    void aSolidBlockIsOnePaletteEntryAndNoBitsAtAll() {
        int[] cells = new int[ChiselBlob.VOXELS];
        byte[] encoded = ChiselBlob.encode(new ChiselBlob.Blob(ChiselBlob.VERSION_COMPACT,
            Collections.singletonList(ChiselBlob.Entry.ofStateId(STONE)), cells));

        // version 0, one entry (state 1), stream offset 0, 0 bytes: every index is 0.
        assertArrayEquals(new byte[]{0, 1, 1, 0, 0}, inflate(encoded));
    }

    @Test
    void leadingZeroWordsAreSkippedAndTheOffsetSaysWhereTheStoredOnesBegin() {
        int[] cells = new int[ChiselBlob.VOXELS];
        cells[ChiselBlob.VOXELS - 1] = 1;
        byte[] encoded = ChiselBlob.encode(new ChiselBlob.Blob(ChiselBlob.VERSION_COMPACT,
            Arrays.asList(ChiselBlob.Entry.ofStateId(0), ChiselBlob.Entry.ofStateId(STONE)), cells));

        // One bit per voxel; the last voxel is bit 31 of word 127, stored big-endian at offset 508
        // (varint FC 03), and the 127 words before it are all zero and not stored.
        assertArrayEquals(new byte[]{0, 2, 0, 1, (byte) 0xFC, 0x03, 4, (byte) 0x80, 0, 0, 0},
            inflate(encoded));
    }

    @Test
    void eachIndexIsWrittenMostSignificantBitFirstFromBitZeroOfTheWordUp() {
        int[] cells = new int[ChiselBlob.VOXELS];
        // Three entries need two bits. Voxel 0 takes index 2 (binary 10), voxel 1 index 1 (01).
        ChiselBlob.Blob blob = new ChiselBlob.Blob(ChiselBlob.VERSION_COMPACT, Arrays.asList(
            ChiselBlob.Entry.ofStateId(0), ChiselBlob.Entry.ofStateId(STONE),
            ChiselBlob.Entry.ofStateId(GRANITE)), cells);
        cells[0] = 2;
        cells[1] = 1;
        // Encoding compacts the palette into first-use order: granite, stone, air.
        byte[] raw = inflate(ChiselBlob.encode(blob));

        // Palette: granite (4097, varint 81 20), stone, air. Then offset 0 and 1024 bytes (256
        // words, varint 80 08).
        assertArrayEquals(new byte[]{0, 3, (byte) 0x81, 0x20, 1, 0, 0, (byte) 0x80, 0x08},
            Arrays.copyOf(raw, 9));
        // From bit 0 up: voxel 0 is index 0 (bits 0, 0), voxel 1 index 1 (0, then 1 at bit 3),
        // every later voxel air, index 2 (1, then 0), setting each even bit from bit 4.
        assertArrayEquals(new byte[]{0x55, 0x55, 0x55, 0x58}, Arrays.copyOfRange(raw, 9, 13));
        assertArrayEquals(new byte[]{0x55, 0x55, 0x55, 0x55}, Arrays.copyOfRange(raw, 13, 17));
        assertEquals(9 + 1024, raw.length);
    }

    @Test
    void aBlobDecodesBackToTheSameVoxels() {
        int[] cells = new int[ChiselBlob.VOXELS];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = (i * 7 + i / 13) % 5;
        }
        ChiselBlob.Blob blob = new ChiselBlob.Blob(ChiselBlob.VERSION_COMPACT, Arrays.asList(
            ChiselBlob.Entry.ofStateId(0), ChiselBlob.Entry.ofStateId(STONE),
            ChiselBlob.Entry.ofStateId(GRANITE), ChiselBlob.Entry.ofStateId(35 | 14 << 12),
            ChiselBlob.Entry.ofStateId(35 | 4 << 12)), cells);

        ChiselBlob.Blob decoded = ChiselBlob.decode(ChiselBlob.encode(blob));
        for (int i = 0; i < cells.length; i++) {
            assertEquals(blob.palette.get(cells[i]), decoded.palette.get(decoded.cells[i]),
                "voxel " + i);
        }
    }

    @Test
    void crossWorldNamesAreWrittenAsANameAndAnEmptyExtraString() {
        int[] cells = new int[ChiselBlob.VOXELS];
        byte[] raw = inflate(ChiselBlob.encode(new ChiselBlob.Blob(ChiselBlob.VERSION_CROSSWORLD,
            Collections.singletonList(ChiselBlob.Entry.ofName("minecraft%3Astone?variant=stone")),
            cells)));

        assertEquals(2, raw[0], "version");
        assertEquals(1, raw[1], "palette size");
        assertEquals(31, raw[2], "name length");
        assertEquals("minecraft%3Astone?variant=stone", new String(raw, 3, 31));
        assertArrayEquals(new byte[]{0, 0, 0}, Arrays.copyOfRange(raw, 34, 37),
            "empty extra string, offset 0, 0 bytes");
        assertEquals("minecraft%3Astone?variant=stone",
            ChiselBlob.decode(ChiselBlob.encode(ChiselBlob.decode(deflate(raw)))).palette.get(0).name);
    }

    @Test
    void aLegacyCrossWorldBlobReadsItsNameAndMeta() {
        byte[] raw = {1, 1, 10, 'm', 'c', ':', 'w', 'o', 'o', 'l', 'x', 'y', 'z', 14, 0, 0};
        ChiselBlob.Blob blob = ChiselBlob.decode(deflate(raw));
        assertEquals("mc:woolxyz", blob.palette.get(0).name);
        assertEquals(14, blob.palette.get(0).meta);
    }

    @Test
    void somethingThatIsNotABlobIsRefusedWithAReason() {
        assertThrows(IllegalArgumentException.class, () -> ChiselBlob.decode(new byte[]{1, 2, 3}));
        assertThrows(IllegalArgumentException.class,
            () -> ChiselBlob.decode(deflate(new byte[]{9, 1, 1, 0, 0})));
        // Two entries, then a stream claiming more bytes than follow.
        assertThrows(IllegalArgumentException.class,
            () -> ChiselBlob.decode(deflate(new byte[]{0, 2, 0, 1, 0, 40, 1})));
    }

    @Test
    void aFaceIsSolidForNeighboursOnlyWhenSixteenOfItsCentreBitsAreSolid() {
        int[] cells = new int[ChiselBlob.VOXELS];
        boolean[] solid = {false, true};
        // Fifteen solid bits in the centre of the bottom face: not enough.
        int placed = 0;
        for (int z = 5; z <= 11 && placed < 15; z++) {
            for (int x = 5; x <= 11 && placed < 15; x++, placed++) {
                cells[ChiselBlob.index(x, 0, z)] = 1;
            }
        }
        assertEquals(0, ChiselBlob.sideFlags(cells, solid));
        cells[ChiselBlob.index(11, 0, 11)] = 1;
        assertEquals(1, ChiselBlob.sideFlags(cells, solid), "down is EnumFacing ordinal 0");

        int[] east = new int[ChiselBlob.VOXELS];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                east[ChiselBlob.index(15, y, z)] = 1;
            }
        }
        assertEquals(1 << 5, ChiselBlob.sideFlags(east, solid), "east is ordinal 5, at x = 15");
    }

    @Test
    void aBlockLightsFullyWhenSixAndAQuarterPercentOfItIsGlowstone() {
        int[] counts = {ChiselBlob.VOXELS - 256, 256};
        assertEquals(15, ChiselBlob.lightValue(counts, new int[]{0, 15}));
        assertEquals(7, ChiselBlob.lightValue(new int[]{ChiselBlob.VOXELS - 128, 128},
            new int[]{0, 15}));
        assertEquals(0, ChiselBlob.lightValue(counts, new int[]{0, 0}));
    }

    @Test
    void thePrimaryStateIsTheMostCommonOneThatIsNotAir() {
        assertEquals(2, ChiselBlob.primary(new int[]{4000, 30, 66}, new boolean[]{true, false, false}));
        assertEquals(-1, ChiselBlob.primary(new int[]{4096}, new boolean[]{true}));
    }

    private static byte[] inflate(byte[] bytes) {
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int read; (read = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] deflate(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream zip = new DeflaterOutputStream(out)) {
            zip.write(raw);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }
}
