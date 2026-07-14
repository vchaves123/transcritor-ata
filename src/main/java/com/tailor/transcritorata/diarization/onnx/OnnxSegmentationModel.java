package com.tailor.transcritorata.diarization.onnx;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Ports {@code run_segmentation} from the reference ONNX pyannote pipeline: slides a 10-second
 * window (50% overlap) over the waveform through the pyannote segmentation-3.0 model, reconciles
 * the per-window local speaker-slot ordering across overlapping windows (the model's "powerset"
 * output is only locally consistent — see {@link #reorder}), and binarizes the resulting speech
 * probability curves into time spans via hysteresis thresholding.
 *
 * <p>The returned {@link TimeSpan}s are <b>not</b> yet attributed to a persistent speaker
 * identity — that only happens later, via embeddings + clustering.
 */
public final class OnnxSegmentationModel implements AutoCloseable {

    private static final int SAMPLE_RATE = 16_000;
    private static final double DURATION_SECONDS = 10.0;
    private static final double STEP_SECONDS = DURATION_SECONDS * 0.5;
    private static final double ONSET = 0.5;
    private static final double OFFSET = 0.5;
    private static final double MIN_DURATION_ON = 0.5;
    private static final double MIN_DURATION_OFF = 0.3;
    private static final int NUM_LOCAL_SPEAKERS = 3;

    /** All 6 permutations of {0,1,2}, used to realign local speaker-slot ordering between windows. */
    private static final int[][] PERMUTATIONS_OF_THREE = {
            {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
    };

    private final OrtEnvironment env;
    private final OrtSession session;

    public OnnxSegmentationModel(OrtEnvironment env, byte[] modelBytes) throws OrtException {
        this.env = env;
        this.session = env.createSession(modelBytes);
    }

    public List<TimeSpan> run(float[] waveform) throws OrtException {
        int windowSamples = (int) (DURATION_SECONDS * SAMPLE_RATE);
        int stepSamples = (int) (STEP_SECONDS * SAMPLE_RATE);

        float[] padded = padToMultipleOf(waveform, windowSamples);

        int numFramesPerWindow = sampleToFrame(windowSamples);
        double secondsPerFrame = DURATION_SECONDS / numFramesPerWindow;
        int overlapFrames = sampleToFrame(windowSamples - stepSamples);

        double totalDurationSeconds = padded.length / (double) SAMPLE_RATE;
        int totalFrames = (int) (totalDurationSeconds / secondsPerFrame) + 100; // buffer, mirrors reference
        double[][] globalScores = new double[totalFrames][NUM_LOCAL_SPEAKERS];

        for (int startSample = 0; startSample <= padded.length - windowSamples; startSample += stepSamples) {
            float[] chunk = new float[windowSamples];
            System.arraycopy(padded, startSample, chunk, 0, windowSamples);

            double[][] speechProb = runWindow(chunk);

            int startFrameGlobal = (int) ((startSample / (double) SAMPLE_RATE) / secondsPerFrame);
            int endFrameGlobal = startFrameGlobal + speechProb.length;

            if (startSample > 0) {
                double[][] overlapSlice = copyRows(globalScores, startFrameGlobal,
                        Math.min(overlapFrames, speechProb.length));
                speechProb = reorder(overlapSlice, speechProb);

                int overlapEnd = startFrameGlobal + overlapFrames;
                for (int f = startFrameGlobal; f < Math.min(overlapEnd, endFrameGlobal); f++) {
                    int localIndex = f - startFrameGlobal;
                    for (int s = 0; s < NUM_LOCAL_SPEAKERS; s++) {
                        globalScores[f][s] = (overlapSlice[localIndex][s] + speechProb[localIndex][s]) / 2.0;
                    }
                }
                for (int f = overlapEnd; f < endFrameGlobal; f++) {
                    int localIndex = f - startFrameGlobal;
                    globalScores[f] = speechProb[localIndex];
                }
            } else {
                for (int f = startFrameGlobal; f < endFrameGlobal; f++) {
                    globalScores[f] = speechProb[f - startFrameGlobal];
                }
            }
        }

        return binarizeWithHysteresis(globalScores, secondsPerFrame, totalDurationSeconds);
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }

    /** Runs the model on a single 10s window and returns the per-frame speech probability for each of the 3 local speaker slots. */
    private double[][] runWindow(float[] chunk) throws OrtException {
        long[] shape = { 1, 1, chunk.length };
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), shape);
                OrtSession.Result result = session.run(Map.of("input_values", input))) {
            float[][][] output = (float[][][]) result.get(0).getValue();
            float[][] frames = output[0]; // (numFrames, 7): powerset classes over up to 3 simultaneous speakers

            double[][] speechProb = new double[frames.length][NUM_LOCAL_SPEAKERS];
            for (int f = 0; f < frames.length; f++) {
                double[] p = new double[7];
                for (int c = 0; c < 7; c++) {
                    p[c] = Math.exp(frames[f][c]);
                }
                // classes: 0=silence, 1=A, 2=B, 3=C, 4=A+B, 5=A+C, 6=B+C (local, arbitrary ordering)
                speechProb[f][0] = p[1] + p[4] + p[5];
                speechProb[f][1] = p[2] + p[4] + p[6];
                speechProb[f][2] = p[3] + p[5] + p[6];
            }
            return speechProb;
        }
    }

    /**
     * Finds which of the 6 permutations of the current window's 3 local speaker columns best
     * matches the already-committed scores in the overlapping region with the previous window,
     * and returns the current window reordered accordingly.
     */
    private static double[][] reorder(double[][] overlapPrevious, double[][] currentWindow) {
        int overlapLen = overlapPrevious.length;
        int[] best = PERMUTATIONS_OF_THREE[0];
        double bestDiff = Double.MAX_VALUE;

        for (int[] perm : PERMUTATIONS_OF_THREE) {
            double diff = 0;
            double[] columnSum = new double[NUM_LOCAL_SPEAKERS];
            for (int f = 0; f < overlapLen; f++) {
                for (int s = 0; s < NUM_LOCAL_SPEAKERS; s++) {
                    columnSum[s] += currentWindow[f][perm[s]] - overlapPrevious[f][s];
                }
            }
            for (int s = 0; s < NUM_LOCAL_SPEAKERS; s++) {
                diff += Math.abs(columnSum[s]);
            }
            if (diff < bestDiff) {
                bestDiff = diff;
                best = perm;
            }
        }

        double[][] reordered = new double[currentWindow.length][NUM_LOCAL_SPEAKERS];
        for (int f = 0; f < currentWindow.length; f++) {
            for (int s = 0; s < NUM_LOCAL_SPEAKERS; s++) {
                reordered[f][s] = currentWindow[f][best[s]];
            }
        }
        return reordered;
    }

    private static List<TimeSpan> binarizeWithHysteresis(double[][] globalScores, double secondsPerFrame,
            double totalDurationSeconds) {
        boolean[] isActive = new boolean[NUM_LOCAL_SPEAKERS];
        double[] startTs = new double[NUM_LOCAL_SPEAKERS];
        List<List<TimeSpan>> perSlot = new ArrayList<>();
        for (int s = 0; s < NUM_LOCAL_SPEAKERS; s++) {
            perSlot.add(new ArrayList<>());
        }

        for (int f = 0; f < globalScores.length; f++) {
            double t = f * secondsPerFrame;
            if (t > totalDurationSeconds) {
                break;
            }
            for (int s = 0; s < NUM_LOCAL_SPEAKERS; s++) {
                double score = globalScores[f][s];
                if (!isActive[s]) {
                    if (score > ONSET) {
                        isActive[s] = true;
                        startTs[s] = t;
                    }
                } else if (score < OFFSET) {
                    isActive[s] = false;
                    double end = t;
                    if (end - startTs[s] >= MIN_DURATION_ON) {
                        perSlot.get(s).add(new TimeSpan(startTs[s], end));
                    }
                }
            }
        }
        for (int s = 0; s < NUM_LOCAL_SPEAKERS; s++) {
            if (isActive[s] && totalDurationSeconds - startTs[s] >= MIN_DURATION_ON) {
                perSlot.get(s).add(new TimeSpan(startTs[s], totalDurationSeconds));
            }
        }

        List<TimeSpan> merged = new ArrayList<>();
        for (List<TimeSpan> spans : perSlot) {
            merged.addAll(mergeWithCollar(spans, MIN_DURATION_OFF));
        }
        return merged;
    }

    /** Merges consecutive spans of the same local slot separated by a gap no larger than {@code collar} seconds. */
    private static List<TimeSpan> mergeWithCollar(List<TimeSpan> spans, double collar) {
        List<TimeSpan> result = new ArrayList<>();
        if (spans.isEmpty()) {
            return result;
        }
        TimeSpan current = spans.get(0);
        for (int i = 1; i < spans.size(); i++) {
            TimeSpan next = spans.get(i);
            if (next.startSeconds() - current.endSeconds() <= collar) {
                current = new TimeSpan(current.startSeconds(), Math.max(current.endSeconds(), next.endSeconds()));
            } else {
                result.add(current);
                current = next;
            }
        }
        result.add(current);
        return result;
    }

    private static float[] padToMultipleOf(float[] waveform, int multiple) {
        int remainder = waveform.length % multiple;
        if (remainder == 0) {
            return waveform;
        }
        int padWidth = multiple - remainder;
        float[] padded = new float[waveform.length + padWidth];
        System.arraycopy(waveform, 0, padded, 0, waveform.length);
        return padded;
    }

    private static double[][] copyRows(double[][] source, int fromRow, int count) {
        double[][] copy = new double[count][];
        for (int i = 0; i < count; i++) {
            copy[i] = source[fromRow + i].clone();
        }
        return copy;
    }

    static int sampleToFrame(int sample) {
        return Math.floorDiv(sample - 721, 270);
    }
}
