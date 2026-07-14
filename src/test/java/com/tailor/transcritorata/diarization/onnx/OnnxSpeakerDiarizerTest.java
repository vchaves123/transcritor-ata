package com.tailor.transcritorata.diarization.onnx;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.diarization.SpeakerTurn;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exercises the real ONNX models end-to-end (segmentation + embedding sessions actually run,
 * not just the surrounding DSP/clustering logic tested elsewhere) using a short synthetic tone
 * as input. This is not a diarization-quality test — synthetic audio carries none of the
 * spectral structure of real speech, so no assertion is made about the resulting speaker labels
 * — its purpose is to catch tensor shape mismatches or model-loading failures early.
 */
class OnnxSpeakerDiarizerTest {

    @Test
    void runsFullPipelineOnShortSyntheticAudioWithoutThrowing(@TempDir Path tempDir) throws IOException {
        Path wav = tempDir.resolve("synthetic.wav");
        writeToneWav(wav, 20); // 20s: long enough to exceed the model's 10s window at least once

        List<SpeakerTurn> turns = assertDoesNotThrow(() ->
                new OnnxSpeakerDiarizer().diarize(wav, new ProcessRunner.Handle(), null));

        assertNotNull(turns);
    }

    private static void writeToneWav(Path path, int seconds) throws IOException {
        int sampleRate = 16_000;
        int numSamples = sampleRate * seconds;
        byte[] pcm = new byte[numSamples * 2];
        for (int i = 0; i < numSamples; i++) {
            // Mistura duas frequencias para nao ser um tom puro degenerado.
            double t = i / (double) sampleRate;
            double value = 0.2 * Math.sin(2 * Math.PI * 220 * t) + 0.1 * Math.sin(2 * Math.PI * 90 * t);
            short sample = (short) Math.round(value * Short.MAX_VALUE);
            pcm[i * 2] = (byte) (sample & 0xFF);
            pcm[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        try (AudioInputStream in = new AudioInputStream(
                new java.io.ByteArrayInputStream(pcm), format, numSamples)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }
}
