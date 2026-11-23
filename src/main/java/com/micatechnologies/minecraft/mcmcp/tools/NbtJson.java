package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.Field;
import javax.annotation.Nullable;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.nbt.NBTTagString;

/**
 * NBT as JSON, for a tile entity's tag.
 *
 * <p>Lossy on purpose, in one direction only: NBT distinguishes a byte from an int from a long and
 * JSON has one number, so the widths are dropped. Nothing reads this back into NBT — it exists to be
 * read by a model asking "what does this block think its state is", where {@code "facing": 3} is the
 * answer and {@code 3b} would be noise.
 *
 * <p>Bounded, because a tag is third-party data of any size: a modded machine can carry a whole
 * inventory, and a map-like block a 16,384-byte colour array. Arrays and lists past
 * {@link #MAX_ELEMENTS} are cut and say so, since a result that silently stops is read as complete.
 * The whole conversion stops near {@link #MAX_CHARS} and says so too ({@code "~truncated": true}).
 * Most callers bound the tag more tightly ({@code maxChars}), and fall back to a summary of its keys.
 *
 * <p>Every numeric array, long arrays included, comes out as a JSON list of numbers. Long arrays used
 * to come out as their SNBT text, cut at 256 characters: a traffic controller's green-state list was
 * unreadable, and a watcher iterating it got characters instead of numbers (#88).
 */
public final class NbtJson {

    /** Longest array or list written out in full. */
    static final int MAX_ELEMENTS = 4096;

    private static final int TAG_BYTE = 1;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;

    private NbtJson() {
    }

    /**
     * The most JSON one conversion writes, in characters, estimated as it goes. The element caps bound
     * each array, not their sum: a tag of a few hundred lists, or of long strings, was megabytes.
     */
    static final int MAX_CHARS = 256 * 1024;

    /**
     * Longest string written out in full: as long as a command block's command may be, so one reads
     * back whole. A longer one keeps its start and says how much more there was.
     */
    static final int MAX_STRING = 32767;

    /** In the outermost compound when anything was cut for {@link #MAX_CHARS}, and in one that lost keys. */
    static final String TRUNCATED_KEY = "~truncated";

    /** The value of a key whose own value was left out for {@link #MAX_CHARS}. */
    static final String TRUNCATED_VALUE = "…(truncated: NBT too large)";

    /** Most keys given {@link #TRUNCATED_VALUE} in one conversion; past it, keys are left out. */
    static final int MAX_MARKERS = 256;

    /** What is left of one conversion's {@link #MAX_CHARS}. */
    private static final class Budget {

        int left;
        int markers;
        boolean truncated;

        Budget(int chars) {
            this.left = chars;
        }

        boolean spent() {
            return left <= 0;
        }
    }

    public static JsonElement toJson(NBTBase tag) {
        return toJson(tag, MAX_CHARS);
    }

    /**
     * As {@link #toJson(NBTBase)}, writing about {@code maxChars} of JSON at most. Past it a compound's
     * remaining keys get {@link #TRUNCATED_VALUE} for a value, a list ends in its {@code elided}
     * marker, and an array that does not fit is elided whole; the outermost compound says
     * {@code "~truncated": true} whenever anything inside was cut for size.
     */
    public static JsonElement toJson(NBTBase tag, int maxChars) {
        Budget budget = new Budget(maxChars);
        JsonElement json = toJson(tag, budget);
        if (budget.truncated && json.isJsonObject()) {
            json.getAsJsonObject().addProperty(TRUNCATED_KEY, true);
        }
        return json;
    }

