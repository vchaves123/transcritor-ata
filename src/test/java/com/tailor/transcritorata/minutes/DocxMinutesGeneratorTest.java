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
            LocalDate.of(2026, 7, 10), "reuniao-diretoria.wmv", Duration.ofMinutes(45), "Tailor");

    private static final List<Segment> SEGMENTS = List.of(
            new Segment(Duration.ZERO, Duration.ofSeconds(5), "Bom dia a todos."),
            new Segment(Duration.ofSeconds(5), Duration.ofSeconds(12), "Vamos revisar o cronograma."));

    @Test
    void generatesSimpleMinutesWithTitleMetadataAndSegments(@TempDir Path tempDir) throws IOException {
        Path output = tempDir.resolve("ata.docx");
        new DocxMinutesGenerator("Tailor").generateSimpleMinutes(output, METADATA, SEGMENTS);

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(output))) {
            String fullText = extractText(document);
            assertTrue(fullText.contains("Ata de Reunião"));
            assertTrue(fullText.contains("reuniao-diretoria.wmv"));
            assertTrue(fullText.contains("00:45:00") || fullText.contains("00:45"));
            assertTrue(fullText.contains("Bom dia a todos."));
            assertTrue(fullText.contains("[00:00:05]"));
        }
    }

    @Test
    void generatesStructuredMinutesWithActionItemsTable(@TempDir Path tempDir) throws IOException {
        StructuredMinutes structured = new StructuredMinutes(
                "Discussão sobre o cronograma do projeto.",
                List.of("Maria", "João"),
                List.of("Revisão de escopo", "Riscos"),
                List.of("Adiar entrega para agosto"),
                List.of(new ActionItem("Atualizar cronograma", "Maria", "2026-07-20")));

        Path output = tempDir.resolve("ata-estruturada.docx");
        new DocxMinutesGenerator("Tailor").generateStructuredMinutes(output, METADATA, structured, SEGMENTS);

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(output))) {
            String fullText = extractText(document);
            assertTrue(fullText.contains("Ata de Reunião Estruturada"));
            assertTrue(fullText.contains("Discussão sobre o cronograma do projeto."));
            assertTrue(fullText.contains("Maria"));
            assertTrue(fullText.contains("Revisão de escopo"));
            assertTrue(fullText.contains("Adiar entrega para agosto"));

            List<XWPFTable> tables = document.getTables();
            boolean foundActionTable = tables.stream().anyMatch(t ->
                    t.getRow(0).getCell(0).getText().equals("Ação")
                            && t.getRow(0).getCell(1).getText().equals("Responsável")
                            && t.getRow(0).getCell(2).getText().equals("Prazo"));
            assertTrue(foundActionTable, "Deveria conter a tabela de acoes com o cabecalho esperado");

            XWPFTable actionTable = tables.stream().filter(t ->
                    t.getRow(0).getCell(0).getText().equals("Ação")).findFirst().orElseThrow();
            assertEquals("Atualizar cronograma", actionTable.getRow(1).getCell(0).getText());
            assertEquals("Maria", actionTable.getRow(1).getCell(1).getText());
            assertEquals("2026-07-20", actionTable.getRow(1).getCell(2).getText());
        }
    }

    @Test
    void includesSpeakerLabelsWhenSegmentsAreAttributed(@TempDir Path tempDir) throws IOException {
        List<AttributedSegment> attributed = List.of(
                new AttributedSegment(SEGMENTS.get(0), "Pessoa 1"),
                new AttributedSegment(SEGMENTS.get(1), "Pessoa 2"));

        Path output = tempDir.resolve("ata-locutores.docx");
        new DocxMinutesGenerator("Tailor").generateSimpleMinutesAttributed(output, METADATA, attributed);

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(output))) {
            String fullText = extractText(document);
            assertTrue(fullText.contains("Pessoa 1"), "deve conter o rótulo do primeiro locutor");
            assertTrue(fullText.contains("Pessoa 2"), "deve conter o rótulo do segundo locutor");
            assertTrue(fullText.contains("Bom dia a todos."));
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
