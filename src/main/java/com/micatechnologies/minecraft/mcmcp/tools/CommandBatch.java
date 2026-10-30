package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The commands of a {@code server_run_commands} call, read from its arguments: each one's text and,
 * when it has one, the position its player runs it from ({@code at}, #127).
 *
 * <p>Some mod commands read their sender's own position and take no coordinates: RCMC's
 * {@code /rcmc station <name>} picks the track nearest the player. Running one somewhere else used
 * to take a hand-written {@code /tp} before it and another after, with the player's position read
 * first and typed back in. {@code at} moves the player for that one command and puts them back.
 *
 * <p>Imports no Minecraft class, so the parsing is unit-tested directly; moving the player is
 * {@code PlayerAt}'s.
 */
public final class CommandBatch {

    /** As vanilla's world border: a position past this is not one a player can stand at. */
    static final double MAX_HORIZONTAL = 30_000_000D;

    /** Lowest and highest y {@code at} accepts: a little past the world, as a player can stand. */
    static final double MIN_Y = -64D;

    static final double MAX_Y = 512D;

    private CommandBatch() {
    }

    /**
     * The command with {@code replace} put before a data tag written where vanilla reads the
     * old-block mode, or null when it needs nothing (#153).
     *
     * <p>{@code /setblock x y z block meta {nbt}} reads like it carries the tag, but vanilla's
     * {@code CommandSetBlock} takes the sixth argument as {@code replace|keep|destroy} and parses a
     * tag only from the seventh on. A tag with no spaces became an unknown mode, which means replace,
     * and was dropped while the command said "Block placed": blank signs on a live server. One with
     * spaces was split there, and the next word failed to parse as a tag. {@code /fill} has the same
     * slot, ninth. Inserting {@code replace} is what vanilla did with the slot anyway, with the tag
     * kept: {@code server_apply_ops} already read these lines that way.
     */
    @Nullable
    public static String withModeBeforeTag(String command) {
        String text = command.startsWith("/") ? command.substring(1) : command;
        String[] words = text.split(" ", -1);
        String name = words[0].toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        int slot = name.equals("setblock") ? 6 : name.equals("fill") ? 9 : -1;
        if (slot < 0 || words.length <= slot || !words[slot].startsWith("{")) {
            return null;
        }
        StringBuilder fixed = new StringBuilder(command.length() + 8);
        if (command.startsWith("/")) {
            fixed.append('/');
        }
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                fixed.append(' ');
            }
            if (i == slot) {
                fixed.append("replace ");
            }
            fixed.append(words[i]);
        }
        return fixed.toString();
    }

    /** The warning for the commands {@link #withModeBeforeTag} changed, by index. */
    public static String modeInsertedWarning(List<Integer> indices) {
        StringBuilder listed = new StringBuilder();
        for (int i = 0; i < indices.size() && i < QUOTED_LINES; i++) {
            listed.append(i == 0 ? "#" : ", #").append(indices.get(i));
        }
        if (indices.size() > QUOTED_LINES) {
            listed.append(", ...");
        }
        return indices.size() + " /setblock or /fill command(s) had a data tag where vanilla reads "
            + "replace|keep|destroy, and vanilla drops a tag there while reporting success; 'replace' was "
            + "put before the tag so it is kept (" + listed + "). Write the mode to make this explicit.";
    }

    /** One command, and where its player runs it from. */
    public static final class Entry {

        public final String command;

        /** {@code {x, y, z, yaw, pitch}}, a rotation not given being NaN; null to run where they are. */
        @Nullable
        public final double[] at;

        Entry(String command, @Nullable double[] at) {
            this.command = command;
            this.at = at;
        }
    }

    /**
     * Reads {@code commands}: each a string, or an object {@code {command, at?}}. A string, or an
     * object without its own {@code at}, runs at {@code defaultAt}. Blank strings are skipped, as
     * they always were.
     *
     * @throws IllegalArgumentException with a message for the caller, for an entry that is neither
     */
    public static List<Entry> parse(@Nullable JsonArray commands, @Nullable double[] defaultAt) {
        if (commands == null) {
            return Collections.emptyList();
        }
        List<Entry> entries = new ArrayList<>();
        int index = 0;
        for (JsonElement element : commands) {
            if (element != null && element.isJsonPrimitive()) {
                String command = bare(element.getAsString());
                if (!command.isEmpty()) {
                    entries.add(new Entry(command, defaultAt));
                }
            }
            else if (element != null && element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                String command = bare(Json.getString(object, "command", ""));
                if (command.isEmpty()) {
                    throw new IllegalArgumentException("commands[" + index + "] is an object without a "
                        + "'command'. Each is a string, or {command, at}.");
                }
                double[] at = object.has("at") && !object.get("at").isJsonNull()
                    ? parseAt(object.get("at"), "commands[" + index + "].at") : defaultAt;
                entries.add(new Entry(command, at));
            }
            else {
                throw new IllegalArgumentException("commands[" + index + "] is " + element
                    + ". Each is a string, or {command, at}.");
            }
            index++;
        }
        return entries;
    }

    /**
     * Reads an {@code at}: {@code {x, y, z, yaw?, pitch?}}. A model often sends the object as a JSON
     * string, so that is read too.
     *
     * @param name what to call it in an error, such as {@code "at"} or {@code "commands[3].at"}
     *
     * @return {@code {x, y, z, yaw, pitch}}, a rotation not given being NaN; null when absent
     *
     * @throws IllegalArgumentException with a message for the caller, for a malformed or out-of-bounds one
     */
    @Nullable
    public static double[] parseAt(@Nullable JsonElement element, String name) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive() && ((JsonPrimitive) element).isString()) {
            try {
                element = Json.parse(element.getAsString());
            }
            catch (RuntimeException notJson) {
                element = null;
            }
        }
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("'" + name + "' is {x, y, z}, with yaw and pitch optional.");
        }
        JsonObject object = element.getAsJsonObject();
        double[] at = new double[5];
        String[] keys = {"x", "y", "z", "yaw", "pitch"};
        for (int i = 0; i < keys.length; i++) {
            at[i] = Json.getDouble(object, keys[i], Double.NaN);
            if (i < 3 && Double.isNaN(at[i])) {
                throw new IllegalArgumentException("'" + name + "' has no number '" + keys[i] + "'. It is {x, y, "
                    + "z}, with yaw and pitch optional.");
            }
            if (Double.isInfinite(at[i])) {
                throw new IllegalArgumentException("'" + name + "." + keys[i] + "' is not a finite number.");
            }
        }
        if (Math.abs(at[0]) > MAX_HORIZONTAL || Math.abs(at[2]) > MAX_HORIZONTAL) {
            throw new IllegalArgumentException("'" + name + "' is past the world border (x and z within "
                + "30,000,000).");
        }
        if (at[1] < MIN_Y || at[1] > MAX_Y) {
            throw new IllegalArgumentException("'" + name + ".y' is " + at[1] + "; it is between -64 and 512.");
        }
        if (!Double.isNaN(at[4]) && Math.abs(at[4]) > 90D) {
            throw new IllegalArgumentException("'" + name + ".pitch' is " + at[4] + "; it is between -90 and 90.");
        }
        return at;
    }

    /** Whether any command runs at a position of its own. */
    public static boolean anyAt(List<Entry> entries) {
        for (Entry entry : entries) {
            if (entry.at != null) {
                return true;
            }
        }
        return false;
    }

    /** The block an {@code at} stands in, {@code {x, y, z}}, as {@code ~} in a command resolves against. */
    public static int[] blockOf(double[] at) {
        return new int[] {(int) Math.floor(at[0]), (int) Math.floor(at[1]), (int) Math.floor(at[2])};
    }

    /** The most {@code loadMargin} grows a command's load box by, in blocks. */
    static final int MAX_LOAD_MARGIN = 64;

    /**
     * A load box grown by {@code margin} blocks on x and z, for a command whose effect reaches past the
     * coordinates it names (#119): LDME's {@code /elevator install x z ...} names the controller
     * column, and builds doorways on the cab's front, which can be in the next chunk.
     *
     * @param box {@code {minX, minY, minZ, maxX, maxY, maxZ}}
     */
    public static int[] grow(int[] box, int margin) {
        int m = Math.max(0, Math.min(MAX_LOAD_MARGIN, margin));
        return new int[] {box[0] - m, box[1], box[2] - m, box[3] + m, box[4], box[5] + m};
    }

    /**
     * Whether a reply line says something was not loaded: {@code doors.landing_unloaded(...)}, "chunk
     * not loaded". A command that skipped work there often still returns success.
     */
    public static boolean saysUnloaded(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("unloaded") || lower.contains("not loaded");
    }

    /** How many lines {@link #unloadedWarning} quotes, and how long each may be. */
    static final int QUOTED_LINES = 3;

    static final int QUOTED_LENGTH = 160;

    /**
     * The top-level warning for commands whose replies said something was unloaded, quoting the first
     * few such lines so a silent partial install is visible without reading every command's output.
     *
     * @param commands  how many commands said so
     * @param succeeded how many of those still counted as succeeded
     * @param quoted    the first lines, each already prefixed with its command's index
     * @param load      whether the call loaded the commands' chunks
     */
    public static String unloadedWarning(int commands, int succeeded, List<String> quoted, boolean load) {
        StringBuilder text = new StringBuilder();
        text.append(commands).append(" command(s) replied that something was unloaded");
        if (succeeded > 0) {
            text.append(", ").append(succeeded == commands ? "all" : succeeded + " of them")
                .append(" still counted as succeeded");
        }
        text.append(" (see saysUnloaded in results)");
        if (!quoted.isEmpty()) {
            text.append(": ");
            for (int i = 0; i < quoted.size() && i < QUOTED_LINES; i++) {
                String line = quoted.get(i);
                text.append(i == 0 ? "" : "; ").append(line.length() > QUOTED_LENGTH
                    ? line.substring(0, QUOTED_LENGTH) + "..." : line);
            }
        }
        text.append(". ");
        if (load) {
            text.append("Their reach is past the coordinates MCMCP read from their text: grow every command's "
                + "load box with loadMargin, name the area with loadBoxes, or hold it with server_keep_loaded, "
                + "and run them again.");
        }
        else {
            text.append("Run them again with load on, and a loadMargin if their reach is past the coordinates "
                + "they name.");
        }
        return text.toString();
    }

    private static String bare(String raw) {
        String trimmed = raw.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1).trim() : trimmed;
    }
}
