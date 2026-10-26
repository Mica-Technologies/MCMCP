package com.micatechnologies.minecraft.mcmcp.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TargetStoreTest {

    private static TargetStore store() throws IOException {
        File folder = Files.createTempDirectory("mcmcp-targets").toFile();
        folder.deleteOnExit();
        return new TargetStore(folder);
    }

    private static BuildTarget one(int x, String state) {
        BuildTarget target = new BuildTarget(0);
        target.put(x, 64, 0, state);
        return target;
    }

    @Test
    void layersMergeInCreationOrderSoReRunningAnEarlyPhaseDoesNotCoverALaterOne() throws IOException {
        TargetStore store = store();
        store.save("alto1873/structure", one(0, "minecraft:air"), false, 100L);
        store.save("alto1873/fitout", one(0, "csm:desk"), false, 200L);
        // The structure is saved again after the fit-out: it is still the earlier layer.
        store.save("alto1873/structure", one(1, "csm:brick"), false, 300L);

        BuildTarget union = store.union("alto1873/*");

        assertNotNull(union);
        assertEquals("csm:desk", union.get(0, 64, 0), "the fit-out still wins its cell");
        assertEquals("csm:brick", union.get(1, 64, 0));
        assertEquals(Arrays.asList("alto1873/structure", "alto1873/fitout"),
            Arrays.asList(store.resolve("alto1873/*").get(0).name, store.resolve("alto1873/*").get(1).name));
    }

    @Test
    void savingUnderANameMergesUnlessToldToReplace() throws IOException {
        TargetStore store = store();
        store.save("a/b", one(0, "csm:brick"), false, 1L);
        store.save("a/b", one(1, "csm:brick"), false, 2L);
        assertEquals(2, store.load("a/b").cells());

        store.save("a/b", one(5, "csm:glass"), true, 3L);
        BuildTarget replaced = store.load("a/b");
        assertEquals(1, replaced.cells());
        assertNull(replaced.get(0, 64, 0));
    }

    @Test
    void aPatternMeansOnlyTheLayersUnderItAndDeleteTakesThemAll() throws IOException {
        TargetStore store = store();
        store.save("tower/structure", one(0, "csm:brick"), false, 1L);
        store.save("tower/life", one(1, "csm:strobe"), false, 2L);
        store.save("towers/other", one(2, "csm:brick"), false, 3L);

        assertEquals(2, store.resolve("tower/*").size(), "'tower/*' is not a prefix of 'towers/'");
        assertEquals(Arrays.asList("tower/structure", "tower/life"), store.delete("tower/*"));
        assertEquals(1, store.list().size());
    }

    @Test
    void refusesANameThatCouldEscapeTheFolder() throws IOException {
        TargetStore store = store();
        for (String bad : new String[] {"../x", "A", "a//b", "/a", "a/", "a b"}) {
            assertThrows(IOException.class, () -> store.save(bad, one(0, "csm:brick"), false, 1L), bad);
        }
    }
}
