package com.micatechnologies.minecraft.mcmcp.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading rolled, gzipped logs as the text they hold (#213). */
class LogTailTest {

    @TempDir
    Path dir;

    private File gzip(String name, String text) throws IOException {
        File file = dir.resolve(name).toFile();
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file.toPath()))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static String log(int lines) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            text.append("[00:00:").append(i).append("] [Server thread/INFO]: line ").append(i);
            if (i == 7) {
                text.append(" Error ticking block entity");
            }
            text.append('\n');
        }
        return text.toString();
    }

    @Test
    void aGzippedLogIsReadAsTheTextItHoldsNewestLast() throws IOException {
        File file = gzip("2026-11-19-1.log.gz", log(50));
        List<String> lines = McmcpPaths.tail(file, 3, null);
        assertEquals(Arrays.asList("[00:00:48] [Server thread/INFO]: line 48", "[00:00:49] [Server thread/INFO]: line 49",
            "[00:00:50] [Server thread/INFO]: line 50"), lines);
    }

    @Test
    void aFilterFindsTextAnywhereInAGzippedLog() throws IOException {
        File file = gzip("2026-11-19-2.log.gz", log(100_000));
        List<String> lines = McmcpPaths.tail(file, 5, "error ticking");
        assertEquals(1, lines.size(), "the one line, though it is 100,000 lines from the end");
        assertTrue(lines.get(0).contains("line 7 Error ticking"), lines.get(0));
    }

    @Test
    void theRewriteRunsBeforeTheFilterOnAGzippedLogToo() throws IOException {
        File file = gzip("a.log.gz", "joined from 10.0.0.5\nother\n");
        List<String> lines = McmcpPaths.tail(file, 10, "joined", line -> line.replace("10.0.0.5", "x.x.x.x"));
        assertEquals(Arrays.asList("joined from x.x.x.x"), lines, "masked, as the companion reads it");
        assertTrue(McmcpPaths.tail(file, 10, "10.0.0.5", line -> line.replace("10.0.0.5", "x.x.x.x")).isEmpty(),
            "a filter cannot find what the mask took out");
    }

    @Test
    void aGzipIsKnownByItsBytesNotItsName() throws IOException {
        File named = gzip("renamed.log", log(5));
        assertEquals(McmcpPaths.LogKind.GZIP, McmcpPaths.kindOf(named));
        assertEquals(5, McmcpPaths.tail(named, 10, null).size());
        File plain = dir.resolve("plain.log.gz").toFile();
        Files.write(plain.toPath(), "just text\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(Arrays.asList("just text"), McmcpPaths.tail(plain, 10, null));
    }

    @Test
    void aPlainLogsNewestLineIsItsLastLineNotTheEmptyOneAfterItsFinalNewline() throws IOException {
        File plain = dir.resolve("latest.log").toFile();
        Files.write(plain.toPath(), "first\r\nsecond\r\nthird\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(Arrays.asList("third"), McmcpPaths.tail(plain, 1, null));
        assertEquals(Arrays.asList("first", "second", "third"), McmcpPaths.tail(plain, 10, null));
        Files.write(plain.toPath(), "no newline at the end".getBytes(StandardCharsets.UTF_8));
        assertEquals(Arrays.asList("no newline at the end"), McmcpPaths.tail(plain, 1, null));
    }

    @Test
    void aFileThatIsNotTextIsRefusedRatherThanReportedEmpty() throws IOException {
        File binary = dir.resolve("crash.bin").toFile();
        Files.write(binary.toPath(), new byte[] {'P', 'K', 3, 4, 0, 0, 1, 2});
        assertThrows(McmcpPaths.NotTextException.class, () -> McmcpPaths.tail(binary, 10, "error"));
    }

    @Test
    void aDamagedGzipIsAnErrorNotZeroMatches() throws IOException {
        File good = gzip("good.log.gz", log(2000));
        byte[] bytes = Files.readAllBytes(good.toPath());
        File cut = dir.resolve("cut.log.gz").toFile();
        Files.write(cut.toPath(), Arrays.copyOf(bytes, bytes.length / 2));
        IOException failure = assertThrows(IOException.class, () -> McmcpPaths.tail(cut, 10, "line"));
        assertTrue(failure.getMessage().contains("cut.log.gz"), failure.getMessage());
    }

    @Test
    void theListingIsNewestFirstAndSaysWhichFilesAreGzipped() throws IOException {
        File older = gzip("2026-11-19-1.log.gz", log(3));
        File newer = dir.resolve("latest.log").toFile();
        Files.write(newer.toPath(), "x\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(older.setLastModified(1_000_000L));
        assertTrue(newer.setLastModified(2_000_000L));
        assertTrue(dir.resolve("sub").toFile().mkdir());
        List<McmcpPaths.LogFile> files = McmcpPaths.listLogs(dir.toFile(), 10);
        assertEquals(2, files.size(), "directories are left out");
        assertEquals("latest.log", files.get(0).name);
        assertEquals("2026-11-19-1.log.gz", files.get(1).name);
        assertTrue(files.get(1).gzip);
        assertEquals(1, McmcpPaths.listLogs(dir.toFile(), 1).size());
    }
}
