package com.tailor.transcritorata.transcription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tailor.transcritorata.audio.AudioExtractor;
import com.tailor.transcritorata.audio.ExternalProcessException;
import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.diarization.SpeakerAttributor;
import com.tailor.transcritorata.diarization.SpeakerDiarizer;
import com.tailor.transcritorata.diarization.SpeakerTurn;
import com.tailor.transcritorata.minutes.DocxMinutesGenerator;
import com.tailor.transcritorata.minutes.MeetingMetadata;
import com.tailor.transcritorata.model.AttributedSegment;
import com.tailor.transcritorata.model.Segment;

/**
 * Orchestrates the full pipeline: audio extraction from one or more source files (concatenated
 * in the given order), transcription, and minutes generation.
 */
public final class TranscriptionPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(TranscriptionPipeline.class);

    private final AudioExtractor audioExtractor;
    private final TranscriptionEngine engine;
    private final DocxMinutesGenerator docxGenerator;
    private final SpeakerDiarizer speakerDiarizer;
    private final boolean diarizationEnabled;

    public TranscriptionPipeline(AudioExtractor audioExtractor, TranscriptionEngine engine,
            DocxMinutesGenerator docxGenerator, SpeakerDiarizer speakerDiarizer, boolean diarizationEnabled) {
        this.audioExtractor = audioExtractor;
        this.engine = engine;
        this.docxGenerator = docxGenerator;
        this.speakerDiarizer = speakerDiarizer;
        this.diarizationEnabled = diarizationEnabled;
    }

    /**
     * @param videoFiles            source files, in the order they should be concatenated
     * @param audioListener         receives progress/log lines for the audio extraction phase (per
     *                              source file, plus the concatenation step when there's more than one)
     * @param transcriptionListener receives progress/log lines for the transcription engine
     * @param diarizationListener   receives progress/log lines for the (optional) diarization step, kept
     *                              separate since diarization runs concurrently with transcription and
     *                              their raw process output would otherwise interleave in the same log
     * @param minutesListener       receives progress/log lines for minutes generation (docx)
     */
    public PipelineResult run(List<Path> videoFiles, Path outputDir,
            ProgressListener audioListener, ProgressListener transcriptionListener,
            ProgressListener diarizationListener, ProgressListener minutesListener,
            ProcessRunner.Handle handle) throws ExternalProcessException, IOException, InterruptedException {
        if (videoFiles.isEmpty()) {
            throw new IllegalArgumentException("No video file selected.");
        }
        Path tempDir = Files.createTempDirectory("transcritor-ata-");
        try {
            Path wav = extractAndConcatenate(videoFiles, tempDir, audioListener, handle);

            // The (optional) diarization runs in parallel with the transcription: both read the same
            // WAV, as independent processes/tasks. Its log goes to diarizationListener, not
            // transcriptionListener, so it doesn't interleave with the transcription engine's output.
            Future<List<SpeakerTurn>> diarizationFuture = null;
            ExecutorService diarizationExecutor = null;
            if (diarizationEnabled && speakerDiarizer != null) {
                diarizationListener.onProgress("Identifying participants in parallel...", -1);
                diarizationExecutor = Executors.newVirtualThreadPerTaskExecutor();
                diarizationFuture = diarizationExecutor.submit(() -> {
                    // Reported as soon as diarization itself finishes, not when attribute() runs —
                    // that would only happen after the transcription (which runs in parallel) also
                    // finished, leaving the phase "stuck" at In progress even though it's already done.
                    List<SpeakerTurn> turns = speakerDiarizer.diarize(wav, handle,
                            line -> diarizationListener.onProgress(line, -1));
                    diarizationListener.onProgress("Participant identification complete.", 100);
                    return turns;
                });
            }

            transcriptionListener.onProgress("Transcribing... (this may take a few minutes)", 0);
            List<Segment> segments;
            try {
                segments = engine.transcribe(wav, (msg, pct) -> transcriptionListener.onProgress(msg, pct), handle);
            } finally {
                if (diarizationExecutor != null) {
                    diarizationExecutor.shutdown();
                }
            }
            transcriptionListener.onProgress("Transcription complete.", 100);

            List<AttributedSegment> attributed = attribute(segments, diarizationFuture, diarizationListener);

            Duration totalDuration = segments.isEmpty() ? Duration.ZERO
                    : segments.get(segments.size() - 1).end();

            String baseName = stripExtension(videoFiles.get(0).getFileName().toString());
            MeetingMetadata metadata = new MeetingMetadata(LocalDate.now(), sourceFileNames(videoFiles),
                    totalDuration);

            minutesListener.onProgress("Generating minutes...", 50);
            Path simpleMinutes = outputDir.resolve(baseName + "-minutes.docx");
            docxGenerator.generateSimpleMinutesAttributed(simpleMinutes, metadata, attributed);

            minutesListener.onProgress("Complete", 100);
            return new PipelineResult(simpleMinutes);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * Extracts audio from each source file (in order) and, when there's more than one, concatenates
     * them into a single WAV — the transcription/diarization stages downstream only ever see one
     * combined recording.
     */
    private Path extractAndConcatenate(List<Path> videoFiles, Path tempDir, ProgressListener audioListener,
            ProcessRunner.Handle handle) throws ExternalProcessException, IOException {
        List<Path> extractedWavs = new ArrayList<>(videoFiles.size());
        for (int i = 0; i < videoFiles.size(); i++) {
            Path videoFile = videoFiles.get(i);
            audioListener.onProgress(
                    "Extracting audio from file %d of %d (%s)...".formatted(i + 1, videoFiles.size(),
                            videoFile.getFileName()),
                    (int) ((i * 100.0) / videoFiles.size()));
            Path wav = tempDir.resolve("audio-" + i + ".wav");
            audioExtractor.extractToWav(videoFile, wav, handle, line -> audioListener.onProgress(line, -1));
            extractedWavs.add(wav);
        }

        Path combined = tempDir.resolve("audio.wav");
        if (extractedWavs.size() > 1) {
            audioListener.onProgress("Concatenating the " + extractedWavs.size() + " audio files...", 90);
            audioExtractor.concatenate(extractedWavs, combined, handle, line -> audioListener.onProgress(line, -1));
        } else {
            Files.move(extractedWavs.get(0), combined, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        audioListener.onProgress("Audio extraction complete.", 100);
        return combined;
    }

    /**
     * Waits for the (optional) diarization result and attributes speakers to the transcription.
     * A diarization failure is non-fatal: the transcription is returned without speaker labels.
     */
    private List<AttributedSegment> attribute(List<Segment> segments, Future<List<SpeakerTurn>> diarizationFuture,
            ProgressListener listener) {
        if (diarizationFuture == null) {
            return segments.stream().map(s -> new AttributedSegment(s, null)).toList();
        }
        try {
            List<SpeakerTurn> turns = diarizationFuture.get();
            return SpeakerAttributor.attribute(segments, turns);
        } catch (ExecutionException e) {
            LOG.warn("Failed to identify participants: {}", e.getCause() == null
                    ? e.getMessage() : e.getCause().getMessage());
            listener.onProgress("Could not identify participants; the minutes will be generated without that information.",
                    100);
            return segments.stream().map(s -> new AttributedSegment(s, null)).toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onProgress("Could not identify participants; the minutes will be generated without that information.",
                    100);
            return segments.stream().map(s -> new AttributedSegment(s, null)).toList();
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** Human-readable summary of the source file(s) for the metadata table in the generated minutes. */
    private static String sourceFileNames(List<Path> videoFiles) {
        if (videoFiles.size() == 1) {
            return videoFiles.get(0).getFileName().toString();
        }
        if (videoFiles.size() <= 3) {
            return videoFiles.stream().map(p -> p.getFileName().toString())
                    .reduce((a, b) -> a + " + " + b).orElse("");
        }
        return videoFiles.get(0).getFileName() + " (+ " + (videoFiles.size() - 1) + " other files)";
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.debug("Could not remove temporary file {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.debug("Could not clean up temporary directory {}: {}", path, e.getMessage());
        }
    }
}
