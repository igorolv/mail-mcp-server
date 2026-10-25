package mail.mcp.convert;

import mail.mcp.model.AttachmentInfo;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static mail.mcp.convert.PandocTest.pandocAvailable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AttachmentTextTest {
    @TempDir Path dir;
    private final Pandoc pandoc = new Pandoc("pandoc");

    private AttachmentInfo info(Path file, String name, String type, String charset) throws Exception {
        return new AttachmentInfo(file.getFileName().toString(), name, type, Files.size(file), null, false, charset);
    }

    private String text(Path file) throws Exception {
        return AttachmentText.toMarkdown(file, info(file, file.getFileName().toString(), "application/octet-stream", null), pandoc)
                .orElseThrow();
    }

    @Test void pdfTextIsExtracted() throws Exception {
        Path file = dir.resolve("report.PDF");
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Attachment PDF text");
                content.endText();
            }
            doc.save(file.toFile());
        }
        assertThat(text(file)).contains("Attachment PDF text");
        assertThat(AttachmentText.toMarkdown(file, info(file, "part", "application/pdf", null), pandoc))
                .hasValueSatisfying(t -> assertThat(t).contains("Attachment PDF text"));
    }

    @Test void xlsxCellsAreExtracted() throws Exception {
        Path file = dir.resolve("cells.xlsx");
        try (var wb = new XSSFWorkbook(); var out = Files.newOutputStream(file)) {
            var sheet = wb.createSheet("Данные");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("Договоры");
            row.createCell(1).setCellValue(42);
            wb.write(out);
        }
        assertThat(text(file)).contains("--- Sheet: Данные ---", "Договоры | 42");
    }

    @Test void pptxSlideTextIsExtracted() throws Exception {
        Path file = dir.resolve("slides.pptx");
        try (var pptx = new XMLSlideShow(); var out = Files.newOutputStream(file)) {
            var slide = pptx.createSlide();
            slide.createTextBox().setText("Отчёт по проекту");
            var table = slide.createTable(1, 2);
            table.getCell(0, 0).setText("Cell one");
            table.getCell(0, 1).setText("Cell two");
            pptx.write(out);
        }
        assertThat(text(file)).contains("--- Slide 1 ---", "Отчёт по проекту", "Cell one | Cell two");
    }

    @Test void docxGoesThroughPandoc() throws Exception {
        assumeTrue(pandocAvailable());
        Path file = dir.resolve("document with spaces.docx");
        try (var doc = new XWPFDocument(); var out = Files.newOutputStream(file)) {
            doc.createParagraph().createRun().setText("Текст документа");
            doc.write(out);
        }
        assertThat(text(file)).contains("Текст документа");
    }

    @Test void csvAndHtmlAreDecodedWithTheirCharset() throws Exception {
        assumeTrue(pandocAvailable());
        for (String format : new String[]{"csv", "html"}) {
            Path file = dir.resolve("data." + format);
            String source = format.equals("csv") ? "Колонка;Цена\nДоговор;100\n" : "<p>Договор и цена</p>";
            Files.write(file, source.getBytes(Charset.forName("windows-1251")));
            assertThat(AttachmentText.toMarkdown(file, info(file, null, "text/" + format, "windows-1251"), pandoc))
                    .hasValueSatisfying(t -> assertThat(t).contains("Договор", format.equals("csv") ? "Цена" : "цена"));
        }
    }

    @Test void plainTextIsDecodedAsIs() throws Exception {
        Path file = dir.resolve("plain.txt");
        String source = "Привет\r\n  сохранённый текст  ";
        Files.write(file, source.getBytes(Charset.forName("windows-1251")));
        assertThat(AttachmentText.toMarkdown(file, info(file, null, "text/plain", "windows-1251"), pandoc)).contains(source);
        Files.writeString(file, source);
        assertThat(text(file)).isEqualTo(source);
        assertThat(AttachmentText.toMarkdown(file, info(file, "pdf", "text/plain", null), pandoc)).contains(source);
        Files.write(file, new byte[]{(byte) 0xff, 65});
        assertThat(text(file)).isEqualTo("\ufffdA");
    }

    @Test void unknownFormatHasNoText() throws Exception {
        for (String name : new String[]{"image.png", "archive.zip", "legacy.doc"}) {
            Path file = dir.resolve(name);
            Files.write(file, new byte[]{(byte) 0x89, 80, 78, 71});
            assertThat(AttachmentText.toMarkdown(file, info(file, name, "application/octet-stream", null), pandoc)).isEmpty();
        }
    }

    @Test void largerThanTheLimitHasNoText() throws Exception {
        Path file = dir.resolve("large.txt");
        try (var sparse = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            sparse.position(20L * 1024 * 1024);
            sparse.write(ByteBuffer.wrap(new byte[]{1}));
        }
        // Invalid charset would fail if the converter attempted to decode this file.
        assertThat(AttachmentText.toMarkdown(file, info(file, null, "text/plain", "invalid-charset"), pandoc)).isEmpty();
    }

    @Test void brokenFileHasNoText() throws Exception {
        assumeTrue(pandocAvailable());
        for (String name : new String[]{"broken.docx", "broken.pdf", "broken.xlsx", "broken.pptx"}) {
            Path file = dir.resolve(name);
            Files.writeString(file, "garbage bytes");
            assertThat(AttachmentText.toMarkdown(file, info(file, name, "application/octet-stream", null), pandoc)).isEmpty();
        }
    }

    @Test void blankTextHasNoText() throws Exception {
        Path file = dir.resolve("blank.txt");
        Files.writeString(file, " \t\r\n");
        assertThat(AttachmentText.toMarkdown(file, info(file, null, "text/plain", null), pandoc)).isEmpty();
        Path pdf = dir.resolve("blank.pdf");
        try (var doc = new PDDocument()) { doc.addPage(new PDPage()); doc.save(pdf.toFile()); }
        assertThat(AttachmentText.toMarkdown(pdf, info(pdf, null, "application/pdf", null), pandoc)).isEmpty();
        Path pptx = dir.resolve("blank.pptx");
        try (var doc = new XMLSlideShow(); var out = Files.newOutputStream(pptx)) { doc.createSlide(); doc.write(out); }
        assertThat(AttachmentText.toMarkdown(pptx, info(pptx, null, "application/octet-stream", null), pandoc)).isEmpty();
    }
}
