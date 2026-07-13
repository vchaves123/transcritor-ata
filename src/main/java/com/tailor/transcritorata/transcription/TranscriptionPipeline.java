package com.tailor.transcritorata.transcription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tailor.transcritorata.ai.MinutesStructurer;
import com.tailor.transcritorata.ai.MinutesStructuringException;
import com.tailor.transcritorata.ai.StructuredMinutes;
import com.tailor.transcritorata.audio.AudioExtractor;
import com.tailor.transcritorata.audio.ExternalProcessException;
import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.minutes.DocxMinutesGenerator;
import com.tailor.transcritorata.minutes.MeetingMetadata;
import com.tailor.transcritorata.model.Segment;

/**
 * Orchestrates the full pipeline: audio extraction, transcription (optionally chunked and run
 * in parallel), plain minutes generation, and the optional AI-structured minutes step.
 *
 * <p>The plain minutes are always written to disk before the AI step runs, so a network or API
 * failure during structuring never loses the transcription.
 */
public final class TranscriptionPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(TranscriptionPipeline.class);

    private final AudioExtractor audioExtractor;
    private final TranscriptionEngine engine;
    private final DocxMinutesGenerator docxGenerator;
    private final MinutesStructurer minutesStructurer;
    private final boolean chunkingEnabled;
    private final int chunkMinutes;
    private final boolean aiEnabled;

    public TranscriptionPipeline(AudioExtractor audioExtractor, TranscriptionEngine engine,
            DocxMinutesGenerator docxGenerator, MinutesStructurer minutesStructurer,
            boolean chunkingEnabled, int chunkMinutes, boolean aiEnabled) {
        this.audioExtractor = audioExtractor;
        this.engine = engine;
        this.docxGenerator = docxGenerator;
        this.minutesStructurer = minutesStructurer;
        this.chunkingEnabled = chunkingEnabled;
        this.chunkMinutes = chunkMinutes;
        this.aiEnabled = aiEnabled;
    }

    public PipelineResult run(Path videoFile, Path outputDir, ProgressListener listener,
            ProcessRunner.Handle handle) throws ExternalProcessException, IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("transcritor-ata-");
        try {
            listener.onProgress("Extraindo áudio...", 0);
            Path wav = tempDir.resolve("audio.wav");
            audioExtractor.extractToWav(videoFile, wav, handle);

            List<Segment> segments = chunkingEnabled
                    ? transcribeInChunks(wav, tempDir, listener, handle)
                    : engine.transcribe(wav, (msg, pct) -> listener.onProgress(msg, pct), handle);

            Duration totalDuration = segments.isEmpty() ? Duration.ZERO
                    : segments.get(segments.size() - 1).end();

            String baseName = stripExtension(videoFile.getFileName().toString());
            MeetingMetadata metadata = new MeetingMetadata(LocalDate.now(), videoFile.getFileName().toString(),
                    totalDuration, docxGenerator.companyNameForDisplay());

            listener.onProgress("Gerando ata...", 95);
            Path simpleMinutes = outputDir.resolve(baseName + "-ata.docx");
            docxGenerator.generateSimpleMinutes(simpleMinutes, metadata, segments);

            Path structuredMinutes = null;
            String aiWarning = null;
            if (aiEnabled && minutesStructurer != null) {
                listener.onProgress("Gerando ata estruturada com IA...", 97);
                try {
                    String transcriptText = String.join("\n", segments.stream().map(Segment::text).toList());
                    StructuredMinutes structured = minutesStructurer.structure(transcriptText);
                    structuredMinutes = outputDir.resolve(baseName + "-ata-estruturada.docx");
                    docxGenerator.generateStructuredMinutes(structuredMinutes, metadata, structured, segments);
                } catch (MinutesStructuringException e) {
                    LOG.warn("Falha ao gerar ata estruturada com IA: {}", e.getMessage(), e);
                    aiWarning = "Não foi possível gerar a ata estruturada com IA (" + e.getMessage()
                            + "). A ata simples foi gerada normalmente.";
                }
            }

            listener.onProgress("Concluído", 100);
            return new PipelineResult(simpleMinutes, structuredMinutes, aiWarning, segments);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private List<Segment> transcribeInChunks(Path wav, Path tempDir, ProgressListener listener,
            ProcessRunner.Handle handle) throws ExternalProcessException, IOException, InterruptedException {
        Path chunkDir = Files.createDirectory(tempDir.resolve("chunks"));
        audioExtractor.splitIntoChunks(wav, chunkDir, "chunk", chunkMinutes, handle);

        List<Path> chunkFiles;
        try (var stream = Files.list(chunkDir)) {
            chunkFiles = stream.filter(p -> p.getFileName().toString().endsWith(".wav")).sorted().toList();
        }

        List<List<Segment>> perChunkResults = new ArrayList<>(chunkFiles.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Segment>>> futures = new ArrayList<>();
            for (Path chunkFile : chunkFiles) {
                futures.add(executor.submit(() -> engine.transcribe(chunkFile, null, handle)));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    perChunkResults.add(futures.get(i).get());
                } catch (ExecutionException e) {
                    throw unwrap(e);
                }
                int percent = (int) (((i + 1) * 90.0) / futures.size());
                listener.onProgress("Transcrevendo bloco %d de %d...".formatted(i + 1, futures.size()), percent);
            }
        }

        return ChunkMerger.merge(perChunkResults, Duration.ofMinutes(chunkMinutes));
    }

    private static ExternalProcessException unwrap(ExecutionException e) throws IOException {
        Throwable cause = e.getCause();
        if (cause instanceof ExternalProcessException externalProcessException) {
            return externalProcessException;
        }
        if (cause instanceof IOException ioException) {
            throw ioException;
        }
        return new ExternalProcessException("Falha ao transcrever bloco: " + cause, "");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.debug("Não foi possível remover arquivo temporário {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.debug("Não foi possível limpar diretório temporário {}: {}", path, e.getMessage());
        }
    }
}
