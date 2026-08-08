package com.tailor.transcritorata.model;

import java.time.Duration;
import java.util.List;

/**
 * One source file's complete, self-contained transcription result -- used when the user chooses
 * to transcribe multiple files individually instead of concatenating them into a single timeline.
 * {@code segments} timestamps are relative to this file alone (starting at 00:00), not to any
 * combined recording.
 *
 * @param fileName the source file's name (not its full path), used as the section/index label
 * @param duration this file's own total duration (the end time of its last segment)
 * @param segments this file's transcription, in playback order
 */
public record FileTranscript(String fileName, Duration duration, List<AttributedSegment> segments) {
}
