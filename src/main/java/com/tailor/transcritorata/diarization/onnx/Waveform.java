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

    // Bounds how much audio speaker identification (an optional, in-process feature) will ever
    // load fully into memory: at 16kHz mono PCM16 this is ~115 MB/hour, so 6 hours (~690 MB of raw
    // samples, before the segmentation/embedding models' own working buffers) comfortably covers
    // this app's stated use case (meeting recordings) while still bounding a pathologically long
    // or corrupted input from driving memory usage without limit.
    private static final long MAX_FRAMES = 16_000L * 60 * 60 * 6;

    private Waveform() {
    }

    public static float[] readMono16k(Path wav) throws IOException {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            AudioFormat format = in.getFormat();
            if (format.getChannels() != 1 || Math.round(format.getSampleRate()) != 16000
                    || format.getSampleSizeInBits() != 16) {
                throw new IOException("Esperado WAV 16kHz mono PCM16, encontrado: " + format);
            }
            long frameLength = in.getFrameLength();
            if (frameLength != javax.sound.sampled.AudioSystem.NOT_SPECIFIED && frameLength > MAX_FRAMES) {
                throw new IOException("Recording too long for speaker identification (" + frameLength
                        + " frames, limit " + MAX_FRAMES + "); skipping this optional feature.");
            }
            byte[] bytes = in.readAllBytes();
            if (bytes.length / 2L > MAX_FRAMES) {
                throw new IOException("Recording too long for speaker identification; skipping this optional "
                        + "feature.");
            }
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
            throw new IOException("Unsupported audio format: " + e.getMessage(), e);
        }
    }
}
