package com.micatechnologies.minecraft.mcmcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The vanilla-style scrolling lists ({@link GuiSlot}) a screen holds, for {@code client_gui_scroll}
 * (#182).
 *
 * <p>Found by type among the screen's own fields, so a mod's list built on {@code GuiSlot} is found
 * too. A row's text is read from its own fields: the Controls screen keeps each category's and
 * binding's displayed text in a String, and other lists keep a button or a key binding. Nothing here
 * is drawn or clicked; scrolling a list to a row is {@code GuiSlot.scrollBy}, which is what the wheel
 * itself ends in.
 *
 * <p>Client thread only.
 */
@SideOnly(Side.CLIENT)
final class GuiLists {

    /** Most rows read when looking for one by text. */
    private static final int MAX_ROWS = 5000;

    private GuiLists() {
    }

    /** Each list's field, scroll position and range: what a scroll changed. */
    static JsonArray describe(@Nullable GuiScreen screen) {
        JsonArray json = new JsonArray();
        if (screen == null) {
            return json;
        }
        for (Found found : lists(screen)) {
            JsonObject list = new JsonObject();
            list.addProperty("field", found.name);
            list.addProperty("scrolled", found.list.getAmountScrolled());
            list.addProperty("maxScroll", found.list.getMaxScroll());
            // Where to drag: the bar's column and the list's span, in GUI coordinates. A list may be
            // wider than its screen and move its bar (Controls puts it 35 to the right).
            int barX = intMethod(found.list, "getScrollBarX", "func_148137_d");
            if (barX >= 0) {
                list.addProperty("barX", barX + 3);
            }
            list.addProperty("top", found.list.top);
            list.addProperty("bottom", found.list.bottom);
            json.add(list);
        }
        return json;
    }

    /**
     * Scrolls the first list holding a row with {@code text} so the row sits a third of the way down
     * the list's view, or as near as the list's ends allow.
     *
     * @return the row's index, text and new position, or {@code error}
     */
    static JsonObject scrollToText(@Nullable GuiScreen screen, String text) {
        JsonObject json = new JsonObject();
        if (screen == null) {
            json.addProperty("error", "No screen is open.");
            return json;
        }
        List<Found> lists = lists(screen);
        if (lists.isEmpty()) {
            json.addProperty("error", "This screen (" + screen.getClass().getSimpleName() + ") holds no "
                + "vanilla-style list, so 'toText' cannot find rows on it. Scroll it with 'amount', "
                + "and check where it got to with a screenshot.");
            return json;
        }
        String needle = text.toLowerCase(Locale.ROOT);
        for (Found found : lists) {
            int size = size(found.list);
            if (size < 0 || !(found.list instanceof GuiListExtended)) {
                continue;
            }
            GuiListExtended extended = (GuiListExtended) found.list;
            int partial = -1;
            String partialText = null;
            int exact = -1;
            String exactText = null;
            for (int index = 0; index < Math.min(size, MAX_ROWS) && exact < 0; index++) {
                for (String candidate : texts(extended.getListEntry(index))) {
                    String plain = strip(candidate).trim();
                    String lower = plain.toLowerCase(Locale.ROOT);
                    if (lower.equals(needle)) {
                        exact = index;
                        exactText = plain;
                        break;
                    }
                    if (partial < 0 && lower.contains(needle)) {
                        partial = index;
                        partialText = plain;
                    }
                }
            }
            int index = exact >= 0 ? exact : partial;
            if (index < 0) {
                continue;
            }
            GuiSlot list = found.list;
            int rowTop = index * list.slotHeight + list.headerPadding;
            int view = list.bottom - list.top - 4;
            int target = Math.max(0, Math.min(list.getMaxScroll(), rowTop - view / 3));
            list.scrollBy(target - list.getAmountScrolled());
            int scrolled = list.getAmountScrolled();
            json.addProperty("field", found.name);
            json.addProperty("index", index);
            json.addProperty("text", exact >= 0 ? exactText : partialText);
            json.addProperty("rowY", list.top + 4 - scrolled + rowTop);
            json.addProperty("rowHeight", list.slotHeight);
            json.addProperty("rowX", list.left + list.width / 2);
            json.addProperty("scrolled", scrolled);
            json.addProperty("maxScroll", list.getMaxScroll());
            json.addProperty("space", "gui");
            return json;
        }
        json.addProperty("error", "No row in this screen's list" + (lists.size() == 1 ? "" : "s")
            + " has text containing '" + text + "'.");
        return json;
    }

    /** The texts a row shows: its String fields, a key binding's name and category, a button's label. */
    private static List<String> texts(Object entry) {
        List<String> texts = new ArrayList<>();
        if (entry == null) {
            return texts;
        }
        for (Class<?> type = entry.getClass(); type != null && type != Object.class;
            type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(entry);
                }
                catch (IllegalAccessException | RuntimeException unreadable) {
                    continue;
                }
                if (value instanceof String) {
                    texts.add((String) value);
                }
                else if (value instanceof KeyBinding) {
                    KeyBinding binding = (KeyBinding) value;
                    texts.add(I18n.format(binding.getKeyDescription()));
                    texts.add(I18n.format(binding.getKeyCategory()));
                }
                else if (value instanceof GuiButton && ((GuiButton) value).displayString != null) {
                    texts.add(((GuiButton) value).displayString);
                }
            }
        }
        return texts;
    }

    private static String strip(String text) {
        String plain = TextFormatting.getTextWithoutFormattingCodes(text);
        return plain == null ? text : plain;
    }

    private static final class Found {
        final String name;
        final GuiSlot list;

        Found(String name, GuiSlot list) {
            this.name = name;
            this.list = list;
        }
    }

    /** The screen's GuiSlot fields, its own class first. */
    private static List<Found> lists(GuiScreen screen) {
        List<Found> found = new ArrayList<>();
        for (Class<?> type = screen.getClass(); type != null && type != Object.class;
            type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !GuiSlot.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(screen);
                    if (value != null) {
                        found.add(new Found(field.getName(), (GuiSlot) value));
                    }
                }
                catch (IllegalAccessException | RuntimeException unreadable) {
                    // Not reachable; the next field may be.
                }
            }
        }
        return found;
    }

    /** {@code GuiSlot.getSize()}, protected, under its dev or SRG name; -1 when it cannot be called. */
    private static int size(GuiSlot list) {
        return intMethod(list, "getSize", "func_148127_b");
    }

    /** A protected no-argument int method on the list, by its dev or SRG name; -1 when unreachable. */
    private static int intMethod(GuiSlot list, String devName, String srgName) {
        for (Class<?> type = list.getClass(); type != null && type != Object.class;
            type = type.getSuperclass()) {
            for (String name : new String[] {devName, srgName}) {
                try {
                    Method method = type.getDeclaredMethod(name);
                    method.setAccessible(true);
                    return (Integer) method.invoke(list);
                }
                catch (NoSuchMethodException tryNext) {
                    // The other spelling, then the superclass.
                }
                catch (ReflectiveOperationException | RuntimeException failed) {
                    return -1;
                }
            }
        }
        return -1;
    }
}
