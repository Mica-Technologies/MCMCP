package com.micatechnologies.minecraft.mcmcp.client.tools;

import javax.annotation.Nullable;

/**
 * Whether a brought player must move to a contact sheet's next camera before it is drawn (#168).
 *
 * <p>Pure, so it is unit-tested: no Minecraft class is named here.
 *
 * <p>A brought player used to stay at the first camera, and an extra view got a trip of its own only
 * when it lay past the render distance. Views 100-170 blocks away, well inside it, still failed: a
 * detached camera draws only chunks the client has already been sent, and a throttled client had not
 * been sent them yet. Distance alone cannot see that, so the chunk under the camera is asked too.
 */
final class ViewHops {

    private ViewHops() {
    }

    /**
     * @param explicit the view's own {@code bringPlayer}, or null when it did not say: false never
     *                 moves the player, true always does
     * @param camera   the view's eye position, or null for a view with no {@code from}
     * @param player   where the player stands now
     * @param reach    how far from the player, in blocks on X or Z, a camera is trusted to be drawn
     * @param cameraChunkHeld whether the client holds a non-empty chunk under the camera right now
     */
    static boolean needsHop(@Nullable Boolean explicit, @Nullable double[] camera, double[] player,
                            double reach, boolean cameraChunkHeld) {
        if (camera == null || Boolean.FALSE.equals(explicit)) {
            return false;
        }
        if (Boolean.TRUE.equals(explicit) || !cameraChunkHeld) {
            return true;
        }
        return Math.max(Math.abs(camera[0] - player[0]), Math.abs(camera[2] - player[2])) > reach;
    }
}
