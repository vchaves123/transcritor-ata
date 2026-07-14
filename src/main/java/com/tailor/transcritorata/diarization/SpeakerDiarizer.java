package com.tailor.transcritorata.diarization;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.tailor.transcritorata.audio.ProcessRunner;

/**
 * Identifies which speaker is talking during each stretch of a recording.
 *
 * <p>Isolated behind this interface so the pipeline can be tested with a mock and so the
 * diarization provider can be swapped in the future without touching callers.
 */
public interface SpeakerDiarizer {

    /**
     * @param wav              16&nbsp;kHz mono PCM WAV to analyze
     * @param handle           allows the caller to cancel the underlying external process
     * @param logLineListener  receives raw output lines for display in a log view (may be {@code null})
     */
    List<SpeakerTurn> diarize(Path wav, ProcessRunner.Handle handle, Consumer<String> logLineListener)
            throws DiarizationException;
}
