package com.tailor.transcritorata.model;

/**
 * A transcribed {@link Segment} optionally attributed to a speaker.
 *
 * @param segment      the underlying transcription segment
 * @param speakerLabel friendly speaker name (e.g. "Pessoa 1"), or {@code null} when diarization
 *                     was not performed
 */
public record AttributedSegment(Segment segment, String speakerLabel) {

    public AttributedSegment {
        if (segment == null) {
            throw new IllegalArgumentException("segment must not be null");
        }
    }

    public boolean hasSpeaker() {
        return speakerLabel != null && !speakerLabel.isBlank();
    }
}
