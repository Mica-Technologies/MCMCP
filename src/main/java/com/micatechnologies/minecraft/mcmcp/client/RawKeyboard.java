package com.micatechnologies.minecraft.mcmcp.client;

import com.micatechnologies.minecraft.mcmcp.Mcmcp;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

/**
 * Keys held down where LWJGL itself reports them (#83).
 *
 * <p>A keybinding hold ({@code KeyBinding.setKeyBindState}) is invisible to a mod that asks LWJGL
 * directly with {@link Keyboard#isKeyDown}, as Immersive Vehicles does for every driving control. That
 * answer comes from {@code Keyboard.keyDownBuffer}, which {@code Display.update()} refills from the
 * operating system at the end of every frame. So a raw hold writes its byte back into the buffer
 * after each refill: at the start of the next client tick and the next render tick, at the highest
 * priority, before any mod's handler reads it.
 *
 * <p>LWJGL is not obfuscated, so the field is found by its name in a released jar too.
 *
 * <p>Client thread only.
 */
@SideOnly(Side.CLIENT)
public final class RawKeyboard {

    /** Key code to the number of holds on it, so two holds of one key release only when both end. */
    private static final Map<Integer, Integer> HELD = new HashMap<>();
    /** Keys LWJGL itself has reported down since their hold began: proof a polling mod saw them. */
    private static final Set<Integer> SEEN = new HashSet<>();

    private static ByteBuffer buffer;
    private static boolean lookedUp;

    private RawKeyboard() {
    }

    /** Whether raw holds can work in this game: LWJGL's buffer was found. */
    public static boolean available() {
        return buffer() != null;
    }

    public static void down(int keyCode) {
        Integer count = HELD.get(keyCode);
        HELD.put(keyCode, count == null ? 1 : count + 1);
        if (count == null) {
            SEEN.remove(keyCode);
        }
        write(keyCode, true);
    }

    public static void up(int keyCode) {
        Integer count = HELD.get(keyCode);
        if (count == null) {
            return;
        }
        if (count > 1) {
            HELD.put(keyCode, count - 1);
            return;
        }
        HELD.remove(keyCode);
        // Down until the next poll otherwise; a human holding the same key gets it back at that poll.
        write(keyCode, false);
    }

    /** Writes every held key back over the last poll. */
    public static void reassert() {
        if (HELD.isEmpty()) {
            return;
        }
        for (Integer keyCode : HELD.keySet()) {
            write(keyCode, true);
            // Asked the way a polling mod asks, so the reply can say the key was seen, not just set.
            if (Keyboard.isCreated() && Keyboard.isKeyDown(keyCode)) {
                SEEN.add(keyCode);
            }
        }
    }

    /** Whether LWJGL reported {@code keyCode} down at least once during its latest hold. */
    public static boolean seen(int keyCode) {
        return SEEN.contains(keyCode);
    }

    public static int heldCount() {
        return HELD.size();
    }

    private static void write(int keyCode, boolean down) {
        ByteBuffer target = buffer();
        if (target != null && keyCode > 0 && keyCode < target.capacity()) {
            target.put(keyCode, (byte) (down ? 1 : 0));
        }
    }

    private static ByteBuffer buffer() {
        if (!lookedUp) {
            lookedUp = true;
            try {
                Field field = Keyboard.class.getDeclaredField("keyDownBuffer");
                field.setAccessible(true);
                buffer = (ByteBuffer) field.get(null);
            }
            catch (ReflectiveOperationException | RuntimeException e) {
                Mcmcp.LOGGER.warn("MCMCP cannot reach LWJGL's key buffer; raw key holds are unavailable", e);
            }
        }
        return buffer;
    }
}
