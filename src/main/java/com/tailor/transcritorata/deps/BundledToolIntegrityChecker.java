package com.tailor.transcritorata.deps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort integrity check of the bundled {@code tools/} executables against the
 * {@code tools/CHECKSUMS.sha256} manifest generated at packaging time (see
 * {@code package-portable.ps1}).
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

    private BundledToolIntegrityChecker() {
    }

    /** Logs a warning for every bundled executable whose hash doesn't match the shipped manifest. */
    public static void verify() {
        Path manifest = AppHome.resolve(MANIFEST_RELATIVE_PATH);
        if (!Files.isRegularFile(manifest)) {
            // Expected in dev builds (mvn/IDE) that never ran package-portable.ps1; not a concern.
            LOG.debug("No bundled-tools checksum manifest at {}; skipping integrity check.", manifest);
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(manifest);
        } catch (IOException e) {
            LOG.warn("Could not read bundled-tools checksum manifest {}: {}", manifest, e.getMessage());
            return;
        }
        Path toolsDir = AppHome.resolve("tools");
        for (String line : lines) {
            verifyLine(toolsDir, line);
        }
    }

    private static void verifyLine(Path toolsDir, String line) {
        // Defensive: strip a leading UTF-8 BOM (U+FEFF), in case the manifest was ever
        // regenerated or hand-edited with a tool that adds one.
        if (!line.isEmpty() && line.charAt(0) == '﻿') {
            line = line.substring(1);
        }
        int separator = line.indexOf("  ");
        if (separator < 0) {
            return;
        }
        String expectedHash = line.substring(0, separator).trim();
        String relativePath = line.substring(separator + 2).trim();
        Path file = toolsDir.resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            // Not bundled in this build (e.g. only the CPU whisper-cli variant was shipped) --
            // not a mismatch, just absent.
            return;
        }
        try {
            String actualHash = HexFormat.of().formatHex(sha256(file));
            if (!actualHash.equalsIgnoreCase(expectedHash)) {
                LOG.warn("Bundled tool {} does not match its shipped checksum (expected {}, got {}) -- it may "
                        + "have been corrupted or modified after installation.", file, expectedHash, actualHash);
            }
        } catch (IOException e) {
            LOG.warn("Could not verify checksum of bundled tool {}: {}", file, e.getMessage());
        }
    }

    private static byte[] sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
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
}
