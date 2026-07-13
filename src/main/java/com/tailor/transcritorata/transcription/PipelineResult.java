package com.tailor.transcritorata.transcription;

import java.nio.file.Path;
import java.util.List;

import com.tailor.transcritorata.model.Segment;

/**
 * Outcome of a full pipeline run.
 *
 * @param simpleMinutesPath     always populated: the plain ata is written before AI structuring runs
 * @param structuredMinutesPath populated only when AI structuring was enabled and succeeded
 * @param aiWarning             friendly Portuguese message when AI structuring was attempted but failed
 * @param segments              the final, offset-merged transcription
 */
public record PipelineResult(Path simpleMinutesPath, Path structuredMinutesPath, String aiWarning,
        List<Segment> segments) {
}
