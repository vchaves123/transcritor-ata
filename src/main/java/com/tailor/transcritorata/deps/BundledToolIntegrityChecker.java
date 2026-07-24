package com.tailor.transcritorata.deps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort integrity check of the bundled {@code tools/} executables/libraries against the
 * {@code tools/CHECKSUMS.sha256} manifest generated at packaging time (see
 * {@code package-installer.ps1}).
 *
 * <p><b>What this does and doesn't defend against:</b> the manifest ships inside the same zip as
 * the binaries it describes, so it cannot detect a tampered release asset itself (that would
 * require signing the zip/binaries, out of scope here) -- what it does catch is local corruption
 * or in-place modification of a bundled executable <em>after</em> extraction (disk corruption, a
 * partial/interrupted unzip, or another local process altering a file post-install). Mismatches
 * are only logged as a warning, never block startup: a false positive here must not prevent the
 * app from running.
 */
public final class BundledToolIntegrityChecker {

    private static final Logger LOG = LoggerFactory.getLogger(BundledToolIntegrityChecker.class);
    private static final String MANIFEST_RELATIVE_PATH = "tools/CHECKSUMS.sha256";

    /**
     * Reports cumulative progress while verifying, by bytes hashed so far out of the total --
     * bytes rather than file count, since a handful of huge files (e.g. CUDA runtime DLLs, each
     * several hundred MB) can dwarf everything else combined, and a file-count progress bar would
     * misleadingly crawl through dozens of small files before appearing to "jump" on those.
     */
    public interface ProgressListener {
        void onProgress(long bytesChecked, long totalBytes);
    }

    private record ManifestEntry(Path file, String expectedHash, long size) {
    }

    private BundledToolIntegrityChecker() {
    }

    /** Logs a warning for every bundled tool whose hash doesn't match the shipped manifest. */
    public static void verify() {
        verify((checked, total) -> { });
    }

    /** Same as {@link #verify()}, but also reports progress (by bytes hashed) as it goes. */
    public static void verify(ProgressListener listener) {
        Path manifest = AppHome.resolve(MANIFEST_RELATIVE_PATH);
        if (!Files.isRegularFile(manifest)) {
            // Expected in dev builds (mvn/IDE) that never ran package-installer.ps1; not a concern.
            LOG.debug("No bundled-tools checksum manifest at {}; skipping integrity check.", manifest);
            listener.onProgress(1, 1);
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(manifest);
        } catch (IOException e) {
            LOG.warn("Could not read bundled-tools checksum manifest {}: {}", manifest, e.getMessage());
            listener.onProgress(1, 1);
            return;
        }

        List<ManifestEntry> entries = parseEntries(AppHome.resolve("tools"), lines);
        long totalBytes = entries.stream().mapToLong(ManifestEntry::size).sum();
        if (totalBytes == 0) {
            listener.onProgress(1, 1);
            return;
        }

        long[] bytesCheckedSoFar = { 0 };
        for (ManifestEntry entry : entries) {
            verifyEntry(entry, bytesRead -> {
                bytesCheckedSoFar[0] += bytesRead;
                listener.onProgress(bytesCheckedSoFar[0], totalBytes);
            });
        }
    }

    /** Parses the manifest into the entries that actually exist on disk, with their current size. */
    private static List<ManifestEntry> parseEntries(Path toolsDir, List<String> lines) {
        List<ManifestEntry> entries = new ArrayList<>();
        for (String rawLine : lines) {
            // Defensive: strip a leading UTF-8 BOM (U+FEFF), in case the manifest was ever
            // regenerated or hand-edited with a tool that adds one.
            String line = !rawLine.isEmpty() && rawLine.charAt(0) == '﻿' ? rawLine.substring(1) : rawLine;
            int separator = line.indexOf("  ");
            if (separator < 0) {
                continue;
            }
            String expectedHash = line.substring(0, separator).trim();
            String relativePath = line.substring(separator + 2).trim();
            Path file = toolsDir.resolve(relativePath);
            if (!Files.isRegularFile(file)) {
                // Not bundled in this build (e.g. only the CPU whisper-cli variant was shipped) --
                // not a mismatch, just absent.
                continue;
            }
            try {
                entries.add(new ManifestEntry(file, expectedHash, Files.size(file)));
            } catch (IOException e) {
                LOG.debug("Could not read the size of bundled tool {}: {}", file, e.getMessage());
            }
        }
        return entries;
    }

    private static void verifyEntry(ManifestEntry entry, LongConsumer onBytesRead) {
        try {
            String actualHash = HexFormat.of().formatHex(sha256(entry.file(), onBytesRead));
            if (!actualHash.equalsIgnoreCase(entry.expectedHash())) {
                LOG.warn("Bundled tool {} does not match its shipped checksum (expected {}, got {}) -- it may "
                        + "have been corrupted or modified after installation.", entry.file(), entry.expectedHash(),
                        actualHash);
            }
        } catch (IOException e) {
            LOG.warn("Could not verify checksum of bundled tool {}: {}", entry.file(), e.getMessage());
        }
    }

    private static byte[] sha256(Path file, LongConsumer onBytesRead) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    onBytesRead.accept(read);
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed to be available on every JDK", e);
        }
    }
}
