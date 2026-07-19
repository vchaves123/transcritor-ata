package com.tailor.transcritorata.transcription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.transcritorata.audio.ExternalProcessException;
import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.model.Segment;

/**
 * Default {@link TranscriptionEngine}, backed by whisper.cpp's {@code whisper-cli.exe} binary.
 *
 * <p>The binary's console output is unstable across versions, so this engine always relies on
 * the JSON file it writes via {@code -oj}, never on stdout parsing.
 */
public final class WhisperCppEngine implements TranscriptionEngine {

    private static final Logger LOG = LoggerFactory.getLogger(WhisperCppEngine.class);
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("progress\\s*=\\s*(\\d+)%");
    // Matches the end timestamp of whisper-cli's per-segment output lines, e.g.
    // "[00:00:00.000 --> 00:00:02.500]  transcribed text", used as a progress fallback since
    // recent whisper.cpp builds don't always print an explicit "progress = N%" line.
    private static final Pattern SEGMENT_END_TIMESTAMP_PATTERN =
            Pattern.compile("-->\\s*(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})]");
    // A degenerate/hallucinating whisper-cli run (see VadModelProvider) can in principle emit a
    // huge number of near-duplicate segments; this bounds how large a -oj JSON file we'll ever
    // fully load into memory, failing fast with a clear error instead of risking an OOM right at
    // the end of a potentially hours-long transcription.
    private static final long MAX_JSON_FILE_SIZE_BYTES = 500L * 1024 * 1024; // 500 MB

    private final String whisperCliExecutable;
    private final Path modelPath;
    private final String language;
    private final long timeoutSeconds;
    private final boolean fastMode;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param fastMode when {@code true}, forces greedy decoding ({@code -bs 1 -bo 1}) instead of
     *                 whisper.cpp's default 5-way beam search. Faster and uses noticeably less
     *                 GPU memory (the beam search multiplies decoder buffer size by the beam
     *                 count), at some cost in transcription accuracy — useful for GPUs with
     *                 little VRAM that would otherwise run out of memory mid-transcription.
     */
    public WhisperCppEngine(String whisperCliExecutable, Path modelPath, String language, long timeoutSeconds,
            boolean fastMode) {
        this.whisperCliExecutable = whisperCliExecutable;
        this.modelPath = modelPath;
        this.language = language;
        this.timeoutSeconds = timeoutSeconds;
        this.fastMode = fastMode;
    }

    @Override
    public List<Segment> transcribe(Path wav, ProgressListener listener, ProcessRunner.Handle handle)
            throws ExternalProcessException, IOException {
        Path outputPrefix = Files.createTempFile("transcritor-ata-whisper-", "").toAbsolutePath();
        Files.deleteIfExists(outputPrefix);

        int threads = Runtime.getRuntime().availableProcessors();
        List<String> command = buildCommand(wav, outputPrefix, threads);
        // Logs only file names, not full paths: the persistent log file (kept for 14 days under
        // %APPDATA%) shouldn't durably retain the user's folder structure.
        LOG.info("Transcribing with whisper.cpp (model {}, {} threads, fastMode={})",
                modelPath.getFileName(), threads, fastMode);

        long totalDurationMillis = wavDurationMillis(wav);
        // Recorded before the process starts, with a small tolerance for filesystem timestamp
        // granularity/clock skew, so we can confirm the JSON file was actually (re)written by
        // *this* invocation rather than being a leftover/planted file at a predictable path.
        Instant invocationStart = Instant.now().minusSeconds(2);
        try {
            ProcessRunner.run(command, handle, timeoutSeconds, line -> reportProgress(line, listener, totalDurationMillis));

            Path jsonFile = Path.of(outputPrefix + ".json");
            if (!Files.exists(jsonFile)) {
                throw new ExternalProcessException(
                        "whisper.cpp did not generate the expected transcription file.", "");
            }
            FileTime lastModified = Files.getLastModifiedTime(jsonFile);
            if (lastModified.toInstant().isBefore(invocationStart)) {
                throw new ExternalProcessException(
                        "The transcription file at " + jsonFile + " predates this whisper.cpp run; "
                                + "refusing to trust it.", "");
            }
            long jsonSize = Files.size(jsonFile);
            if (jsonSize > MAX_JSON_FILE_SIZE_BYTES) {
                throw new ExternalProcessException(
                        "whisper.cpp produced an unexpectedly large transcription file (" + jsonSize
                                + " bytes) — this usually indicates whisper.cpp got stuck repeating itself on "
                                + "this recording. Aborting instead of risking an out-of-memory crash.", "");
            }
            return parseJson(jsonFile);
        } finally {
            Files.deleteIfExists(Path.of(outputPrefix + ".json"));
        }
    }

