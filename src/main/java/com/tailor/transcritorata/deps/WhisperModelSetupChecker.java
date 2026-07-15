package com.tailor.transcritorata.deps;

import com.tailor.transcritorata.config.AppConfig;

/**
 * Decides whether the first-run "choose and download a Whisper model" dialog should be shown:
 * only when no valid model is currently configured.
 */
public final class WhisperModelSetupChecker {

    private WhisperModelSetupChecker() {
    }

    public static boolean isNeeded(AppConfig config, ExecutableLocator locator) {
        return !new DependencyChecker(config, locator).checkWhisperModel().ok();
    }
}
