package com.tailor.transcritorata.transcription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private final String whisperCliExecutable;
    private final Path modelPath;
    private final String language;
    private final long timeoutSeconds;
    private final boolean fastMode;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WhisperCppEngine(String whisperCliExecutable, Path modelPath, String language, long timeoutSeconds) {
        this(whisperCliExecutable, modelPath, language, timeoutSeconds, false);
    }

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
        LOG.info("Transcrevendo {} com whisper.cpp (modelo {}, {} threads, fastMode={})",
                wav, modelPath, threads, fastMode);

        try {
            ProcessRunner.run(command, handle, timeoutSeconds, line -> reportProgress(line, listener));

            Path jsonFile = Path.of(outputPrefix + ".json");
            if (!Files.exists(jsonFile)) {
                throw new ExternalProcessException(
                        "O whisper.cpp não gerou o arquivo de transcrição esperado.", "");
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
        command.add(wav.toString());
        return command;
    }

    /**
     * Forwards whisper-cli's console output to the listener. Lines matching whisper.cpp's
     * {@code progress = N%} marker update the progress bar; every other non-blank line (which
     * includes the {@code [00:00:00.000 --> 00:00:02.500]  texto} segment lines whisper-cli
     * prints as it transcribes) is forwarded as a log-only message using {@code percent = -1},
     * a sentinel the GUI recognizes to mean "append to the log without moving the progress bar".
     */
    private void reportProgress(String line, ProgressListener listener) {
        if (listener == null || line.isBlank()) {
            return;
        }
        Matcher matcher = PROGRESS_PATTERN.matcher(line);
        if (matcher.find()) {
            int percent = Integer.parseInt(matcher.group(1));
            listener.onProgress("Transcrevendo...", percent);
        } else {
            listener.onProgress(line.strip(), -1);
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
            segments.add(new Segment(
                    java.time.Duration.ofMillis(entry.offsets.from),
                    java.time.Duration.ofMillis(entry.offsets.to),
                    entry.text));
        }
        return segments;
    }
}
