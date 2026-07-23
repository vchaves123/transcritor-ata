package com.tailor.transcritorata.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tailor.transcritorata.deps.AppHome;

/**
 * Invoked by the installer's uninstall chain (see {@code installer/bundle.wxs}) as
 * {@code java -cp transcritor-ata.jar com.tailor.transcritorata.gui.MainApp --uninstall-cleanup},
 * before the app's own files are removed.
 *
 * <p>The downloaded Whisper transcription model(s) under {@code tools/models/} can be several GB
 * and aren't tracked by the installer at all (they're fetched by the app itself after
 * installation, via {@link ModelSetupDialog}, not part of the installer's own file list) --
 * without this, uninstalling would silently leave them orphaned on disk. Runs as its own
 * unelevated bundle-chain step (not a deferred custom action inside the per-machine MSI) so the
 * confirmation dialog is always shown in the user's interactive session, regardless of whether
 * the per-user or the per-machine installer variant was used.
 */
final class UninstallCleanup {

    private static final Logger LOG = LoggerFactory.getLogger(UninstallCleanup.class);

    private UninstallCleanup() {
    }

    static void run() {
        Path modelsDir = AppHome.resolve("tools/models");
        if (!Files.isDirectory(modelsDir)) {
            LOG.debug("No models directory at {}; nothing to offer to clean up.", modelsDir);
            return;
        }
        long sizeBytes;
        try {
            sizeBytes = directorySize(modelsDir);
        } catch (IOException e) {
            LOG.warn("Could not compute the size of {}: {}", modelsDir, e.getMessage());
            return;
        }
        if (sizeBytes <= 0) {
            return;
        }

        Display display = new Display();
        try {
            Shell shell = new Shell(display);
            AppIcon.apply(shell);
            MessageBox box = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
            box.setText("Uninstall transcritor-ata");
            box.setMessage("Also delete the downloaded transcription model(s)?\n\n" + modelsDir
                    + "\n\nThis will free up " + formatBytes(sizeBytes)
                    + ". Keep it if you plan to reinstall transcritor-ata later and don't want to "
                    + "download the model again.");
            boolean deleteModels = box.open() == SWT.YES;
            shell.dispose();

            if (deleteModels) {
                deleteRecursively(modelsDir);
                LOG.info("Deleted the transcription models directory at {} on uninstall.", modelsDir);
            } else {
                LOG.info("User chose to keep the transcription models directory at {} on uninstall.", modelsDir);
            }
        } finally {
            display.dispose();
        }
    }

    /** Package-visible for testing. */
    static long directorySize(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        }
    }

    /** Package-visible for testing. */
    static void deleteRecursively(Path path) {
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.warn("Could not delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.warn("Could not walk {} for deletion: {}", path, e.getMessage());
        }
    }

    /** Package-visible for testing. */
    static String formatBytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 1024) {
            return String.format(Locale.US, "%.0f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
