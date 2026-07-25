package com.tailor.transcritorata.deps;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes this app's own leftover temporary files/directories from previous runs that didn't
 * shut down cleanly (crash, force-kill, power loss). Normal shutdowns never need this: every
 * temp file/directory this app creates (see {@code TranscriptionPipeline},
 * {@code WhisperCppEngine}, {@code AudioExtractor}) is already deleted in a {@code finally} block
 * immediately after the operation that created it finishes -- this is purely a backstop for
 * abnormal termination, best-effort and never allowed to block or fail startup.
 */
public final class StaleTempFileCleaner {

    private static final Logger LOG = LoggerFactory.getLogger(StaleTempFileCleaner.class);

    // Matches the prefixes used by every Files.createTempFile/createTempDirectory call in this
    // app (TranscriptionPipeline's per-run audio directory, WhisperCppEngine's whisper-cli JSON
    // output).
    private static final List<String> OWN_TEMP_PREFIXES = List.of("transcritor-ata-", "transcritor-ata-whisper-");

    // Deliberately generous: a transcription pipeline's temp directory only gets new entries
    // written into it during audio extraction -- a single very long transcribe step afterwards
    // (whisper-cli writes its own output elsewhere) means the directory's own last-modified time
    // can go quite a while without changing even though that run is still legitimately in
    // progress. This must never delete a directory a still-running instance of this app (this one
    // or another one started later) might be using.
    private static final Duration MIN_AGE = Duration.ofHours(24);

    private StaleTempFileCleaner() {
    }

    /** Scans the OS temp directory for this app's own stale leftovers and deletes them. */
    public static void cleanup() {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        try (var entries = Files.list(tempDir)) {
            entries.filter(StaleTempFileCleaner::isOwnStaleEntry).forEach(StaleTempFileCleaner::deleteQuietly);
        } catch (IOException e) {
            LOG.debug("Could not scan {} for leftover temporary files: {}", tempDir, e.getMessage());
        }
    }

    private static boolean isOwnStaleEntry(Path path) {
        String name = path.getFileName().toString();
        if (OWN_TEMP_PREFIXES.stream().noneMatch(name::startsWith)) {
            return false;
        }
        try {
            Instant lastModified = Files.getLastModifiedTime(path).toInstant();
            return lastModified.isBefore(Instant.now().minus(MIN_AGE));
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            if (Files.isDirectory(path)) {
                deleteRecursivelyWithoutFollowingReparsePoints(path);
            } else {
                Files.deleteIfExists(path);
            }
            LOG.info("Removed leftover temporary file from a previous run: {}", path.getFileName());
        } catch (IOException e) {
            LOG.debug("Could not remove leftover temporary file {}: {}", path, e.getMessage());
        }
    }

    /**
     * Deletes {@code root} and its contents, but never descends into an NTFS junction (a
     * directory reparse point) planted inside it. {@code Files.walk()}'s default symlink
     * avoidance does not recognize junctions -- unlike real symlinks, they require no special
     * privilege to create on Windows -- so a same-user process could otherwise plant one under
     * {@code root} and have this cleanup recursively delete whatever real directory it points to.
     * A junction is detected by comparing each subdirectory's real (resolved) path against
     * {@code root}'s: a plain subdirectory always resolves to somewhere under {@code root}, while
     * a junction resolves to its target instead. When that happens, only the junction entry
     * itself is removed -- never its target's contents.
     */
    private static void deleteRecursivelyWithoutFollowingReparsePoints(Path root) throws IOException {
        Path rootReal = root.toRealPath();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(root) && !dir.toRealPath().startsWith(rootReal)) {
                    try {
                        Files.deleteIfExists(dir);
                    } catch (IOException ignored) {
                        // Best-effort: leaves the rest of the sweep unaffected.
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // Best-effort: leaves the rest of the sweep unaffected.
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                try {
                    Files.deleteIfExists(dir);
                } catch (IOException ignored) {
                    // Best-effort: leaves the rest of the sweep unaffected.
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
