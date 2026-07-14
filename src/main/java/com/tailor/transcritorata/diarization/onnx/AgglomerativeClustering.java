package com.tailor.transcritorata.diarization.onnx;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-linkage agglomerative clustering with a distance threshold (Euclidean), matching
 * {@code sklearn.cluster.AgglomerativeClustering(n_clusters=None, distance_threshold=..., metric='euclidean', linkage='single')}
 * for the case (used by our pipeline) where the number of speakers is not known in advance.
 */
final class AgglomerativeClustering {

    private AgglomerativeClustering() {
    }

    /** @return a 0-indexed cluster label per input point. */
    static int[] fitSingleLinkage(float[][] points, double distanceThreshold) {
        int n = points.length;
        if (n == 0) {
            return new int[0];
        }
        if (n == 1) {
            return new int[] { 0 };
        }

        // clusterOf[i] = which cluster (by id) point i currently belongs to.
        // members[id] = list of point indices in that cluster.
        int[] clusterOf = new int[n];
        List<List<Integer>> members = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            clusterOf[i] = i;
            List<Integer> single = new ArrayList<>();
            single.add(i);
            members.add(single);
        }

        double[][] pointDist = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = euclidean(points[i], points[j]);
                pointDist[i][j] = d;
                pointDist[j][i] = d;
            }
        }

        boolean[] active = new boolean[n]; // active[id] = true if cluster id still exists
        java.util.Arrays.fill(active, true);

        while (true) {
            int bestA = -1;
            int bestB = -1;
            double bestDistance = Double.MAX_VALUE;

            List<Integer> activeIds = new ArrayList<>();
            for (int id = 0; id < n; id++) {
                if (active[id]) {
                    activeIds.add(id);
                }
            }

            for (int a = 0; a < activeIds.size(); a++) {
                for (int b = a + 1; b < activeIds.size(); b++) {
                    int idA = activeIds.get(a);
                    int idB = activeIds.get(b);
                    double linkageDistance = singleLinkageDistance(members.get(idA), members.get(idB), pointDist);
                    if (linkageDistance < bestDistance) {
                        bestDistance = linkageDistance;
                        bestA = idA;
                        bestB = idB;
                    }
                }
            }

            if (bestA < 0 || bestDistance > distanceThreshold) {
                break;
            }

            members.get(bestA).addAll(members.get(bestB));
            for (int point : members.get(bestB)) {
                clusterOf[point] = bestA;
            }
            members.get(bestB).clear();
            active[bestB] = false;
        }

        return relabelContiguously(clusterOf);
    }

    private static double singleLinkageDistance(List<Integer> clusterA, List<Integer> clusterB, double[][] pointDist) {
        double min = Double.MAX_VALUE;
        for (int i : clusterA) {
            for (int j : clusterB) {
                min = Math.min(min, pointDist[i][j]);
            }
        }
        return min;
    }

    private static double euclidean(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    private static int[] relabelContiguously(int[] clusterOf) {
        java.util.Map<Integer, Integer> remap = new java.util.LinkedHashMap<>();
        int[] result = new int[clusterOf.length];
        for (int i = 0; i < clusterOf.length; i++) {
            int newLabel = remap.computeIfAbsent(clusterOf[i], k -> remap.size());
            result[i] = newLabel;
        }
        return result;
    }
}
