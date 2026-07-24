package com.tailor.transcritorata.gui;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.swt.widgets.Display;

import com.tailor.transcritorata.config.AppConfig;
import com.tailor.transcritorata.deps.BundledFfmpegSelector;
import com.tailor.transcritorata.deps.BundledToolIntegrityChecker;
import com.tailor.transcritorata.deps.ExecutableLocator;
import com.tailor.transcritorata.deps.GpuDetector;
import com.tailor.transcritorata.deps.StaleTempFileCleaner;
import com.tailor.transcritorata.deps.WhisperVariantSelector;

/**
 * Application entry point. On Windows, SWT runs fine on the main thread; the
 * {@code -XstartOnFirstThread} requirement applies only to macOS (documented in the README).
 */
public final class MainApp {

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
        log.info("Starting transcritor-ata");

        Display display = new Display();
        try {
            SplashScreen splash = SplashScreen.show(display);

            AppConfig config = new AppConfig();
            runIntegrityCheckWithSplash(display, splash);
            splash.close();

            ExecutableLocator locator = new ExecutableLocator.Default();
            BundledFfmpegSelector.applyIfBundlePresent(config, locator);
            WhisperVariantSelector.applyBestVariant(config, new GpuDetector(locator), locator);
            ModelSetupDialog.showIfNeeded(display, config);

            MainWindow window = new MainWindow(display, config);
            window.open();

            // Best-effort backstop for temp files left behind by a previous run that didn't shut
            // down cleanly (crash, force-kill); never awaited, must never delay startup.
            Thread.ofVirtual().start(StaleTempFileCleaner::cleanup);

            while (!window.isDisposed()) {
                if (!display.readAndDispatch()) {
                    display.sleep();
                }
            }
        } finally {
            display.dispose();
        }
    }

    /**
     * Runs the bundled-tools integrity check (which can take a while to hash upwards of 1 GB of
     * DLLs) on a background thread while the splash screen's progress bar tracks it, and pumps
     * the UI thread's event loop until it's done -- keeps the splash responsive/repainting
     * instead of freezing for the whole check, without making the check itself asynchronous with
     * respect to the rest of startup (which still waits for it, same as before).
     */
    private static void runIntegrityCheckWithSplash(Display display, SplashScreen splash) {
        AtomicBoolean done = new AtomicBoolean(false);
        Thread.ofVirtual().start(() -> {
            BundledToolIntegrityChecker.verify((bytesChecked, totalBytes) -> display.asyncExec(() -> {
                if (!display.isDisposed()) {
                    splash.setProgress("Verifying installed files...", bytesChecked, totalBytes);
                }
            }));
            done.set(true);
            if (!display.isDisposed()) {
                display.wake();
            }
        });
        while (!done.get()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
    }
}
