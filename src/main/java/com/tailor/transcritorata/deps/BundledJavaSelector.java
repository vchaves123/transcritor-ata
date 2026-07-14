package com.tailor.transcritorata.deps;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tailor.transcritorata.config.AppConfig;

/**
 * At startup, points the Java executable used to run LIUM_SpkDiarization to the bundled runtime
 * under {@code tools/jre/bin/java.exe} (relative to the working directory) when present.
 *
 * <p>This matters specifically for the portable release: the app's own runtime (produced by
 * {@code jpackage --type app-image}) does not include a {@code java.exe} launcher at all — only
 * the native app launcher — so the default {@code "java"} command silently fails to start on any
 * machine without a separately installed JDK on PATH. Bundling a small jlink-built runtime just
 * for this purpose avoids that dependency.
 */
public final class BundledJavaSelector {

    private static final Logger LOG = LoggerFactory.getLogger(BundledJavaSelector.class);
    private static final String BUNDLED_JAVA = "tools/jre/bin/java.exe";

    private BundledJavaSelector() {
    }

    public static void applyIfBundlePresent(AppConfig config, ExecutableLocator locator) {
        if (!locator.exists(Path.of(BUNDLED_JAVA))) {
            LOG.debug("Runtime Java empacotado não encontrado em {}; mantendo configuração existente.",
                    BUNDLED_JAVA);
            return;
        }
        LOG.info("Usando runtime Java empacotado em {} para a diarização", BUNDLED_JAVA);
        config.set(AppConfig.KEY_DIARIZATION_JAVA, BUNDLED_JAVA);
        config.save();
    }
}
