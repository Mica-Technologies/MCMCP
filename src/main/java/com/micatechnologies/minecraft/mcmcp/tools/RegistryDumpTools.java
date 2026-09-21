package com.micatechnologies.minecraft.mcmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import com.micatechnologies.minecraft.mcmcp.game.McmcpPaths;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.registry.RegistryNamespaced;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/**
 * Dumps one namespace's worth of game registries to a JSON file, so two builds of a mod can be
 * compared for identical registration.
 *
 * <p>The use case is a refactor that must not change what a mod registers — a mod split into
 * modules, a registration mechanism rewritten, a creative tab reorganised. The game has no dump
 * command, and eyeballing 1,600 blocks in the creative inventory proves nothing. This tool writes
 * blocks, items, tile-entity keys, sound events, recipes and entities for a namespace, in registry
 * order, with the creative tab and translated display name of every block and item, to a file the
 * caller can diff against an earlier dump.</p>
 *
 * <p>Registry order matters and is preserved: the creative inventory renders a tab by walking the
 * item registry in that order, so a reordering shows up here even though every id is still
 * present. Outside a world the order is the registration order; inside a world it is the saved
 * id mapping, so dumps taken for comparison should be taken in the same state (main menu, or the
 * same world).</p>
 *
 * <p>Output goes to a file rather than the response. A dump for a large mod is several hundred
 * kilobytes, which would be useless in a model's context and is exactly what a diff tool wants on
 * disk.</p>
 */
public final class RegistryDumpTools {

    private static final String DUMP_DIRECTORY = "mcmcp/dumps";

    private RegistryDumpTools() {
    }

    public static void register() {
        registerDumpRegistries();
    }

    private static void registerDumpRegistries() {
        McpRegistry.registerTool(McpTool.named("game_dump_registries")
            .title("Dump registries for a namespace")
            .description("Write every registered block, item, tile entity key, sound event, recipe "
                + "and entity belonging to one mod namespace (for example \"csm\") to a JSON file "
                + "under the game directory, in registry order, with each block's and item's class, "
                + "creative tab and translated display name. Use this to prove that a build change "
                + "did not alter what a mod registers: dump before, dump after, diff the two files. "
                + "Registry order is the creative-inventory display order, so it is preserved. "
                + "Each tile-entity block also names the tile entity class it creates and, when "
                + "dumped from the client, its special renderer (TESR) class or 'none' — the way to "
                + "find which blocks are drawn by a TESR without placing them. "
                + "Returns the file path, a SHA-256 of its contents and per-registry counts; read "
                + "the file itself for the entries.")
            .schema(JsonSchema.object()
                .string("namespace", "Registry namespace to dump, the part before the colon in an "
                    + "id such as \"csm:trafficpolevertical\". Lowercase, 1-64 characters.")
                .string("file", "File name for the dump, inside <game directory>/" + DUMP_DIRECTORY
                    + "/. Must end in .json and contain no path separators. Default "
                    + "registries-<namespace>.json. An existing file is overwritten.")
                .bool("display_names", "Include the translated display name of every block and "
                    + "item (default true). Turn off to compare two builds whose language files "
                    + "differ deliberately.")
                .required("namespace")
                .build())
            .readOnly()
            .closedWorld()
            .handler(context -> {
                final String namespace = Json.getString(context.getArguments(), "namespace", "")
                    .trim().toLowerCase(Locale.ROOT);
                if (namespace.isEmpty() || namespace.length() > 64) {
                    return ToolResult.error("namespace must be 1-64 characters, e.g. \"csm\".");
                }
                String fileName = Json.getString(context.getArguments(), "file",
                    "registries-" + namespace + ".json");
                String fileProblem = validateFileName(fileName);
                if (fileProblem != null) {
                    return ToolResult.error(fileProblem);
                }
                final boolean displayNames = Json.getBoolean(context.getArguments(), "display_names",
                    true);

                // Registries are static, but display names run item code (getItemStackDisplayName)
                // that some mods implement against world or client state. The game thread is the
                // only place that is guaranteed safe, and this is a diagnostic — a few hundred
                // milliseconds there is fine.
                JsonObject dump = context.onGameThread(new Callable<JsonObject>() {
                    @Override
                    public JsonObject call() {
                        return buildDump(namespace, context.getSide().id(), displayNames);
                    }
                });

                File directory = new File(McmcpPaths.gameDirectory(), DUMP_DIRECTORY);
                File target = new File(directory, fileName);
                byte[] bytes = Json.writePretty(dump).getBytes(StandardCharsets.UTF_8);
                try {
                    if (!directory.isDirectory() && !directory.mkdirs()) {
                        return ToolResult.error("Could not create " + directory.getAbsolutePath());
                    }
                    Files.write(target.toPath(), bytes);
                } catch (IOException e) {
                    return ToolResult.error("Could not write " + target.getAbsolutePath() + ": "
                        + e.getMessage());
                }

                JsonObject result = new JsonObject();
                result.addProperty("namespace", namespace);
                result.addProperty("side", context.getSide().id());
                result.addProperty("file", target.getAbsolutePath());
                result.addProperty("bytes", bytes.length);
                result.addProperty("sha256", sha256(bytes));
                result.add("counts", dump.getAsJsonObject("counts"));
                result.add("mods", dump.getAsJsonArray("mods"));
                return ToolResult.structured(result);
            })
            .build());
    }

