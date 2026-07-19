package com.tailor.transcritorata.deps;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tailor.transcritorata.config.AppConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WhisperVariantSelectorTest {

    @Test
    void pointsToCudaBuildWhenGpuDetectedAndBundlePresent(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        GpuDetector gpuDetector = mock(GpuDetector.class);
        when(gpuDetector.hasNvidiaGpu()).thenReturn(true);

        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.exists(any(Path.class))).thenReturn(true);

        WhisperVariantSelector.applyBestVariant(config, gpuDetector, locator);

        assertEquals(AppHome.resolve("tools/whisper-cuda/Release/whisper-cli.exe").toString(),
                config.get(AppConfig.KEY_WHISPER_BINARY, null));
    }

    @Test
    void pointsToCpuBuildWhenNoGpuDetected(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        GpuDetector gpuDetector = mock(GpuDetector.class);
        when(gpuDetector.hasNvidiaGpu()).thenReturn(false);

        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.exists(any(Path.class))).thenReturn(true);

        WhisperVariantSelector.applyBestVariant(config, gpuDetector, locator);

        assertEquals(AppHome.resolve("tools/whisper-cpu/Release/whisper-cli.exe").toString(),
                config.get(AppConfig.KEY_WHISPER_BINARY, null));
    }

    @Test
    void leavesExistingConfigUntouchedWhenBundledBinaryIsMissing(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        config.set(AppConfig.KEY_WHISPER_BINARY, "C:/custom/whisper-cli.exe");

        GpuDetector gpuDetector = mock(GpuDetector.class);
        when(gpuDetector.hasNvidiaGpu()).thenReturn(true);

        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.exists(any(Path.class))).thenReturn(false);

        WhisperVariantSelector.applyBestVariant(config, gpuDetector, locator);

        assertEquals("C:/custom/whisper-cli.exe", config.get(AppConfig.KEY_WHISPER_BINARY, null));
    }
}
