package com.tailor.transcritorata.minutes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFldChar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;

import com.tailor.transcritorata.ai.ActionItem;
import com.tailor.transcritorata.ai.StructuredMinutes;
import com.tailor.transcritorata.model.Segment;

/**
 * Builds professional-looking {@code .docx} meeting minutes with Apache POI.
 *
 * <p>All visual decisions (fonts, sizes, spacing, header/footer) live here so both the simple
 * minutes and the AI-structured minutes share one consistent look. Kept ready for a future
 * evolution to a corporate {@code .dotx} template: callers only interact with the two
 * {@code generate*} entry points, never with raw POI calls.
 */
public final class DocxMinutesGenerator {

    private static final String FONT_FAMILY = "Calibri";
    private static final int TITLE_SIZE = 22;
    private static final int SECTION_HEADING_SIZE = 14;
    private static final int BODY_SIZE = 11;
    private static final int FOOTER_SIZE = 9;
    private static final String ACCENT_COLOR = "1F4E79";

    private final String companyName;

    public DocxMinutesGenerator(String companyName) {
        this.companyName = companyName == null ? "" : companyName;
    }

    public String companyNameForDisplay() {
        return companyName;
    }

    /** Generates the plain minutes: title, metadata table, and the transcription as timestamped paragraphs. */
    public void generateSimpleMinutes(Path outputPath, MeetingMetadata metadata, List<Segment> segments)
            throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            applyHeaderFooter(document);
            addTitle(document, "Ata de Reunião");
            addMetadataTable(document, metadata);
            addSectionHeading(document, "Transcrição");
            addTranscription(document, segments);
            write(document, outputPath);
        }
    }

    /**
     * Generates the AI-structured minutes: executive summary, participants, agenda, decisions,
     * an action items table, followed by the full transcription as an appendix.
     */
    public void generateStructuredMinutes(Path outputPath, MeetingMetadata metadata, StructuredMinutes structured,
            List<Segment> segments) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            applyHeaderFooter(document);
            addTitle(document, "Ata de Reunião Estruturada");
            addMetadataTable(document, metadata);

            addSectionHeading(document, "Resumo executivo");
            addBodyParagraph(document, structured.executiveSummary());

            addSectionHeading(document, "Participantes");
            addBulletedList(document, structured.participants());

            addSectionHeading(document, "Pauta");
            addBulletedList(document, structured.agenda());

            addSectionHeading(document, "Decisões");
            addBulletedList(document, structured.decisions());

            addSectionHeading(document, "Ações");
            addActionItemsTable(document, structured.actionItems());

            document.createParagraph().setPageBreak(true);
            addSectionHeading(document, "Anexo: transcrição completa");
            addTranscription(document, segments);

            write(document, outputPath);
        }
    }

    private void applyHeaderFooter(XWPFDocument document) {
        XWPFHeaderFooterPolicy policy = document.createHeaderFooterPolicy();

        var header = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
        XWPFParagraph headerParagraph = header.getParagraphArray(0) != null
                ? header.getParagraphArray(0) : header.createParagraph();
        headerParagraph.setAlignment(ParagraphAlignment.RIGHT);
        XWPFRun headerRun = headerParagraph.createRun();
        headerRun.setText(companyName.isBlank() ? "Ata de Reunião" : companyName);
        headerRun.setFontFamily(FONT_FAMILY);
        headerRun.setFontSize(FOOTER_SIZE);
        headerRun.setColor("808080");

        var footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
        XWPFParagraph footerParagraph = footer.getParagraphArray(0) != null
                ? footer.getParagraphArray(0) : footer.createParagraph();
        footerParagraph.setAlignment(ParagraphAlignment.CENTER);
        addPageNumberField(footerParagraph);
    }

    private void addPageNumberField(XWPFParagraph paragraph) {
        XWPFRun run = paragraph.createRun();
        run.setFontFamily(FONT_FAMILY);
        run.setFontSize(FOOTER_SIZE);
        run.setText("Página ");

        CTFldChar begin = run.getCTR().addNewFldChar();
        begin.setFldCharType(STFldCharType.BEGIN);

        XWPFRun instrRun = paragraph.createRun();
        instrRun.getCTR().addNewInstrText().setStringValue("PAGE");

        CTFldChar end = run.getCTR().addNewFldChar();
        end.setFldCharType(STFldCharType.END);
    }

    private void addTitle(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingAfter(300);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontFamily(FONT_FAMILY);
        run.setFontSize(TITLE_SIZE);
        run.setColor(ACCENT_COLOR);
    }

    private void addSectionHeading(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(200);
        paragraph.setSpacingAfter(120);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontFamily(FONT_FAMILY);
        run.setFontSize(SECTION_HEADING_SIZE);
        run.setColor(ACCENT_COLOR);
    }

    private void addBodyParagraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(120);
        XWPFRun run = paragraph.createRun();
        run.setText(text == null || text.isBlank() ? "-" : text);
        run.setFontFamily(FONT_FAMILY);
        run.setFontSize(BODY_SIZE);
    }

    private void addBulletedList(XWPFDocument document, List<String> items) {
        if (items == null || items.isEmpty()) {
            addBodyParagraph(document, "-");
            return;
        }
        for (String item : items) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setSpacingAfter(60);
            paragraph.setIndentationLeft(360);
            XWPFRun run = paragraph.createRun();
            run.setText("• " + item);
            run.setFontFamily(FONT_FAMILY);
            run.setFontSize(BODY_SIZE);
        }
    }

    private void addMetadataTable(XWPFDocument document, MeetingMetadata metadata) {
        XWPFTable table = document.createTable(3, 2);
        table.setWidth("100%");

        String dateText = metadata.meetingDate() != null
                ? metadata.meetingDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                : "-";
        String durationText = metadata.duration() != null ? Segment.format(metadata.duration()) : "-";

        setRow(table, 0, "Data da reunião", dateText);
        setRow(table, 1, "Arquivo de origem", metadata.sourceFileName());
        setRow(table, 2, "Duração", durationText);

        document.createParagraph().setSpacingAfter(200);
    }

    private void setRow(XWPFTable table, int rowIndex, String label, String value) {
        XWPFTableCell labelCell = table.getRow(rowIndex).getCell(0);
        styleCell(labelCell, label, true);
        XWPFTableCell valueCell = table.getRow(rowIndex).getCell(1);
        styleCell(valueCell, value == null ? "-" : value, false);
    }

    private void styleCell(XWPFTableCell cell, String text, boolean bold) {
        cell.removeParagraph(0);
        XWPFParagraph paragraph = cell.addParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontFamily(FONT_FAMILY);
        run.setFontSize(BODY_SIZE);
    }

    private void addActionItemsTable(XWPFDocument document, List<ActionItem> actionItems) {
        XWPFTable table = document.createTable(1, 3);
        table.setWidth("100%");
        styleHeaderCell(table.getRow(0).getCell(0), "Ação");
        styleHeaderCell(table.getRow(0).getCell(1), "Responsável");
        styleHeaderCell(table.getRow(0).getCell(2), "Prazo");

        if (actionItems == null || actionItems.isEmpty()) {
            XWPFTableCell cell = table.createRow().getCell(0);
            styleCell(cell, "Nenhuma ação identificada", false);
            return;
        }

        for (ActionItem item : actionItems) {
            XWPFTableRow row = table.createRow();
            styleCell(row.getCell(0), item.description(), false);
            styleCell(row.getCell(1), item.owner() == null ? "-" : item.owner(), false);
            styleCell(row.getCell(2), item.dueDate() == null ? "-" : item.dueDate(), false);
        }
    }

    private void styleHeaderCell(XWPFTableCell cell, String text) {
        cell.removeParagraph(0);
        XWPFParagraph paragraph = cell.addParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setColor("FFFFFF");
        run.setFontFamily(FONT_FAMILY);
        run.setFontSize(BODY_SIZE);
        cell.setColor(ACCENT_COLOR);
    }

    private void addTranscription(XWPFDocument document, List<Segment> segments) {
        for (Segment segment : segments) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setSpacingAfter(120);

            XWPFRun timestampRun = paragraph.createRun();
            timestampRun.setText("[" + segment.formattedStart() + "] ");
            timestampRun.setBold(true);
            timestampRun.setFontFamily(FONT_FAMILY);
            timestampRun.setFontSize(BODY_SIZE);

            XWPFRun textRun = paragraph.createRun();
            textRun.setText(segment.text());
            textRun.setFontFamily(FONT_FAMILY);
            textRun.setFontSize(BODY_SIZE);
        }
    }

    private void write(XWPFDocument document, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(outputPath)) {
            document.write(out);
        }
    }
}
