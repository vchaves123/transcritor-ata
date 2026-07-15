package com.tailor.transcritorata.diarization;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tailor.transcritorata.model.AttributedSegment;
import com.tailor.transcritorata.model.Segment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpeakerAttributorTest {

    private static Segment segment(long startSec, long endSec, String text) {
        return new Segment(Duration.ofSeconds(startSec), Duration.ofSeconds(endSec), text);
    }

    private static SpeakerTurn turn(long startSec, long endSec, String label) {
        return new SpeakerTurn(Duration.ofSeconds(startSec), Duration.ofSeconds(endSec), label);
    }

    @Test
    void assignsSegmentToSpeakerWithLargestOverlapAndMapsToFriendlyNames() {
        List<Segment> segments = List.of(
                segment(0, 5, "first utterance"),
                segment(6, 10, "second utterance"),
                segment(11, 15, "third utterance"));

        List<SpeakerTurn> turns = List.of(
                turn(0, 5, "S0"),
                turn(5, 10, "S1"),
                turn(10, 15, "S0"));

        List<AttributedSegment> attributed = SpeakerAttributor.attribute(segments, turns);

        // S0 appears first -> "Speaker 1"; S1 next -> "Speaker 2"; S0 reappears -> stays "Speaker 1"
        assertEquals("Speaker 1", attributed.get(0).speakerLabel());
        assertEquals("Speaker 2", attributed.get(1).speakerLabel());
        assertEquals("Speaker 1", attributed.get(2).speakerLabel());
    }

    @Test
    void choosesSpeakerWithMoreOverlapWhenSegmentSpansTwoTurns() {
        // segment 0..10; S0 covers 0..3, S1 covers 3..10 -> S1 has more overlap
        List<Segment> segments = List.of(segment(0, 10, "overlapping speech"));
        List<SpeakerTurn> turns = List.of(turn(0, 3, "S0"), turn(3, 10, "S1"));

        List<AttributedSegment> attributed = SpeakerAttributor.attribute(segments, turns);

        assertEquals("Speaker 1", attributed.get(0).speakerLabel());
    }

    @Test
    void leavesSpeakerNullWhenNoTurnOverlaps() {
        List<Segment> segments = List.of(segment(20, 25, "isolated speech"));
        List<SpeakerTurn> turns = List.of(turn(0, 5, "S0"));

        List<AttributedSegment> attributed = SpeakerAttributor.attribute(segments, turns);

        assertNull(attributed.get(0).speakerLabel());
        assertFalse(attributed.get(0).hasSpeaker());
    }

    @Test
    void handlesEmptyTurnsGracefully() {
        List<Segment> segments = List.of(segment(0, 5, "speech"));
        List<AttributedSegment> attributed = SpeakerAttributor.attribute(segments, List.of());

        assertEquals(1, attributed.size());
        assertNull(attributed.get(0).speakerLabel());
    }
}
