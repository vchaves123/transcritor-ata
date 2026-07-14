package com.tailor.transcritorata.diarization.onnx;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SpeakerClustererTest {

    @Test
    void assignsShortSegmentsToNearestLongSegmentCluster() {
        // 2 locutores bem separados, cada um com 2 segmentos longos (>= min_duration) e o
        // segundo locutor tem tambem um segmento curto que deve ser atribuido a ele.
        List<float[]> embeddings = List.of(
                new float[] { 0f, 0f },   // locutor A, longo
                new float[] { 0.05f, 0f }, // locutor A, longo
                new float[] { 10f, 10f },  // locutor B, longo
                new float[] { 10.05f, 10f }, // locutor B, longo
                new float[] { 10.1f, 9.9f }  // locutor B, curto -> deve ficar com o mesmo label dos longos de B
        );
        List<TimeSpan> segments = List.of(
                new TimeSpan(0, 10),
                new TimeSpan(10, 20),
                new TimeSpan(20, 30),
                new TimeSpan(30, 40),
                new TimeSpan(40, 40.5)); // curto: 0.5s < min_duration

        int[] labels = SpeakerClusterer.cluster(embeddings, segments);

        assertEquals(labels[0], labels[1]);
        assertEquals(labels[2], labels[3]);
        assertEquals(labels[4], labels[2], "segmento curto deveria ficar com o locutor B (mais proximo)");
        assertNotEquals(labels[0], labels[2]);
    }

    @Test
    void fallsBackToSingleSpeakerWhenFewerThanTwoLongSegments() {
        List<float[]> embeddings = List.of(new float[] { 0f, 0f }, new float[] { 10f, 10f });
        // ambos curtos (duracao 0.5s cada, total 1s -> min_duration=clip(1/60,2,5)=2, nenhum >= 2)
        List<TimeSpan> segments = List.of(new TimeSpan(0, 0.5), new TimeSpan(0.5, 1.0));

        int[] labels = SpeakerClusterer.cluster(embeddings, segments);

        assertEquals(0, labels[0]);
        assertEquals(0, labels[1]);
    }

    @Test
    void handlesEmptyAndSingleEmbeddingLists() {
        assertEquals(0, SpeakerClusterer.cluster(List.of(), List.of()).length);

        int[] single = SpeakerClusterer.cluster(
                List.of(new float[] { 1f, 2f }), List.of(new TimeSpan(0, 5)));
        assertEquals(1, single.length);
        assertEquals(0, single[0]);
    }
}
