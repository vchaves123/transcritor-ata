package com.tailor.transcritorata.diarization.onnx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgglomerativeClusteringTest {

    @Test
    void resistsChainingAcrossALineOfEvenlySpacedPoints() {
        // 10 points 1 apart on a line: every adjacent pair is exactly at distance 1, so
        // single-linkage would chain-merge all of them into one cluster at any threshold >= 1
        // (it only ever needs the nearest boundary pair, which never exceeds 1 here). Average
        // linkage requires the *whole* cluster-to-cluster average to stay under the threshold,
        // so once a few points have grouped up, merging the two halves end-to-end would need an
        // average far above 1.5 -- it must stop short of one giant cluster.
        float[][] points = new float[10][];
        for (int i = 0; i < 10; i++) {
            points[i] = new float[] { i, 0f };
        }

        int[] labels = AgglomerativeClustering.fitAverageLinkage(points, 1.5);

        long distinctLabels = java.util.Arrays.stream(labels).distinct().count();
        assertTrue(distinctLabels > 1, "expected more than one cluster, got " + distinctLabels);
    }

    @Test
    void groupsTwoWellSeparatedClustersCorrectly() {
        float[][] points = {
                { 0f, 0f }, { 0.1f, 0.1f }, { -0.1f, 0.05f }, // cluster A, near origin
                { 10f, 10f }, { 10.1f, 9.9f }, { 9.9f, 10.05f } // cluster B, far away
        };

        int[] labels = AgglomerativeClustering.fitAverageLinkage(points, 1.0);

        assertEquals(labels[0], labels[1]);
        assertEquals(labels[0], labels[2]);
        assertEquals(labels[3], labels[4]);
        assertEquals(labels[3], labels[5]);
        assertNotEquals(labels[0], labels[3]);
    }

    @Test
    void mergesEverythingIntoOneClusterWhenThresholdIsVeryLarge() {
        float[][] points = { { 0f, 0f }, { 100f, 100f }, { -100f, 50f } };
        int[] labels = AgglomerativeClustering.fitAverageLinkage(points, 10_000.0);

        assertEquals(labels[0], labels[1]);
        assertEquals(labels[0], labels[2]);
    }

    @Test
    void keepsEveryPointSeparateWhenThresholdIsZero() {
        float[][] points = { { 0f, 0f }, { 1f, 1f }, { 2f, 2f } };
        int[] labels = AgglomerativeClustering.fitAverageLinkage(points, 0.0);

        assertNotEquals(labels[0], labels[1]);
        assertNotEquals(labels[1], labels[2]);
        assertNotEquals(labels[0], labels[2]);
    }

    @Test
    void handlesSinglePoint() {
        float[][] points = { { 1f, 2f, 3f } };
        assertEquals(1, AgglomerativeClustering.fitAverageLinkage(points, 5.0).length);
    }
}
