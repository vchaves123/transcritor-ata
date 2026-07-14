package com.tailor.transcritorata.diarization.onnx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AgglomerativeClusteringTest {

    @Test
    void groupsTwoWellSeparatedClustersCorrectly() {
        float[][] points = {
                { 0f, 0f }, { 0.1f, 0.1f }, { -0.1f, 0.05f }, // cluster A, near origin
                { 10f, 10f }, { 10.1f, 9.9f }, { 9.9f, 10.05f } // cluster B, far away
        };

        int[] labels = AgglomerativeClustering.fitSingleLinkage(points, 1.0);

        assertEquals(labels[0], labels[1]);
        assertEquals(labels[0], labels[2]);
        assertEquals(labels[3], labels[4]);
        assertEquals(labels[3], labels[5]);
        assertNotEquals(labels[0], labels[3]);
    }

    @Test
    void mergesEverythingIntoOneClusterWhenThresholdIsVeryLarge() {
        float[][] points = { { 0f, 0f }, { 100f, 100f }, { -100f, 50f } };
        int[] labels = AgglomerativeClustering.fitSingleLinkage(points, 10_000.0);

        assertEquals(labels[0], labels[1]);
        assertEquals(labels[0], labels[2]);
    }

    @Test
    void keepsEveryPointSeparateWhenThresholdIsZero() {
        float[][] points = { { 0f, 0f }, { 1f, 1f }, { 2f, 2f } };
        int[] labels = AgglomerativeClustering.fitSingleLinkage(points, 0.0);

        assertNotEquals(labels[0], labels[1]);
        assertNotEquals(labels[1], labels[2]);
        assertNotEquals(labels[0], labels[2]);
    }

    @Test
    void handlesSinglePoint() {
        float[][] points = { { 1f, 2f, 3f } };
        assertEquals(1, AgglomerativeClustering.fitSingleLinkage(points, 5.0).length);
    }
}
