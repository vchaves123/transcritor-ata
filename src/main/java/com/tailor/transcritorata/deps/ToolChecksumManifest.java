package com.tailor.transcritorata.deps;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maintains {@code tools/CHECKSUMS.sha256}, the manifest {@link BundledToolIntegrityChecker}
 * verifies against. Previously generated once, at packaging time, by {@code package-installer.ps1}
 * (back when ffmpeg/whisper-cli were bundled into the installer); now populated incrementally by
 * {@link ToolPackageDownloader} as each tool is downloaded on demand instead, so the integrity
 * check still has something real to verify.
 */
final class ToolChecksumManifest {

    private static final Logger LOG = LoggerFactory.getLogger(ToolChecksumManifest.class);
    private static final String MANIFEST_FILE_NAME = "CHECKSUMS.sha256";

    private ToolChecksumManifest() {
    }

    /**
     * Recomputes the SHA-256 of every {@code .exe}/{@code .dll} under {@code packageDir} and
     * merges those entries into {@code toolsDir}/CHECKSUMS.sha256, replacing any existing entries
     * for that same subdirectory (e.g. a previous, now-superseded download of the same package).
     * Best-effort: a failure here is logged, never thrown -- the tool itself was already
     * checksum-verified as a zip before extraction, so a manifest-update failure must not block
     * the app from using it.
     */
    static void updateFor(Path toolsDir, Path packageDir) {
        try {
            Map<String, String> entries = readExisting(toolsDir);
            String prefix = toolsDir.relativize(packageDir).toString().replace('\\', '/') + "/";
            entries.keySet().removeIf(relativePath -> relativePath.startsWith(prefix));
            entries.putAll(hashPackageFiles(toolsDir, packageDir));
            write(toolsDir, entries);
        } catch (IOException e) {
            LOG.warn("Could not update the bundled-tools checksum manifest after downloading {}: {}",
                    packageDir, e.getMessage());
        }
    }

    private static Map<String, String> readExisting(Path toolsDir) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        Path manifest = toolsDir.resolve(MANIFEST_FILE_NAME);
        if (!Files.isRegularFile(manifest)) {
            return entries;
        }
        for (String rawLine : Files.readAllLines(manifest)) {
            String line = !rawLine.isEmpty() && rawLine.charAt(0) == '﻿' ? rawLine.substring(1) : rawLine;
            int separator = line.indexOf("  ");
            if (separator < 0) {
                continue;
            }
            entries.put(line.substring(separator + 2).trim(), line.substring(0, separator).trim());
        }
        return entries;
    }

    private static Map<String, String> hashPackageFiles(Path toolsDir, Path packageDir) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (!Files.isDirectory(packageDir)) {
            return result;
        }
        try (Stream<Path> walk = Files.walk(packageDir)) {
            List<Path> hashable = walk.filter(Files::isRegularFile).filter(ToolChecksumManifest::hasHashableExtension).toList();
            for (Path file : hashable) {
                String relativePath = toolsDir.relativize(file).toString().replace('\\', '/');
                result.put(relativePath, HexFormat.of().formatHex(sha256(file)));
            }
        }
        return result;
    }

    private static boolean hasHashableExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") || name.endsWith(".dll");
    }

    private static byte[] sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed to be available on every JDK", e);
        }
    }

    /**
     * Writes to a sibling temp file first, then atomically renames it over the live manifest --
     * same crash-safety rationale as {@code AppConfig.save()}.
     */
    private static void write(Path toolsDir, Map<String, String> entries) throws IOException {
        List<String> lines = entries.entrySet().stream().map(e -> e.getValue() + "  " + e.getKey()).toList();
        Path manifest = toolsDir.resolve(MANIFEST_FILE_NAME);
        Path tempFile = manifest.resolveSibling(manifest.getFileName() + ".tmp");
        Files.write(tempFile, lines, StandardCharsets.UTF_8);
        try {
            Files.move(tempFile, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileSystemException e) {
            Files.move(tempFile, manifest, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
