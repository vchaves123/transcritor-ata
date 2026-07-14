package com.tailor.transcritorata.diarization;

/**
 * Thrown when the optional speaker-diarization stage fails. Callers must treat this as
 * non-fatal: the transcription itself has already succeeded, so the minutes are still produced,
 * just without speaker labels.
 */
public class DiarizationException extends Exception {

    private static final long serialVersionUID = 1L;

    public DiarizationException(String message, Throwable cause) {
        super(message, cause);
    }

    public DiarizationException(String message) {
        super(message);
    }
}
