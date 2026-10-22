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

    private static String bare(String raw) {
        String trimmed = raw.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1).trim() : trimmed;
    }
}
