package com.micatechnologies.minecraft.mcmcp.chunkload;

import javax.annotation.Nullable;

/**
 * Which chunks a detached view has the server send again before it is drawn (#187).
 *
 * <p>A client draws a 'from' view from its own copy of the world, and its copy of a chunk the server
 * has stopped sending it changes for is as old as the player's last visit. {@code server_refresh_chunks}
 * sends such chunks again; this works out the box to ask for. Pure, so the arithmetic is unit-tested.
 */
public final class ViewRefresh {

    /** Most chunks one refresh covers: 11 x 11. */
    public static final int MAX_CHUNKS = 121;

    /** Blocks round the camera and its subject that are refreshed with them. */
    public static final int MARGIN = 40;

    /** Chunks each side of the subject's when the whole view is too much for one refresh. */
    private static final int SUBJECT_RADIUS = 5;

    private ViewRefresh() {
    }

    /**
     * The block box {@code [minX, minZ, maxX, maxZ]} to refresh for a view from {@code from}: the
     * camera and what it looks at, with {@link #MARGIN} round them. When that is more than
     * {@link #MAX_CHUNKS}, the eleven chunks square round the subject instead: what the picture is
     * of matters more than the ground the camera stands over.
     *
     * @param lookAt the point looked at, or null for a view aimed by yaw and pitch, whose subject is
     *               taken to be round the camera
     */
    public static int[] box(double[] from, @Nullable double[] lookAt) {
        double[] subject = lookAt != null ? lookAt : from;
        int minX = floor(Math.min(from[0], subject[0])) - MARGIN;
        int maxX = floor(Math.max(from[0], subject[0])) + MARGIN;
        int minZ = floor(Math.min(from[2], subject[2])) - MARGIN;
        int maxZ = floor(Math.max(from[2], subject[2])) + MARGIN;
        if (ChunkKeys.boxCount(minX, minZ, maxX, maxZ) <= MAX_CHUNKS) {
            return new int[] {minX, minZ, maxX, maxZ};
        }
        int cx = floor(subject[0]) >> 4;
        int cz = floor(subject[2]) >> 4;
        return new int[] {(cx - SUBJECT_RADIUS) << 4, (cz - SUBJECT_RADIUS) << 4,
            ((cx + SUBJECT_RADIUS) << 4) + 15, ((cz + SUBJECT_RADIUS) << 4) + 15};
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
