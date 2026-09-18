package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import org.junit.jupiter.api.Test;

class NbtJsonTest {

    @Test
    void a_tile_entity_tag_reads_as_the_json_a_model_would_expect() {
        // The shape of the tag that prompted this: an Immersive Engineering junction box, which had
        // only ever been readable by sending /blockdata and grepping the log for the reply.
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", "immersiveengineering:junctionbox");
        tag.setInteger("x", 2067);
        tag.setByte("facing", (byte) 3);
        tag.setLong("lastTick", 9_000_000_000L);
        tag.setDouble("charge", 0.5D);
        tag.setIntArray("signal", new int[]{0, 15, 0});
        tag.setTag("wires", new NBTTagCompound());

        JsonObject json = NbtJson.toJson(tag).getAsJsonObject();

        assertEquals("immersiveengineering:junctionbox", json.get("id").getAsString());
        assertEquals(2067, json.get("x").getAsInt());
        assertEquals(3, json.get("facing").getAsInt());
        assertEquals(9_000_000_000L, json.get("lastTick").getAsLong());
        assertEquals(0.5D, json.get("charge").getAsDouble());
        assertEquals("[0,15,0]", Json.write(json.get("signal")));
        assertTrue(json.get("wires").isJsonObject());
    }

    @Test
    void an_oversized_array_says_it_was_cut_rather_than_stopping_silently() {
        // A result that simply ends is read as complete. A map-like block carries 16,384 bytes.
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByteArray("colors", new byte[16384]);

        JsonObject colors = NbtJson.toJson(tag).getAsJsonObject().getAsJsonObject("colors");

        assertEquals("byte[]", colors.get("elided").getAsString());
        assertEquals(16384, colors.get("length").getAsInt());
    }

    @Test
    void an_oversized_list_keeps_its_head_and_counts_the_rest() {
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < NbtJson.MAX_ELEMENTS + 10; i++) {
            list.appendTag(new NBTTagString("entry" + i));
        }

        JsonArray json = NbtJson.toJson(list).getAsJsonArray();

        assertEquals(NbtJson.MAX_ELEMENTS + 1, json.size());
        assertEquals("entry0", json.get(0).getAsString());
        JsonObject marker = json.get(NbtJson.MAX_ELEMENTS).getAsJsonObject();
        assertEquals(NbtJson.MAX_ELEMENTS + 10, marker.get("length").getAsInt());
    }

    @Test
    void a_float_json_cannot_carry_does_not_fail_the_whole_read() {
        // Gson refuses to write NaN, so one odd value in a modded tag would otherwise turn the call
        // into an error and hide every other key with it.
        NBTTagCompound tag = new NBTTagCompound();
        tag.setFloat("ratio", Float.NaN);

        String written = Json.write(NbtJson.toJson(tag));

        assertEquals("{\"ratio\":\"NaN\"}", written);
    }
}
