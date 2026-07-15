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
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFldChar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;

import com.tailor.transcritorata.model.AttributedSegment;
import com.tailor.transcritorata.model.Segment;

/**
 * Builds professional-looking {@code .docx} meeting minutes with Apache POI.
 *
 * <p>All visual decisions (fonts, sizes, spacing, header/footer) live here. Kept ready for a
 * future evolution to a corporate {@code .dotx} template: callers only interact with
 * {@link #generateSimpleMinutesAttributed}, never with raw POI calls.
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

    /** Generates the plain minutes: title, metadata table, and the transcription as timestamped paragraphs. */
    public void generateSimpleMinutesAttributed(Path outputPath, MeetingMetadata metadata,
            List<AttributedSegment> segments) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            applyHeaderFooter(document);
            addTitle(document, "Meeting Minutes");
            addMetadataTable(document, metadata);
            addSectionHeading(document, "Transcript");
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
        headerRun.setText(companyName.isBlank() ? "Meeting Minutes" : companyName);
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
        run.setText("Page ");

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

    private void addMetadataTable(XWPFDocument document, MeetingMetadata metadata) {
        XWPFTable table = document.createTable(3, 2);
        table.setWidth("100%");

        String dateText = metadata.meetingDate() != null
                ? metadata.meetingDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                : "-";
        String durationText = metadata.duration() != null ? Segment.format(metadata.duration()) : "-";

        setRow(table, 0, "Meeting date", dateText);
        setRow(table, 1, "Source file", metadata.sourceFileName());
        setRow(table, 2, "Duration", durationText);

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

    private void addTranscription(XWPFDocument document, List<AttributedSegment> segments) {
        for (AttributedSegment attributed : segments) {
            Segment segment = attributed.segment();
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setSpacingAfter(120);

            if (attributed.hasSpeaker()) {
                XWPFRun speakerRun = paragraph.createRun();
                speakerRun.setText(attributed.speakerLabel() + " ");
                speakerRun.setBold(true);
                speakerRun.setColor(ACCENT_COLOR);
                speakerRun.setFontFamily(FONT_FAMILY);
                speakerRun.setFontSize(BODY_SIZE);
            }

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
