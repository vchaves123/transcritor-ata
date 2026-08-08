package com.tailor.transcritorata.minutes;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tailor.transcritorata.model.AttributedSegment;
import com.tailor.transcritorata.model.FileTranscript;
import com.tailor.transcritorata.model.Segment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxMinutesGeneratorTest {

    private static final MeetingMetadata METADATA = new MeetingMetadata(
            LocalDate.of(2026, 7, 10), "board-meeting.wmv", Duration.ofMinutes(45));

    private static final List<Segment> SEGMENTS = List.of(
            new Segment(Duration.ZERO, Duration.ofSeconds(5), "Good morning, everyone."),
            new Segment(Duration.ofSeconds(5), Duration.ofSeconds(12), "Let's review the schedule."));

    private static final List<AttributedSegment> UNATTRIBUTED_SEGMENTS = SEGMENTS.stream()
            .map(s -> new AttributedSegment(s, null)).toList();

    @Test
    void generatesSimpleMinutesWithTitleMetadataAndSegments(@TempDir Path tempDir) throws IOException {
        Path output = tempDir.resolve("minutes.docx");
        new DocxMinutesGenerator().generateSimpleMinutesAttributed(output, METADATA, UNATTRIBUTED_SEGMENTS);

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(output))) {
            String fullText = extractText(document);
            assertTrue(fullText.contains("Meeting Minutes"));
            assertTrue(fullText.contains("board-meeting.wmv"));
            assertTrue(fullText.contains("00:45:00") || fullText.contains("00:45"));
            assertTrue(fullText.contains("Good morning, everyone."));
            assertTrue(fullText.contains("[00:00:05]"));
        }
    }

    @Test
    void includesSpeakerLabelsWhenSegmentsAreAttributed(@TempDir Path tempDir) throws IOException {
        List<AttributedSegment> attributed = List.of(
                new AttributedSegment(SEGMENTS.get(0), "Speaker 1"),
                new AttributedSegment(SEGMENTS.get(1), "Speaker 2"));

        Path output = tempDir.resolve("minutes-speakers.docx");
        new DocxMinutesGenerator().generateSimpleMinutesAttributed(output, METADATA, attributed);

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(output))) {
            String fullText = extractText(document);
            assertTrue(fullText.contains("Speaker 1"), "should contain the first speaker's label");
            assertTrue(fullText.contains("Speaker 2"), "should contain the second speaker's label");
            assertTrue(fullText.contains("Good morning, everyone."));
        }
    }

    @Test
    void generatesMultiFileMinutesWithAnIndexPageAndOneSectionPerFile(@TempDir Path tempDir) throws IOException {
        FileTranscript fileA = new FileTranscript("part1.mp4", Duration.ofSeconds(3),
                List.of(new AttributedSegment(new Segment(Duration.ZERO, Duration.ofSeconds(3), "Content of part 1."), null)));
        FileTranscript fileB = new FileTranscript("part2.mp4", Duration.ofMinutes(1),
                List.of(new AttributedSegment(new Segment(Duration.ZERO, Duration.ofMinutes(1), "Content of part 2."), null)));

        Path output = tempDir.resolve("minutes-multi.docx");
        new DocxMinutesGenerator().generateMultiFileMinutes(output, METADATA, List.of(fileA, fileB));

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(output))) {
            String fullText = extractText(document);
            assertTrue(fullText.contains("Index"), "should have an index heading");
            assertTrue(fullText.contains("1. part1.mp4 (00:00:03)"), "index entry for the first file");
            assertTrue(fullText.contains("2. part2.mp4 (00:01:00)"), "index entry for the second file");
            assertTrue(fullText.contains("part1.mp4") && fullText.contains("Content of part 1."),
                    "first file's own section heading and transcript");
            assertTrue(fullText.contains("part2.mp4") && fullText.contains("Content of part 2."),
                    "second file's own section heading and transcript");

            assertEquals(2, countBookmarks(document), "one bookmark per file section");
            assertEquals(2, countInternalHyperlinkFields(document), "one internal HYPERLINK field per index entry");
        }
    }

    /** Counts {@code <w:bookmarkStart>} elements across every paragraph in the document. */
    private static int countBookmarks(XWPFDocument document) {
        int count = 0;
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            count += paragraph.getCTP().getBookmarkStartArray().length;
        }
        return count;
    }

    /** Counts field runs whose instrText begins with {@code HYPERLINK \l} (an internal, same-document jump). */
    private static int countInternalHyperlinkFields(XWPFDocument document) {
        int count = 0;
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            for (var run : paragraph.getRuns()) {
                for (var instrText : run.getCTR().getInstrTextArray()) {
                    if (instrText.getStringValue() != null && instrText.getStringValue().startsWith("HYPERLINK \\l")) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static String extractText(XWPFDocument document) {
        StringBuilder builder = new StringBuilder();
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            builder.append(paragraph.getText()).append('\n');
        }
        for (XWPFTable table : document.getTables()) {
            builder.append(table.getText()).append('\n');
        }
        return builder.toString();
    }
}