    /**
     * Rejects anything that could escape the dump directory. Returns a message for the caller, or
     * null when the name is acceptable.
     */
    @Nullable
    static String validateFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "file must not be empty.";
        }
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            return "file must be a bare file name inside " + DUMP_DIRECTORY
                + ", with no path separators.";
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return "file must end in .json.";
        }
        if (fileName.length() > 128) {
            return "file must be at most 128 characters.";
        }
        return null;
    }

    private static JsonObject buildDump(String namespace, String side, boolean displayNames) {
        JsonObject dump = new JsonObject();
        dump.addProperty("namespace", namespace);
        dump.addProperty("side", side);

        JsonArray mods = new JsonArray();
        for (ModContainer container : Loader.instance().getModList()) {
            JsonObject mod = new JsonObject();
            mod.addProperty("id", container.getModId());
            mod.addProperty("name", container.getName());
            mod.addProperty("version", container.getDisplayVersion());
            mods.add(mod);
        }
        dump.add("mods", mods);

        // Renderers are only asked about on the client endpoint. The integrated server shares this
        // JVM and its proxy, but the dispatcher lookup writes to a map the client thread reads.
        boolean includeRendering = "client".equals(side);
        int blocksWithTileEntity = 0;
        int blocksWithRenderer = 0;
        JsonArray blocks = new JsonArray();
        for (Block block : ForgeRegistries.BLOCKS) {
            ResourceLocation id = block.getRegistryName();
            if (id == null || !namespace.equals(id.getNamespace())) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", id.toString());
            entry.addProperty("class", block.getClass().getName());
            entry.addProperty("translationKey", block.getTranslationKey());
            addCreativeTab(entry, block.getCreativeTab());
            boolean hasTileEntity = block.hasTileEntity(block.getDefaultState());
            entry.addProperty("hasTileEntity", hasTileEntity);
            if (hasTileEntity) {
                blocksWithTileEntity++;
                if (describeTileEntity(block, entry, includeRendering)) {
                    blocksWithRenderer++;
                }
            }
            Item itemBlock = Item.getItemFromBlock(block);
            entry.addProperty("hasItem", itemBlock != net.minecraft.init.Items.AIR);
            if (displayNames) {
                entry.addProperty("displayName", displayNameOf(block.getTranslationKey() + ".name"));
            }
            blocks.add(entry);
        }
        dump.add("blocks", blocks);

        JsonArray items = new JsonArray();
        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation id = item.getRegistryName();
            if (id == null || !namespace.equals(id.getNamespace())) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", id.toString());
            entry.addProperty("class", item.getClass().getName());
            entry.addProperty("translationKey", item.getTranslationKey());
            entry.addProperty("isItemBlock", item instanceof ItemBlock);
            addCreativeTab(entry, item.getCreativeTab());
            if (displayNames) {
                entry.addProperty("displayName", stackDisplayName(item));
            }
            items.add(entry);
        }
        dump.add("items", items);

        JsonArray tileEntities = new JsonArray();
        RegistryNamespaced<ResourceLocation, Class<? extends TileEntity>> tileRegistry =
            tileEntityRegistry();
        if (tileRegistry != null) {
            for (ResourceLocation key : tileRegistry.getKeys()) {
                if (!namespace.equals(key.getNamespace())) {
                    continue;
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("key", key.toString());
                Class<? extends TileEntity> clazz = tileRegistry.getObject(key);
                entry.addProperty("class", clazz == null ? null : clazz.getName());
                tileEntities.add(entry);
            }
        }
        dump.add("tileEntities", tileEntities);

        JsonArray sounds = new JsonArray();
        for (SoundEvent sound : ForgeRegistries.SOUND_EVENTS) {
            ResourceLocation id = sound.getRegistryName();
            if (id != null && namespace.equals(id.getNamespace())) {
                sounds.add(id.toString());
            }
        }
        dump.add("soundEvents", sounds);

        JsonArray recipes = new JsonArray();
        for (IRecipe recipe : ForgeRegistries.RECIPES) {
            ResourceLocation id = recipe.getRegistryName();
            if (id != null && namespace.equals(id.getNamespace())) {
                recipes.add(id.toString());
            }
        }
        dump.add("recipes", recipes);

        JsonArray entities = new JsonArray();
        for (EntityEntry entity : ForgeRegistries.ENTITIES) {
            ResourceLocation id = entity.getRegistryName();
            if (id != null && namespace.equals(id.getNamespace())) {
                entities.add(id.toString());
            }
        }
        dump.add("entities", entities);

        JsonObject counts = new JsonObject();
        counts.addProperty("blocks", blocks.size());
        counts.addProperty("items", items.size());
        counts.addProperty("tileEntities", tileEntities.size());
        counts.addProperty("blocksWithTileEntity", blocksWithTileEntity);
        if (includeRendering) {
            counts.addProperty("blocksWithRenderer", blocksWithRenderer);
        }
        counts.addProperty("tileEntityRegistryReadable", tileRegistry != null);
        counts.addProperty("soundEvents", sounds.size());
        counts.addProperty("recipes", recipes.size());
        counts.addProperty("entities", entities.size());
        dump.add("counts", counts);
        return dump;
    }

    /**
     * Adds which tile entity {@code block} creates and, on the client, what draws it. The dump
     * listed tile entity keys and classes, and flagged blocks that have one, but never joined the
     * two — so "which of these blocks has a special renderer" had no answer short of placing each.
     *
     * @return whether a special renderer draws it
     */
    private static boolean describeTileEntity(Block block, JsonObject entry, boolean includeRendering) {
        TileEntity tileEntity;
        try {
            // No world: this is a registry question, and most blocks create their tile entity
            // without looking at one. Those that do throw, and are recorded as such.
            tileEntity = block.createTileEntity(null, block.getDefaultState());
        } catch (RuntimeException | LinkageError e) {
            entry.addProperty("tileEntityClass", "error: " + e.getClass().getSimpleName());
            return false;
        }
        if (tileEntity == null) {
            entry.addProperty("tileEntityClass", "none");
            return false;
        }
        entry.addProperty("tileEntityClass", tileEntity.getClass().getName());
        ResourceLocation key = TileEntity.getKey(tileEntity.getClass());
        if (key != null) {
            entry.addProperty("tileEntityKey", key.toString());
        }
        if (!includeRendering) {
            return false;
        }
        JsonObject rendering = Mcmcp.proxy.describeTileEntityRendering(tileEntity);
        if (rendering == null) {
            return false;
        }
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> field : rendering.entrySet()) {
            entry.add(field.getKey(), field.getValue());
        }
        return rendering.has("renderer") && !"none".equals(rendering.get("renderer").getAsString())
            && !rendering.get("renderer").getAsString().startsWith("error:");
    }

    private static void addCreativeTab(JsonObject entry, @Nullable CreativeTabs tab) {
        if (tab == null) {
            entry.add("creativeTab", null);
            return;
        }
        // The label ("tabhvac") is what a human recognises, but CreativeTabs.getTabLabel is
        // client-only and stripped from a dedicated server, so it is reached through the proxy.
        // The index is common to both sides and is stable for a given mod set and load order.
        entry.addProperty("creativeTabIndex", tab.getIndex());
        entry.addProperty("creativeTab", Mcmcp.proxy.creativeTabLabel(tab));
    }

    private static String displayNameOf(String key) {
        // The deprecated common-code translator is the right one here: it reads the LanguageMap
        // that both the client (after locale load) and the dedicated server (mod lang injection)
        // populate, and a difference between the two sides is itself something worth seeing in
        // a dump.
        @SuppressWarnings("deprecation")
        String translated = net.minecraft.util.text.translation.I18n.translateToLocal(key);
        return translated;
    }

    private static String stackDisplayName(Item item) {
        try {
            return new ItemStack(item).getDisplayName();
        } catch (RuntimeException e) {
            // An item whose display name needs NBT or a world. The key is still worth recording.
            return "<error: " + e.getClass().getSimpleName() + ">";
        }
    }

    /**
     * The tile entity registry is private static on TileEntity with no accessor that lists it.
     *
     * <p>It is found by type rather than by name. Name-based reflection fails in one environment
     * or the other: the reobfuscator rewrites a string literal matching an MCP field name into its
     * SRG form, so a shipped jar loaded into a development client asks for {@code field_190562_f}
     * on a class whose field is called {@code REGISTRY}, and Forge's SRG-to-MCP remapping helper
     * does not cover that case either. There is exactly one static {@code RegistryNamespaced} on
     * {@code TileEntity}, in every mapping, so the type is the stable handle. A future change
     * making it ambiguous or absent leaves the dump reporting the registry as unreadable rather
     * than crashing.</p>
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private static RegistryNamespaced<ResourceLocation, Class<? extends TileEntity>> tileEntityRegistry() {
        Field found = null;
        for (Field field : TileEntity.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                && RegistryNamespaced.class.isAssignableFrom(field.getType())) {
                if (found != null) {
                    Mcmcp.LOGGER.warn("TileEntity has more than one static RegistryNamespaced field; "
                        + "not guessing which is the tile entity registry.");
                    return null;
                }
                found = field;
            }
        }
        if (found == null) {
            Mcmcp.LOGGER.warn("TileEntity has no static RegistryNamespaced field; the tile entity "
                + "registry cannot be dumped.");
            return null;
        }
        try {
            found.setAccessible(true);
            return (RegistryNamespaced<ResourceLocation, Class<? extends TileEntity>>) found.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Mcmcp.LOGGER.warn("Could not read the tile entity registry for a dump: {}", e.toString());
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
