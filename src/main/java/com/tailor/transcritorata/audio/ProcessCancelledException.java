package com.tailor.transcritorata.audio;

/**
 * Thrown when an external process was terminated because the user clicked "Cancel", as
 * opposed to failing on its own. Callers should treat this as an expected, non-error outcome —
 * e.g. show a neutral "cancelled" message instead of the friendly-error dialog.
 */
public final class ProcessCancelledException extends ExternalProcessException {

    private static final long serialVersionUID = 1L;

    public ProcessCancelledException(String processOutput) {
        super("Operation cancelled by the user.", processOutput);
    }
}
