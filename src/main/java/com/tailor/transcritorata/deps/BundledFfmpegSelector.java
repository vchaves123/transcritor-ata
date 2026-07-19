package com.tailor.transcritorata.deps;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tailor.transcritorata.config.AppConfig;

/**
 * At startup, points the configured ffmpeg executable to the bundled build under
 * {@code tools/ffmpeg/bin/ffmpeg.exe} (relative to the working directory) when present, leaving
 * any existing configuration untouched otherwise — e.g. a fresh checkout without the bundled
 * binaries, or a user who deliberately configured their own ffmpeg path.
 */
public final class BundledFfmpegSelector {

    private static final Logger LOG = LoggerFactory.getLogger(BundledFfmpegSelector.class);
    private static final String BUNDLED_BINARY = "tools/ffmpeg/bin/ffmpeg.exe";

    private BundledFfmpegSelector() {
    }

    public static void applyIfBundlePresent(AppConfig config, ExecutableLocator locator) {
        Path resolved = AppHome.resolve(BUNDLED_BINARY);
        if (!locator.exists(resolved)) {
            LOG.debug("Bundled ffmpeg not found at {}; keeping existing configuration.", resolved);
            return;
        }
        LOG.info("Using bundled ffmpeg at {}", resolved);
        config.set(AppConfig.KEY_FFMPEG_BINARY, resolved.toString());
        config.save();
    }
}
