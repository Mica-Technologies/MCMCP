package com.micatechnologies.minecraft.mcmcp.regions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Parses the {@code /fill}, {@code /setblock} and {@code /blockdata} lines a build generator emits, so
 * the server can replay them itself (#72) rather than a script reading the whole box down to replay
 * them locally and diff.
 *
 * <p>The syntax is vanilla 1.12's. Coordinates must be absolute: there is no sender to be relative to.
 * A block's data may be a metadata number or a state string ({@code facing=north,half=top}), which
 * this class keeps as text for the game side to resolve against the block. An NBT tag is everything
 * from the first {@code {} after the positional arguments to the end of the line, spaces and all.
 *
 * <p>Pure: text in, records out.
 */
public final class CommandOps {

    private CommandOps() {
    }

    /** What an op does. */
    public enum Kind {
        FILL, SETBLOCK, BLOCKDATA
    }

    /** What a fill or setblock does with the blocks already there. */
    public enum Mode {
        /** Every cell (or only cells matching the filter, for fill with one). */
        REPLACE,
        /** Only air cells. */
        KEEP,
        /** As REPLACE; nothing drops, since nothing is broken by a player. */
        DESTROY,
        /** The box's shell with the block, its inside with air. */
        HOLLOW,
        /** The box's shell with the block, its inside left alone. */
        OUTLINE
    }

    /** One parsed line. */
    public static final class Op {
        public final Kind kind;
        public final int x1;
        public final int y1;
        public final int z1;
        public final int x2;
        public final int y2;
        public final int z2;
        /** Null for blockdata. */
        @Nullable
        public final String block;
        /** A metadata number or a state string; null for the block's default. */
        @Nullable
        public final String data;
        public final Mode mode;
        /** fill ... replace &lt;filter&gt;: only cells holding this block. */
        @Nullable
        public final String filterBlock;
        /** The filter's metadata, or -1 for any. */
        public final int filterMeta;
        /** SNBT, or null. */
        @Nullable
        public final String nbt;

        Op(Kind kind, int x1, int y1, int z1, int x2, int y2, int z2, @Nullable String block, @Nullable String data,
           Mode mode, @Nullable String filterBlock, int filterMeta, @Nullable String nbt) {
            this.kind = kind;
            this.x1 = Math.min(x1, x2);
            this.y1 = Math.min(y1, y2);
            this.z1 = Math.min(z1, z2);
            this.x2 = Math.max(x1, x2);
            this.y2 = Math.max(y1, y2);
            this.z2 = Math.max(z1, z2);
            this.block = block;
            this.data = data;
            this.mode = mode;
            this.filterBlock = filterBlock;
            this.filterMeta = filterMeta;
            this.nbt = nbt;
        }

        public long cells() {
            return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        }

        /** Whether a cell of this op's box is on its shell, for hollow and outline. */
        public boolean onShell(int x, int y, int z) {
            return x == x1 || x == x2 || y == y1 || y == y2 || z == z1 || z == z2;
        }
    }

    /** Why a line could not be parsed. */
    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }

    /** Parses one line, with or without its leading slash. */
    public static Op parse(String line) throws ParseException {
        String text = line == null ? "" : line.trim();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        String nbt = null;
        int brace = text.indexOf('{');
        if (brace >= 0) {
            nbt = text.substring(brace).trim();
            text = text.substring(0, brace).trim();
        }
        String[] words = text.isEmpty() ? new String[0] : text.split("\\s+");
        if (words.length == 0) {
            throw new ParseException("The line is empty.");
        }
        String command = words[0].toLowerCase(Locale.ROOT);
        if ("fill".equals(command)) {
            return fill(words, nbt);
        }
        if ("setblock".equals(command)) {
            return setblock(words, nbt);
        }
        if ("blockdata".equals(command)) {
            if (words.length != 4 || nbt == null) {
                throw new ParseException("blockdata takes x y z {nbt}.");
            }
            int[] at = coordinates(words, 1, 3);
            return new Op(Kind.BLOCKDATA, at[0], at[1], at[2], at[0], at[1], at[2], null, null, Mode.REPLACE, null, -1, nbt);
        }
        throw new ParseException("Only fill, setblock and blockdata can be replayed; this is '" + words[0] + "'.");
    }

    private static Op fill(String[] words, @Nullable String nbt) throws ParseException {
        if (words.length < 8) {
            throw new ParseException("fill takes x1 y1 z1 x2 y2 z2 block [data] [replace|keep|destroy|hollow|outline].");
        }
        int[] at = coordinates(words, 1, 6);
        String block = words[7];
        int next = 8;
        String data = null;
        if (next < words.length && !isMode(words[next])) {
            data = words[next++];
        }
        Mode mode = Mode.REPLACE;
        String filter = null;
        int filterMeta = -1;
        if (next < words.length) {
            mode = mode(words[next++]);
            if (mode == Mode.REPLACE && next < words.length) {
                filter = words[next++];
                if (next < words.length) {
                    filterMeta = integer(words[next++], "the replace filter's data value");
                    if (filterMeta < -1 || filterMeta > 15) {
                        throw new ParseException("The replace filter's data value must be -1 to 15.");
                    }
                }
            }
        }
        if (next < words.length) {
            throw new ParseException("Unexpected '" + words[next] + "' after the fill's arguments.");
        }
        return new Op(Kind.FILL, at[0], at[1], at[2], at[3], at[4], at[5], block, data, mode, filter, filterMeta, nbt);
    }

    private static Op setblock(String[] words, @Nullable String nbt) throws ParseException {
        if (words.length < 5) {
            throw new ParseException("setblock takes x y z block [data] [replace|keep|destroy].");
        }
        int[] at = coordinates(words, 1, 3);
        String block = words[4];
        int next = 5;
        String data = null;
        if (next < words.length && !isMode(words[next])) {
            data = words[next++];
        }
        Mode mode = Mode.REPLACE;
        if (next < words.length) {
            mode = mode(words[next++]);
            if (mode == Mode.HOLLOW || mode == Mode.OUTLINE) {
                throw new ParseException("setblock takes replace, keep or destroy, not " + words[next - 1] + ".");
            }
        }
        if (next < words.length) {
            throw new ParseException("Unexpected '" + words[next] + "' after the setblock's arguments.");
        }
        return new Op(Kind.SETBLOCK, at[0], at[1], at[2], at[0], at[1], at[2], block, data, mode, null, -1, nbt);
    }

    private static boolean isMode(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        return "replace".equals(lower) || "keep".equals(lower) || "destroy".equals(lower) || "hollow".equals(lower)
            || "outline".equals(lower);
    }

    private static Mode mode(String word) throws ParseException {
        if (!isMode(word)) {
            throw new ParseException("'" + word + "' is not replace, keep, destroy, hollow or outline.");
        }
        return Mode.valueOf(word.toUpperCase(Locale.ROOT));
    }

    private static int[] coordinates(String[] words, int from, int count) throws ParseException {
        if (words.length < from + count) {
            throw new ParseException("Expected " + count + " coordinates.");
        }
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            String word = words[from + i];
            if (word.startsWith("~") || word.startsWith("^")) {
                throw new ParseException("Coordinates must be absolute; '" + word + "' is relative to a sender, "
                    + "and a replay has none.");
            }
            values[i] = integer(word, "a coordinate");
        }
        return values;
    }

    private static int integer(String word, String what) throws ParseException {
        try {
            return Integer.parseInt(word);
        }
        catch (NumberFormatException e) {
            throw new ParseException("'" + word + "' is not a whole number, for " + what + ".");
        }
    }

    /** A parse of many lines: the ops that parsed, and why each other line did not. */
    public static final class Parsed {
        public final List<Op> ops = new ArrayList<>();
        /** Index into {@link #ops} -> index of the line it came from. */
        public final List<Integer> lineOf = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public final List<Integer> errorLines = new ArrayList<>();
    }

    /** Parses every line, keeping going past the ones that fail. */
    public static Parsed parseAll(List<String> lines) {
        Parsed parsed = new Parsed();
        for (int i = 0; i < lines.size(); i++) {
            try {
                parsed.ops.add(parse(lines.get(i)));
                parsed.lineOf.add(i);
            }
            catch (ParseException e) {
                parsed.errors.add(e.getMessage());
                parsed.errorLines.add(i);
            }
        }
        return parsed;
    }
}
