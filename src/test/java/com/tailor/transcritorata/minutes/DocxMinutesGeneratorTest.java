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

import com.tailor.transcritorata.ai.ActionItem;
import com.tailor.transcritorata.ai.StructuredMinutes;
import com.tailor.transcritorata.model.AttributedSegment;
import com.tailor.transcritorata.model.Segment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxMinutesGeneratorTest {

    private static final MeetingMetadata METADATA = new MeetingMetadata(
            LocalDate.of(2026, 7, 10), "board-meeting.wmv", Duration.ofMinutes(45), "Tailor");

    private static final List<Segment> SEGMENTS = List.of(
            new Segment(Duration.ZERO, Duration.ofSeconds(5), "Good morning, everyone."),
            new Segment(Duration.ofSeconds(5), Duration.ofSeconds(12), "Let's review the schedule."));

    @Test
    void generatesSimpleMinutesWithTitleMetadataAndSegments(@TempDir Path tempDir) throws IOException {
        Path output = tempDir.resolve("minutes.docx");
        new DocxMinutesGenerator("Tailor").generateSimpleMinutes(output, METADATA, SEGMENTS);

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
    void generatesStructuredMinutesWithActionItemsTable(@TempDir Path tempDir) throws IOException {
        StructuredMinutes structured = new StructuredMinutes(
                "Discussion about the project schedule.",
                List.of("Maria", "John"),
                List.of("Scope review", "Risks"),
                List.of("Postpone delivery to August"),
                List.of(new ActionItem("Update schedule", "Maria", "2026-07-20")));

        Path output = tempDir.resolve("minutes-structured.docx");
        new DocxMinutesGenerator("Tailor").generateStructuredMinutes(output, METADATA, structured, SEGMENTS);

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(output))) {
            String fullText = extractText(document);
            assertTrue(fullText.contains("Structured Meeting Minutes"));
            assertTrue(fullText.contains("Discussion about the project schedule."));
            assertTrue(fullText.contains("Maria"));
            assertTrue(fullText.contains("Scope review"));
            assertTrue(fullText.contains("Postpone delivery to August"));

            List<XWPFTable> tables = document.getTables();
            boolean foundActionTable = tables.stream().anyMatch(t ->
                    t.getRow(0).getCell(0).getText().equals("Action")
                            && t.getRow(0).getCell(1).getText().equals("Owner")
                            && t.getRow(0).getCell(2).getText().equals("Due Date"));
            assertTrue(foundActionTable, "Should contain the action items table with the expected header");

            XWPFTable actionTable = tables.stream().filter(t ->
                    t.getRow(0).getCell(0).getText().equals("Action")).findFirst().orElseThrow();
            assertEquals("Update schedule", actionTable.getRow(1).getCell(0).getText());
            assertEquals("Maria", actionTable.getRow(1).getCell(1).getText());
            assertEquals("2026-07-20", actionTable.getRow(1).getCell(2).getText());
        }
    }

    @Test
    void includesSpeakerLabelsWhenSegmentsAreAttributed(@TempDir Path tempDir) throws IOException {
        List<AttributedSegment> attributed = List.of(
                new AttributedSegment(SEGMENTS.get(0), "Speaker 1"),
                new AttributedSegment(SEGMENTS.get(1), "Speaker 2"));

        Path output = tempDir.resolve("minutes-speakers.docx");
        new DocxMinutesGenerator("Tailor").generateSimpleMinutesAttributed(output, METADATA, attributed);

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(output))) {
            String fullText = extractText(document);
            assertTrue(fullText.contains("Speaker 1"), "should contain the first speaker's label");
            assertTrue(fullText.contains("Speaker 2"), "should contain the second speaker's label");
            assertTrue(fullText.contains("Good morning, everyone."));
        }
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
