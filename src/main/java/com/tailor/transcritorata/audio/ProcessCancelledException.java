package com.tailor.transcritorata.audio;

/**
 * Thrown when an external process was terminated because the user clicked "Cancelar", as
 * opposed to failing on its own. Callers should treat this as an expected, non-error outcome —
 * e.g. show a neutral "cancelado" message instead of the friendly-error dialog.
 */
public final class ProcessCancelledException extends ExternalProcessException {

    private static final long serialVersionUID = 1L;

    public ProcessCancelledException(String processOutput) {
        super("Operação cancelada pelo usuário.", processOutput);
    }
}
