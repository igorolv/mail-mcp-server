package mail.mcp.convert;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;

/** Adapted from redmine-mcp-server extraction/parser/PdfTextParser.java at f62b3e5. */
public final class PdfText {
    private PdfText() { }

    public static String extract(Path file) throws IOException {
        try (var doc = Loader.loadPDF(file.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            return text == null || text.isBlank() ? "" : text;
        }
    }
}
