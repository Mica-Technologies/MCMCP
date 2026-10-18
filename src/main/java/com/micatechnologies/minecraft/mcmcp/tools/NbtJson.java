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
 * The callers bound the whole tag as well ({@code maxChars}), and fall back to a summary of its keys.
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

    public static JsonElement toJson(NBTBase tag) {
        if (tag instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) tag;
            JsonObject json = new JsonObject();
            for (String key : compound.getKeySet()) {
                json.add(key, toJson(compound.getTag(key)));
            }
            return json;
        }
        if (tag instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) tag;
            JsonArray json = new JsonArray();
            int shown = Math.min(list.tagCount(), MAX_ELEMENTS);
            for (int i = 0; i < shown; i++) {
                json.add(toJson(list.get(i)));
            }
            if (shown < list.tagCount()) {
                json.add(elided("list", list.tagCount()));
            }
            return json;
        }
        if (tag instanceof NBTTagString) {
            return new JsonPrimitive(((NBTTagString) tag).getString());
        }
        if (tag instanceof NBTTagByteArray) {
            byte[] values = ((NBTTagByteArray) tag).getByteArray();
            if (values.length > MAX_ELEMENTS) {
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
            if (values.length > MAX_ELEMENTS) {
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
                if (values.length > MAX_ELEMENTS) {
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
        return new JsonPrimitive(text.length() > MAX_TEXT
            ? text.substring(0, MAX_TEXT) + "… (" + text.length() + " characters)"
            : text);
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
