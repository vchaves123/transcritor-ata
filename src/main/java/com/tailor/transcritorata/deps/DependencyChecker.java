package com.tailor.transcritorata.deps;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.tailor.transcritorata.config.AppConfig;

/**
 * Checks that the external tools and models required by the transcription pipeline are present,
 * producing a list of {@link DependencyStatus} with concrete installation instructions
 * for whatever is missing. Never throws to the caller.
 */
public final class DependencyChecker {

    private static final long PROBE_TIMEOUT_SECONDS = 10;

    private final ExecutableLocator locator;
    private final AppConfig config;

    public DependencyChecker(AppConfig config) {
        this(config, new ExecutableLocator.Default());
    }

    public DependencyChecker(AppConfig config, ExecutableLocator locator) {
        this.config = config;
        this.locator = locator;
    }

    /** Checks ffmpeg + the Whisper binary/model. */
    public List<DependencyStatus> checkAll() {
        List<DependencyStatus> results = new ArrayList<>();
        results.add(checkFfmpeg());
        results.add(checkWhisperBinary());
        results.add(checkWhisperModel());
        return results;
    }

    public DependencyStatus checkFfmpeg() {
        String configured = config.get(AppConfig.KEY_FFMPEG_BINARY, "ffmpeg");
        if (!"ffmpeg".equals(configured) && locator.exists(Path.of(configured))) {
            return new DependencyStatus("ffmpeg", true, configured, null, null);
        }

        List<Path> candidates = List.of(Path.of("C:\\ffmpeg\\bin"));
        Optional<Path> found = locator.findOnPathOrCandidates("ffmpeg.exe", candidates);
        List<String> command = found.isPresent()
                ? List.of(found.get().toString(), "-version")
                : List.of(configured, "-version");
        boolean ok = locator.canRun(command, PROBE_TIMEOUT_SECONDS);

        if (ok) {
            return new DependencyStatus("ffmpeg", true,
                    found.map(Path::toString).orElse("found on PATH"), null, null);
        }
        String instructions = """
                ffmpeg was not found. On Windows 11, the simplest way to install it is to open the \
                Terminal/PowerShell and run:

                    winget install Gyan.FFmpeg

                Alternatively, download the build from https://www.gyan.dev/ffmpeg/builds/ and extract it into a \
                folder (e.g. C:\\ffmpeg), adding the "bin" subfolder to the system PATH.

                Important: after installing via winget, close and reopen transcritor-ata so the updated PATH \
                is picked up.
                """;
        return new DependencyStatus("ffmpeg", false, "not found", instructions,
                "https://www.gyan.dev/ffmpeg/builds/");
    }

    public DependencyStatus checkWhisperBinary() {
        String configured = config.get(AppConfig.KEY_WHISPER_BINARY, "");
        List<Path> candidates = new ArrayList<>();
        if (!configured.isBlank()) {
            Path configuredPath = Path.of(configured);
            if (locator.exists(configuredPath)) {
                return new DependencyStatus("whisper-cli (whisper.cpp)", true, configured, null, null);
            }
            candidates.add(configuredPath.getParent());
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            candidates.add(Path.of(localAppData, "transcritor-ata", "whisper"));
        }
        Optional<Path> found = locator.findOnPathOrCandidates("whisper-cli.exe", candidates);
        if (found.isPresent()) {
            return new DependencyStatus("whisper-cli (whisper.cpp)", true, found.get().toString(), null, null);
        }

        String instructions = """
                The whisper-cli.exe executable was not found. Download the prebuilt Windows binary from \
                the official whisper.cpp releases:

                    https://github.com/ggml-org/whisper.cpp/releases

                Look for the zip file with the "-bin-x64" suffix, extract its contents into a folder of your \
                choice, and in transcritor-ata's settings, point the "whisper-cli" field to the extracted \
                whisper-cli.exe file.

                There is no need to compile anything.
                """;
        return new DependencyStatus("whisper-cli (whisper.cpp)", false, "not found", instructions,
                "https://github.com/ggml-org/whisper.cpp/releases");
    }

    public DependencyStatus checkWhisperModel() {
        String configured = config.get(AppConfig.KEY_WHISPER_MODEL, "");
        if (!configured.isBlank()) {
            Path modelPath = Path.of(configured);
            long size = locator.sizeOf(modelPath);
            long minimumPlausibleBytes = 10L * 1024 * 1024; // any real ggml model is well above 10 MB
            if (locator.exists(modelPath) && size >= minimumPlausibleBytes) {
                return new DependencyStatus("Whisper model (.bin)", true, configured, null, null);
            }
        }
        String instructions = """
                No valid Whisper model is configured. Download a model in ggml format from:

                    https://huggingface.co/ggerganov/whisper.cpp/tree/main

                Recommendations:
                  - ggml-medium.bin: good balance for CPU use (recommended)
                  - ggml-small.bin: for more modest machines
                  - ggml-large-v3.bin: for those with a GPU available

                Save the downloaded file and select it in the "Whisper Model" field of the settings.
                """;
        return new DependencyStatus("Whisper model (.bin)", false, "not configured or invalid", instructions,
                "https://huggingface.co/ggerganov/whisper.cpp/tree/main");
    }
}
