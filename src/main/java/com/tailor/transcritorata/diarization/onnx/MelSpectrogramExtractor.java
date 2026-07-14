package com.tailor.transcritorata.diarization.onnx;

import org.jtransforms.fft.DoubleFFT_1D;

/**
 * Ports {@code librosa.feature.melspectrogram(sr=16000, n_fft=400, hop_length=160, n_mels=80,
 * window='hamming', center=False)} followed by {@code log(melspec + 1e-6)} and per-utterance
 * mean normalization, exactly as used by the reference ONNX pyannote pipeline to prepare input
 * for the wespeaker embedding model.
 *
 * <p>Deliberately mirrors librosa's defaults bin-for-bin (periodic Hamming window, Slaney mel
 * scale and filter normalization) rather than a generic DSP approximation, since the embedding
 * model was exported expecting exactly this feature representation.
 */
public final class MelSpectrogramExtractor {

    private static final int N_FFT = 400;
    private static final int HOP_LENGTH = 160;
    private static final int N_MELS = 80;
    private static final int SAMPLE_RATE = 16_000;
    private static final int NUM_BINS = N_FFT / 2 + 1;
    private static final double LOG_EPSILON = 1e-6;

    private final double[] window;
    private final double[][] melFilterbank;

    public MelSpectrogramExtractor() {
        this.window = periodicHammingWindow(N_FFT);
        this.melFilterbank = buildMelFilterbank(N_MELS, N_FFT, SAMPLE_RATE);
    }

    /** @return log-mel features shaped {@code [numFrames][N_MELS]}, mean-centered over time (matches the Python reference's CMVN step). */
    public float[][] extract(float[] samples) {
        if (samples.length < N_FFT) {
            return new float[0][N_MELS];
        }
        int numFrames = 1 + (samples.length - N_FFT) / HOP_LENGTH;
        double[][] logMel = new double[numFrames][N_MELS];
        DoubleFFT_1D fft = new DoubleFFT_1D(N_FFT);
        double[] spectrumBuffer = new double[2 * N_FFT];

        for (int frame = 0; frame < numFrames; frame++) {
            int start = frame * HOP_LENGTH;
            java.util.Arrays.fill(spectrumBuffer, 0.0);
            for (int i = 0; i < N_FFT; i++) {
                spectrumBuffer[i] = samples[start + i] * window[i];
            }
            fft.realForwardFull(spectrumBuffer);

            double[] power = new double[NUM_BINS];
            for (int k = 0; k < NUM_BINS; k++) {
                double re = spectrumBuffer[2 * k];
                double im = spectrumBuffer[2 * k + 1];
                power[k] = re * re + im * im;
            }

            for (int m = 0; m < N_MELS; m++) {
                double[] filterRow = melFilterbank[m];
                double sum = 0;
                for (int k = 0; k < NUM_BINS; k++) {
                    sum += filterRow[k] * power[k];
                }
                logMel[frame][m] = Math.log(sum + LOG_EPSILON);
            }
        }

        double[] columnMean = new double[N_MELS];
        for (double[] frameRow : logMel) {
            for (int m = 0; m < N_MELS; m++) {
                columnMean[m] += frameRow[m];
            }
        }
        for (int m = 0; m < N_MELS; m++) {
            columnMean[m] /= numFrames;
        }

        float[][] result = new float[numFrames][N_MELS];
        for (int f = 0; f < numFrames; f++) {
            for (int m = 0; m < N_MELS; m++) {
                result[f][m] = (float) (logMel[f][m] - columnMean[m]);
            }
        }
        return result;
    }

    /** Periodic (DFT-even) Hamming window, matching {@code scipy.signal.windows.hamming(n, sym=False)}. */
    private static double[] periodicHammingWindow(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / n);
        }
        return w;
    }

    private static double hzToMelSlaney(double hz) {
        double fSp = 200.0 / 3.0;
        double mels = hz / fSp;
        double minLogHz = 1000.0;
        double minLogMel = minLogHz / fSp;
        double logstep = Math.log(6.4) / 27.0;
        if (hz >= minLogHz) {
            mels = minLogMel + Math.log(hz / minLogHz) / logstep;
        }
        return mels;
    }

    private static double melToHzSlaney(double mel) {
        double fSp = 200.0 / 3.0;
        double hz = mel * fSp;
        double minLogHz = 1000.0;
        double minLogMel = minLogHz / fSp;
        double logstep = Math.log(6.4) / 27.0;
        if (mel >= minLogMel) {
            hz = minLogHz * Math.exp(logstep * (mel - minLogMel));
        }
        return hz;
    }

    /** Mirrors {@code librosa.filters.mel(sr, n_fft, n_mels, htk=False, norm='slaney')}. */
    private static double[][] buildMelFilterbank(int numMels, int nFft, int sampleRate) {
        int numBins = nFft / 2 + 1;
        double[] fftFreqs = new double[numBins];
        for (int k = 0; k < numBins; k++) {
            fftFreqs[k] = k * (sampleRate / 2.0) / (numBins - 1);
        }

        double melMin = hzToMelSlaney(0.0);
        double melMax = hzToMelSlaney(sampleRate / 2.0);
        double[] melPoints = new double[numMels + 2];
        for (int i = 0; i < melPoints.length; i++) {
            melPoints[i] = melMin + (melMax - melMin) * i / (numMels + 1);
        }
        double[] hzPoints = new double[numMels + 2];
        for (int i = 0; i < hzPoints.length; i++) {
            hzPoints[i] = melToHzSlaney(melPoints[i]);
        }

        double[][] filters = new double[numMels][numBins];
        for (int m = 0; m < numMels; m++) {
            double lower = hzPoints[m];
            double center = hzPoints[m + 1];
            double upper = hzPoints[m + 2];
            double enorm = 2.0 / (upper - lower);
            for (int k = 0; k < numBins; k++) {
                double lowerSlope = (fftFreqs[k] - lower) / (center - lower);
                double upperSlope = (upper - fftFreqs[k]) / (upper - center);
                double weight = Math.max(0.0, Math.min(lowerSlope, upperSlope));
                filters[m][k] = weight * enorm;
            }
        }
        return filters;
    }
}
