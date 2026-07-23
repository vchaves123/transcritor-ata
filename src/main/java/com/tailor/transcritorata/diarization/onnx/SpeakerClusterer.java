package com.tailor.transcritorata.diarization.onnx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Two-stage clustering of segment embeddings into speaker labels, matching
 * {@code cluster_embeddings} in the reference pipeline: long (reliable) segments are clustered
 * first to establish the speakers present, then short segments are assigned to whichever
 * resulting speaker centroid is nearest — short segments rarely carry enough signal for
 * clustering to be reliable on their own.
 */
final class SpeakerClusterer {

    private static final Logger LOG = LoggerFactory.getLogger(SpeakerClusterer.class);

    // AgglomerativeClustering.fitSingleLinkage is O(n^3) in the number of "long" segments; a very
    // long recording with frequent turn-taking (or an adversarially crafted one) could otherwise
    // drive n into the thousands and hang the app for an unbounded amount of time with no
    // feedback. Above this cap, speaker identification degrades to the same single-speaker
    // fallback already used when there aren't enough long segments to cluster at all.
    private static final int MAX_SEGMENTS_TO_CLUSTER = 500;

    private SpeakerClusterer() {
    }

    /** @return a 0-indexed speaker label per embedding, or all zeros if there aren't enough long segments to cluster. */
    static int[] cluster(List<float[]> embeddings, List<TimeSpan> segments) {
        int n = embeddings.size();
        if (n == 0) {
            return new int[0];
        }
        if (n == 1) {
            return new int[] { 0 };
        }

        double totalDuration = 0;
        for (TimeSpan s : segments) {
            totalDuration += s.endSeconds() - s.startSeconds();
        }
        double minDuration = clip(totalDuration / 60.0, 2.0, 5.0);

        List<Integer> longIndices = new ArrayList<>();
        List<Integer> shortIndices = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double duration = segments.get(i).endSeconds() - segments.get(i).startSeconds();
            if (duration >= minDuration) {
                longIndices.add(i);
            } else {
                shortIndices.add(i);
            }
        }

        if (longIndices.size() < 2) {
            return new int[n]; // all zeros: single speaker fallback
        }
        if (longIndices.size() > MAX_SEGMENTS_TO_CLUSTER) {
            LOG.warn("Too many segments ({}) for speaker clustering (limit {}); falling back to a single "
                    + "speaker for this recording.", longIndices.size(), MAX_SEGMENTS_TO_CLUSTER);
            return new int[n]; // all zeros: single speaker fallback
        }

        float[][] longEmbeddings = new float[longIndices.size()][];
        for (int i = 0; i < longIndices.size(); i++) {
            longEmbeddings[i] = embeddings.get(longIndices.get(i));
        }

        double distanceThreshold = Math.max((350.0 - totalDuration) / 350.0, 0.73);
        int[] longLabels = AgglomerativeClustering.fitAverageLinkage(longEmbeddings, distanceThreshold);

        int[] labels = new int[n];
        for (int i = 0; i < longIndices.size(); i++) {
            labels[longIndices.get(i)] = longLabels[i];
        }

        if (!shortIndices.isEmpty()) {
            Map<Integer, List<float[]>> byLabel = new LinkedHashMap<>();
            for (int i = 0; i < longIndices.size(); i++) {
                byLabel.computeIfAbsent(longLabels[i], k -> new ArrayList<>()).add(longEmbeddings[i]);
            }
            List<Integer> centroidLabels = new ArrayList<>(byLabel.keySet());
            List<float[]> centroids = new ArrayList<>();
            for (Integer label : centroidLabels) {
                centroids.add(mean(byLabel.get(label)));
            }

            for (int shortIndex : shortIndices) {
                float[] embedding = embeddings.get(shortIndex);
                int nearest = 0;
                double bestDistance = Double.MAX_VALUE;
                for (int c = 0; c < centroids.size(); c++) {
                    double d = euclidean(embedding, centroids.get(c));
                    if (d < bestDistance) {
                        bestDistance = d;
                        nearest = c;
                    }
                }
                labels[shortIndex] = centroidLabels.get(nearest);
            }
        }

        return labels;
    }

    private static double clip(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float[] mean(List<float[]> vectors) {
        int dim = vectors.get(0).length;
        float[] result = new float[dim];
        for (float[] v : vectors) {
            for (int i = 0; i < dim; i++) {
                result[i] += v[i];
            }
        }
        for (int i = 0; i < dim; i++) {
            result[i] /= vectors.size();
        }
        return result;
    }

    private static double euclidean(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }
}
