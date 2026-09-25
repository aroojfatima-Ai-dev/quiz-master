package com.quiz.importing;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Extracts the raw text of a questions file.
 *
 * <ul>
 *   <li>{@code .txt} - read directly as UTF-8.</li>
 *   <li>{@code .pdf} - Apache PDFBox.</li>
 *   <li>{@code .docx} - Apache POI, including text held in tables.</li>
 * </ul>
 *
 * <p>This class is the only part of the application that knows about PDFBox and POI,
 * and its contract is deliberately narrow: it either returns the file's text or
 * throws an {@link IOException} whose message is a complete sentence suitable for
 * showing to the teacher. Unchecked exceptions thrown by the parsers are wrapped,
 * so a corrupt or mislabelled file can never take the application down.
 */
public final class QuestionFileReader {

    /** Files larger than this are refused before anything is parsed. */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private QuestionFileReader() {
        // Utility class - not instantiable.
    }

    /**
     * Reads a questions file as text.
     *
     * @param file the file chosen by the teacher
     * @return the file's text; never null
     * @throws IOException with a message meant for the teacher, when the file is
     *                     missing, empty, too large, of an unsupported type, or cannot
     *                     be parsed
     */
    public static String readText(File file) throws IOException {
        if (file == null) {
            throw new IOException("No file was selected.");
        }
        if (!file.exists() || !file.isFile()) {
            throw new IOException("The file '" + file.getName() + "' could not be found.");
        }
        if (!file.canRead()) {
            throw new IOException("The file '" + file.getName() + "' cannot be read. Check its permissions.");
        }

        long size = file.length();
        if (size == 0) {
            throw new IOException("The file '" + file.getName() + "' is empty.");
        }
        if (size > MAX_FILE_BYTES) {
            throw new IOException("The file '" + file.getName() + "' is " + megabytes(size)
                    + " MB, but the limit is " + (MAX_FILE_BYTES / (1024 * 1024))
                    + " MB. Split it into smaller files and upload them one at a time.");
        }

        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".txt")) {
            return stripByteOrderMark(readPlainText(file));
        }
        if (name.endsWith(".pdf")) {
            return readPdf(file);
        }
        if (name.endsWith(".docx")) {
            return readDocx(file);
        }
        if (name.endsWith(".doc")) {
            throw new IOException("Legacy Word '.doc' files are not supported. Open the file in Word, "
                    + "choose 'Save As' and pick the '.docx' format, then upload it again.");
        }
        throw new IOException("'" + file.getName() + "' is not a supported file type. "
                + "Please upload a PDF (.pdf), a Word document (.docx) or a text file (.txt).");
    }

    // ------------------------------------------------------------------
    // Plain text
    // ------------------------------------------------------------------

    private static String readPlainText(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        String text = new String(bytes, StandardCharsets.UTF_8);

        // U+FFFD is what the UTF-8 decoder emits for a byte sequence that is not valid
        // UTF-8. Keeping it silently would corrupt the question text on its way into
        // the database (which stores utf8mb4), so say what to do about it instead.
        if (text.indexOf('\uFFFD') >= 0) {
            throw new IOException("The file '" + file.getName() + "' is not valid UTF-8 text. "
                    + "Re-save it as UTF-8 (Notepad: File > Save As > Encoding: UTF-8) and upload it again.");
        }
        return text;
    }

    private static String stripByteOrderMark(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    // ------------------------------------------------------------------
    // PDF
    // ------------------------------------------------------------------

    private static String readPdf(File file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Sorting by position stops a two-column sheet from being interleaved, and
            // the explicit separator means every visual line arrives on its own line,
            // which is what QuestionFileParser expects.
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            return stripper.getText(document);

        } catch (InvalidPasswordException passwordProtected) {
            throw new IOException("The PDF '" + file.getName() + "' is password-protected. "
                    + "Remove the password (or print it to a new PDF) and upload it again.",
                    passwordProtected);
        } catch (IOException e) {
            throw new IOException("The PDF '" + file.getName() + "' could not be read. It may be damaged: "
                    + describe(e), e);
        } catch (RuntimeException | StackOverflowError e) {
            // A malformed PDF can make PDFBox throw almost anything, including
            // unchecked exceptions and, on deeply nested content, a StackOverflowError.
            throw new IOException("The PDF '" + file.getName() + "' does not look like a valid PDF file: "
                    + describe(e), e);
        }
    }

    // ------------------------------------------------------------------
    // Word .docx
    // ------------------------------------------------------------------

    private static String readDocx(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath());
             XWPFDocument document = new XWPFDocument(in)) {

            StringBuilder text = new StringBuilder();
            // Walking the body elements rather than calling getParagraphs() keeps a
            // question sheet laid out in a table readable, which is a common way to
            // author them.
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    text.append(paragraph.getText()).append('\n');
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            text.append(cell.getText()).append('\n');
                        }
                    }
                }
            }
            return text.toString();

        } catch (IOException e) {
            throw new IOException("The Word file '" + file.getName() + "' could not be read. "
                    + "It may be damaged: " + describe(e), e);
        } catch (RuntimeException | StackOverflowError e) {
            // Thrown for files that are not OOXML at all - most often an old .doc that
            // has simply been renamed to .docx.
            throw new IOException("'" + file.getName() + "' does not look like a Word .docx document. "
                    + "If it is an older '.doc' file, open it in Word, choose 'Save As' and pick "
                    + "the '.docx' format, then upload it again.", e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String singleLine = message.replace('\n', ' ').strip();
        return singleLine.length() > 160 ? singleLine.substring(0, 160) + "\u2026" : singleLine;
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }
}
