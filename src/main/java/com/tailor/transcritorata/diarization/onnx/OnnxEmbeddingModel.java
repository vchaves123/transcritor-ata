package com.tailor.transcritorata.diarization.onnx;

import java.nio.FloatBuffer;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Extracts a fixed-size, L2-normalized speaker embedding from log-mel features via the
 * wespeaker-voxceleb-resnet34-LM ONNX model, matching {@code extract_embeddings} in the
 * reference pipeline.
 */
public final class OnnxEmbeddingModel implements AutoCloseable {

    private final OrtEnvironment env;
    private final OrtSession session;
    private final MelSpectrogramExtractor melExtractor = new MelSpectrogramExtractor();

    public OnnxEmbeddingModel(OrtEnvironment env, byte[] modelBytes) throws OrtException {
        this.env = env;
        this.session = env.createSession(modelBytes);
    }

    /** @return the L2-normalized embedding, or {@code null} if the segment is too short to produce features. */
    public float[] embed(float[] segmentSamples) throws OrtException {
        float[][] features = melExtractor.extract(segmentSamples);
        if (features.length == 0) {
            return null;
        }

        int numFrames = features.length;
        int numMels = features[0].length;
        float[] flat = new float[numFrames * numMels];
        for (int f = 0; f < numFrames; f++) {
            System.arraycopy(features[f], 0, flat, f * numMels, numMels);
        }

        long[] shape = { 1, numFrames, numMels };
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape);
                OrtSession.Result result = session.run(Map.of("input_features", input))) {
            float[][] output = (float[][]) result.get(0).getValue();
            float[] embedding = output[0];
            return l2Normalize(embedding);
        }
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }

    private static float[] l2Normalize(float[] vector) {
        double sumSquares = 0;
        for (float v : vector) {
            sumSquares += (double) v * v;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm < 1e-6) {
            return vector;
        }
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }
}
