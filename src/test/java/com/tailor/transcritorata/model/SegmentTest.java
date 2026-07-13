package com.tailor.transcritorata.model;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SegmentTest {

    @Test
    void formatsDurationAsHhMmSs() {
        assertEquals("00:00:00", Segment.format(Duration.ZERO));
        assertEquals("00:01:05", Segment.format(Duration.ofSeconds(65)));
        assertEquals("01:02:03", Segment.format(Duration.ofSeconds(3723)));
    }

    @Test
    void appliesOffsetToStartAndEnd() {
        Segment segment = new Segment(Duration.ofSeconds(10), Duration.ofSeconds(20), "ola");
        Segment shifted = segment.withOffset(Duration.ofMinutes(5));

        assertEquals(Duration.ofSeconds(310), shifted.start());
        assertEquals(Duration.ofSeconds(320), shifted.end());
        assertEquals("ola", shifted.text());
    }

    @Test
    void trimsTextOnConstruction() {
        Segment segment = new Segment(Duration.ZERO, Duration.ofSeconds(1), "  texto  ");
        assertEquals("texto", segment.text());
    }
}
