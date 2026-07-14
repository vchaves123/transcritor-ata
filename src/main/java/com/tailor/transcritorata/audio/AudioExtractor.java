package com.tailor.transcritorata.audio;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts a 16 kHz mono PCM16 WAV track from an input video/audio file using ffmpeg.
 */
public final class AudioExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(AudioExtractor.class);

    private final String ffmpegExecutable;
    private final long timeoutSeconds;

    public AudioExtractor(String ffmpegExecutable, long timeoutSeconds) {
        this.ffmpegExecutable = ffmpegExecutable;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Converts {@code input} to {@code outputWav} (16 kHz, mono, PCM 16-bit).
     *
     * @param handle           allows the caller to cancel the running ffmpeg process
     * @param outputLineListener called with each line ffmpeg prints (stdout/stderr combined),
     *                           so the caller can surface it in a log view; may be {@code null}
     */
    public void extractToWav(Path input, Path outputWav, ProcessRunner.Handle handle,
            Consumer<String> outputLineListener) throws ExternalProcessException {
        List<String> command = List.of(
                ffmpegExecutable,
                "-y",
                "-i", input.toString(),
                "-ar", "16000",
                "-ac", "1",
                "-c:a", "pcm_s16le",
                outputWav.toString());

        LOG.info("Extraindo áudio de {} para {}", input, outputWav);
        ProcessRunner.run(command, handle, timeoutSeconds, forward(outputLineListener));
    }

    /**
     * Splits {@code inputWav} into fixed-length chunks using ffmpeg's segment muxer, writing
     * files named {@code <outputPrefix>-000.wav}, {@code -001.wav}, etc.
     */
    public void splitIntoChunks(Path inputWav, Path outputDir, String outputPrefix, int chunkMinutes,
            ProcessRunner.Handle handle, Consumer<String> outputLineListener) throws ExternalProcessException {
        Path pattern = outputDir.resolve(outputPrefix + "-%03d.wav");
        List<String> command = List.of(
                ffmpegExecutable,
                "-y",
                "-i", inputWav.toString(),
                "-f", "segment",
                "-segment_time", Integer.toString(chunkMinutes * 60),
                "-c", "copy",
                pattern.toString());

        LOG.info("Dividindo {} em blocos de {} minutos", inputWav, chunkMinutes);
        ProcessRunner.run(command, handle, timeoutSeconds, forward(outputLineListener));
    }

    private static Consumer<String> forward(Consumer<String> outputLineListener) {
        return outputLineListener != null ? outputLineListener : line -> { };
    }
}
