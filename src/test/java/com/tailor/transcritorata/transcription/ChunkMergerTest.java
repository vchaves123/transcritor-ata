package com.tailor.transcritorata.transcription;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tailor.transcritorata.model.Segment;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkMergerTest {

    @Test
    void appliesCumulativeOffsetPerChunk() {
        List<Segment> chunk0 = List.of(
                new Segment(Duration.ofSeconds(0), Duration.ofSeconds(5), "primeiro"));
        List<Segment> chunk1 = List.of(
                new Segment(Duration.ofSeconds(0), Duration.ofSeconds(3), "segundo"));
        List<Segment> chunk2 = List.of(
                new Segment(Duration.ofSeconds(2), Duration.ofSeconds(4), "terceiro"));

        List<Segment> merged = ChunkMerger.merge(List.of(chunk0, chunk1, chunk2), Duration.ofMinutes(20));

        assertEquals(3, merged.size());
        assertEquals(Duration.ofSeconds(0), merged.get(0).start());
        assertEquals(Duration.ofMinutes(20), merged.get(1).start());
        assertEquals(Duration.ofMinutes(40).plusSeconds(2), merged.get(2).start());
        assertEquals("terceiro", merged.get(2).text());
    }

    @Test
    void returnsEmptyListWhenNoChunks() {
        assertEquals(List.of(), ChunkMerger.merge(List.of(), Duration.ofMinutes(20)));
    }
}
