package com.tailor.transcritorata.transcription;

import java.nio.file.Path;

/**
 * Outcome of a full pipeline run.
 *
 * @param simpleMinutesPath the generated meeting minutes document
 */
public record PipelineResult(Path simpleMinutesPath) {
}
