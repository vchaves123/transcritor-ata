package com.tailor.transcritorata.deps;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolChecksumManifestTest {

    @Test
    void hashesExeAndDllButNotOtherFiles(@TempDir Path toolsDir) throws Exception {
        Path packageDir = toolsDir.resolve("ffmpeg");
        Path exe = writeFile(packageDir.resolve("bin/ffmpeg.exe"), "exe-content");
        writeFile(packageDir.resolve("bin/readme.txt"), "not hashed");

        ToolChecksumManifest.updateFor(toolsDir, packageDir);

        List<String> lines = Files.readAllLines(toolsDir.resolve("CHECKSUMS.sha256"));
        assertEquals(1, lines.size());
        assertEquals(sha256Hex(exe) + "  ffmpeg/bin/ffmpeg.exe", lines.get(0));
    }

    @Test
    void replacesStaleEntriesForTheSamePackageOnRedownload(@TempDir Path toolsDir) throws Exception {
        Path packageDir = toolsDir.resolve("whisper-cpu");
        Path exe = writeFile(packageDir.resolve("Release/whisper-cli.exe"), "first-version");
        ToolChecksumManifest.updateFor(toolsDir, packageDir);
        String firstHash = sha256Hex(exe);

        writeFile(packageDir.resolve("Release/whisper-cli.exe"), "second-version");
        ToolChecksumManifest.updateFor(toolsDir, packageDir);

        List<String> lines = Files.readAllLines(toolsDir.resolve("CHECKSUMS.sha256"));
        assertEquals(1, lines.size(), "must not accumulate a duplicate stale entry for the same file");
        assertFalse(lines.get(0).startsWith(firstHash), "must reflect the new content, not the old one");
        assertEquals(sha256Hex(exe) + "  whisper-cpu/Release/whisper-cli.exe", lines.get(0));
    }

    @Test
    void preservesEntriesFromOtherPackages(@TempDir Path toolsDir) throws Exception {
        Path ffmpegDir = toolsDir.resolve("ffmpeg");
        Path ffmpegExe = writeFile(ffmpegDir.resolve("bin/ffmpeg.exe"), "ffmpeg-content");
        ToolChecksumManifest.updateFor(toolsDir, ffmpegDir);

        Path whisperDir = toolsDir.resolve("whisper-cpu");
        writeFile(whisperDir.resolve("Release/whisper-cli.exe"), "whisper-content");
        ToolChecksumManifest.updateFor(toolsDir, whisperDir);

        List<String> lines = Files.readAllLines(toolsDir.resolve("CHECKSUMS.sha256"));
        assertEquals(2, lines.size());
        assertTrue(lines.contains(sha256Hex(ffmpegExe) + "  ffmpeg/bin/ffmpeg.exe"));
    }

    private static Path writeFile(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }
}
