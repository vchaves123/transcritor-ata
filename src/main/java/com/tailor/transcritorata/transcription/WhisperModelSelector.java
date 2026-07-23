package com.tailor.transcritorata.transcription;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.tailor.transcritorata.deps.WhisperModelOption;
import com.tailor.transcritorata.transcription.AdaptiveWhisperEngine.ModelCandidate;

/**
 * Orders locally available Whisper models for {@link AdaptiveWhisperEngine} and picks the CPU
 * fallback model, taking into account that file size alone isn't a reliable proxy for speed once
 * quantized/pruned model variants are in play (see {@link WhisperModelOption#isRecommendedForGpu()}/
 * {@link WhisperModelOption#isRecommendedForCpu()}).
 */
public final class WhisperModelSelector {

    private WhisperModelSelector() {
    }

    /**
     * Orders {@code candidates} with any model marked {@link WhisperModelOption#isRecommendedForGpu()}
     * first — tried on GPU before anything else, even a larger model that would also fit in VRAM —
     * then every other candidate largest-first (the original "biggest that fits, cascading down on
     * OOM" behavior).
     */
    public static List<ModelCandidate> orderForGpuCascade(List<ModelCandidate> candidates) {
        return candidates.stream()
                .sorted(Comparator
                        .comparing((ModelCandidate c) -> isRecommendedForGpu(c.path()) ? 0 : 1)
                        .thenComparing(Comparator.comparingLong(ModelCandidate::sizeBytes).reversed()))
                .toList();
    }

    /**
     * @return the model to use for the final CPU attempt: whichever locally available candidate is
     * marked {@link WhisperModelOption#isRecommendedForCpu()}, if any; otherwise the first entry of
     * {@code orderedCandidates} (the previous, size-based behavior), or {@code configuredModelPath}
     * if there are no candidates at all.
     */
    public static Path selectCpuFallback(List<ModelCandidate> orderedCandidates, Path configuredModelPath) {
        return orderedCandidates.stream()
                .filter(c -> isRecommendedForCpu(c.path()))
                .findFirst()
                .map(ModelCandidate::path)
                .orElseGet(() -> orderedCandidates.isEmpty() ? configuredModelPath : orderedCandidates.get(0).path());
    }

    private static boolean isRecommendedForCpu(Path modelPath) {
        return matchingOption(modelPath).map(WhisperModelOption::isRecommendedForCpu).orElse(false);
    }

    private static boolean isRecommendedForGpu(Path modelPath) {
        return matchingOption(modelPath).map(WhisperModelOption::isRecommendedForGpu).orElse(false);
    }

    private static Optional<WhisperModelOption> matchingOption(Path modelPath) {
        String fileName = modelPath.getFileName().toString();
        for (WhisperModelOption option : WhisperModelOption.values()) {
            if (option.fileName().equals(fileName)) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }
}
