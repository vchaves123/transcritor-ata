package com.tailor.transcritorata.deps;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tailor.transcritorata.config.AppConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BundledFfmpegSelectorTest {

    @Test
    void pointsToBundledFfmpegWhenPresent(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.exists(any(Path.class))).thenReturn(true);

        BundledFfmpegSelector.applyIfBundlePresent(config, locator);

        assertEquals(AppHome.resolve("tools/ffmpeg/bin/ffmpeg.exe").toString(),
                config.get(AppConfig.KEY_FFMPEG_BINARY, null));
    }

    @Test
    void leavesExistingConfigUntouchedWhenBundleIsMissing(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        config.set(AppConfig.KEY_FFMPEG_BINARY, "C:/custom/ffmpeg.exe");

        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.exists(any(Path.class))).thenReturn(false);

        BundledFfmpegSelector.applyIfBundlePresent(config, locator);

        assertEquals("C:/custom/ffmpeg.exe", config.get(AppConfig.KEY_FFMPEG_BINARY, null));
    }
}
