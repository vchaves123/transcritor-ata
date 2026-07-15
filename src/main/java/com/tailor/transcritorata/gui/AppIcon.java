package com.tailor.transcritorata.gui;

import java.io.InputStream;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the application icon (embedded in the jar under {@code /icon}) at the resolutions Windows
 * asks for (title bar, taskbar, Alt+Tab), and applies it to a {@link Shell}.
 */
final class AppIcon {

    private static final Logger LOG = LoggerFactory.getLogger(AppIcon.class);
    private static final int[] SIZES = { 16, 24, 32, 48, 64, 128 };

    // Loaded once and reused across all Shells (main window and dialogs) — Image is shareable
    // across Shells of the same Display, so reloading it every time a dialog opens would just
    // leak GDI handles for no reason.
    private static Image[] cachedImages;

    private AppIcon() {
    }

    /** Applies the app icon to {@code shell}, at every bundled resolution. */
    static void apply(Shell shell) {
        Display display = shell.getDisplay();
        if (cachedImages == null) {
            cachedImages = loadAll(display);
        }
        if (cachedImages.length > 0) {
            shell.setImages(cachedImages);
        }
    }

    private static Image[] loadAll(Display display) {
        java.util.List<Image> images = new java.util.ArrayList<>();
        for (int size : SIZES) {
            String path = "/icon/app-" + size + ".png";
            try (InputStream in = AppIcon.class.getResourceAsStream(path)) {
                if (in == null) {
                    continue;
                }
                images.add(new Image(display, in));
            } catch (Exception e) {
                LOG.debug("Could not load icon {}: {}", path, e.getMessage());
            }
        }
        return images.toArray(new Image[0]);
    }
}
