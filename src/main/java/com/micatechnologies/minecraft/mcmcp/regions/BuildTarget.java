package com.micatechnologies.minecraft.mcmcp.regions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;

/**
 * What an area should be: a block state for each cell a build placed, and nothing for the cells it did
 * not touch.
 *
 * <p>A build's checks were scripts that replayed its commands into a map and read the whole box back to
 * compare, then printed what differed into the agent's context (a builder's own audit, 2026-10-21, found
 * script output was a third of its context growth). A target is that expected map, kept by the server
 * under a name, so a check is one call that answers with counts.
 *
 * <p>States are kept as text, {@code id[prop=value,...]} with properties sorted, which is what
 * 1.12's {@code IBlockState.toString()} produces: the game side compares against its own blocks, and
 * this side can be tested without one. A cell is either a palette entry or untouched ("don't care").
 * Air is a real entry: a build that clears a room's interior wants it to stay clear.
 *
 * <p>Pure, and not thread-safe.
 */
public final class BuildTarget {

    private static final int MAGIC = 0x4D435447;  // "MCTG"
    private static final int VERSION = 1;

    private final int dimension;
    private final List<String> palette = new ArrayList<>();
    private final Map<String, Integer> paletteIndex = new HashMap<>();
    /** Palette index + 1 per cell, with {@link #PROTECTED} set on a cell that must stay as it is; 0 is untouched. */
    private final CellOverlay cells = new CellOverlay();

    /**
     * Marks a cell that must stay as it is, a person's hand edit most often: a check reports it apart and a
     * repair never writes it, whatever a later layer wants there.
     */
    private static final int PROTECTED = 1 << 30;
    private static final int INDEX = PROTECTED - 1;

    public BuildTarget(int dimension) {
        this.dimension = dimension;
    }

    public int dimension() {
        return dimension;
    }

    /** Sets a cell's wanted state, replacing what was wanted there. */
    public void put(int x, int y, int z, String state) {
        put(x, y, z, state, false);
    }

    /** Sets a cell's wanted state, and whether it must stay as it is. */
    public void put(int x, int y, int z, String state, boolean protect) {
        Integer index = paletteIndex.get(state);
        if (index == null) {
            index = palette.size();
            palette.add(state);
            paletteIndex.put(state, index);
        }
        cells.set(x, y, z, (index + 1) | (protect ? PROTECTED : 0));
    }

    /** Whether the cell must stay as it is. */
    public boolean isProtected(int x, int y, int z) {
        return (cells.get(x, y, z) & PROTECTED) != 0;
    }

    /** The wanted state at a cell, or null if the target does not care. */
    @Nullable
    public String get(int x, int y, int z) {
        int value = cells.get(x, y, z) & INDEX;
        return value == 0 ? null : palette.get(value - 1);
    }

    /** Cells the target cares about. */
    public long cells() {
        return cells.cells();
    }

    /** Distinct states, air included. */
    public List<String> palette() {
        return Collections.unmodifiableList(palette);
    }

    /**
     * Takes every cell {@code other} cares about, replacing what this one wanted there: a later layer over
     * an earlier one. A cell protected in either stays protected, keeping this one's state if it was
     * protected here, since a later layer does not get to overwrite a hand edit.
     */
    public void merge(BuildTarget other) {
        other.forEachCell((x, y, z, state, protect) -> {
            if (isProtected(x, y, z)) {
                return;
            }
            put(x, y, z, state, protect);
        });
    }

    /** Receives each cell the target cares about. */
    public interface CellVisitor {
        void visit(int x, int y, int z, String state);
    }

    /** Receives each cell the target cares about, with whether it is protected. */
    public interface FlaggedVisitor {
        void visit(int x, int y, int z, String state, boolean protect);
    }

    /** Visits the cells bottom up, section by section, as a write wants them. */
    public void forEach(CellVisitor visitor) {
        forEachCell((x, y, z, state, protect) -> visitor.visit(x, y, z, state));
    }

