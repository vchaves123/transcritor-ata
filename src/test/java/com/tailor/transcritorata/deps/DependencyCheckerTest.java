package com.tailor.transcritorata.deps;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tailor.transcritorata.config.AppConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DependencyCheckerTest {

    @Test
    void reportsMissingFfmpegWithInstructions(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.canRun(anyCommand(), anyLong())).thenReturn(false);
        when(locator.findOnPathOrCandidates(eq("ffmpeg.exe"), anyList())).thenReturn(Optional.empty());

        DependencyChecker checker = new DependencyChecker(config, locator);
        DependencyStatus status = checker.checkFfmpeg();

        assertFalse(status.ok());
        assertTrue(status.instructions().contains("winget install Gyan.FFmpeg"));
    }

    @Test
    void reportsFfmpegOkWhenFound(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.findOnPathOrCandidates(eq("ffmpeg.exe"), anyList()))
                .thenReturn(Optional.of(Path.of("C:/ffmpeg/bin/ffmpeg.exe")));
        when(locator.canRun(anyCommand(), anyLong())).thenReturn(true);

        DependencyChecker checker = new DependencyChecker(config, locator);
        assertTrue(checker.checkFfmpeg().ok());
    }

    @Test
    void acceptsBundledFfmpegPathWithoutTouchingPath(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        Path bundled = Path.of("tools/ffmpeg/bin/ffmpeg.exe");
        config.set(AppConfig.KEY_FFMPEG_BINARY, bundled.toString());

        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.exists(bundled)).thenReturn(true);

        DependencyChecker checker = new DependencyChecker(config, locator);
        DependencyStatus status = checker.checkFfmpeg();

        assertTrue(status.ok());
        assertEquals(bundled.toString(), status.detail());
    }

    @Test
    void reportsMissingWhisperModelWithHuggingFaceLink(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        DependencyChecker checker = new DependencyChecker(config, mock(ExecutableLocator.class));

        DependencyStatus status = checker.checkWhisperModel();

        assertFalse(status.ok());
        assertTrue(status.instructions().contains("ggml-medium.bin"));
        assertTrue(status.helpUrl().contains("huggingface.co"));
    }

    @Test
    void acceptsConfiguredWhisperModelWhenLargeEnough(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        Path modelPath = Path.of("C:/models/ggml-medium.bin");
        config.set(AppConfig.KEY_WHISPER_MODEL, modelPath.toString());

        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.exists(modelPath)).thenReturn(true);
        when(locator.sizeOf(modelPath)).thenReturn(500L * 1024 * 1024);

        DependencyChecker checker = new DependencyChecker(config, locator);
        assertTrue(checker.checkWhisperModel().ok());
    }

    @Test
    void diarizationCheckIsOptionalAndMissingByDefault(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        DependencyChecker checker = new DependencyChecker(config, mock(ExecutableLocator.class));

        DependencyStatus status = checker.checkDiarization();

        assertFalse(status.ok());
        assertTrue(status.optional(), "diarização deve ser marcada como opcional");
        assertTrue(status.instructions().contains("lium_spkdiarization"));
    }

    @Test
    void acceptsConfiguredLiumJarWhenPresent(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        Path jarPath = Path.of("C:/tools/lium_spkdiarization-8.4.1.jar");
        config.set(AppConfig.KEY_DIARIZATION_JAR, jarPath.toString());

        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.exists(jarPath)).thenReturn(true);
        when(locator.sizeOf(jarPath)).thenReturn(5L * 1024 * 1024);

        DependencyChecker checker = new DependencyChecker(config, locator);
        DependencyStatus status = checker.checkDiarization();

        assertTrue(status.ok());
        assertTrue(status.optional());
    }

    private static List<String> anyCommand() {
        return org.mockito.ArgumentMatchers.anyList();
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private static List<Path> anyList() {
        return org.mockito.ArgumentMatchers.anyList();
    }
}
