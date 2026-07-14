package com.tailor.transcritorata.transcription;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tailor.transcritorata.audio.ExternalProcessException;
import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.model.Segment;

/**
 * Wraps a GPU-accelerated {@link TranscriptionEngine} and automatically retries on a CPU-only
 * engine when whisper-cli fails with a CUDA out-of-memory error.
 *
 * <p>This happens in practice on GPUs with little VRAM (2&nbsp;GB cards, common in older/entry
 * laptops): a long recording with a large model and wide beam search can exceed available VRAM
 * mid-transcription, well after the model itself loaded successfully. Restarting the whole
 * transcription on CPU is slower but far friendlier than surfacing a raw CUDA error to a
 * non-technical user.
 */
public final class CudaFallbackTranscriptionEngine implements TranscriptionEngine {

    private static final Logger LOG = LoggerFactory.getLogger(CudaFallbackTranscriptionEngine.class);

    private final TranscriptionEngine primary;
    private final TranscriptionEngine cpuFallback;

    public CudaFallbackTranscriptionEngine(TranscriptionEngine primary, TranscriptionEngine cpuFallback) {
        this.primary = primary;
        this.cpuFallback = cpuFallback;
    }

    @Override
    public List<Segment> transcribe(Path wav, ProgressListener listener, ProcessRunner.Handle handle)
            throws ExternalProcessException, IOException {
        try {
            return primary.transcribe(wav, listener, handle);
        } catch (ExternalProcessException e) {
            if (!isCudaOutOfMemory(e)) {
                throw e;
            }
            LOG.warn("whisper-cli falhou por falta de memória na GPU; tentando novamente com CPU. Detalhes: {}",
                    e.getMessage());
            if (listener != null) {
                listener.onProgress(
                        "A GPU não tinha memória suficiente para este áudio. Tentando novamente usando a CPU "
                                + "(mais lento, mas deve funcionar)...",
                        -1);
            }
            return cpuFallback.transcribe(wav, listener, handle);
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
