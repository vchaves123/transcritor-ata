package com.tailor.transcritorata.transcription;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vosk.Model;
import org.vosk.Recognizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.transcritorata.audio.ExternalProcessException;
import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.model.Segment;

/**
 * Lightweight offline fallback {@link TranscriptionEngine} backed by Vosk. Slightly less
 * accurate than whisper.cpp but requires no separate binary — only a model directory.
 */
public final class VoskEngine implements TranscriptionEngine {

    private static final Logger LOG = LoggerFactory.getLogger(VoskEngine.class);
    private static final float SAMPLE_RATE = 16000f;

    private final Path modelDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VoskEngine(Path modelDir) {
        this.modelDir = modelDir;
    }

    @Override
    public List<Segment> transcribe(Path wav, ProgressListener listener, ProcessRunner.Handle handle)
            throws ExternalProcessException, IOException {
        List<Segment> segments = new ArrayList<>();
        LOG.info("Transcrevendo {} com Vosk (modelo em {})", wav, modelDir);

        try (Model model = new Model(modelDir.toString());
                Recognizer recognizer = new Recognizer(model, SAMPLE_RATE)) {
            recognizer.setWords(true);

            try (AudioInputStream audioIn = AudioSystem.getAudioInputStream(wav.toFile())) {
                long totalFrames = audioIn.getFrameLength();
                long framesRead = 0;
                byte[] buffer = new byte[4096];
                int read;
                while ((read = audioIn.read(buffer)) >= 0) {
                    framesRead += read / Math.max(1, audioIn.getFormat().getFrameSize());
                    if (recognizer.acceptWaveForm(buffer, read)) {
                        segments.addAll(parseResult(recognizer.getResult(), listener));
                    }
                    if (listener != null && totalFrames > 0) {
                        int percent = (int) Math.min(99, (framesRead * 100) / totalFrames);
                        listener.onProgress("Transcrevendo...", percent);
                    }
                }
            } catch (UnsupportedAudioFileException e) {
                throw new IOException("Formato de áudio não suportado pelo Vosk: " + e.getMessage(), e);
            }

            segments.addAll(parseResult(recognizer.getFinalResult(), listener));
        }

        if (listener != null) {
            listener.onProgress("Transcrevendo...", 100);
        }
        return segments;
    }

    /**
     * Parses one Vosk result chunk into a segment and, if {@code listener} is given, forwards
     * the recognized text as a log-only message ({@code percent = -1}) so the user can follow
     * along as phrases are recognized.
     */
    private List<Segment> parseResult(String json, ProgressListener listener) throws IOException {
        VoskJsonResult result = objectMapper.readValue(json, VoskJsonResult.class);
        List<Segment> segments = new ArrayList<>();
        if (result.result == null || result.result.isEmpty()) {
            return segments;
        }
        double start = result.result.get(0).start;
        double end = result.result.get(result.result.size() - 1).end;
        String text = result.text != null ? result.text
                : result.result.stream().map(w -> w.word).reduce((a, b) -> a + " " + b).orElse("");
        segments.add(new Segment(
                Duration.ofMillis((long) (start * 1000)),
                Duration.ofMillis((long) (end * 1000)),
                text));
        if (listener != null && !text.isBlank()) {
            listener.onProgress(text, -1);
        }
        return segments;
    }
}
