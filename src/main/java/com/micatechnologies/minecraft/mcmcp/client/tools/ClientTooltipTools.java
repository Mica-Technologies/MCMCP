package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.client.RawKeyboard;
import com.micatechnologies.minecraft.mcmcp.json.JsonSchema;
import com.micatechnologies.minecraft.mcmcp.mcp.McpRegistry;
import com.micatechnologies.minecraft.mcmcp.mcp.McpTool;
import com.micatechnologies.minecraft.mcmcp.mcp.ToolResult;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

/**
 * Item tooltips as text (#176): the lines {@code ItemStack.getTooltip} returns once Forge's
 * {@code ItemTooltipEvent} has run, which is everything a mod adds. Checking a mod's tooltip help
 * used to need a screenshot after moving a mouse no tool could move.
 *
 * <p>"Hold Shift for more" lines are reached by holding Shift where LWJGL reports it
 * ({@link RawKeyboard}) for the one call, which is what {@code GuiScreen.isShiftKeyDown()} reads.
 */
@SideOnly(Side.CLIENT)
public final class ClientTooltipTools {

    private ClientTooltipTools() {
    }

    public static void register() {
        McpRegistry.registerTool(McpTool.named("client_item_tooltip")
            .title("Read an item's tooltip")
            .description("Return the tooltip lines of an item the player has, as the game would draw "
                + "them on hover, including every line mods add through ItemTooltipEvent. Needs no "
                + "open screen.\n\n"
                + "'slot' is 'mainhand' (default), 'offhand', 'hovered' (the slot under the cursor "
                + "on an open container screen), or a player inventory index: 0-8 hotbar, 9-35 "
                + "main, 36-39 armour (boots first), 40 offhand. 'containerSlot' is a slot number in "
                + "the open container instead. 'shift' holds Shift for the call, for 'hold Shift' "
                + "lines; 'advanced' is the F3+H view (default: the game's setting).")
            .schema(JsonSchema.object()
                .string("slot", "'mainhand', 'offhand', 'hovered', or an inventory index 0-40.")
                .integer("containerSlot", "A slot number in the open container, instead of 'slot'.",
                    0, 1000)
                .bool("shift", "Hold Shift while the tooltip is built. Default false.")
                .bool("advanced", "Advanced tooltips (item id, durability). Default: the game's "
                    + "F3+H setting.")
                .bool("formatted", "Also return the lines with their colour codes. Default false.")
                .build())
            .clientOnly()
            .readOnly()
            .handler(context -> {
                final String slot = context.getString("slot", null);
                final Integer containerSlot = context.has("containerSlot")
                    ? context.getBoundedInt("containerSlot", 0, 0, 1000) : null;
                if (slot != null && containerSlot != null) {
                    return ToolResult.error("Pass 'slot' or 'containerSlot', not both.");
                }
                final boolean shift = context.getBoolean("shift", false);
                final Boolean advanced = context.has("advanced") ? context.getBoolean("advanced", false) : null;
                final boolean formatted = context.getBoolean("formatted", false);
                JsonObject result = context.onGameThread(() -> {
                    Minecraft mc = ClientStateTools.requireInWorld();
                    JsonObject json = new JsonObject();
                    ItemStack stack = resolve(mc, slot, containerSlot, json);
                    if (json.has("error")) {
                        return json;
                    }
                    describe(json, stack, mc, advanced, shift, formatted);
                    return json;
                });
                if (result.has("error")) {
                    return ToolResult.error(result.get("error").getAsString());
                }
                if (result.has("empty")) {
                    return ToolResult.text("That slot is empty.").withStructured(result);
                }
                return ToolResult.structured(result);
            })
            .build());
    }

