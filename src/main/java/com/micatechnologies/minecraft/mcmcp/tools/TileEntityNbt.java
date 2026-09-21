package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonElement;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import javax.annotation.Nullable;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Writing tile-entity data as part of a block placement, the way {@code /blockdata} does.
 *
 * <p>Placing a block that needs tile-entity state — a sign's text, a beacon's mode, a panel's linked
 * positions — used to take a {@code server_set_blocks} and then one {@code /blockdata} per block
 * through {@code server_run_command}: eight to thirty extra calls per scene, hundreds in a session.
 *
 * <p>The patch is <em>merged</em> into what the tile entity writes, exactly as {@code /blockdata}
 * merges, so a caller names only the fields it cares about and the rest keep the defaults the block
 * placed with. The position fields are put back afterwards, so a patch cannot move a tile entity.
 */
public final class TileEntityNbt {

    /** What {@link #merge} did. */
    public enum Outcome {
        APPLIED,
        UNCHANGED,
        NO_TILE_ENTITY
    }

    private TileEntityNbt() {
    }

    /**
     * Parses a patch given as an SNBT string ({@code {Text1:"\"hi\""}}) or a JSON object.
     *
     * <p>A JSON object is read as SNBT, which accepts quoted keys, so {@code {"Text1": "..."}} works
     * — but JSON has one kind of number, so an integer arrives as an NBT int and a fraction as a
     * double. A field that must be a byte, short, long or float needs the SNBT string form with its
     * suffix ({@code 1b}, {@code 5L}).
     *
     * @throws IllegalArgumentException with a message a caller can act on
     */
    public static NBTTagCompound parse(JsonElement value) {
        String text;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            text = value.getAsString();
        } else if (value.isJsonObject()) {
            text = Json.write(value);
        } else {
            throw new IllegalArgumentException("nbt must be an SNBT string or a JSON object; got "
                + Json.write(value) + ".");
        }
        try {
            return JsonToNBT.getTagFromJson(text);
        } catch (NBTException e) {
            String message = "nbt is not valid SNBT: " + e.getMessage() + ".";
            if (String.valueOf(e.getMessage()).contains("Invalid escape")) {
                message += " SNBT has no \\n escape: put a literal line feed in the string for a line "
                    + "break.";
            }
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Merges {@code patch} into the tile entity at {@code pos}. Game thread only.
     *
     * @return what happened; {@link Outcome#UNCHANGED} when the patch matched what was there
     */
    public static Outcome merge(World world, BlockPos pos, @Nullable NBTTagCompound patch) {
        TileEntity tileEntity = world.getTileEntity(pos);
        if (tileEntity == null) {
            return Outcome.NO_TILE_ENTITY;
        }
        if (patch == null || patch.isEmpty()) {
            return Outcome.UNCHANGED;
        }
        NBTTagCompound current = tileEntity.writeToNBT(new NBTTagCompound());
        NBTTagCompound before = current.copy();
        current.merge(patch);
        current.setInteger("x", pos.getX());
        current.setInteger("y", pos.getY());
        current.setInteger("z", pos.getZ());
        if (current.equals(before)) {
            return Outcome.UNCHANGED;
        }
        tileEntity.readFromNBT(current);
        tileEntity.markDirty();
        // What /blockdata does to get the new data to clients: a block update carries the tile
        // entity's update packet with it.
        IBlockState state = world.getBlockState(pos);
        world.notifyBlockUpdate(pos, state, state, 3);
        return Outcome.APPLIED;
    }
}
