package com.tailor.transcritorata.transcription;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tailor.transcritorata.transcription.AdaptiveWhisperEngine.ModelCandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies model selection accounts for architecturally different model families, where plain
 * file size isn't a reliable speed proxy (see WhisperModelOption's recommendedForGpu/Cpu flags,
 * set from real benchmarking on Portuguese meeting audio: Large Turbo (compact) is fastest on
 * GPU despite not being the largest candidate, but slower than Medium (compact) on CPU).
 */
class WhisperModelSelectorTest {

    private static final ModelCandidate SMALL = candidate("ggml-small.bin", 466_000_000);
    private static final ModelCandidate MEDIUM = candidate("ggml-medium.bin", 1_500_000_000L);
    private static final ModelCandidate MEDIUM_Q5_0 = candidate("ggml-medium-q5_0.bin", 514_000_000);
    private static final ModelCandidate LARGE_TURBO_Q5_0 = candidate("ggml-large-v3-turbo-q5_0.bin", 547_000_000);

    @Test
    void gpuPreferredModelIsOrderedFirstEvenWhenSmallerThanOtherCandidates() {
        // Medium (full) is the largest file here, but Large Turbo (compact) is the one flagged
        // as GPU-preferred, so it must be tried on GPU first regardless of the size ordering.
        List<ModelCandidate> ordered = WhisperModelSelector.orderForGpuCascade(
                List.of(MEDIUM, SMALL, LARGE_TURBO_Q5_0, MEDIUM_Q5_0));

        assertEquals(LARGE_TURBO_Q5_0, ordered.get(0));
        assertEquals(MEDIUM, ordered.get(1), "everything else falls back to largest-first, unchanged");
        assertEquals(MEDIUM_Q5_0, ordered.get(2));
        assertEquals(SMALL, ordered.get(3));
    }

    @Test
    void orderingIsUnchangedWhenNoCandidateIsGpuPreferred() {
        List<ModelCandidate> ordered = WhisperModelSelector.orderForGpuCascade(List.of(SMALL, MEDIUM, MEDIUM_Q5_0));

        assertEquals(MEDIUM, ordered.get(0));
        assertEquals(MEDIUM_Q5_0, ordered.get(1));
        assertEquals(SMALL, ordered.get(2));
    }

    @Test
    void cpuFallbackPrefersTheCpuRecommendedModelEvenWhenALargerGpuOrientedModelIsPresent() {
        List<ModelCandidate> ordered = WhisperModelSelector.orderForGpuCascade(
                List.of(LARGE_TURBO_Q5_0, MEDIUM_Q5_0, SMALL));

        Path cpuFallback = WhisperModelSelector.selectCpuFallback(ordered, Path.of(""));

        assertEquals(MEDIUM_Q5_0.path(), cpuFallback,
                "Large Turbo (compact) benchmarked slower than Medium (compact) on CPU despite being bigger");
    }

    @Test
    void cpuFallbackFallsBackToLargestCandidateWhenNoCpuRecommendedModelIsAvailable() {
        List<ModelCandidate> ordered = WhisperModelSelector.orderForGpuCascade(List.of(SMALL, MEDIUM));

        Path cpuFallback = WhisperModelSelector.selectCpuFallback(ordered, Path.of(""));

        assertEquals(MEDIUM.path(), cpuFallback);
    }

    @Test
    void cpuFallbackUsesConfiguredModelWhenThereAreNoCandidatesAtAll() {
        Path configured = Path.of("custom-model.bin");

        Path cpuFallback = WhisperModelSelector.selectCpuFallback(List.of(), configured);

        assertEquals(configured, cpuFallback);
    }

    private static ModelCandidate candidate(String fileName, long sizeBytes) {
        return new ModelCandidate(Path.of(fileName), sizeBytes);
    }
}
