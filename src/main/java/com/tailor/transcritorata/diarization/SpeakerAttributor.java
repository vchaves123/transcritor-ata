package com.tailor.transcritorata.diarization;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tailor.transcritorata.model.AttributedSegment;
import com.tailor.transcritorata.model.Segment;

/**
 * Attributes each transcribed {@link Segment} to a speaker by cross-referencing it with the
 * diarization turns: a segment is assigned to whichever speaker turn overlaps it the most in
 * time. Raw diarizer ids ({@code S0}, {@code S1}, ...) are mapped to friendly, ordinal English
 * labels ("Speaker 1", "Speaker 2", ...) in order of first appearance.
 */
public final class SpeakerAttributor {

    private SpeakerAttributor() {
    }

    public static List<AttributedSegment> attribute(List<Segment> segments, List<SpeakerTurn> turns) {
        Map<String, String> friendlyNames = new LinkedHashMap<>();
        List<AttributedSegment> result = new ArrayList<>(segments.size());

        for (Segment segment : segments) {
            String rawLabel = bestMatchingSpeaker(segment, turns);
            String friendly = rawLabel == null ? null
                    : friendlyNames.computeIfAbsent(rawLabel, key -> "Speaker " + (friendlyNames.size() + 1));
            result.add(new AttributedSegment(segment, friendly));
        }
        return result;
    }

    private static String bestMatchingSpeaker(Segment segment, List<SpeakerTurn> turns) {
        String best = null;
        Duration bestOverlap = Duration.ZERO;
        for (SpeakerTurn turn : turns) {
            Duration overlap = turn.overlapWith(segment.start(), segment.end());
            if (overlap.compareTo(bestOverlap) > 0) {
                bestOverlap = overlap;
                best = turn.speakerLabel();
            }
        }
        return best;
    }
}
