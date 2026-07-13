package com.tailor.transcritorata.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    @Test
    void persistsValuesAcrossReload(@TempDir Path tempDir) {
        Path file = tempDir.resolve("config.properties");

        AppConfig first = new AppConfig(file);
        first.set(AppConfig.KEY_WHISPER_MODEL, "C:/models/ggml-medium.bin");
        first.setBoolean(AppConfig.KEY_AI_ENABLED, true);
        first.setInt(AppConfig.KEY_CHUNK_MINUTES, 15);
        first.save();

        AppConfig reloaded = new AppConfig(file);
        assertEquals("C:/models/ggml-medium.bin", reloaded.get(AppConfig.KEY_WHISPER_MODEL, null));
        assertTrue(reloaded.getBoolean(AppConfig.KEY_AI_ENABLED, false));
        assertEquals(15, reloaded.getInt(AppConfig.KEY_CHUNK_MINUTES, -1));
    }

    @Test
    void appliesDefaultsWhenMissing(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        assertEquals("whisper", config.get(AppConfig.KEY_ENGINE, null));
        assertEquals("claude-sonnet-4-6", config.get(AppConfig.KEY_AI_MODEL, null));
    }

    @Test
    void resolvesApiKeyFromPreferencesWhenEnvNotSet(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        assertNull(config.resolveAnthropicApiKey());

        config.set(AppConfig.KEY_AI_API_KEY, "sk-test-123");
        assertEquals("sk-test-123", config.resolveAnthropicApiKey());
    }
}
