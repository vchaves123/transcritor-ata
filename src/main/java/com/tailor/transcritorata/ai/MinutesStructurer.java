package com.tailor.transcritorata.ai;

/**
 * Structures a raw meeting transcript into {@link StructuredMinutes} using an external AI
 * provider. Isolated behind this interface so the GUI/pipeline can be tested with a mock and so
 * the provider can be swapped in the future without touching callers.
 */
public interface MinutesStructurer {

    StructuredMinutes structure(String transcriptText) throws MinutesStructuringException;
}
