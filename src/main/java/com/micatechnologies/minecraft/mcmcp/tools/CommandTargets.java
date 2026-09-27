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
