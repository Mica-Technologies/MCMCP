package com.micatechnologies.minecraft.mcmcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The block boxes a vanilla build command will touch, read from its text.
 *
 * <p>For {@code client_run_commands}' load check: a {@code /fill} into a chunk the server has not
 * loaded fails with "Cannot place block outside of the world", and in a batch of a thousand that line
 * is easy to lose. Knowing the box before sending lets the batch say "not loaded" for that command
 * instead.
 *
 * <p>Covers {@code setblock}, {@code fill}, {@code clone} and {@code blockdata}, with absolute and
 * {@code ~} coordinates; anything else — another command, {@code ^} coordinates, a malformed line — is
 * "unknown", which the caller treats as nothing to check rather than as an error. Imports no Minecraft
 * class, so it is unit-tested directly.
 */
public final class CommandTargets {

    private CommandTargets() {
    }

    /**
     * @param command the command, with or without its leading slash
     * @param base    the sender's block position, {@code {x, y, z}}, which {@code ~} is relative to
     *
     * @return each box as {@code {minX, minY, minZ, maxX, maxY, maxZ}}; empty when unknown
     */
    public static List<int[]> boxes(String command, int[] base) {
        String[] args = command.trim().replaceFirst("^/", "").split("\\s+");
        String name = args[0].toLowerCase(Locale.ROOT);
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        try {
            List<int[]> boxes = new ArrayList<>();
            if ("setblock".equals(name) || "blockdata".equals(name)) {
                int[] at = point(args, 1, base);
                boxes.add(box(at, at));
            } else if ("fill".equals(name)) {
                boxes.add(box(point(args, 1, base), point(args, 4, base)));
            } else if ("clone".equals(name)) {
                int[] source = box(point(args, 1, base), point(args, 4, base));
                int[] destination = point(args, 7, base);
                boxes.add(source);
                boxes.add(new int[]{destination[0], destination[1], destination[2],
                    destination[0] + source[3] - source[0],
                    destination[1] + source[4] - source[1],
                    destination[2] + source[5] - source[2]});
            }
            return boxes;
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException unparsable) {
            return Collections.emptyList();
        }
    }

    /** How far around a coordinate a mod's command reaches is unknown; a few blocks covers its neighbours. */
    static final int MARGIN = 4;

    /** A run of six coordinates spanning more than this is two points, not one box. */
    static final int MAX_BOX_SPAN = 512;

    /** Vanilla commands whose numbers are counts, levels or times, never coordinates. */
    private static final java.util.Set<String> NOT_POSITIONAL = new java.util.HashSet<>(java.util.Arrays.asList(
        "give", "clear", "effect", "enchant", "xp", "experience", "time", "weather", "gamerule", "difficulty",
        "defaultgamemode", "gamemode", "scoreboard", "title", "tellraw", "say", "tell", "msg", "w", "me",
        "kill", "replaceitem", "toggledownfall", "worldborder", "trigger", "stats", "advancement",
        "recipe", "function", "reload", "save-all", "save-on", "save-off", "seed", "list", "help", "kick"));

    /**
     * The boxes to load before running a command, for {@code server_run_commands load: true}: the
     * vanilla build commands as {@link #boxes} reads them, and for any other command the coordinates
     * its numbers most likely are (#106). A mod's command has its own grammar, so this reads shape,
     * not grammar:
     *
     * <ul>
     *   <li>a run of two integers is an {@code x z} column, as LDME's {@code /elevator install x z
     *       facing y...} names one;</li>
     *   <li>a run of six is a box ({@code x1 y1 z1 x2 y2 z2}), unless it spans more than
     *       {@value #MAX_BOX_SPAN} blocks, when it is two points;</li>
     *   <li>any other run of three or more is points, three numbers each.</li>
     * </ul>
     *
     * A run continues across spaces and commas, as in CSM's {@code x,y,z;x,y,z} lists, and stops at
     * a word or a semicolon. Every box grows by {@value #MARGIN} blocks, for what the command touches
     * beside the block it names. Loading a chunk too many costs a little; a chunk too few makes the
     * command fail, or report success for a write it skipped.
     */
    public static List<int[]> loadBoxes(String command, int[] base) {
        List<int[]> known = boxes(command, base);
        if (!known.isEmpty()) {
            return known;
        }
        String trimmed = command.trim().replaceFirst("^/", "");
        String first = trimmed.split("\\s+")[0];
        String name = first.toLowerCase(Locale.ROOT);
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        if (NOT_POSITIONAL.contains(name) || "setblock".equals(name) || "fill".equals(name)
            || "clone".equals(name) || "blockdata".equals(name)) {
            return Collections.emptyList();
        }
        List<int[]> boxes = new ArrayList<>();
        List<Integer> run = new ArrayList<>();
        // Words end a run; semicolons do too. Commas and spaces separate numbers inside one.
        for (String segment : trimmed.substring(first.length()).split(";")) {
            for (String token : segment.split("[\\s,]+")) {
                String bare = token.replaceAll("^[\\[\\]{}()]+|[\\[\\]{}()]+$", "");
                Integer value = integer(bare);
                if (value != null) {
                    run.add(value);
                }
                else if (!bare.isEmpty()) {
                    flush(run, boxes);
                }
            }
            flush(run, boxes);
        }
        return boxes;
    }

    private static void flush(List<Integer> run, List<int[]> boxes) {
        int n = run.size();
        if (n == 2) {
            boxes.add(grow(new int[] {run.get(0), 0, run.get(1), run.get(0), 255, run.get(1)}));
        }
        else if (n == 6 && Math.abs(run.get(3) - run.get(0)) <= MAX_BOX_SPAN
            && Math.abs(run.get(5) - run.get(2)) <= MAX_BOX_SPAN) {
            boxes.add(grow(box(new int[] {run.get(0), run.get(1), run.get(2)},
                new int[] {run.get(3), run.get(4), run.get(5)})));
        }
        else if (n >= 3) {
            for (int i = 0; i + 2 < n; i += 3) {
                boxes.add(grow(new int[] {run.get(i), run.get(i + 1), run.get(i + 2),
                    run.get(i), run.get(i + 1), run.get(i + 2)}));
            }
        }
        run.clear();
    }

    private static int[] grow(int[] box) {
        return new int[] {box[0] - MARGIN, box[1], box[2] - MARGIN, box[3] + MARGIN, box[4], box[5] + MARGIN};
    }

    @javax.annotation.Nullable
    private static Integer integer(String token) {
        if (token.isEmpty() || token.length() > 9) {
            return null;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!(c >= '0' && c <= '9') && !(i == 0 && c == '-' && token.length() > 1)) {
                return null;
            }
        }
        return Integer.parseInt(token);
    }

    private static int[] point(String[] args, int from, int[] base) {
        return new int[]{
            coordinate(args[from], base[0]),
            coordinate(args[from + 1], base[1]),
            coordinate(args[from + 2], base[2])};
    }

    /** As {@code CommandBase.parseBlockPos}: {@code ~} adds to the sender's block, then floors. */
    private static int coordinate(String arg, int base) {
        if (arg.startsWith("~")) {
            String offset = arg.substring(1);
            return (int) Math.floor(base + (offset.isEmpty() ? 0.0D : Double.parseDouble(offset)));
        }
        return (int) Math.floor(Double.parseDouble(arg));
    }

    private static int[] box(int[] a, int[] b) {
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
            Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};
    }
}
