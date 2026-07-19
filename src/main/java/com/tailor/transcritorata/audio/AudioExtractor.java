package com.tailor.transcritorata.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

        // Logs only the file name, not the full path: the persistent log file (kept for 14 days
        // under %APPDATA%) shouldn't durably retain the user's folder structure, which can itself
        // be sensitive (e.g. an HR/restructuring-related folder name).
        LOG.info("Extracting audio from {} to a temporary WAV", input.getFileName());
        ProcessRunner.run(command, handle, timeoutSeconds, forward(outputLineListener));
    }

    /**
     * Concatenates several WAV files (already extracted via {@link #extractToWav}, so all share
     * the same 16&nbsp;kHz mono PCM16 format) into a single WAV, in list order, using ffmpeg's
     * concat demuxer with stream copy (no re-encoding, since the format already matches).
     */
    public void concatenate(List<Path> wavFiles, Path outputWav, ProcessRunner.Handle handle,
            Consumer<String> outputLineListener) throws ExternalProcessException, IOException {
        if (wavFiles.size() == 1) {
            Files.copy(wavFiles.get(0), outputWav, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        Path listFile = Files.createTempFile(outputWav.getParent(), "concat-list-", ".txt");
        StringBuilder listContents = new StringBuilder();
        for (Path wav : wavFiles) {
            String absolutePath = wav.toAbsolutePath().toString();
            // This escaping only handles ffmpeg's own quoting inside a 'file ...' line -- it does
            // NOT handle a path starting with '#' (parsed as a comment by ffmpeg's concat demuxer)
            // or one containing a newline. That's fine today because every path reaching this
            // method is one this class generated itself (audio-N.wav in a fresh temp directory,
            // never a user-chosen path), but this method must never be reused for user-supplied
            // paths without revisiting this escaping first -- enforced here, not just documented.
            if (absolutePath.indexOf('\n') >= 0 || absolutePath.indexOf('\r') >= 0 || absolutePath.startsWith("#")) {
                throw new IllegalArgumentException(
                        "Refusing to build an ffmpeg concat list from an unexpected path: " + absolutePath);
            }
            listContents.append("file '").append(absolutePath.replace("'", "'\\''")).append("'\n");
        }
        Files.writeString(listFile, listContents.toString(), StandardCharsets.UTF_8);

        try {
            List<String> command = List.of(
                    ffmpegExecutable,
                    "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listFile.toString(),
                    "-c", "copy",
                    outputWav.toString());

            LOG.info("Concatenating {} audio files into a single temporary WAV", wavFiles.size());
            ProcessRunner.run(command, handle, timeoutSeconds, forward(outputLineListener));
        } finally {
            Files.deleteIfExists(listFile);
        }
    }

    private static Consumer<String> forward(Consumer<String> outputLineListener) {
        return outputLineListener != null ? outputLineListener : line -> { };
    }
}