    private static JsonElement toJson(NBTBase tag, Budget budget) {
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            JsonObject json = new JsonObject();
            budget.left -= 2;
            for (String key : compound.getKeySet()) {
                if (budget.spent()) {
                    budget.truncated = true;
                    // The key stays, with a marker for its value: callers convert the whole tag and
                    // pick keys out of it afterwards, and a key left out would read as one the tag
                    // does not have. Only so many, or the markers are the megabytes.
                    if (budget.markers >= MAX_MARKERS) {
                        json.addProperty(TRUNCATED_KEY, true);
                        break;
                    }
                    budget.markers++;
                    json.addProperty(key, TRUNCATED_VALUE);
                    continue;
                }
                budget.left -= key.length() + 4;
                json.add(key, toJson(compound.getTag(key), budget));
            }
            return json;
        }
        if (tag instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) tag;
            JsonArray json = new JsonArray();
            budget.left -= 2;
            int shown = Math.min(list.tagCount(), MAX_ELEMENTS);
            for (int i = 0; i < shown; i++) {
                if (budget.spent()) {
                    budget.truncated = true;
                    shown = i;
                    break;
                }
                budget.left -= 1;
                json.add(toJson(list.get(i), budget));
            }
            if (shown < list.tagCount()) {
                json.add(elided("list", list.tagCount()));
            }
            return json;
        }
        if (tag instanceof NBTTagString) {
            String text = ((NBTTagString) tag).getString();
            if (text.length() > MAX_STRING) {
                text = text.substring(0, MAX_STRING) + "…(+" + (text.length() - MAX_STRING) + " chars)";
            }
            budget.left -= text.length() + 2;
            return new JsonPrimitive(text);
        }
        if (tag instanceof NBTTagByteArray) {
            byte[] values = ((NBTTagByteArray) tag).getByteArray();
            if (values.length > MAX_ELEMENTS || !fits(budget, values.length, 4)) {
                return elided("byte[]", values.length);
            }
            JsonArray json = new JsonArray();
            for (byte value : values) {
                json.add(value);
            }
            return json;
        }
        if (tag instanceof NBTTagIntArray) {
            int[] values = ((NBTTagIntArray) tag).getIntArray();
            if (values.length > MAX_ELEMENTS || !fits(budget, values.length, 8)) {
                return elided("int[]", values.length);
            }
            JsonArray json = new JsonArray();
            for (int value : values) {
                json.add(value);
            }
            return json;
        }
        if (tag instanceof NBTTagLongArray) {
            long[] values = longs((NBTTagLongArray) tag);
            if (values != null) {
                if (values.length > MAX_ELEMENTS || !fits(budget, values.length, 16)) {
                    return elided("long[]", values.length);
                }
                JsonArray json = new JsonArray();
                for (long value : values) {
                    json.add(value);
                }
                return json;
            }
        }
        if (tag instanceof NBTPrimitive) {
            NBTPrimitive primitive = (NBTPrimitive) tag;
            int id = tag.getId();
            budget.left -= 8;
            if (id >= TAG_BYTE && id <= TAG_LONG) {
                return new JsonPrimitive(primitive.getLong());
            }
            if (id == TAG_FLOAT || id == TAG_DOUBLE) {
                double value = id == TAG_FLOAT ? primitive.getFloat() : primitive.getDouble();
                // JSON has no NaN or infinity and Gson refuses to write one, which would turn one odd
                // float in a modded tag into a failed tool call.
                return Double.isNaN(value) || Double.isInfinite(value)
                    ? new JsonPrimitive(String.valueOf(value))
                    : new JsonPrimitive(value);
            }
        }
        // Anything a later version adds. Its own text form is still an answer, cut like everything else.
        String text = tag.toString();
        budget.left -= Math.min(text.length(), MAX_TEXT) + 24;
        return new JsonPrimitive(text.length() > MAX_TEXT
            ? text.substring(0, MAX_TEXT) + "… (" + text.length() + " characters)"
            : text);
    }

    /**
     * Charges an array of {@code length} numbers of about {@code charsEach} to the budget, or, when
     * what is left would not hold it, marks the conversion truncated and charges only its marker.
     */
    private static boolean fits(Budget budget, int length, int charsEach) {
        long cost = 2L + (long) length * charsEach;
        if (cost > Math.max(0, budget.left) && length > 0) {
            budget.truncated = true;
            budget.left -= 32;
            return false;
        }
        budget.left -= (int) cost;
        return true;
    }

    /** Longest text form of an unknown tag written out. */
    private static final int MAX_TEXT = 1024;

    @Nullable
    private static volatile Field longArrayData;

    /**
     * A long array's values. 1.12.2 gives NBTTagLongArray no accessor; its one field is found by type,
     * which needs neither its dev name nor its SRG name. Null if that ever fails.
     */
    @Nullable
    static long[] longs(NBTTagLongArray tag) {
        try {
            Field field = longArrayData;
            if (field == null) {
                for (Field candidate : NBTTagLongArray.class.getDeclaredFields()) {
                    if (candidate.getType() == long[].class) {
                        candidate.setAccessible(true);
                        field = candidate;
                        break;
                    }
                }
                if (field == null) {
                    return null;
                }
                longArrayData = field;
            }
            return (long[]) field.get(tag);
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static JsonObject elided(String type, int length) {
        JsonObject json = new JsonObject();
        json.addProperty("elided", type);
        json.addProperty("length", length);
        return json;
    }
}
