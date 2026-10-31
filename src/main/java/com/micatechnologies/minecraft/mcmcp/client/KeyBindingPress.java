package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import java.lang.reflect.Field;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Presses one {@link KeyBinding} by its own state, not by the key it is bound to (#165).
 *
 * <p>{@code KeyBinding.setKeyBindState} and {@code onTick} look a binding up by key code, through
 * Forge's conflict-context and modifier map. A mod's binding is often unbound, bound with a modifier,
 * or sharing a key with a binding of another context, and then the lookup finds something else or
 * nothing. Setting the binding's {@code pressed} flag and bumping its {@code pressTime} is exactly
 * what a found binding gets, so {@code isKeyDown()} and {@code isPressed()} read the same as for a
 * real key.
 *
 * <p>Both fields are private with no setters; each is looked up by its MCP and SRG name, because a
 * released jar has the SRG one. Client thread only.
 */
@SideOnly(Side.CLIENT)
public final class KeyBindingPress {

    @Nullable
    private static final Field PRESSED = field("pressed", "field_74513_e");
    @Nullable
    private static final Field PRESS_TIME = field("pressTime", "field_151474_i");

    private KeyBindingPress() {
    }

    @Nullable
    private static Field field(String... names) {
        for (String name : names) {
            try {
                Field field = KeyBinding.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            }
            catch (NoSuchFieldException | RuntimeException ignored) {
                // Try the other spelling.
            }
        }
        Mcmcp.LOGGER.warn("MCMCP cannot find KeyBinding.{}; pressing a keybinding by id is unavailable",
            names[0]);
        return null;
    }

    /** Whether bindings can be pressed directly in this game. */
    public static boolean available() {
        return PRESSED != null && PRESS_TIME != null;
    }

    /** The registered binding whose description is {@code id}, such as key.jei.showRecipe, or null. */
    @Nullable
    public static KeyBinding find(String id) {
        for (KeyBinding binding : Minecraft.getMinecraft().gameSettings.keyBindings) {
            if (binding.getKeyDescription().equals(id)) {
                return binding;
            }
        }
        return null;
    }

    public static void press(KeyBinding binding) {
        try {
            if (PRESSED != null && PRESS_TIME != null) {
                PRESSED.setBoolean(binding, true);
                PRESS_TIME.setInt(binding, PRESS_TIME.getInt(binding) + 1);
            }
        }
        catch (IllegalAccessException e) {
            Mcmcp.LOGGER.warn("MCMCP could not press keybinding {}", binding.getKeyDescription(), e);
        }
    }

    /**
     * Presses not yet consumed by an {@code isPressed()} call, or -1 if unreadable. Zero after a
     * press means something read it, which is the evidence a caller wants.
     */
    public static int unconsumed(KeyBinding binding) {
        try {
            return PRESS_TIME == null ? -1 : PRESS_TIME.getInt(binding);
        }
        catch (IllegalAccessException e) {
            return -1;
        }
    }

    public static void release(KeyBinding binding) {
        try {
            if (PRESSED != null) {
                PRESSED.setBoolean(binding, false);
            }
        }
        catch (IllegalAccessException e) {
            Mcmcp.LOGGER.warn("MCMCP could not release keybinding {}", binding.getKeyDescription(), e);
        }
    }
}
