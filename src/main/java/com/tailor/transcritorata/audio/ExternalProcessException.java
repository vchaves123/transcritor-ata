package com.tailor.transcritorata.audio;

/**
 * Thrown when an external process (ffmpeg, whisper-cli, ...) fails or times out.
 * The message is safe to show to end users in a friendly dialog; {@link #getProcessOutput()}
 * carries the raw stdout/stderr for a "view details" expander.
 */
public class ExternalProcessException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String processOutput;

    public ExternalProcessException(String message, String processOutput) {
        super(message);
        this.processOutput = processOutput == null ? "" : processOutput;
    }

    public String getProcessOutput() {
        return processOutput;
    }
}
