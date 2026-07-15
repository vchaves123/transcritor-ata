package com.tailor.transcritorata.transcription;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tailor.transcritorata.audio.ExternalProcessException;
import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.deps.GpuDetector;
import com.tailor.transcritorata.model.Segment;

/**
 * Picks the best Whisper model/decoding settings for the available GPU, retrying with cheaper
 * options whenever whisper-cli reports a CUDA out-of-memory error:
 *
 * <ol>
 *   <li>Start from the largest locally available model that fits in the detected VRAM.</li>
 *   <li>On CUDA OOM, retry the same model in fast mode (greedy decoding, much less VRAM).</li>
 *   <li>If it still OOMs, move down to the next smaller available model and repeat from step 1.</li>
 *   <li>Once every candidate model has been exhausted (or no GPU/VRAM info is available), fall
 *       back to the CPU build with the largest available model.</li>
 * </ol>
 *
 * <p>Any failure that isn't a CUDA out-of-memory error is propagated immediately — this class
 * only handles the "ran out of GPU memory" case, not other transcription failures.
 */
public final class AdaptiveWhisperEngine implements TranscriptionEngine {

    private static final Logger LOG = LoggerFactory.getLogger(AdaptiveWhisperEngine.class);

    // Safety margin below the raw model file size vs. detected VRAM: inference needs some extra
    // memory beyond the model weights themselves (activations, KV cache, decoding buffers), so a
    // model whose file size is *exactly* the VRAM size would almost certainly still OOM.
    private static final double MODEL_FIT_VRAM_MARGIN = 0.9;

    /** A locally available Whisper model file and its size, used to rank candidates by size. */
    public record ModelCandidate(Path path, long sizeBytes) {
    }

    /** Builds the concrete engine used for one (binary, model, decoding mode) attempt. */
    interface EngineFactory {
        TranscriptionEngine create(String binary, Path model, boolean fastMode);
    }

    private final String cudaBinary;
    private final String cpuBinary;
    private final List<ModelCandidate> candidatesDescBySize;
    private final Path cpuFallbackModel;
    private final boolean preferFastModeFirst;
    private final GpuDetector gpuDetector;
    private final EngineFactory engineFactory;

    /**
     * @param cudaBinary           path to the CUDA whisper-cli build, or {@code null} if not
     *                              available
     * @param cpuBinary             path to the CPU-only whisper-cli build (always required)
     * @param candidatesDescBySize  locally available models, sorted largest-first; may be empty
     * @param cpuFallbackModel      model used for the final CPU attempt (typically the largest
     *                              candidate)
     * @param preferFastModeFirst   when {@code true}, each model's first attempt uses fast mode
     *                              (greedy decoding) directly instead of starting with beam search
     */
    public AdaptiveWhisperEngine(String cudaBinary, String cpuBinary, List<ModelCandidate> candidatesDescBySize,
            Path cpuFallbackModel, String language, long timeoutSeconds, boolean preferFastModeFirst,
            GpuDetector gpuDetector) {
        this(cudaBinary, cpuBinary, candidatesDescBySize, cpuFallbackModel, preferFastModeFirst, gpuDetector,
                (binary, model, fastMode) -> new WhisperCppEngine(binary, model, language, timeoutSeconds, fastMode));
    }

    /** Package-visible for testing, so a fake {@link EngineFactory} can simulate OOM/success without a real binary. */
    AdaptiveWhisperEngine(String cudaBinary, String cpuBinary, List<ModelCandidate> candidatesDescBySize,
            Path cpuFallbackModel, boolean preferFastModeFirst, GpuDetector gpuDetector, EngineFactory engineFactory) {
        this.cudaBinary = cudaBinary;
        this.cpuBinary = cpuBinary;
        this.candidatesDescBySize = candidatesDescBySize;
        this.cpuFallbackModel = cpuFallbackModel;
        this.preferFastModeFirst = preferFastModeFirst;
        this.gpuDetector = gpuDetector;
        this.engineFactory = engineFactory;
    }

    @Override
    public List<Segment> transcribe(Path wav, ProgressListener listener, ProcessRunner.Handle handle)
            throws ExternalProcessException, IOException {
        if (cudaBinary != null && !candidatesDescBySize.isEmpty() && gpuDetector.hasNvidiaGpu()) {
            Optional<Long> vramMb = gpuDetector.vramMb();
            if (vramMb.isPresent() && vramMb.get() > 0) {
                long vramBytes = Math.round(vramMb.get() * 1024.0 * 1024.0 * MODEL_FIT_VRAM_MARGIN);
                int startIndex = firstFittingIndex(vramBytes);
                for (int i = startIndex; i >= 0 && i < candidatesDescBySize.size(); i++) {
                    List<Segment> result = tryModelOnGpu(candidatesDescBySize.get(i), wav, listener, handle);
                    if (result != null) {
                        return result;
                    }
                }
                if (startIndex < 0) {
                    notify(listener, "No available model fits the detected GPU memory; using the CPU instead "
                            + "(slower, but should work)...");
                }
            }
        }
        return attempt(cpuBinary, cpuFallbackModel, preferFastModeFirst, "CPU", wav, listener, handle);
    }

    private int firstFittingIndex(long vramBytes) {
        for (int i = 0; i < candidatesDescBySize.size(); i++) {
            if (candidatesDescBySize.get(i).sizeBytes() <= vramBytes) {
                return i;
            }
        }
        return -1;
    }

    /** @return the transcription result, or {@code null} to signal "OOM'd, try the next model". */
    private List<Segment> tryModelOnGpu(ModelCandidate candidate, Path wav, ProgressListener listener,
            ProcessRunner.Handle handle) throws ExternalProcessException, IOException {
        if (!preferFastModeFirst) {
            try {
                return attempt(cudaBinary, candidate.path(), false, "GPU", wav, listener, handle);
            } catch (ExternalProcessException e) {
                if (!isCudaOutOfMemory(e)) {
                    throw e;
                }
                notify(listener, "GPU ran out of memory with " + candidate.path().getFileName()
                        + " (beam search); retrying the same model in fast mode...");
            }
        }
        try {
            return attempt(cudaBinary, candidate.path(), true, "GPU", wav, listener, handle);
        } catch (ExternalProcessException e) {
            if (!isCudaOutOfMemory(e)) {
                throw e;
            }
            notify(listener, "GPU still out of memory with " + candidate.path().getFileName()
                    + " (fast mode); trying a smaller model if one is available...");
            return null;
        }
    }

    private List<Segment> attempt(String binary, Path model, boolean fastMode, String device, Path wav,
            ProgressListener listener, ProcessRunner.Handle handle) throws ExternalProcessException, IOException {
        notify(listener, "Trying " + model.getFileName() + " on " + device
                + (fastMode ? " (fast mode)" : " (beam search)") + "...");
        return engineFactory.create(binary, model, fastMode).transcribe(wav, listener, handle);
    }

    private static void notify(ProgressListener listener, String message) {
        LOG.info(message);
        if (listener != null) {
            listener.onProgress(message, -1);
        }
    }

    /** Package-visible for testing. */
    static boolean isCudaOutOfMemory(ExternalProcessException e) {
        String haystack = (safe(e.getMessage()) + " " + safe(e.getProcessOutput())).toLowerCase();
        return haystack.contains("cuda error") && haystack.contains("out of memory");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
