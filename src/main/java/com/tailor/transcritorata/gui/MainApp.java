package com.tailor.transcritorata.gui;

import java.nio.file.Path;

import org.eclipse.swt.widgets.Display;

import com.tailor.transcritorata.config.AppConfig;
import com.tailor.transcritorata.deps.BundledFfmpegSelector;
import com.tailor.transcritorata.deps.BundledToolIntegrityChecker;
import com.tailor.transcritorata.deps.ExecutableLocator;
import com.tailor.transcritorata.deps.GpuDetector;
import com.tailor.transcritorata.deps.WhisperVariantSelector;

/**
 * Application entry point. On Windows, SWT runs fine on the main thread; the
 * {@code -XstartOnFirstThread} requirement applies only to macOS (documented in the README).
 */
public final class MainApp {

    private static final String UNINSTALL_CLEANUP_ARG = "--uninstall-cleanup";

    public static void main(String[] args) {
        // Computed without touching AppConfig (or any other class with a static Logger field):
        // loading such a class would initialize logback before this system property is set,
        // permanently locking in the default "logs" (relative) directory.
        String appData = System.getenv("APPDATA");
        Path base = appData != null
                ? Path.of(appData)
                : Path.of(System.getProperty("user.home"), "AppData", "Roaming");
        Path logsDir = base.resolve("transcritor-ata").resolve("logs");
        System.setProperty("transcritorata.logDir", logsDir.toString());

        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MainApp.class);

        if (args.length > 0 && UNINSTALL_CLEANUP_ARG.equals(args[0])) {
            log.info("Running uninstall cleanup");
            UninstallCleanup.run();
            return;
        }

        log.info("Starting transcritor-ata");

        Display display = new Display();
        try {
            AppConfig config = new AppConfig();

            BundledToolIntegrityChecker.verify();

            ExecutableLocator locator = new ExecutableLocator.Default();
            BundledFfmpegSelector.applyIfBundlePresent(config, locator);
            WhisperVariantSelector.applyBestVariant(config, new GpuDetector(locator), locator);
            ModelSetupDialog.showIfNeeded(display, config);

            MainWindow window = new MainWindow(display, config);
            window.open();

            while (!window.isDisposed()) {
                if (!display.readAndDispatch()) {
                    display.sleep();
                }
            }
        } finally {
            display.dispose();
        }
    }
}
