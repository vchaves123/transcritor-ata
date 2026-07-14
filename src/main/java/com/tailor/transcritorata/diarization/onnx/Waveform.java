package com.tailor.transcritorata.diarization.onnx;

import java.io.IOException;
import java.nio.file.Path;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Reads a 16&nbsp;kHz mono PCM16 WAV file (the format {@code AudioExtractor} always produces)
 * into a normalized {@code float[]} in {@code [-1, 1]}, matching the convention used by
 * librosa/PyTorch audio loaders.
 */
public final class Waveform {

    private Waveform() {
    }

    public static float[] readMono16k(Path wav) throws IOException {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            AudioFormat format = in.getFormat();
            if (format.getChannels() != 1 || Math.round(format.getSampleRate()) != 16000
                    || format.getSampleSizeInBits() != 16) {
                throw new IOException("Esperado WAV 16kHz mono PCM16, encontrado: " + format);
            }
            byte[] bytes = in.readAllBytes();
            boolean bigEndian = format.isBigEndian();
            int sampleCount = bytes.length / 2;
            float[] samples = new float[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                int lo;
                int hi;
                if (bigEndian) {
                    hi = bytes[i * 2];
                    lo = bytes[i * 2 + 1] & 0xFF;
                } else {
                    lo = bytes[i * 2] & 0xFF;
                    hi = bytes[i * 2 + 1];
                }
                short value = (short) ((hi << 8) | lo);
                samples[i] = value / 32768f;
            }
            return samples;
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Formato de áudio não suportado: " + e.getMessage(), e);
        }
    }
}
