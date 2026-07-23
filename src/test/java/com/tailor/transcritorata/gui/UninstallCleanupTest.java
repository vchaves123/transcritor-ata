package com.tailor.transcritorata.gui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UninstallCleanupTest {

    @Test
    void directorySizeSumsAllRegularFilesRecursively(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.bin"), "12345");
        Path subDir = Files.createDirectory(dir.resolve("sub"));
        Files.writeString(subDir.resolve("b.bin"), "1234567");

        assertEquals(5 + 7, UninstallCleanup.directorySize(dir));
    }

    @Test
    void deleteRecursivelyRemovesEverythingIncludingTheRootDirectory(@TempDir Path dir) throws Exception {
        Path subDir = Files.createDirectory(dir.resolve("sub"));
        Files.writeString(dir.resolve("a.bin"), "x");
        Files.writeString(subDir.resolve("b.bin"), "y");

        UninstallCleanup.deleteRecursively(dir);

        assertFalse(Files.exists(dir));
    }

    @Test
    void formatBytesUsesMbBelowOneGbAndGbAbove() {
        assertEquals("500 MB", UninstallCleanup.formatBytes(500L * 1024 * 1024));
        assertTrue(UninstallCleanup.formatBytes(2L * 1024 * 1024 * 1024).endsWith("GB"));
    }
}