    List<String> buildCommand(Path wav, Path outputPrefix, int threads) {
        List<String> command = new ArrayList<>(List.of(
                whisperCliExecutable,
                "-m", modelPath.toString(),
                "-l", language,
                "-oj",
                "-of", outputPrefix.toString(),
                "-t", Integer.toString(threads)));
        if (fastMode) {
            command.addAll(List.of("-bs", "1", "-bo", "1"));
        }
        VadModelProvider.resolve().ifPresent(vadModel -> command.addAll(List.of("--vad", "-vm", vadModel.toString())));
        command.add(wav.toString());
        return command;
    }

    /**
     * Forwards whisper-cli's console output to the listener. Lines matching whisper.cpp's
     * {@code progress = N%} marker update the progress bar directly; when that marker isn't
     * printed (some builds don't emit it), the end timestamp of each per-segment output line
     * ({@code [00:00:00.000 --> 00:00:02.500]  text}) is used instead, as a fraction of the
     * audio's total duration. Everything else is forwarded as a log-only message using
     * {@code percent = -1}, a sentinel the GUI recognizes to mean "append to the log without
     * moving the progress indicator".
     */
    void reportProgress(String line, ProgressListener listener, long totalDurationMillis) {
        if (listener == null || line.isBlank()) {
            return;
        }
        // Defensive: this callback runs synchronously inside ProcessRunner's stdout-reading loop
        // while whisper-cli is still alive. A garbled/oversized number in a malformed progress
        // line (e.g. from a corrupted build) must not throw out of here uncaught -- that would
        // skip ProcessRunner's own process cleanup and leave whisper-cli running unmanaged.
        try {
            reportProgressUnsafe(line, listener, totalDurationMillis);
        } catch (NumberFormatException e) {
            listener.onProgress(line.strip(), -1);
        }
    }

    private void reportProgressUnsafe(String line, ProgressListener listener, long totalDurationMillis) {
        Matcher progressMatcher = PROGRESS_PATTERN.matcher(line);
        if (progressMatcher.find()) {
            listener.onProgress("Transcribing...", Integer.parseInt(progressMatcher.group(1)));
            return;
        }
        Matcher timestampMatcher = SEGMENT_END_TIMESTAMP_PATTERN.matcher(line);
        if (totalDurationMillis > 0 && timestampMatcher.find()) {
            long endMillis = (Long.parseLong(timestampMatcher.group(1)) * 3600_000L)
                    + (Long.parseLong(timestampMatcher.group(2)) * 60_000L)
                    + (Long.parseLong(timestampMatcher.group(3)) * 1000L)
                    + Long.parseLong(timestampMatcher.group(4));
            int percent = (int) Math.min(99, (endMillis * 100) / totalDurationMillis);
            listener.onProgress(line.strip(), percent);
        } else {
            listener.onProgress(line.strip(), -1);
        }
    }

    /** @return the WAV's duration in milliseconds, or -1 if it couldn't be read. */
    private static long wavDurationMillis(Path wav) {
        try {
            AudioFileFormat format = AudioSystem.getAudioFileFormat(wav.toFile());
            long frameLength = format.getFrameLength();
            float frameRate = format.getFormat().getFrameRate();
            if (frameLength <= 0 || frameRate <= 0) {
                return -1;
            }
            return Math.round((frameLength / frameRate) * 1000);
        } catch (UnsupportedAudioFileException | IOException e) {
            LOG.debug("Could not read the duration of {} to estimate progress: {}", wav, e.getMessage());
            return -1;
        }
    }

    List<Segment> parseJson(Path jsonFile) throws IOException {
        WhisperJsonResult result = objectMapper.readValue(jsonFile.toFile(), WhisperJsonResult.class);
        List<Segment> segments = new ArrayList<>();
        if (result.transcription == null) {
            return segments;
        }
        for (WhisperJsonResult.WhisperTranscriptionEntry entry : result.transcription) {
            if (entry.offsets == null) {
                continue;
            }
            // Guards against a degenerate/corrupted whisper-cli run emitting a negative or
            // inverted offset pair, which would otherwise produce a malformed timestamp (e.g.
            // "00:00:-5") silently embedded in the generated .docx minutes.
            if (entry.offsets.from < 0 || entry.offsets.to < entry.offsets.from) {
                LOG.warn("Skipping transcription entry with invalid offsets (from={}, to={})",
                        entry.offsets.from, entry.offsets.to);
                continue;
            }
            segments.add(new Segment(
                    java.time.Duration.ofMillis(entry.offsets.from),
                    java.time.Duration.ofMillis(entry.offsets.to),
                    entry.text));
        }
        return segments;
    }
}
