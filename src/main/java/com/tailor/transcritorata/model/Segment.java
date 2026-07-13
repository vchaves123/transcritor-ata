package com.tailor.transcritorata.model;

import java.time.Duration;

/**
 * A single transcribed segment of speech, with its start/end offsets within the audio track.
 */
public record Segment(Duration start, Duration end, String text) {

    public Segment {
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end must not be null");
        }
        text = text == null ? "" : text.trim();
    }

    /** Shifts this segment forward by the given offset (used when merging chunked transcriptions). */
    public Segment withOffset(Duration offset) {
        return new Segment(start.plus(offset), end.plus(offset), text);
    }

    public String formattedStart() {
        return format(start);
    }

    public static String format(Duration duration) {
        long totalSeconds = duration.toSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return "%02d:%02d:%02d".formatted(hours, minutes, seconds);
    }
}
