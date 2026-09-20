package com.micatechnologies.minecraft.mcmcp.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The parser behind {@code game_heap_histogram}. */
class HeapHistogramTest {

    /** What Java 8 prints — the JVM 1.12.2 runs on. */
    private static final String JAVA_8 =
        "\n"
        + " num     #instances         #bytes  class name\n"
        + "----------------------------------------------\n"
        + "   1:        412345       52428800  [B\n"
        + "   2:        300000       10485760  java.lang.String\n"
        + "   3:          5000        2097152  com.example.signals.block.TileSignal\n"
        + "   4:          2000        1048576  com.example.signals.render.SignalModel\n"
        + "   5:           100         524288  [[Ljava.lang.Object;\n"
        + "Total        719445       66584576\n";

    /** Java 9 and later name the module after the class. */
    private static final String JAVA_17 =
        " num     #instances         #bytes  class name (module)\n"
        + "-------------------------------------------------------\n"
        + "   1:        412345       52428800  [B (java.base@17.0.19)\n"
        + "   2:          5000        2097152  com.example.signals.block.TileSignal\n"
        + "Total        417345       54525952\n";

    @Test
    void headerTotalAndBlankLinesAreNotRows() {
        HeapHistogram histogram = HeapHistogram.parse(JAVA_8);

        assertEquals(5, histogram.classCount());
        assertEquals(719_445L, histogram.totalInstances());
        assertEquals(66_584_576L, histogram.totalBytes());
    }

    @Test
    void aModuleSuffixDoesNotBecomePartOfTheClassName() {
        HeapHistogram histogram = HeapHistogram.parse(JAVA_17);

        assertEquals(2, histogram.classCount());
        assertEquals("byte[]", histogram.largest(1, null).get(0).className);
    }

    @Test
    void arrayDescriptorsReadTheWayAPersonWouldWriteThem() {
        assertEquals("byte[]", HeapHistogram.readable("[B"));
        assertEquals("int[][]", HeapHistogram.readable("[[I"));
        assertEquals("java.lang.Object[][]", HeapHistogram.readable("[[Ljava.lang.Object;"));
        assertEquals("java.lang.String", HeapHistogram.readable("java.lang.String"));
    }

    @Test
    void aFilterNarrowsTheClassListToOneMod() {
        List<HeapHistogram.Row> rows = HeapHistogram.parse(JAVA_8).largest(10, "com.example");

        assertEquals(2, rows.size());
        assertEquals("com.example.signals.block.TileSignal", rows.get(0).className);
    }

    @Test
    void aModsManySmallClassesAddUpInThePackageRollUp() {
        List<HeapHistogram.Row> packages = HeapHistogram.parse(JAVA_8).largestPackages(10);

        assertEquals("java.lang", packages.get(0).className);
        // String and Object[][] both belong to java.lang.
        assertEquals(10_485_760L + 524_288L, packages.get(0).bytes);
        assertEquals("com.example.signals", packages.get(1).className);
        assertEquals(7000L, packages.get(1).instances);
        assertEquals(2_097_152L + 1_048_576L, packages.get(1).bytes);
    }

    @Test
    void primitiveArraysAreNotAPackage() {
        // byte[] is the biggest row in the sample. It leads the class list, and saying so again in
        // the roll-up would only push a real package off it.
        List<HeapHistogram.Row> packages = HeapHistogram.parse(JAVA_8).largestPackages(10);

        assertEquals(2, packages.size());
    }

    @Test
    void theRunningJvmsOwnHistogramParses() throws Exception {
        // The one test here that is not hermetic, and deliberately so: the format is the JVM's, not
        // ours, and a sample pasted into a test proves only that the parser reads the sample.
        // '-all', so the test suite does not pay for a full collection.
        HeapHistogram histogram = HeapHistogram.parse(HeapHistogram.capture(false));

        assertTrue(histogram.classCount() > 100, "parsed " + histogram.classCount() + " classes");
        assertTrue(histogram.totalBytes() > 1_000_000L);
        assertEquals(1, histogram.largest(50, "java.lang.String").stream()
            .filter(row -> row.className.equals("java.lang.String")).count());
    }
}
