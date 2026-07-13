package com.tailor.transcritorata.ai;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTest {

    @Test
    void returnsSingleChunkWhenUnderLimit() {
        List<String> chunks = TextChunker.split("texto curto", 1000);
        assertEquals(1, chunks.size());
        assertEquals("texto curto", chunks.get(0));
    }

    @Test
    void splitsLongTextIntoMultipleChunksRespectingLimit() {
        String paragraph = "Esta é uma frase de exemplo repetida para simular uma transcrição longa. ";
        String text = paragraph.repeat(200);

        List<String> chunks = TextChunker.split(text, 500);

        assertTrue(chunks.size() > 1);
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 550, "cada bloco deve respeitar aproximadamente o limite configurado");
        }
        assertEquals(text.replaceAll("\\s+", " ").strip(),
                String.join(" ", chunks).replaceAll("\\s+", " ").strip());
    }

    @Test
    void returnsEmptyListForBlankInput() {
        assertEquals(List.of(), TextChunker.split("", 100));
    }
}
