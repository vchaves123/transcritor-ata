package com.tailor.transcritorata.deps;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tailor.transcritorata.config.AppConfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WhisperModelSetupCheckerTest {

    @Test
    void isNeededWhenWhisperEngineHasNoModelConfigured(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        ExecutableLocator locator = mock(ExecutableLocator.class);

        assertTrue(WhisperModelSetupChecker.isNeeded(config, locator));
    }

    @Test
    void isNotNeededWhenModelAlreadyConfiguredAndValid(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        Path modelPath = Path.of("tools/models/ggml-medium.bin");
        config.set(AppConfig.KEY_WHISPER_MODEL, modelPath.toString());

        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.exists(modelPath)).thenReturn(true);
        when(locator.sizeOf(modelPath)).thenReturn(500L * 1024 * 1024);

        assertFalse(WhisperModelSetupChecker.isNeeded(config, locator));
    }

    @Test
    void isNeededWhenConfiguredModelFileIsMissing(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        Path modelPath = Path.of("tools/models/ggml-medium.bin");
        config.set(AppConfig.KEY_WHISPER_MODEL, modelPath.toString());

        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.exists(any(Path.class))).thenReturn(false);

        assertTrue(WhisperModelSetupChecker.isNeeded(config, locator));
    }
}
