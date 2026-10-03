package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.common.base.Optional;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.IFluidBlock;

/**
 * Chisels &amp; Bits tile-entity tags in terms of real block states: reading {@code X} into a
 * palette and a grid a model can read, and building the whole tag for a grid it wrote.
 *
 * <p>Common code, and no C&amp;B class is named: every fact the tag needs comes from vanilla. That
 * matters because C&amp;B <em>trusts</em> {@code b}, {@code s}, {@code lv} and {@code nc} as given
 * rather than recomputing them from the blob, so a tag written with only {@code X} draws with the
 * wrong particles and culls its neighbours wrongly. They are computed here the way C&amp;B's
 * {@code NBTBlobConverter.updateFromBlob} does.
 */
public final class ChiselNbt {

    public static final String MOD_ID = "chiselsandbits";

    /** The tag holding the blob. */
    public static final String TAG_BLOB = "X";

    private static final int TAG_BYTE_ARRAY = 7;

    /** Grid characters, after '.' for air: the most common state gets the first. */
    static final String KEYS = "#abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private ChiselNbt() {
    }

    /** Whether {@code blockId} is one of C&amp;B's chiseled blocks. */
    public static boolean isChiseledBlock(String blockId) {
        return blockId.startsWith(MOD_ID + ":chiseled_");
    }

    /** Whether the tag carries a blob to describe. */
    public static boolean hasBlob(NBTTagCompound tag) {
        return tag.hasKey(TAG_BLOB, TAG_BYTE_ARRAY);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * The blob as states with counts, the box the bits occupy, and with {@code grid} the voxels
     * themselves as {@link #grid}. Never throws: a blob that will not decode says why.
     */
    public static JsonObject describe(NBTTagCompound tag, boolean grid) {
        JsonObject json = new JsonObject();
        byte[] bytes = tag.getByteArray(TAG_BLOB);
        json.addProperty("blobBytes", bytes.length);
        ChiselBlob.Blob blob;
        try {
            blob = ChiselBlob.decode(bytes);
        } catch (IllegalArgumentException e) {
            json.addProperty("error", "The voxel blob could not be read: " + e.getMessage());
            return json;
        }
        json.addProperty("format", blob.version == ChiselBlob.VERSION_COMPACT ? "compact"
            : blob.version == ChiselBlob.VERSION_CROSSWORLD ? "cross-world" : "cross-world-legacy");

        List<String> names = new ArrayList<>(blob.palette.size());
        for (ChiselBlob.Entry entry : blob.palette) {
            names.add(entryName(blob.version, entry));
        }
        int[] counts = blob.counts();

        // States in descending count, merging entries that name the same state.
        Map<String, Integer> byName = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            Integer before = byName.get(names.get(i));
            byName.put(names.get(i), (before == null ? 0 : before) + counts[i]);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(byName.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        JsonObject bits = new JsonObject();
        int air = 0;
        for (Map.Entry<String, Integer> entry : sorted) {
            if (AIR.equals(entry.getKey())) {
                air = entry.getValue();
            } else {
                bits.addProperty(entry.getKey(), entry.getValue());
            }
        }
        json.add("bits", bits);
        json.addProperty("air", air);

        int[] min = {16, 16, 16};
        int[] max = {-1, -1, -1};
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    if (!AIR.equals(names.get(blob.cells[ChiselBlob.index(x, y, z)]))) {
                        int[] at = {x, y, z};
                        for (int a = 0; a < 3; a++) {
                            min[a] = Math.min(min[a], at[a]);
                            max[a] = Math.max(max[a], at[a]);
                        }
                    }
                }
            }
        }
        if (max[0] >= 0) {
            JsonObject bounds = new JsonObject();
            bounds.add("from", ints(min));
            bounds.add("to", ints(max));
            json.add("bounds", bounds);
        }

        if (grid) {
            List<String> legendOrder = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : sorted) {
                if (!AIR.equals(entry.getKey())) {
                    legendOrder.add(entry.getKey());
                }
            }
            if (legendOrder.size() > KEYS.length()) {
                json.addProperty("gridError", "The blob has " + legendOrder.size()
                    + " states; a grid can show at most " + KEYS.length() + ".");
            } else {
                char[] keyOf = new char[names.size()];
                JsonObject legend = new JsonObject();
                legend.addProperty(".", AIR);
                for (int i = 0; i < legendOrder.size(); i++) {
                    legend.addProperty(String.valueOf(KEYS.charAt(i)), legendOrder.get(i));
                }
                for (int i = 0; i < names.size(); i++) {
                    int at = legendOrder.indexOf(names.get(i));
                    keyOf[i] = at < 0 ? '.' : KEYS.charAt(at);
                }
                JsonObject gridJson = new JsonObject();
                gridJson.add("legend", legend);
                gridJson.add("layers", layers(blob.cells, keyOf));
                json.add("grid", gridJson);
            }
        }
        return json;
    }

    /**
     * Sixteen layers, y = 0 (bottom) first. Each is sixteen rows, z = 0 (north) first, of sixteen
     * characters, x = 0 (west) first — the same shape {@code client_chisel_block} takes.
     */
    static JsonArray layers(int[] cells, char[] keyOf) {
        JsonArray layers = new JsonArray();
        char[] row = new char[16];
        for (int y = 0; y < 16; y++) {
            JsonArray rows = new JsonArray();
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    row[x] = keyOf[cells[ChiselBlob.index(x, y, z)]];
                }
                rows.add(new String(row));
            }
            layers.add(rows);
        }
        return layers;
    }

    private static final String AIR = "minecraft:air";

    /** The state an entry names, in the {@code modid:name[prop=value]} form block states print as. */
    static String entryName(int version, ChiselBlob.Entry entry) {
        IBlockState state = entryState(version, entry);
        if (state == null) {
            // Not registered here: say what the blob said rather than guess.
            return entry.name == null ? "unknown#" + entry.stateId : "unknown:" + entry;
        }
        return stateName(state);
    }

    @Nullable
    private static IBlockState entryState(int version, ChiselBlob.Entry entry) {
        if (version == ChiselBlob.VERSION_COMPACT) {
            return entry.stateId == 0 ? Blocks.AIR.getDefaultState()
                : Block.getStateById(entry.stateId);
        }
        if (version == ChiselBlob.VERSION_CROSSWORLD_LEGACY) {
            Block block = Block.REGISTRY.getObject(new ResourceLocation(entry.name));
            return block == null ? null : block.getStateFromMeta(entry.meta);
        }
        return crossWorldState(entry.name);
    }

    /** C&amp;B's {@code StringStates.getStateIDFromName}: {@code urlenc(name)?prop=val&prop=val}. */
    @Nullable
    private static IBlockState crossWorldState(String encoded) {
        String[] parts = encoded.split("[?&]");
        Block block = Block.REGISTRY.getObject(new ResourceLocation(urlDecode(parts[0])));
        if (block == null) {
            return null;
        }
        IBlockState state = block.getDefaultState();
        for (int i = 1; i < parts.length; i++) {
            String[] pair = parts[i].split("=");
            if (pair.length == 2) {
                IBlockState with = withProperty(state, urlDecode(pair[0]), urlDecode(pair[1]));
                state = with == null ? state : with;
            }
        }
        return state;
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // States by name
    // ------------------------------------------------------------------

    /** {@code minecraft:stone[variant=granite]}; the form a block state prints itself in. */
    public static String stateName(IBlockState state) {
        return state.toString();
    }

    /**
     * Parses {@code modid:name} or {@code modid:name[prop=value,...]}. Properties left out keep the
     * block's default.
     *
     * @throws IllegalArgumentException naming what is wrong
     */
    public static IBlockState parseState(String text) {
        String trimmed = text.trim();
        String name = trimmed;
        String properties = null;
        int open = trimmed.indexOf('[');
        if (open >= 0) {
            if (!trimmed.endsWith("]")) {
                throw new IllegalArgumentException("'" + text + "': a property list must end with "
                    + "']'.");
            }
            name = trimmed.substring(0, open);
            properties = trimmed.substring(open + 1, trimmed.length() - 1);
        }
        ResourceLocation id = new ResourceLocation(name);
        if (!Block.REGISTRY.containsKey(id)) {
            throw new IllegalArgumentException("'" + name + "' is not a block in this game.");
        }
        IBlockState state = Block.REGISTRY.getObject(id).getDefaultState();
        if (properties != null && !properties.trim().isEmpty()) {
            for (String pair : properties.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) {
                    throw new IllegalArgumentException("'" + pair.trim() + "' in '" + text
                        + "' is not prop=value.");
                }
                IBlockState with = withProperty(state, kv[0].trim(), kv[1].trim());
                if (with == null) {
                    throw new IllegalArgumentException("'" + name + "' has no property '"
                        + kv[0].trim() + "' with value '" + kv[1].trim() + "'. "
                        + describeProperties(state.getBlock()));
                }
                state = with;
            }
        }
        return state;
    }

    /** {@code Its properties: variant: stone|granite, ...}, or that it has none. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String describeProperties(Block block) {
        StringBuilder text = new StringBuilder();
        for (IProperty property : block.getBlockState().getProperties()) {
            text.append(text.length() == 0 ? "Its properties: " : "; ").append(property.getName())
                .append(": ");
            boolean first = true;
            for (Object value : property.getAllowedValues()) {
                text.append(first ? "" : "|").append(property.getName((Comparable) value));
                first = false;
            }
        }
        return text.length() == 0 ? "It has no properties." : text.append('.').toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Nullable
    private static IBlockState withProperty(IBlockState state, String name, String value) {
        IProperty property = state.getBlock().getBlockState().getProperty(name);
        if (property == null) {
            return null;
        }
        Optional parsed = property.parseValue(value);
        return parsed.isPresent() ? state.withProperty(property, (Comparable) parsed.get()) : null;
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** A tag ready to write, and the chiseled block it belongs in. */
    public static final class Built {

        public final NBTTagCompound tag;
        /** The block to place, {@code chiselsandbits:chiseled_<material>}, or air for no bits. */
        public final String blockId;
        public final int blobBytes;

        Built(NBTTagCompound tag, String blockId, int blobBytes) {
            this.tag = tag;
            this.blockId = blockId;
            this.blobBytes = blobBytes;
        }
    }

    /**
     * Builds {@code X}, {@code b}, {@code s}, {@code lv} and {@code nc} for a grid of states, in the
     * compact format: numeric state ids, which a client shares with the server it is connected to
     * because Forge sends the server's id map at login.
     *
     * @param states palette; {@code cells} index into it
     */
    public static Built build(List<IBlockState> states, int[] cells) {
        List<ChiselBlob.Entry> palette = new ArrayList<>(states.size());
        for (IBlockState state : states) {
            palette.add(ChiselBlob.Entry.ofStateId(isAir(state) ? 0 : Block.getStateId(state)));
        }
        ChiselBlob.Blob blob = new ChiselBlob.Blob(ChiselBlob.VERSION_COMPACT, palette, cells);

        int[] counts = blob.counts();
        boolean[] air = new boolean[states.size()];
        boolean[] solid = new boolean[states.size()];
        int[] light = new int[states.size()];
        boolean normal = true;
        for (int i = 0; i < states.size(); i++) {
            IBlockState state = states.get(i);
            air[i] = isAir(state);
            solid[i] = !air[i] && !isFluid(state);
            light[i] = air[i] ? 0 : state.getLightValue();
            if (counts[i] > 0) {
                normal &= !air[i] && state.isNormalCube();
            }
        }
        int primary = ChiselBlob.primary(counts, air);
        if (primary < 0) {
            return new Built(new NBTTagCompound(), AIR, 0);
        }

        byte[] bytes = ChiselBlob.encode(blob);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("lv", ChiselBlob.lightValue(counts, light));
        tag.setInteger("b", Block.getStateId(states.get(primary)));
        tag.setInteger("s", ChiselBlob.sideFlags(cells, solid));
        tag.setBoolean("nc", normal);
        tag.setTag(TAG_BLOB, new NBTTagByteArray(bytes));
        return new Built(tag, hostBlock(states.get(primary)), bytes.length);
    }

    /**
     * Which chiseled block holds bits whose most common state is {@code primary}: C&amp;B registers
     * one per material ({@code ModBlocks.validMaterials}) and falls back to rock.
     */
    static String hostBlock(IBlockState primary) {
        if (isFluid(primary)) {
            return MOD_ID + ":chiseled_fluid";
        }
        Material material = primary.getMaterial();
        String name = "rock";
        if (material == Material.WOOD) {
            name = "wood";
        } else if (material == Material.IRON) {
            name = "iron";
        } else if (material == Material.CLOTH) {
            name = "cloth";
        } else if (material == Material.ICE) {
            name = "ice";
        } else if (material == Material.PACKED_ICE) {
            name = "packedice";
        } else if (material == Material.CLAY) {
            name = "clay";
        } else if (material == Material.GLASS) {
            name = "glass";
        } else if (material == Material.SAND) {
            name = "sand";
        } else if (material == Material.GROUND) {
            name = "ground";
        } else if (material == Material.GRASS) {
            name = "grass";
        } else if (material == Material.CRAFTED_SNOW) {
            name = "snow";
        } else if (material == Material.LEAVES) {
            name = "leaves";
        }
        return MOD_ID + ":chiseled_" + name;
    }

    /** Reads a blob's voxels back as states, for checking what a server stored. */
    @Nullable
    public static IBlockState[] states(byte[] bytes) {
        ChiselBlob.Blob blob = ChiselBlob.decode(bytes);
        IBlockState[] byEntry = new IBlockState[blob.palette.size()];
        for (int i = 0; i < byEntry.length; i++) {
            byEntry[i] = entryState(blob.version, blob.palette.get(i));
        }
        IBlockState[] states = new IBlockState[ChiselBlob.VOXELS];
        for (int i = 0; i < states.length; i++) {
            states[i] = byEntry[blob.cells[i]];
        }
        return states;
    }

    static boolean isAir(IBlockState state) {
        return state.getBlock() == Blocks.AIR;
    }

    private static boolean isFluid(IBlockState state) {
        return state.getBlock() instanceof BlockLiquid || state.getBlock() instanceof IFluidBlock;
    }

    private static JsonArray ints(int[] values) {
        JsonArray array = new JsonArray();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }
}
