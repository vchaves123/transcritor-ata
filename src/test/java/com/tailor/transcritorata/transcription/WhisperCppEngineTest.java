package com.tailor.transcritorata.transcription;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tailor.transcritorata.model.Segment;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static Path resourcePath(String name) throws URISyntaxException {
        return Paths.get(WhisperCppEngineTest.class.getClassLoader().getResource(name).toURI());
    }
}
