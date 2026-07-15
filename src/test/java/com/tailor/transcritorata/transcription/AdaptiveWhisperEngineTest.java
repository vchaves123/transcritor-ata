package com.tailor.transcritorata.transcription;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.tailor.transcritorata.audio.ExternalProcessException;
import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.deps.GpuDetector;
import com.tailor.transcritorata.model.Segment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdaptiveWhisperEngineTest {

    private static final String CUDA_OOM_OUTPUT = "CUDA error: out of memory";
    private static final Path LARGE_MODEL = Path.of("ggml-large-v3.bin");
    private static final Path MEDIUM_MODEL = Path.of("ggml-medium.bin");
    private static final Path SMALL_MODEL = Path.of("ggml-small.bin");

    private static final List<Segment> RESULT = List.of(
            new Segment(Duration.ZERO, Duration.ofSeconds(1), "ok"));

    private static AdaptiveWhisperEngine.ModelCandidate candidate(Path path, long sizeBytes) {
        return new AdaptiveWhisperEngine.ModelCandidate(path, sizeBytes);
    }

    private static GpuDetector gpuDetector(boolean hasGpu, Long vramMb) {
        GpuDetector detector = mock(GpuDetector.class);
        when(detector.hasNvidiaGpu()).thenReturn(hasGpu);
        when(detector.vramMb()).thenReturn(vramMb == null ? Optional.empty() : Optional.of(vramMb));
        return detector;
    }

    /** Records every (binary, model, fastMode) attempt so tests can assert the exact sequence. */
    private static final class RecordingFactory implements AdaptiveWhisperEngine.EngineFactory {
        record Attempt(String binary, Path model, boolean fastMode) {
        }

        final List<Attempt> attempts = new ArrayList<>();
        final List<Object> outcomes; // ExternalProcessException or a List<Segment> result, consumed in order

        RecordingFactory(List<Object> outcomes) {
            this.outcomes = new ArrayList<>(outcomes);
        }

        @Override
        public TranscriptionEngine create(String binary, Path model, boolean fastMode) {
            attempts.add(new Attempt(binary, model, fastMode));
            Object outcome = outcomes.remove(0);
            return (wav, listener, handle) -> {
                if (outcome instanceof ExternalProcessException ex) {
                    throw ex;
                }
                @SuppressWarnings("unchecked")
                List<Segment> segments = (List<Segment>) outcome;
                return segments;
            };
        }
    }

    private static ExternalProcessException oomException() {
        return new ExternalProcessException("The external process exited with error code 1.", CUDA_OOM_OUTPUT);
    }

    @Test
    void picksLargestModelThatFitsAndSucceedsOnFirstTry() throws Exception {
        List<AdaptiveWhisperEngine.ModelCandidate> candidates = List.of(
                candidate(LARGE_MODEL, 3_000_000_000L),
                candidate(MEDIUM_MODEL, 1_500_000_000L),
                candidate(SMALL_MODEL, 500_000_000L));
        RecordingFactory factory = new RecordingFactory(List.of(RESULT));

        AdaptiveWhisperEngine engine = new AdaptiveWhisperEngine("cuda.exe", "cpu.exe", candidates, LARGE_MODEL,
                false, gpuDetector(true, 2000L), factory);

        List<Segment> result = engine.transcribe(Path.of("audio.wav"), null, new ProcessRunner.Handle());

        assertEquals(RESULT, result);
        assertEquals(1, factory.attempts.size());
        assertEquals(new RecordingFactory.Attempt("cuda.exe", MEDIUM_MODEL, false), factory.attempts.get(0));
    }

    @Test
    void escalatesToFastModeOnOomThenSucceeds() throws Exception {
        List<AdaptiveWhisperEngine.ModelCandidate> candidates = List.of(candidate(MEDIUM_MODEL, 1_000_000_000L));
        RecordingFactory factory = new RecordingFactory(List.of(oomException(), RESULT));

        AdaptiveWhisperEngine engine = new AdaptiveWhisperEngine("cuda.exe", "cpu.exe", candidates, MEDIUM_MODEL,
                false, gpuDetector(true, 2000L), factory);

        List<Segment> result = engine.transcribe(Path.of("audio.wav"), null, new ProcessRunner.Handle());

        assertEquals(RESULT, result);
        assertEquals(2, factory.attempts.size());
        assertEquals(new RecordingFactory.Attempt("cuda.exe", MEDIUM_MODEL, false), factory.attempts.get(0));
        assertEquals(new RecordingFactory.Attempt("cuda.exe", MEDIUM_MODEL, true), factory.attempts.get(1));
    }

    @Test
    void movesToSmallerModelAfterFastModeStillOoms() throws Exception {
        List<AdaptiveWhisperEngine.ModelCandidate> candidates = List.of(
                candidate(MEDIUM_MODEL, 1_000_000_000L),
                candidate(SMALL_MODEL, 400_000_000L));
        RecordingFactory factory = new RecordingFactory(List.of(oomException(), oomException(), RESULT));

        AdaptiveWhisperEngine engine = new AdaptiveWhisperEngine("cuda.exe", "cpu.exe", candidates, MEDIUM_MODEL,
                false, gpuDetector(true, 2000L), factory);

        List<Segment> result = engine.transcribe(Path.of("audio.wav"), null, new ProcessRunner.Handle());

        assertEquals(RESULT, result);
        assertEquals(3, factory.attempts.size());
        assertEquals(new RecordingFactory.Attempt("cuda.exe", MEDIUM_MODEL, false), factory.attempts.get(0));
        assertEquals(new RecordingFactory.Attempt("cuda.exe", MEDIUM_MODEL, true), factory.attempts.get(1));
        assertEquals(new RecordingFactory.Attempt("cuda.exe", SMALL_MODEL, false), factory.attempts.get(2));
    }

    @Test
    void fallsBackToCpuWithLargestModelWhenAllGpuAttemptsOom() throws Exception {
        List<AdaptiveWhisperEngine.ModelCandidate> candidates = List.of(
                candidate(MEDIUM_MODEL, 1_000_000_000L),
                candidate(SMALL_MODEL, 400_000_000L));
        RecordingFactory factory = new RecordingFactory(
                List.of(oomException(), oomException(), oomException(), oomException(), RESULT));

        AdaptiveWhisperEngine engine = new AdaptiveWhisperEngine("cuda.exe", "cpu.exe", candidates, MEDIUM_MODEL,
                false, gpuDetector(true, 2000L), factory);

        List<Segment> result = engine.transcribe(Path.of("audio.wav"), null, new ProcessRunner.Handle());

        assertEquals(RESULT, result);
        assertEquals(5, factory.attempts.size());
        assertEquals(new RecordingFactory.Attempt("cpu.exe", MEDIUM_MODEL, false), factory.attempts.get(4));
    }

    @Test
    void skipsGpuEntirelyWhenNoModelFitsInVram() throws Exception {
        List<AdaptiveWhisperEngine.ModelCandidate> candidates = List.of(candidate(LARGE_MODEL, 3_000_000_000L));
        RecordingFactory factory = new RecordingFactory(List.of(RESULT));

        AdaptiveWhisperEngine engine = new AdaptiveWhisperEngine("cuda.exe", "cpu.exe", candidates, LARGE_MODEL,
                false, gpuDetector(true, 1000L), factory);

        List<Segment> result = engine.transcribe(Path.of("audio.wav"), null, new ProcessRunner.Handle());

        assertEquals(RESULT, result);
        assertEquals(1, factory.attempts.size());
        assertEquals(new RecordingFactory.Attempt("cpu.exe", LARGE_MODEL, false), factory.attempts.get(0));
    }

    @Test
    void skipsGpuWhenNoNvidiaGpuDetected() throws Exception {
        List<AdaptiveWhisperEngine.ModelCandidate> candidates = List.of(candidate(MEDIUM_MODEL, 1_000_000_000L));
        RecordingFactory factory = new RecordingFactory(List.of(RESULT));

        AdaptiveWhisperEngine engine = new AdaptiveWhisperEngine("cuda.exe", "cpu.exe", candidates, MEDIUM_MODEL,
                false, gpuDetector(false, null), factory);

        engine.transcribe(Path.of("audio.wav"), null, new ProcessRunner.Handle());

        assertEquals(1, factory.attempts.size());
        assertEquals("cpu.exe", factory.attempts.get(0).binary());
    }

    @Test
    void preferFastModeFirstSkipsBeamSearchAttempt() throws Exception {
        List<AdaptiveWhisperEngine.ModelCandidate> candidates = List.of(candidate(MEDIUM_MODEL, 1_000_000_000L));
        RecordingFactory factory = new RecordingFactory(List.of(RESULT));

        AdaptiveWhisperEngine engine = new AdaptiveWhisperEngine("cuda.exe", "cpu.exe", candidates, MEDIUM_MODEL,
                true, gpuDetector(true, 2000L), factory);

        engine.transcribe(Path.of("audio.wav"), null, new ProcessRunner.Handle());

        assertEquals(1, factory.attempts.size());
        assertEquals(new RecordingFactory.Attempt("cuda.exe", MEDIUM_MODEL, true), factory.attempts.get(0));
    }

    @Test
    void nonOomFailurePropagatesImmediatelyWithoutFallback() {
        List<AdaptiveWhisperEngine.ModelCandidate> candidates = List.of(candidate(MEDIUM_MODEL, 1_000_000_000L));
        ExternalProcessException unrelated = new ExternalProcessException(
                "The external process exited with error code 1.", "model file not found");
        RecordingFactory factory = new RecordingFactory(List.of(unrelated));

        AdaptiveWhisperEngine engine = new AdaptiveWhisperEngine("cuda.exe", "cpu.exe", candidates, MEDIUM_MODEL,
                false, gpuDetector(true, 2000L), factory);

        assertThrows(ExternalProcessException.class,
                () -> engine.transcribe(Path.of("audio.wav"), null, new ProcessRunner.Handle()));
        assertEquals(1, factory.attempts.size());
    }
}
