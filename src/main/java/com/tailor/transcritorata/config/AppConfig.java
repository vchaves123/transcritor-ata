package com.tailor.transcritorata.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * User preferences persisted as a properties file under
 * {@code %APPDATA%/transcritor-ata/config.properties}.
 *
 * <p>Not thread-safe by design: all reads/writes are expected to happen from the GUI thread
 * or during short-lived pipeline setup, never concurrently.
 */
public final class AppConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfig.class);
    private static final String FILE_NAME = "config.properties";

    public static final String KEY_FFMPEG_BINARY = "ffmpeg.binary";
    public static final String KEY_WHISPER_BINARY = "whisper.binary";
    public static final String KEY_WHISPER_MODEL = "whisper.model";
    public static final String KEY_WHISPER_FAST_MODE = "whisper.fastMode";
    public static final String KEY_LAST_VIDEO_DIR = "lastVideoDir";
    public static final String KEY_OUTPUT_DIR = "outputDir";
    public static final String KEY_COMPANY_NAME = "docx.companyName";
    public static final String KEY_PROCESS_TIMEOUT_SECONDS = "process.timeoutSeconds";
    public static final String KEY_AI_ENABLED = "ai.enabled";
    public static final String KEY_AI_MODEL = "ai.model";
    public static final String KEY_AI_API_KEY = "ai.apiKey";
    public static final String KEY_AI_PRIVACY_CONSENT = "ai.privacyConsentGiven";
    public static final String KEY_AI_CHUNK_CHAR_LIMIT = "ai.chunkCharLimit";
    public static final String KEY_DIARIZATION_ENABLED = "diarization.enabled";

    private final Properties properties = new Properties();
    private final Path configFile;

    public AppConfig() {
        this(defaultConfigDir().resolve(FILE_NAME));
    }

    public AppConfig(Path configFile) {
        this.configFile = configFile;
        load();
        applyDefaults();
    }

    public static Path defaultConfigDir() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null
                ? Path.of(appData)
                : Path.of(System.getProperty("user.home"), "AppData", "Roaming");
        return base.resolve("transcritor-ata");
    }

    public static Path logsDir() {
        return defaultConfigDir().resolve("logs");
    }

    private void load() {
        if (!Files.exists(configFile)) {
            return;
        }
        try (InputStream in = Files.newInputStream(configFile)) {
            properties.load(in);
        } catch (IOException e) {
            LOG.warn("Could not read config file {}: {}", configFile, e.getMessage());
        }
    }

    private void applyDefaults() {
        properties.putIfAbsent(KEY_FFMPEG_BINARY, "ffmpeg");
        properties.putIfAbsent(KEY_WHISPER_FAST_MODE, "false");
        properties.putIfAbsent(KEY_PROCESS_TIMEOUT_SECONDS, "3600");
        properties.putIfAbsent(KEY_AI_ENABLED, "false");
        properties.putIfAbsent(KEY_AI_MODEL, "claude-sonnet-4-6");
        properties.putIfAbsent(KEY_AI_PRIVACY_CONSENT, "false");
        properties.putIfAbsent(KEY_AI_CHUNK_CHAR_LIMIT, "12000");
        properties.putIfAbsent(KEY_COMPANY_NAME, "");
        properties.putIfAbsent(KEY_DIARIZATION_ENABLED, "false");
    }

    public void save() {
        try {
            Files.createDirectories(configFile.getParent());
            try (OutputStream out = Files.newOutputStream(configFile)) {
                properties.store(out, "transcritor-ata configuration - auto-generated");
            }
        } catch (IOException e) {
            LOG.warn("Could not save config file {}: {}", configFile, e.getMessage());
        }
    }

    public String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public void set(String key, String value) {
        if (value == null) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    public void setBoolean(String key, boolean value) {
        set(key, Boolean.toString(value));
    }

    public int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public void setInt(String key, int value) {
        set(key, Integer.toString(value));
    }

    /**
     * Resolves the Anthropic API key, preferring the {@code ANTHROPIC_API_KEY} environment
     * variable over the value stored in preferences.
     */
    public String resolveAnthropicApiKey() {
        String envKey = System.getenv("ANTHROPIC_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey;
        }
        String stored = properties.getProperty(KEY_AI_API_KEY);
        return stored == null || stored.isBlank() ? null : stored;
    }
}
