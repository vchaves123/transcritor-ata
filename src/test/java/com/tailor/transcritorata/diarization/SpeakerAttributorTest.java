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
                segment(0, 5, "primeira fala"),
                segment(6, 10, "segunda fala"),
                segment(11, 15, "terceira fala"));

        List<SpeakerTurn> turns = List.of(
                turn(0, 5, "S0"),
                turn(5, 10, "S1"),
                turn(10, 15, "S0"));

        List<AttributedSegment> attributed = SpeakerAttributor.attribute(segments, turns);

        // S0 aparece primeiro -> "Pessoa 1"; S1 depois -> "Pessoa 2"; S0 reaparece -> continua "Pessoa 1"
        assertEquals("Pessoa 1", attributed.get(0).speakerLabel());
        assertEquals("Pessoa 2", attributed.get(1).speakerLabel());
        assertEquals("Pessoa 1", attributed.get(2).speakerLabel());
    }

    @Test
    void choosesSpeakerWithMoreOverlapWhenSegmentSpansTwoTurns() {
        // segmento 0..10; S0 cobre 0..3, S1 cobre 3..10 -> S1 tem mais sobreposição
        List<Segment> segments = List.of(segment(0, 10, "fala sobreposta"));
        List<SpeakerTurn> turns = List.of(turn(0, 3, "S0"), turn(3, 10, "S1"));

        List<AttributedSegment> attributed = SpeakerAttributor.attribute(segments, turns);

        assertEquals("Pessoa 1", attributed.get(0).speakerLabel());
    }

    @Test
    void leavesSpeakerNullWhenNoTurnOverlaps() {
        List<Segment> segments = List.of(segment(20, 25, "fala isolada"));
        List<SpeakerTurn> turns = List.of(turn(0, 5, "S0"));

        List<AttributedSegment> attributed = SpeakerAttributor.attribute(segments, turns);

        assertNull(attributed.get(0).speakerLabel());
        assertFalse(attributed.get(0).hasSpeaker());
    }

    @Test
    void handlesEmptyTurnsGracefully() {
        List<Segment> segments = List.of(segment(0, 5, "fala"));
        List<AttributedSegment> attributed = SpeakerAttributor.attribute(segments, List.of());

        assertEquals(1, attributed.size());
        assertNull(attributed.get(0).speakerLabel());
    }
}
