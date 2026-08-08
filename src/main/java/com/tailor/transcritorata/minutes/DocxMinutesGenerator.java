package com.tailor.transcritorata.minutes;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;

import com.tailor.transcritorata.model.AttributedSegment;
import com.tailor.transcritorata.model.FileTranscript;
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

    public DocxMinutesGenerator() {
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

    /**
     * Generates minutes for several source files transcribed individually (each on its own
     * 00:00-based timeline, never concatenated): title, metadata table, an index page listing
     * every file with a clickable link, then each file's own section -- in the same order -- with
     * its transcription. Each index entry jumps straight to its file's section heading via an
     * internal Word bookmark/hyperlink pair, since a same-document jump has no dedicated
     * high-level POI API (unlike the external hyperlinks {@code createHyperlinkRun} supports).
     */
    public void generateMultiFileMinutes(Path outputPath, MeetingMetadata metadata,
            List<FileTranscript> fileTranscripts) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            applyHeaderFooter(document);
            addTitle(document, "Meeting Minutes");
            addMetadataTable(document, metadata);

            addSectionHeading(document, "Index");
            for (int i = 0; i < fileTranscripts.size(); i++) {
                FileTranscript fileTranscript = fileTranscripts.get(i);
                String durationText = fileTranscript.duration() != null ? Segment.format(fileTranscript.duration()) : "-";
                addIndexEntry(document, bookmarkNameFor(i),
                        (i + 1) + ". " + fileTranscript.fileName() + " (" + durationText + ")");
            }

            for (int i = 0; i < fileTranscripts.size(); i++) {
                FileTranscript fileTranscript = fileTranscripts.get(i);
                addFileSectionHeading(document, bookmarkNameFor(i), i, fileTranscript.fileName());
                addTranscription(document, fileTranscript.segments());
            }

            write(document, outputPath);
        }
    }

    private static String bookmarkNameFor(int fileIndex) {
        return "transcritorAtaFile" + fileIndex;
    }

    private void applyHeaderFooter(XWPFDocument document) {
        XWPFHeaderFooterPolicy policy = document.createHeaderFooterPolicy();

        var header = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
        XWPFParagraph headerParagraph = header.getParagraphArray(0) != null
                ? header.getParagraphArray(0) : header.createParagraph();
        headerParagraph.setAlignment(ParagraphAlignment.RIGHT);
        XWPFRun headerRun = headerParagraph.createRun();
        headerRun.setText("Meeting Minutes");
        headerRun.setFontFamily(FONT_FAMILY);
        headerRun.setFontSize(FOOTER_SIZE);
        headerRun.setColor("808080");

        var footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
        XWPFParagraph footerParagraph = footer.getParagraphArray(0) != null
                ? footer.getParagraphArray(0) : footer.createParagraph();
        footerParagraph.setAlignment(ParagraphAlignment.CENTER);
        addPageNumberField(footerParagraph);
    }

    /**
     * Inserts a real Word {@code PAGE} field (begin/instrText/separate/end each in their own
     * run, per the OOXML field convention) so Word recalculates and displays the actual page
     * number, instead of a hardcoded number that would be wrong on every page but the first.
     */
    private void addPageNumberField(XWPFParagraph paragraph) {
        XWPFRun labelRun = paragraph.createRun();
        labelRun.setFontFamily(FONT_FAMILY);
        labelRun.setFontSize(FOOTER_SIZE);
        labelRun.setText("Page ");

        XWPFRun beginRun = paragraph.createRun();
        beginRun.getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);

        XWPFRun instrRun = paragraph.createRun();
        instrRun.getCTR().addNewInstrText().setStringValue("PAGE");

        XWPFRun separateRun = paragraph.createRun();
        separateRun.getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);

        XWPFRun cachedValueRun = paragraph.createRun();
        cachedValueRun.setFontFamily(FONT_FAMILY);
        cachedValueRun.setFontSize(FOOTER_SIZE);
        cachedValueRun.setText("1");

        XWPFRun endRun = paragraph.createRun();
        endRun.getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
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

    /**
     * A section heading with a Word bookmark wrapped around its run, so an index entry elsewhere
     * in the document can jump straight to it. {@code bookmarkStart}/{@code bookmarkEnd} must
     * bracket the run(s) they name -- calling {@code addNewBookmarkStart()} before creating the
     * run and {@code addNewBookmarkEnd()} after appends them in that document order, per how
     * {@link XWPFParagraph#createRun()} itself appends to the same underlying paragraph XML.
     */
    private void addFileSectionHeading(XWPFDocument document, String bookmarkName, int bookmarkId, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(200);
        paragraph.setSpacingAfter(120);

        var bookmarkStart = paragraph.getCTP().addNewBookmarkStart();
        bookmarkStart.setName(bookmarkName);
        bookmarkStart.setId(BigInteger.valueOf(bookmarkId));

        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontFamily(FONT_FAMILY);
        run.setFontSize(SECTION_HEADING_SIZE);
        run.setColor(ACCENT_COLOR);

        paragraph.getCTP().addNewBookmarkEnd().setId(BigInteger.valueOf(bookmarkId));
    }

    /**
     * An index entry: an internal {@code HYPERLINK \l "bookmarkName"} field (begin/instrText/
     * separate/end runs, same OOXML field convention as {@link #addPageNumberField}) wrapping a
     * visible, underlined display run -- clicking it in Word jumps to the matching bookmark added
     * by {@link #addFileSectionHeading}.
     */
    private void addIndexEntry(XWPFDocument document, String bookmarkName, String displayText) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(80);
        paragraph.setIndentationLeft(240);

        XWPFRun beginRun = paragraph.createRun();
        beginRun.getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);

        XWPFRun instrRun = paragraph.createRun();
        instrRun.getCTR().addNewInstrText().setStringValue("HYPERLINK \\l \"" + bookmarkName + "\"");

        XWPFRun separateRun = paragraph.createRun();
        separateRun.getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);

        XWPFRun displayRun = paragraph.createRun();
        displayRun.setText(displayText);
        displayRun.setColor("0563C1");
        displayRun.setUnderline(UnderlinePatterns.SINGLE);
        displayRun.setFontFamily(FONT_FAMILY);
        displayRun.setFontSize(BODY_SIZE);

        XWPFRun endRun = paragraph.createRun();
        endRun.getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
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

    /**
     * Writes to a sibling temp file first, then atomically renames it over the final path once
     * the document is fully serialized — so a crash, forced kill, or disk-full error mid-write can
     * never leave a truncated/corrupt file at {@code outputPath}, e.g. destroying a previously
     * generated minutes document when the user reprocesses the same recording.
     */
    private void write(XWPFDocument document, Path outputPath) throws IOException {
        Path absoluteOutputPath = outputPath.toAbsolutePath();
        Files.createDirectories(absoluteOutputPath.getParent());
        Path tempFile = absoluteOutputPath.resolveSibling(absoluteOutputPath.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tempFile)) {
            document.write(out);
        }
        try {
            Files.move(tempFile, absoluteOutputPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (FileSystemException e) {
            // Some filesystems (e.g. across drives) don't support ATOMIC_MOVE; a plain replace is
            // still far better than the original truncate-in-place, since it's a single rename
            // instead of a byte-by-byte overwrite.
            Files.move(tempFile, absoluteOutputPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
