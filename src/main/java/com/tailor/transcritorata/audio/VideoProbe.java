package com.tailor.transcritorata.audio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a video file's duration via ffprobe, without decoding the media (metadata read only, so
 * it returns almost instantly even for long recordings).
 */
public final class VideoProbe {

    private static final Logger LOG = LoggerFactory.getLogger(VideoProbe.class);
    private static final long PROBE_TIMEOUT_SECONDS = 10;

    private VideoProbe() {
    }

    /** @return the video's duration, or empty if ffprobe is unavailable or the file can't be read */
    public static Optional<Duration> probeDuration(String ffprobeExecutable, Path video) {
        List<String> command = List.of(ffprobeExecutable, "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", video.toString());
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            process = builder.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining()).trim();
            }
            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            double seconds = Double.parseDouble(output);
            return Optional.of(Duration.ofMillis(Math.round(seconds * 1000)));
        } catch (IOException | InterruptedException | NumberFormatException e) {
            LOG.debug("Could not obtain the duration of {}: {}", video, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        } finally {
            // Defensive backstop: an exception thrown between start() and waitFor() (e.g. while
            // reading the output stream) would otherwise leave ffprobe running unmanaged until it
            // exits on its own.
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** Derives the ffprobe path from the configured ffmpeg path, assuming they sit side by side. */
    public static String resolveFfprobeExecutable(String ffmpegExecutable) {
        Path ffmpegPath = Path.of(ffmpegExecutable);
        Path parent = ffmpegPath.getParent();
        String fileName = ffmpegPath.getFileName().toString();
        String ffprobeFileName = fileName.toLowerCase(java.util.Locale.ROOT).replace("ffmpeg", "ffprobe");
        return parent == null ? ffprobeFileName : parent.resolve(ffprobeFileName).toString();
    }
}
