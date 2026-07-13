package com.tailor.transcritorata.transcription;

/**
 * Callback used by long-running pipeline stages to report progress.
 * Implementations must be safe to call from a background thread; GUI listeners
 * are expected to marshal updates back onto the UI thread themselves.
 */
@FunctionalInterface
public interface ProgressListener {

    void onProgress(String message, int percent);
}
