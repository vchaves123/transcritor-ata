package com.tailor.transcritorata.diarization;

import java.time.Duration;

/**
 * A contiguous stretch of audio attributed to a single speaker by the diarization stage.
 *
 * @param start        offset of the turn within the recording
 * @param end          end offset of the turn
 * @param speakerLabel raw speaker id as produced by the diarizer (e.g. {@code S0}, {@code S1})
 */
public record SpeakerTurn(Duration start, Duration end, String speakerLabel) {

    public SpeakerTurn {
        if (start == null || end == null || speakerLabel == null) {
            throw new IllegalArgumentException("start, end and speakerLabel must not be null");
        }
    }

    /** Length of the overlap between this turn and the [otherStart, otherEnd] interval; zero if disjoint. */
    public Duration overlapWith(Duration otherStart, Duration otherEnd) {
        Duration overlapStart = start.compareTo(otherStart) > 0 ? start : otherStart;
        Duration overlapEnd = end.compareTo(otherEnd) < 0 ? end : otherEnd;
        Duration overlap = overlapEnd.minus(overlapStart);
        return overlap.isNegative() ? Duration.ZERO : overlap;
    }
}
