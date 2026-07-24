package com.tailor.transcritorata.transcription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tailor.transcritorata.audio.AudioExtractor;
import com.tailor.transcritorata.audio.ExternalProcessException;
import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.deps.SleepDetector;
import com.tailor.transcritorata.diarization.DiarizationException;
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
     * @param diarizationListener   receives progress/log lines for the (optional) diarization step
     * @param minutesListener       receives progress/log lines for minutes generation (docx)
     * @param totalSleepListener    receives the combined suspended time across every phase (zero if
     *                              none), once the whole run ends -- regardless of how it ended
     */
    public PipelineResult run(List<Path> videoFiles, Path outputDir,
            ProgressListener audioListener, ProgressListener transcriptionListener,
            ProgressListener diarizationListener, ProgressListener minutesListener,
            Consumer<Duration> totalSleepListener,
            ProcessRunner.Handle handle) throws ExternalProcessException, IOException, InterruptedException {
        if (videoFiles.isEmpty()) {
            throw new IllegalArgumentException("No video file selected.");
        }
        Path tempDir = Files.createTempDirectory("transcritor-ata-");
        List<PhaseWindow> phaseWindows = new ArrayList<>();
        try {
            Instant audioStart = Instant.now();
            Path wav = extractAndConcatenate(videoFiles, tempDir, audioListener, handle);
            phaseWindows.add(new PhaseWindow(audioListener, audioStart, Instant.now()));

            // Transcription and (optional) diarization run one at a time, not concurrently: both
            // are CPU-heavy on their own (whisper-cli requests every logical CPU for itself, and
            // the ONNX Runtime diarization models default to using every physical core too), so
            // running them in parallel only meant each got a smaller, unpredictable slice of the
            // CPU instead of finishing sooner — net slower, not faster.
            Instant transcriptionStart = Instant.now();
            Duration cpuBeforeTranscription = handle.cpuTimeUsed();
            transcriptionListener.onProgress("Transcribing... (this may take a few minutes)", 0);
            List<Segment> segments = engine.transcribe(wav,
                    (msg, pct) -> transcriptionListener.onProgress(msg, pct), handle);
            transcriptionListener.onProgress("Transcription complete.", 100);
            reportCpuTime(transcriptionListener, handle.cpuTimeUsed().minus(cpuBeforeTranscription));
            phaseWindows.add(new PhaseWindow(transcriptionListener, transcriptionStart, Instant.now()));

            Instant diarizationStart = Instant.now();
            long cpuBeforeDiarizationNanos = processCpuTimeNanos();
            List<AttributedSegment> attributed = attribute(wav, segments, handle, diarizationListener);
            if (diarizationEnabled && speakerDiarizer != null) {
                reportCpuTime(diarizationListener, inProcessCpuDelta(cpuBeforeDiarizationNanos, processCpuTimeNanos()));
                phaseWindows.add(new PhaseWindow(diarizationListener, diarizationStart, Instant.now()));
            }

            Duration totalDuration = segments.isEmpty() ? Duration.ZERO
                    : segments.get(segments.size() - 1).end();

            String baseName = stripExtension(videoFiles.get(0).getFileName().toString());
            MeetingMetadata metadata = new MeetingMetadata(LocalDate.now(), sourceFileNames(videoFiles),
                    totalDuration);

            Instant minutesStart = Instant.now();
            long cpuBeforeMinutesNanos = processCpuTimeNanos();
            minutesListener.onProgress("Generating minutes...", 50);
            Path simpleMinutes = outputDir.resolve(baseName + "-minutes.docx");
            docxGenerator.generateSimpleMinutesAttributed(simpleMinutes, metadata, attributed);
            reportCpuTime(minutesListener, inProcessCpuDelta(cpuBeforeMinutesNanos, processCpuTimeNanos()));
            phaseWindows.add(new PhaseWindow(minutesListener, minutesStart, Instant.now()));

            minutesListener.onProgress("Complete", 100);
            return new PipelineResult(simpleMinutes);
        } finally {
            reportSleepIntervalsPerPhase(phaseWindows, totalSleepListener);
            deleteRecursively(tempDir);
        }
    }

    /** One phase's own wall-clock window, used to attribute a detected sleep/resume cycle to the phase it actually interrupted. */
    private record PhaseWindow(ProgressListener listener, Instant start, Instant end) {
    }

    /**
     * Checks Windows' own event log (see {@link SleepDetector}) for any sleep/resume cycle that
     * happened during this run, and reports each one only to the specific phase(s) whose window it
     * actually falls within -- not to every phase indiscriminately, since a suspension during (say)
     * transcription didn't happen "during" minutes generation and showing it there would wrongly
     * suggest that phase was interrupted too. Also reports the combined total across every phase via
     * {@code totalSleepListener}, e.g. to annotate an overall elapsed-time indicator. Run regardless
     * of how the pipeline ended (an unexplained timeout or failure is often exactly when this
     * matters most), and never lets a query failure affect the pipeline's own outcome (already the
     * case inside SleepDetector).
     */
    private static void reportSleepIntervalsPerPhase(List<PhaseWindow> phaseWindows, Consumer<Duration> totalSleepListener) {
        if (phaseWindows.isEmpty()) {
            return;
        }
        Instant earliestStart = phaseWindows.stream().map(PhaseWindow::start).min(Instant::compareTo).orElseThrow();
        List<SleepDetector.SleepInterval> allIntervals = SleepDetector.findSleepIntervalsSince(earliestStart);

        for (PhaseWindow phase : phaseWindows) {
            List<SleepDetector.SleepInterval> overlapping = allIntervals.stream()
                    .filter(interval -> !interval.sleepTime().isBefore(phase.start()) && interval.sleepTime().isBefore(phase.end()))
                    .toList();
            reportSleepForPhase(phase.listener(), overlapping);
        }

        Duration grandTotal = allIntervals.stream().map(SleepDetector.SleepInterval::duration)
                .reduce(Duration.ZERO, Duration::plus);
        totalSleepListener.accept(grandTotal);
    }

    private static void reportSleepForPhase(ProgressListener listener, List<SleepDetector.SleepInterval> intervals) {
        if (intervals.isEmpty()) {
            listener.onProgress("No system sleep/hibernation detected during this phase.", -1);
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        Duration total = Duration.ZERO;
        for (SleepDetector.SleepInterval interval : intervals) {
            listener.onProgress("System was suspended from " + interval.sleepTime().atZone(zone).format(CLOCK_TIME_FORMAT)
                    + " to " + interval.wakeTime().atZone(zone).format(CLOCK_TIME_FORMAT)
                    + " (" + formatDuration(interval.duration()) + ")", -1);
            total = total.plus(interval.duration());
        }
        // Only worth a separate summary line when there's more than one occurrence to sum -- for a
        // single occurrence it would just repeat the line above.
        if (intervals.size() > 1) {
            listener.onProgress("Total time suspended during this phase: " + formatDuration(total)
                    + " across " + intervals.size() + " occurrences.", -1);
        }
    }

    private static final DateTimeFormatter CLOCK_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static String formatDuration(Duration duration) {
        long totalSeconds = duration.toSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0 ? "%d:%02d:%02d".formatted(hours, minutes, seconds) : "%02d:%02d".formatted(minutes, seconds);
    }

    /**
     * @return the JVM process's total CPU time in nanoseconds since it started, or -1 if the
     *         platform doesn't support this measurement.
     */
    private static long processCpuTimeNanos() {
        var osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        return osBean instanceof com.sun.management.OperatingSystemMXBean sunBean ? sunBean.getProcessCpuTime() : -1;
    }

    /** @return the CPU time delta, or a negative sentinel duration if either snapshot was unsupported. */
    private static Duration inProcessCpuDelta(long beforeNanos, long afterNanos) {
        if (beforeNanos < 0 || afterNanos < 0) {
            return Duration.ofNanos(-1);
        }
        return Duration.ofNanos(Math.max(0, afterNanos - beforeNanos));
    }

    /** Reports {@code cpuTime} as a log-only line, unless the measurement was unavailable (negative). */
    private static void reportCpuTime(ProgressListener listener, Duration cpuTime) {
        if (cpuTime.isNegative()) {
            return;
        }
        listener.onProgress("CPU time: " + formatSeconds(cpuTime) + "s", -1);
    }

    private static String formatSeconds(Duration duration) {
        return String.format(Locale.ROOT, "%.1f", duration.toNanos() / 1_000_000_000.0);
    }

    /**
     * Extracts audio from each source file (in order) and, when there's more than one, concatenates
     * them into a single WAV — the transcription/diarization stages downstream only ever see one
     * combined recording.
     */
    private Path extractAndConcatenate(List<Path> videoFiles, Path tempDir, ProgressListener audioListener,
            ProcessRunner.Handle handle) throws ExternalProcessException, IOException {
        Duration cpuBeforeExtraction = handle.cpuTimeUsed();
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
        reportCpuTime(audioListener, handle.cpuTimeUsed().minus(cpuBeforeExtraction));
        return combined;
    }

    /**
     * Runs the (optional) diarization step, after transcription has already finished, and
     * attributes speakers to the transcription. A diarization failure is non-fatal: the
     * transcription is returned without speaker labels.
     */
    private List<AttributedSegment> attribute(Path wav, List<Segment> segments, ProcessRunner.Handle handle,
            ProgressListener listener) {
        if (!diarizationEnabled || speakerDiarizer == null) {
            return segments.stream().map(s -> new AttributedSegment(s, null)).toList();
        }
        listener.onProgress("Identifying participants...", -1);
        try {
            List<SpeakerTurn> turns = speakerDiarizer.diarize(wav, handle, line -> listener.onProgress(line, -1));
            listener.onProgress("Participant identification complete.", 100);
            return SpeakerAttributor.attribute(segments, turns);
        } catch (DiarizationException e) {
            LOG.warn("Failed to identify participants: {}", e.getMessage());
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
