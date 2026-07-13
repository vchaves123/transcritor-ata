package com.tailor.transcritorata.transcription;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.tailor.transcritorata.audio.ExternalProcessException;
import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.model.Segment;

/**
 * Converts a WAV audio file into a list of timestamped {@link Segment}s.
 */
public interface TranscriptionEngine {

    List<Segment> transcribe(Path wav, ProgressListener listener, ProcessRunner.Handle handle)
            throws ExternalProcessException, IOException;
}
