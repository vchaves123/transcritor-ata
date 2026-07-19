package com.tailor.transcritorata.deps;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the directory that should contain this app's bundled {@code tools/} folder (ffmpeg,
 * whisper-cli, models).
 *
 * <p>The bundled tools ship as a {@code tools/} folder sitting next to the jpackage-generated
 * launcher executable. Naively resolving {@code Path.of("tools/...")} looks it up relative to the
 * JVM's current working directory instead — which usually happens to match when the app is
 * launched by double-clicking the exe (Explorer sets CWD to the exe's folder), but not when
 * launched via a shortcut with a custom "Start in" folder, a script, or a scheduled task. Worse,
 * anyone who can write into whatever folder ends up as the CWD could plant a same-named file
 * there and have it trusted and executed as if it were the bundled binary. Resolving instead
 * against the running launcher's own directory (via {@link ProcessHandle}) ties the lookup to the
 * actual install location regardless of how the app was launched.
 */
public final class AppHome {

    private static final Path DIRECTORY = resolveDirectory();

    private AppHome() {
    }

    /** @return the directory that should contain this app's bundled "tools/" folder. */
    public static Path directory() {
        return DIRECTORY;
    }

    /** @return {@code relativePath} resolved against the app's install directory (not the CWD). */
    public static Path resolve(String relativePath) {
        return DIRECTORY.resolve(relativePath);
    }

    private static Path resolveDirectory() {
        try {
            Path launcherPath = ProcessHandle.current().info().command().map(Path::of).orElse(null);
            if (launcherPath != null) {
                Path launcherDir = launcherPath.getParent();
                if (launcherDir != null && Files.isDirectory(launcherDir.resolve("tools"))) {
                    return launcherDir;
                }
            }
        } catch (RuntimeException e) {
            // Fall through to the working-directory fallback below.
        }
        // Not a packaged app-image launch (e.g. running from Maven/an IDE during development) --
        // the working directory is the project root, which already contains "tools/" directly.
        return Path.of("").toAbsolutePath();
    }
}
