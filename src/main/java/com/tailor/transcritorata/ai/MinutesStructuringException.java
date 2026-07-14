package com.tailor.transcritorata.ai;

/**
 * Thrown when the AI-structured minutes step fails (network error, invalid API key, or
 * persistently malformed JSON after retrying). Callers must treat this as non-fatal: the plain
 * minutes have already been written to disk before this stage runs.
 */
public class MinutesStructuringException extends Exception {

    private static final long serialVersionUID = 1L;

    public MinutesStructuringException(String message, Throwable cause) {
        super(message, cause);
    }

    public MinutesStructuringException(String message) {
        super(message);
    }
}
