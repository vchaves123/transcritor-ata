package com.tailor.transcritorata.diarization.onnx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.diarization.DiarizationException;
import com.tailor.transcritorata.diarization.SpeakerDiarizer;
import com.tailor.transcritorata.diarization.SpeakerTurn;

/**
 * {@link SpeakerDiarizer} backed by a from-scratch Java port of the pyannote/speaker-diarization-3.1
 * pipeline (segmentation-3.0 + wespeaker-resnet34 embeddings), run via ONNX Runtime entirely
 * inside this JVM — no external process, no extra bundled runtime.
 *
 * <p>The two ONNX models (~32&nbsp;MB combined) ship as classpath resources under
 * {@code /models}, so this replaces the LIUM_SpkDiarization integration (and the dedicated
 * {@code tools/jre} runtime it required) with something both smaller and considerably more
 * accurate, at the cost of the DSP/clustering logic below being a from-scratch port rather than
 * an off-the-shelf library.
 */
public final class OnnxSpeakerDiarizer implements SpeakerDiarizer {

    private static final Logger LOG = LoggerFactory.getLogger(OnnxSpeakerDiarizer.class);
    private static final int SAMPLE_RATE = 16_000;
    private static final int MIN_SEGMENT_SAMPLES = 400;

    @Override
    public List<SpeakerTurn> diarize(Path wav, ProcessRunner.Handle handle, Consumer<String> logLineListener)
            throws DiarizationException {
        log(logLineListener, "Carregando modelos de identificação de participantes (ONNX)...");
        try {
            float[] waveform = Waveform.readMono16k(wav);
            OrtEnvironment env = OrtEnvironment.getEnvironment();

            List<TimeSpan> spans;
            try (OnnxSegmentationModel segmentationModel =
                    new OnnxSegmentationModel(env, readResource("/models/segmentation.onnx"))) {
                log(logLineListener, "Identificando trechos de fala...");
                spans = segmentationModel.run(waveform);
            }

            if (handle != null && handle.isCancelled()) {
                throw new DiarizationException("Identificação de participantes cancelada.");
            }

            List<float[]> embeddings = new ArrayList<>();
            List<TimeSpan> validSpans = new ArrayList<>();
            try (OnnxEmbeddingModel embeddingModel =
                    new OnnxEmbeddingModel(env, readResource("/models/embedding.onnx"))) {
                log(logLineListener, "Extraindo características de voz de " + spans.size() + " trecho(s)...");
                for (TimeSpan span : spans) {
                    if (handle != null && handle.isCancelled()) {
                        throw new DiarizationException("Identificação de participantes cancelada.");
                    }
                    int startSample = Math.max(0, (int) (span.startSeconds() * SAMPLE_RATE));
                    int endSample = Math.min(waveform.length, (int) (span.endSeconds() * SAMPLE_RATE));
                    if (endSample - startSample < MIN_SEGMENT_SAMPLES) {
                        continue;
                    }
                    float[] segmentSamples = java.util.Arrays.copyOfRange(waveform, startSample, endSample);
                    float[] embedding = embeddingModel.embed(segmentSamples);
                    if (embedding != null) {
                        embeddings.add(embedding);
                        validSpans.add(span);
                    }
                }
            }

            log(logLineListener, "Agrupando trechos por locutor...");
            int[] labels = SpeakerClusterer.cluster(embeddings, validSpans);

            List<SpeakerTurn> turns = new ArrayList<>();
            Set<Integer> distinctSpeakers = new HashSet<>();
            for (int i = 0; i < validSpans.size(); i++) {
                TimeSpan span = validSpans.get(i);
                distinctSpeakers.add(labels[i]);
                turns.add(new SpeakerTurn(
                        Duration.ofMillis((long) (span.startSeconds() * 1000)),
                        Duration.ofMillis((long) (span.endSeconds() * 1000)),
                        "S" + labels[i]));
            }
            log(logLineListener, "Identificação concluída: " + distinctSpeakers.size() + " locutor(es) detectado(s).");
            return turns;
        } catch (OrtException | IOException e) {
            throw new DiarizationException("Falha na identificação de participantes: " + e.getMessage(), e);
        }
    }

    private static void log(Consumer<String> logLineListener, String message) {
        LOG.debug(message);
        if (logLineListener != null) {
            logLineListener.accept(message);
        }
    }

    private static byte[] readResource(String classpathPath) throws IOException {
        try (InputStream in = OnnxSpeakerDiarizer.class.getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IOException("Recurso não encontrado no classpath: " + classpathPath);
            }
            return in.readAllBytes();
        }
    }
}