    /** The stack a caller named, or EMPTY with {@code error} set on {@code json}. Client thread. */
    private static ItemStack resolve(Minecraft mc, @Nullable String slot, @Nullable Integer containerSlot,
                                     JsonObject json) {
        if (containerSlot != null) {
            Container container = mc.player.openContainer;
            if (containerSlot >= container.inventorySlots.size()) {
                json.addProperty("error", "The open container has " + container.inventorySlots.size()
                    + " slots (0-" + (container.inventorySlots.size() - 1) + "); " + containerSlot
                    + " is not one.");
                return ItemStack.EMPTY;
            }
            json.addProperty("containerSlot", containerSlot);
            return container.getSlot(containerSlot).getStack();
        }
        String name = slot == null ? "mainhand" : slot.trim().toLowerCase(Locale.ROOT);
        json.addProperty("slot", name);
        switch (name) {
            case "mainhand":
            case "hand":
                return mc.player.getHeldItemMainhand();
            case "offhand":
                return mc.player.getHeldItemOffhand();
            case "hovered": {
                GuiScreen screen = mc.currentScreen;
                Slot hovered = screen instanceof GuiContainer ? ((GuiContainer) screen).getSlotUnderMouse() : null;
                if (hovered == null) {
                    json.addProperty("error", screen instanceof GuiContainer
                        ? "The cursor is over no slot. Move it with client_gui_click_at hover: true."
                        : "'hovered' needs an open container screen; " + (screen == null ? "none is open"
                            : screen.getClass().getSimpleName() + " is not one") + ".");
                    return ItemStack.EMPTY;
                }
                json.addProperty("containerSlot", hovered.slotNumber);
                return hovered.getStack();
            }
            default:
                try {
                    int index = Integer.parseInt(name);
                    if (index < 0 || index >= mc.player.inventory.getSizeInventory()) {
                        json.addProperty("error", "Inventory index " + index + " is outside 0-"
                            + (mc.player.inventory.getSizeInventory() - 1) + ".");
                        return ItemStack.EMPTY;
                    }
                    return mc.player.inventory.getStackInSlot(index);
                }
                catch (NumberFormatException notIndex) {
                    json.addProperty("error", "'slot' is mainhand, offhand, hovered or an inventory "
                        + "index; '" + slot + "' is none of them.");
                    return ItemStack.EMPTY;
                }
        }
    }

    /**
     * The slot under the cursor on a container screen, with its item and tooltip, or null. Client
     * thread. Read from the screen's own record of what its last frame drew under the mouse.
     */
    @Nullable
    static JsonObject hoveredSlot(@Nullable GuiScreen screen, boolean formatted) {
        if (!(screen instanceof GuiContainer)) {
            return null;
        }
        Slot slot = ((GuiContainer) screen).getSlotUnderMouse();
        if (slot == null) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("slot", slot.slotNumber);
        json.addProperty("inventoryIndex", slot.getSlotIndex());
        describe(json, slot.getStack(), Minecraft.getMinecraft(), null, false, formatted);
        return json;
    }

    private static void describe(JsonObject json, ItemStack stack, Minecraft mc, @Nullable Boolean advanced,
                                 boolean shift, boolean formatted) {
        if (stack.isEmpty()) {
            json.addProperty("empty", true);
            return;
        }
        ResourceLocation id = stack.getItem().getRegistryName();
        json.addProperty("item", id == null ? "?" : id.toString());
        json.addProperty("meta", stack.getMetadata());
        json.addProperty("count", stack.getCount());
        boolean useAdvanced = advanced != null ? advanced : mc.gameSettings.advancedItemTooltips;
        List<String> lines = linesOf(stack, mc, useAdvanced, shift);
        JsonArray plain = new JsonArray();
        JsonArray raw = new JsonArray();
        for (String line : lines) {
            String stripped = TextFormatting.getTextWithoutFormattingCodes(line);
            plain.add(stripped == null ? line : stripped);
            raw.add(line);
        }
        json.add("lines", plain);
        if (formatted) {
            json.add("formatted", raw);
        }
        json.addProperty("advanced", useAdvanced);
        if (shift) {
            json.addProperty("shift", true);
        }
    }

    /** The tooltip lines, with Shift held where LWJGL reports it when asked. */
    private static List<String> linesOf(ItemStack stack, Minecraft mc, boolean advanced, boolean shift) {
        ITooltipFlag flag = advanced ? ITooltipFlag.TooltipFlags.ADVANCED : ITooltipFlag.TooltipFlags.NORMAL;
        boolean press = shift && !Keyboard.isKeyDown(Keyboard.KEY_LSHIFT);
        if (press) {
            RawKeyboard.down(Keyboard.KEY_LSHIFT);
        }
        try {
            return stack.getTooltip(mc.player, flag);
        }
        finally {
            if (press) {
                RawKeyboard.up(Keyboard.KEY_LSHIFT);
            }
        }
    }
}
