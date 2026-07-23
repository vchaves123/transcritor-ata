package com.tailor.transcritorata.transcription;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.tailor.transcritorata.audio.AudioExtractor;
import com.tailor.transcritorata.audio.ExternalProcessException;
import com.tailor.transcritorata.audio.ProcessCancelledException;
import com.tailor.transcritorata.audio.ProcessRunner;
import com.tailor.transcritorata.diarization.DiarizationException;
import com.tailor.transcritorata.diarization.SpeakerDiarizer;
import com.tailor.transcritorata.minutes.DocxMinutesGenerator;
import com.tailor.transcritorata.minutes.MeetingMetadata;
import com.tailor.transcritorata.model.AttributedSegment;
import com.tailor.transcritorata.model.Segment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Exercises the orchestration in {@link TranscriptionPipeline} itself (temp-directory lifecycle,
 * non-fatal diarization failures) with mocked collaborators -- this class had no test coverage at
 * all before, despite being the most complex control-flow file in the codebase.
 */
class TranscriptionPipelineTest {

    private static final ProgressListener NO_OP_LISTENER = (message, percent) -> { };

    @Test
    void deletesTheTemporaryDirectoryAfterASuccessfulRun(@TempDir Path outputDir) throws Exception {
        AudioExtractor audioExtractor = mock(AudioExtractor.class);
        TranscriptionEngine engine = mock(TranscriptionEngine.class);
        DocxMinutesGenerator docxGenerator = mock(DocxMinutesGenerator.class);

        List<Segment> segments = List.of(new Segment(Duration.ZERO, Duration.ofSeconds(1), "hi"));
        var wavCaptor = ArgumentCaptor.forClass(Path.class);
        org.mockito.Mockito.when(engine.transcribe(wavCaptor.capture(), any(), any())).thenReturn(segments);

        TranscriptionPipeline pipeline = new TranscriptionPipeline(audioExtractor, engine, docxGenerator, null, false);

        List<Path> videos = List.of(Path.of("a.mp4"), Path.of("b.mp4"));
        pipeline.run(videos, outputDir, NO_OP_LISTENER, NO_OP_LISTENER, NO_OP_LISTENER, NO_OP_LISTENER,
                new ProcessRunner.Handle());

        Path tempDir = wavCaptor.getValue().getParent();
        assertFalse(Files.exists(tempDir), "the per-run temp directory must be cleaned up after success");
    }

    @Test
    void deletesTheTemporaryDirectoryEvenWhenTranscriptionFails(@TempDir Path outputDir) throws Exception {
        AudioExtractor audioExtractor = mock(AudioExtractor.class);
        TranscriptionEngine engine = mock(TranscriptionEngine.class);
        DocxMinutesGenerator docxGenerator = mock(DocxMinutesGenerator.class);

        var wavCaptor = ArgumentCaptor.forClass(Path.class);
        org.mockito.Mockito.when(engine.transcribe(wavCaptor.capture(), any(), any()))
                .thenThrow(new ExternalProcessException("boom", ""));

        TranscriptionPipeline pipeline = new TranscriptionPipeline(audioExtractor, engine, docxGenerator, null, false);
        List<Path> videos = List.of(Path.of("a.mp4"), Path.of("b.mp4"));

        assertThrows(ExternalProcessException.class, () -> pipeline.run(videos, outputDir, NO_OP_LISTENER,
                NO_OP_LISTENER, NO_OP_LISTENER, NO_OP_LISTENER, new ProcessRunner.Handle()));

        Path tempDir = wavCaptor.getValue().getParent();
        assertFalse(Files.exists(tempDir), "the per-run temp directory must be cleaned up even after a failure");
    }

    @Test
    void propagatesCancellationInsteadOfSwallowingIt(@TempDir Path outputDir) throws Exception {
        AudioExtractor audioExtractor = mock(AudioExtractor.class);
        TranscriptionEngine engine = mock(TranscriptionEngine.class);
        DocxMinutesGenerator docxGenerator = mock(DocxMinutesGenerator.class);
        org.mockito.Mockito.when(engine.transcribe(any(), any(), any()))
                .thenThrow(new ProcessCancelledException(""));

        TranscriptionPipeline pipeline = new TranscriptionPipeline(audioExtractor, engine, docxGenerator, null, false);
        List<Path> videos = List.of(Path.of("a.mp4"), Path.of("b.mp4"));

        assertThrows(ProcessCancelledException.class, () -> pipeline.run(videos, outputDir, NO_OP_LISTENER,
                NO_OP_LISTENER, NO_OP_LISTENER, NO_OP_LISTENER, new ProcessRunner.Handle()));
    }

    @Test
    void diarizationFailureIsNonFatalAndYieldsUnattributedSegments(@TempDir Path outputDir) throws Exception {
        AudioExtractor audioExtractor = mock(AudioExtractor.class);
        TranscriptionEngine engine = mock(TranscriptionEngine.class);
        DocxMinutesGenerator docxGenerator = mock(DocxMinutesGenerator.class);
        SpeakerDiarizer diarizer = mock(SpeakerDiarizer.class);

        List<Segment> segments = List.of(new Segment(Duration.ZERO, Duration.ofSeconds(1), "hi"));
        org.mockito.Mockito.when(engine.transcribe(any(), any(), any())).thenReturn(segments);
        doThrow(new DiarizationException("model failed", null)).when(diarizer)
                .diarize(any(), any(), any());

        ArgumentCaptor<List<AttributedSegment>> attributedCaptor = listCaptor();
        TranscriptionPipeline pipeline = new TranscriptionPipeline(audioExtractor, engine, docxGenerator, diarizer, true);
        List<Path> videos = List.of(Path.of("a.mp4"), Path.of("b.mp4"));

        pipeline.run(videos, outputDir, NO_OP_LISTENER, NO_OP_LISTENER, NO_OP_LISTENER, NO_OP_LISTENER,
                new ProcessRunner.Handle());

        verify(docxGenerator).generateSimpleMinutesAttributed(any(Path.class), any(MeetingMetadata.class),
                attributedCaptor.capture());
        List<AttributedSegment> attributed = attributedCaptor.getValue();
        assertEquals(1, attributed.size());
        assertNull(attributed.get(0).speakerLabel(), "a failed diarization must fall back to no speaker labels");
    }

    /** Isolates the single unchecked cast Mockito's raw-typed {@code ArgumentCaptor.forClass(List.class)} requires. */
    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<List<T>> listCaptor() {
        return (ArgumentCaptor<List<T>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
    }
}
