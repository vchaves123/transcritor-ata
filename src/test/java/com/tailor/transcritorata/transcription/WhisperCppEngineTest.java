package com.tailor.transcritorata.transcription;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tailor.transcritorata.model.Segment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhisperCppEngineTest {

    @Test
    void parsesWhisperCppJsonOutputIntoSegments() throws Exception {
        Path fixture = resourcePath("whisper-sample.json");
        WhisperCppEngine engine = new WhisperCppEngine("whisper-cli.exe", Path.of("model.bin"), "pt", 60, false);

        List<Segment> segments = engine.parseJson(fixture);

        assertEquals(2, segments.size());

        Segment first = segments.get(0);
        assertEquals(Duration.ofMillis(0), first.start());
        assertEquals(Duration.ofMillis(2500), first.end());
        assertEquals("Bom dia a todos, vamos começar a reunião.", first.text());

        Segment second = segments.get(1);
        assertEquals(Duration.ofMillis(2500), second.start());
        assertEquals(Duration.ofMillis(7120), second.end());
        assertEquals("Hoje vamos discutir o cronograma do projeto.", second.text());
    }

    @Test
    void nonFastModeDoesNotAddBeamSizeFlags() {
        WhisperCppEngine engine = new WhisperCppEngine("whisper-cli.exe", Path.of("model.bin"), "pt", 60, false);

        List<String> command = engine.buildCommand(Path.of("audio.wav"), Path.of("saida"), 4);

        assertFalse(command.contains("-bs"), "without fastMode it should not force beam-size");
        assertFalse(command.contains("-bo"), "without fastMode it should not force best-of");
    }

    @Test
    void fastModeAddsGreedyDecodingFlags() {
        WhisperCppEngine engine = new WhisperCppEngine("whisper-cli.exe", Path.of("model.bin"), "pt", 60, true);

        List<String> command = engine.buildCommand(Path.of("audio.wav"), Path.of("saida"), 4);

        assertTrue(command.contains("-bs"));
        assertTrue(command.contains("-bo"));
        int bsIndex = command.indexOf("-bs");
        int boIndex = command.indexOf("-bo");
        assertEquals("1", command.get(bsIndex + 1));
        assertEquals("1", command.get(boIndex + 1));
    }

    @Test
    void explicitProgressMarkerTakesPrecedence() {
        WhisperCppEngine engine = new WhisperCppEngine("whisper-cli.exe", Path.of("model.bin"), "pt", 60, false);
        List<int[]> reported = new ArrayList<>();

        engine.reportProgress("whisper_print_progress_callback: progress = 42%",
                (message, percent) -> reported.add(new int[] { percent }), 100_000);

        assertEquals(1, reported.size());
        assertEquals(42, reported.get(0)[0]);
    }

    @Test
    void segmentEndTimestampEstimatesProgressWhenNoExplicitMarker() {
        WhisperCppEngine engine = new WhisperCppEngine("whisper-cli.exe", Path.of("model.bin"), "pt", 60, false);
        List<int[]> reported = new ArrayList<>();
        long totalDurationMillis = 10_000; // 10 s de áudio total

        // Segmento termina em 5s -> 50% do total.
        engine.reportProgress("[00:00:02.500 --> 00:00:05.000]  Bom dia a todos.",
                (message, percent) -> reported.add(new int[] { percent }), totalDurationMillis);

        assertEquals(1, reported.size());
        assertEquals(50, reported.get(0)[0]);
    }

    @Test
    void segmentTimestampIsLogOnlyWhenTotalDurationUnknown() {
        WhisperCppEngine engine = new WhisperCppEngine("whisper-cli.exe", Path.of("model.bin"), "pt", 60, false);
        List<int[]> reported = new ArrayList<>();

        engine.reportProgress("[00:00:02.500 --> 00:00:05.000]  Bom dia a todos.",
                (message, percent) -> reported.add(new int[] { percent }), -1);

        assertEquals(1, reported.size());
        assertEquals(-1, reported.get(0)[0]);
    }

    private static Path resourcePath(String name) throws URISyntaxException {
        return Paths.get(WhisperCppEngineTest.class.getClassLoader().getResource(name).toURI());
    }
}
