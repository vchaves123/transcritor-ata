package com.tailor.transcritorata.diarization;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SegFileParserTest {

    @Test
    void parsesSegFileConvertingFramesToDurationsAndSortingByStart() throws Exception {
        Path fixture = resourcePath("diarization-sample.seg");

        List<SpeakerTurn> turns = SegFileParser.parse(fixture);

        // 4 linhas de dados (comentários ;; ignorados), ordenadas por início
        assertEquals(4, turns.size());

        // primeiro turno: startFrame=0, durationFrames=500 -> 0ms a 5000ms, locutor S0
        assertEquals(Duration.ZERO, turns.get(0).start());
        assertEquals(Duration.ofMillis(5000), turns.get(0).end());
        assertEquals("S0", turns.get(0).speakerLabel());

        // segundo turno (por início): startFrame=500 -> 5000ms, locutor S1
        assertEquals(Duration.ofMillis(5000), turns.get(1).start());
        assertEquals(Duration.ofMillis(7000), turns.get(1).end());
        assertEquals("S1", turns.get(1).speakerLabel());

        // último turno: startFrame=1000 -> 10000ms, duração 450 frames -> 14500ms
        assertEquals(Duration.ofMillis(10000), turns.get(3).start());
        assertEquals(Duration.ofMillis(14500), turns.get(3).end());
        assertEquals("S1", turns.get(3).speakerLabel());
    }

    @Test
    void ignoresCommentsAndMalformedLines() {
        List<SpeakerTurn> turns = SegFileParser.parseLines(List.of(
                ";; comentário",
                "",
                "reuniao 1 0 100 M S U S0",
                "linha incompleta",
                "reuniao 1 abc def M S U S0"));

        assertEquals(1, turns.size());
        assertEquals("S0", turns.get(0).speakerLabel());
    }

    private static Path resourcePath(String name) throws URISyntaxException {
        return Paths.get(SegFileParserTest.class.getClassLoader().getResource(name).toURI());
    }
}
