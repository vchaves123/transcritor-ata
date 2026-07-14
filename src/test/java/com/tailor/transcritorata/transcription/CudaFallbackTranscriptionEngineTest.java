package com.tailor.transcritorata.transcription;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tailor.transcritorata.audio.ExternalProcessException;
import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.model.Segment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CudaFallbackTranscriptionEngineTest {

    // Saida real capturada de um caso reportado por um usuario com GPU de 2 GB de VRAM (MX330).
    private static final String CUDA_OOM_OUTPUT = """
            whisper_model_load:        CUDA0 total size =  1533.14 MB
            [00:00:00.000 --> 00:00:28.000]   Bom dia, Bruno. Professoral, bom dia. Bom dia.
            CUDA error: out of memory
              current device: 0, in function alloc at D:\\a\\whisper.cpp\\whisper.cpp\\ggml\\src\\ggml-cuda\\ggml-cuda.cu:550
              cuMemSetAccess((CUdeviceptr)((char *)(pool_addr) + pool_size), reserve_size, &access, 1)
            D:\\a\\whisper.cpp\\whisper.cpp\\ggml\\src\\ggml-cuda\\ggml-cuda.cu:103: CUDA error
            """;

    private static final List<Segment> CPU_RESULT = List.of(
            new Segment(Duration.ZERO, Duration.ofSeconds(28), "Bom dia, Bruno. Professor, bom dia. Bom dia."));

    @Test
    void fallsBackToCpuWhenGpuRunsOutOfMemory() throws Exception {
        TranscriptionEngine primary = mock(TranscriptionEngine.class);
        TranscriptionEngine cpuFallback = mock(TranscriptionEngine.class);

        when(primary.transcribe(any(), any(), any()))
                .thenThrow(new ExternalProcessException(
                        "O processo externo terminou com código de erro 1.", CUDA_OOM_OUTPUT));
        when(cpuFallback.transcribe(any(), any(), any())).thenReturn(CPU_RESULT);

        CudaFallbackTranscriptionEngine engine = new CudaFallbackTranscriptionEngine(primary, cpuFallback);
        List<Segment> result = engine.transcribe(java.nio.file.Path.of("audio.wav"), null, new ProcessRunner.Handle());

        assertEquals(CPU_RESULT, result);
    }

    @Test
    void doesNotFallBackOnUnrelatedFailures() throws Exception {
        TranscriptionEngine primary = mock(TranscriptionEngine.class);
        TranscriptionEngine cpuFallback = mock(TranscriptionEngine.class);

        when(primary.transcribe(any(), any(), any()))
                .thenThrow(new ExternalProcessException(
                        "O processo externo terminou com código de erro 1.", "modelo nao encontrado"));

        CudaFallbackTranscriptionEngine engine = new CudaFallbackTranscriptionEngine(primary, cpuFallback);

        assertThrows(ExternalProcessException.class, () ->
                engine.transcribe(java.nio.file.Path.of("audio.wav"), null, new ProcessRunner.Handle()));
        verify(cpuFallback, never()).transcribe(any(), any(), any());
    }

    @Test
    void doesNotCallFallbackWhenPrimarySucceeds() throws Exception {
        TranscriptionEngine primary = mock(TranscriptionEngine.class);
        TranscriptionEngine cpuFallback = mock(TranscriptionEngine.class);
        when(primary.transcribe(any(), any(), any())).thenReturn(CPU_RESULT);

        CudaFallbackTranscriptionEngine engine = new CudaFallbackTranscriptionEngine(primary, cpuFallback);
        engine.transcribe(java.nio.file.Path.of("audio.wav"), null, new ProcessRunner.Handle());

        verify(cpuFallback, never()).transcribe(any(), any(), any());
    }

    @Test
    void detectsCudaOutOfMemorySignatureInProcessOutput() {
        ExternalProcessException match = new ExternalProcessException("erro", CUDA_OOM_OUTPUT);
        ExternalProcessException noMatch = new ExternalProcessException("erro", "algum outro problema qualquer");

        assertEquals(true, CudaFallbackTranscriptionEngine.isCudaOutOfMemory(match));
        assertEquals(false, CudaFallbackTranscriptionEngine.isCudaOutOfMemory(noMatch));
    }
}
