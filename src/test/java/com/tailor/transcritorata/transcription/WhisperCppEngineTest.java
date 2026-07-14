package com.tailor.transcritorata.transcription;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
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
        WhisperCppEngine engine = new WhisperCppEngine("whisper-cli.exe", Path.of("model.bin"), "pt", 60);

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
    void defaultConstructorDoesNotAddBeamSizeFlags() {
        WhisperCppEngine engine = new WhisperCppEngine("whisper-cli.exe", Path.of("model.bin"), "pt", 60);

        List<String> command = engine.buildCommand(Path.of("audio.wav"), Path.of("saida"), 4);

        assertFalse(command.contains("-bs"), "sem fastMode nao deveria forcar beam-size");
        assertFalse(command.contains("-bo"), "sem fastMode nao deveria forcar best-of");
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
    void explicitFastModeFalseMatchesDefaultConstructor() {
        WhisperCppEngine engine = new WhisperCppEngine("whisper-cli.exe", Path.of("model.bin"), "pt", 60, false);

        List<String> command = engine.buildCommand(Path.of("audio.wav"), Path.of("saida"), 4);

        assertFalse(command.contains("-bs"));
        assertFalse(command.contains("-bo"));
    }

    private static Path resourcePath(String name) throws URISyntaxException {
        return Paths.get(WhisperCppEngineTest.class.getClassLoader().getResource(name).toURI());
    }
}
