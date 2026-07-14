package com.tailor.transcritorata.diarization.onnx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MelSpectrogramExtractorTest {

    @Test
    void extractsExpectedNumberOfFramesAndMelBins() {
        // 1 segundo de audio a 16kHz = 16000 amostras; n_fft=400, hop=160 ->
        // numFrames = 1 + (16000-400)/160 = 1 + 97 = 98
        float[] samples = new float[16000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) Math.sin(2 * Math.PI * 200 * i / 16000.0);
        }

        float[][] features = new MelSpectrogramExtractor().extract(samples);

        assertEquals(98, features.length);
        assertEquals(80, features[0].length);
    }

    @Test
    void returnsEmptyWhenSignalShorterThanOneFftWindow() {
        float[] tooShort = new float[100];
        float[][] features = new MelSpectrogramExtractor().extract(tooShort);
        assertEquals(0, features.length);
    }

    @Test
    void featuresAreMeanCenteredAcrossTime() {
        float[] samples = new float[16000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) Math.sin(2 * Math.PI * 440 * i / 16000.0);
        }

        float[][] features = new MelSpectrogramExtractor().extract(samples);

        for (int m = 0; m < 80; m++) {
            double sum = 0;
            for (float[] frame : features) {
                sum += frame[m];
            }
            double mean = sum / features.length;
            assertTrue(Math.abs(mean) < 1e-3, "media da coluna " + m + " deveria ser ~0, foi " + mean);
        }
    }

    @Test
    void doesNotProduceNaNOrInfiniteValues() {
        float[] samples = new float[32000];
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (random.nextFloat() - 0.5f) * 0.2f;
        }

        float[][] features = new MelSpectrogramExtractor().extract(samples);

        for (float[] frame : features) {
            for (float value : frame) {
                assertTrue(Float.isFinite(value), "valor nao finito encontrado: " + value);
            }
        }
    }
}