    /** The 16-cube sections holding cells, bottom up: the unit a slice of game-thread work walks. */
    public List<Long> sectionKeys() {
        return cells.sectionsBottomUp();
    }

    /** The chunk {x, z} a section key is in. */
    public static int[] chunkOf(long sectionKey) {
        return new int[] {CellOverlay.sectionX(sectionKey), CellOverlay.sectionZ(sectionKey)};
    }

    /** Visits one section's cells. */
    public void forEachCellIn(long sectionKey, FlaggedVisitor visitor) {
        cells.visit(sectionKey, (x, y, z, value) -> visitor.visit(x, y, z, palette.get((value & INDEX) - 1),
            (value & PROTECTED) != 0));
    }

    /** {@link #forEach}, with the protected flag. */
    public void forEachCell(FlaggedVisitor visitor) {
        for (long key : cells.sectionsBottomUp()) {
            cells.visit(key, (x, y, z, value) -> visitor.visit(x, y, z, palette.get((value & INDEX) - 1),
                (value & PROTECTED) != 0));
        }
    }

    /** The smallest box holding every cell: {minX, minY, minZ, maxX, maxY, maxZ}, or null if empty. */
    @Nullable
    public int[] bounds() {
        final int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        forEach((x, y, z, state) -> {
            box[0] = Math.min(box[0], x);
            box[1] = Math.min(box[1], y);
            box[2] = Math.min(box[2], z);
            box[3] = Math.max(box[3], x);
            box[4] = Math.max(box[4], y);
            box[5] = Math.max(box[5], z);
        });
        return box[0] == Integer.MAX_VALUE ? null : box;
    }

    // ------------------------------------------------------------------
    // Codec: gzip of a header, the palette, then each section run-length encoded
    // ------------------------------------------------------------------

    public void write(OutputStream out) throws IOException {
        GZIPOutputStream gzip = new GZIPOutputStream(out);
        DataOutputStream data = new DataOutputStream(gzip);
        data.writeInt(MAGIC);
        data.writeInt(VERSION);
        data.writeInt(dimension);
        data.writeInt(palette.size());
        for (String state : palette) {
            data.writeUTF(state);
        }
        List<Long> keys = cells.sectionsBottomUp();
        data.writeInt(keys.size());
        for (long key : keys) {
            data.writeLong(key);
            int[] values = cells.sectionArray(key);
            int i = 0;
            while (i < values.length) {
                int value = values[i];
                int run = 1;
                while (i + run < values.length && values[i + run] == value) {
                    run++;
                }
                writeVarInt(data, value);
                writeVarInt(data, run);
                i += run;
            }
        }
        data.flush();
        gzip.finish();
    }

    public static BuildTarget read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(new GZIPInputStream(in));
        if (data.readInt() != MAGIC) {
            throw new IOException("not a build target");
        }
        int version = data.readInt();
        if (version != VERSION) {
            throw new IOException("build target version " + version + "; this MCMCP reads " + VERSION);
        }
        BuildTarget target = new BuildTarget(data.readInt());
        int paletteSize = data.readInt();
        for (int i = 0; i < paletteSize; i++) {
            String state = data.readUTF();
            target.paletteIndex.put(state, i);
            target.palette.add(state);
        }
        int sections = data.readInt();
        for (int s = 0; s < sections; s++) {
            long key = data.readLong();
            int[] values = new int[4096];
            int i = 0;
            while (i < values.length) {
                int value = readVarInt(data);
                int run = readVarInt(data);
                if (value < 0 || (value & INDEX) > paletteSize || (value != 0 && (value & INDEX) == 0)
                    || run <= 0 || i + run > values.length) {
                    throw new IOException("corrupt build target section");
                }
                for (int r = 0; r < run; r++) {
                    values[i++] = value;
                }
            }
            target.cells.putSection(key, values);
        }
        return target;
    }

    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            write(out);
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    public static BuildTarget fromBytes(byte[] bytes) throws IOException {
        return read(new ByteArrayInputStream(bytes));
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("varint too long");
    }
}
