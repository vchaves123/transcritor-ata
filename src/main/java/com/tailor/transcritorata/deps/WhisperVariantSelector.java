package com.tailor.transcritorata.deps;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tailor.transcritorata.config.AppConfig;

/**
 * At startup, points the configured whisper-cli executable to the bundled CUDA/cuBLAS build when
 * an NVIDIA GPU is detected, or to the bundled CPU-only build otherwise. Runs on every launch, so
 * a GPU added or removed since the last run is picked up automatically.
 *
 * <p>Only overwrites the preference when the corresponding bundled binary actually exists (e.g.
 * under {@code tools/whisper-cuda} / {@code tools/whisper-cpu} relative to the working directory);
 * otherwise it leaves whatever whisper-cli path the user already configured untouched.
 */
public final class WhisperVariantSelector {

    private static final Logger LOG = LoggerFactory.getLogger(WhisperVariantSelector.class);

    private static final String CUDA_BINARY = "tools/whisper-cuda/Release/whisper-cli.exe";
    private static final String CPU_BINARY = "tools/whisper-cpu/Release/whisper-cli.exe";

    private WhisperVariantSelector() {
    }

    public static void applyBestVariant(AppConfig config, GpuDetector gpuDetector, ExecutableLocator locator) {
        boolean hasGpu = gpuDetector.hasNvidiaGpu();
        Path resolved = AppHome.resolve(hasGpu ? CUDA_BINARY : CPU_BINARY);

        if (!locator.exists(resolved)) {
            LOG.debug("whisper-cli build not found at {}; keeping existing configuration.", resolved);
            return;
        }

        LOG.info("NVIDIA GPU {}; using whisper-cli at {}", hasGpu ? "detected" : "not detected", resolved);
        config.set(AppConfig.KEY_WHISPER_BINARY, resolved.toString());
        config.save();
    }
}
