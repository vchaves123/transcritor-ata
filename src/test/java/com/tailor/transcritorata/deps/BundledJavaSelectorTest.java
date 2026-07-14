package com.tailor.transcritorata.deps;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tailor.transcritorata.config.AppConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BundledJavaSelectorTest {

    @Test
    void pointsToBundledJavaWhenPresent(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.exists(any(Path.class))).thenReturn(true);

        BundledJavaSelector.applyIfBundlePresent(config, locator);

        assertEquals("tools/jre/bin/java.exe", config.get(AppConfig.KEY_DIARIZATION_JAVA, null));
    }

    @Test
    void leavesExistingConfigUntouchedWhenBundleIsMissing(@TempDir Path tempDir) {
        AppConfig config = new AppConfig(tempDir.resolve("config.properties"));
        config.set(AppConfig.KEY_DIARIZATION_JAVA, "java");

        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.exists(any(Path.class))).thenReturn(false);

        BundledJavaSelector.applyIfBundlePresent(config, locator);

        assertEquals("java", config.get(AppConfig.KEY_DIARIZATION_JAVA, null));
    }
}
