package com.micatechnologies.minecraft.mcmcp.chunkload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ViewRefreshTest {

    @Test
    void aViewsBoxCoversTheCameraAndItsSubjectWithAMarginRoundBoth() {
        int[] box = ViewRefresh.box(new double[] {-1366.5, 77, -6672.2}, new double[] {-1365, 74, -6640});
        assertArrayEquals(new int[] {-1367 - 40, -6673 - 40, -1365 + 40, -6640 + 40}, box);
        assertTrue(ChunkKeys.boxCount(box[0], box[1], box[2], box[3]) <= ViewRefresh.MAX_CHUNKS);
    }

    @Test
    void aViewAimedByYawAndPitchRefreshesRoundTheCamera() {
        int[] box = ViewRefresh.box(new double[] {100.9, 80, 200.1}, null);
        assertArrayEquals(new int[] {60, 160, 140, 240}, box);
    }

    @Test
    void aViewTooLongForOneRefreshKeepsTheElevenChunksRoundWhatItLooksAt() {
        double[] subject = {-896, 74, -6342};
        int[] box = ViewRefresh.box(new double[] {-600, 200, -5883}, subject);
        assertEquals(ViewRefresh.MAX_CHUNKS, ChunkKeys.boxCount(box[0], box[1], box[2], box[3]));
        int cx = -896 >> 4;
        int cz = -6342 >> 4;
        assertEquals((cx - 5) << 4, box[0]);
        assertEquals(((cz + 5) << 4) + 15, box[3]);
        assertTrue(box[0] <= subject[0] && subject[0] <= box[2] && box[1] <= subject[2] && subject[2] <= box[3]);
    }

    @Test
    void theLargestBoxThatStillFitsIsNotCutDown() {
        // 96 blocks apart on X with the margin each side spans 176 blocks: eleven or twelve chunks.
        int[] box = ViewRefresh.box(new double[] {0, 70, 0}, new double[] {95, 70, 0});
        long count = ChunkKeys.boxCount(box[0], box[1], box[2], box[3]);
        assertTrue(count <= ViewRefresh.MAX_CHUNKS, "never more than one call resends: " + count);
    }
}
