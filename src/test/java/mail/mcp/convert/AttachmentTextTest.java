package mail.mcp.convert;

import mail.mcp.model.AttachmentInfo;
import mail.mcp.LegacyOfficeFixtures;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.record.crypto.Biff8EncryptionKey;
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
import static mail.mcp.OutlookFixtures.*;

class AttachmentTextTest {
    @TempDir Path dir;
    private final Pandoc pandoc = new Pandoc("pandoc");

    private AttachmentInfo info(Path file, String name, String type, String charset) throws Exception {
        return new AttachmentInfo(file.getFileName().toString(), name, type, Files.size(file), null, false, charset, null);
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

    @Test void nestedMessageBecomesHeadersAndBody() throws Exception {
        Path file = dir.resolve("forwarded.eml");
        String headers = "Subject: Forwarded subject\r\nFrom: Sender <sender@example.com>, bare@example.com\r\n"
                + "To: Reader <reader@example.com>\r\nCc: copy@example.com\r\n"
                + "Date: Mon, 26 Oct 2026 12:00:00 +0300\r\n";
        Files.writeString(file, headers + "Content-Type: text/plain; charset=UTF-8\r\n\r\nPlain body\r\n");
        assertThat(text(file)).isEqualTo("Subject: Forwarded subject\n"
                + "From: Sender <sender@example.com>, bare@example.com\nTo: Reader <reader@example.com>\n"
                + "Cc: copy@example.com\nDate: 2026-10-26T09:00:00Z\n\nPlain body\r\n");
        assertThat(AttachmentText.toMarkdown(file, info(file, "part", "message/rfc822", null), pandoc))
                .contains(text(file));
        assertThat(AttachmentText.toMarkdown(file, info(file, "forwarded.txt", "message/rfc822", null), pandoc))
                .contains(text(file));
        Files.writeString(file, "Content-Type: application/octet-stream\r\n\r\nbytes");
        assertThat(AttachmentText.toMarkdown(file, info(file, null, "message/rfc822", null), pandoc)).isEmpty();
        Files.writeString(file, "Subject: Only header\r\nContent-Type: application/octet-stream\r\n\r\nbytes");
        assertThat(text(file)).isEqualTo("Subject: Only header\n");
        if (pandocAvailable()) {
            Files.writeString(file, headers + "Content-Type: text/html; charset=UTF-8\r\n\r\n<p>HTML <b>body</b></p>");
            assertThat(text(file)).contains("Subject: Forwarded subject\n", "\n\nHTML **body**");
        }
    }

    @Test void msgBecomesHeadersAndSelectedBody() throws Exception {
        Path file = dir.resolve("headers.msg");
        Files.write(file, msg(root -> {
            properties(root, false, 1, 0x00390040, 134375652000000000L);
            string(root, 0x0037, "Учебная тема");
            string(root, 0x0C1A, "Отправитель");
            string(root, 0x0E04, "Читатель; Другой читатель");
            string(root, 0x0E03, "Копия");
            string(root, 0x1000, "Выбранный текст");
            string(root, 0x1013, "<p>Unselected HTML</p>");
            stream(root, "__substg1.0_10090102", rtf("{\\rtf1 Unselected RTF}"));
            attachment(root, 0, new Child("attachment.txt", "Excluded payload".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }));
        String result = text(file);
        assertThat(result).startsWith("Subject: Учебная тема\nFrom: Отправитель\nTo: Читатель; Другой читатель\nCc: Копия\nDate: ")
                .endsWith("\n\nВыбранный текст").doesNotContain("Unselected", "attachment.txt", "Excluded payload");
        assertThat(java.time.Instant.parse(result.substring(result.indexOf("Date: ") + 6, result.indexOf("\n\n"))))
                .isEqualTo(java.time.Instant.ofEpochSecond(13437565200L - 11644473600L));
        // Explicit code page for legacy MAPI strings, with independently missing headers.
        Files.write(file, msg(root -> {
            properties(root, false, 0, 0x3FFD0003, 1251);
            stream(root, "__substg1.0_0037001E", "Тема\0".getBytes(Charset.forName("windows-1251")));
            stream(root, "__substg1.0_1000001E", "Текст\0".getBytes(Charset.forName("windows-1251")));
        }));
        assertThat(text(file)).isEqualTo("Subject: Тема\n\nТекст");
        assertThat(AttachmentText.toMarkdown(file, info(file, "misleading.txt", "APPLICATION/VND.MS-OUTLOOK", null), pandoc))
                .contains("Subject: Тема\n\nТекст");
    }

    @Test void msgPlainBodyKeepsMessageCodepageWhenHtmlIsUtf8() throws Exception {
        for (String htmlType : new String[]{"001E", "0102"}) {
            Path file = dir.resolve("mixed-codepages-" + htmlType + ".msg");
            byte[] original = msg(root -> {
                properties(root, false, 0, 0x3FFD0003, 1251, 0x3FDE0003, 65001);
                stream(root, "__substg1.0_0037001E", "Тема сообщения\0".getBytes(Charset.forName("windows-1251")));
                stream(root, "__substg1.0_1000001E", "Сохранённый простой текст\0".getBytes(Charset.forName("windows-1251")));
                stream(root, "__substg1.0_1013" + htmlType,
                        "<p>Другой текст HTML</p>\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            });
            Files.write(file, original);
            var timestamp = Files.getLastModifiedTime(file);
            // Plain wins without invoking pandoc, even with a separately encoded HTML alternative.
            assertThat(AttachmentText.toMarkdown(file, info(file, null, "application/vnd.ms-outlook", null),
                    new Pandoc("unused-it15-pandoc")))
                    .contains("Subject: Тема сообщения\n\nСохранённый простой текст");
            assertThat(Files.readAllBytes(file)).isEqualTo(original);
            assertThat(Files.getLastModifiedTime(file)).isEqualTo(timestamp);
        }
    }

    @Test void msgHtmlAndRtfBodiesUsePandoc() throws Exception {
        assumeTrue(pandocAvailable());
        Path file = dir.resolve("body.msg");
        Files.write(file, msg(root -> {
            properties(root, false, 0, 0x3FDE0003, 1251);
            string(root, 0x1000, "   ");
            stream(root, "__substg1.0_10130102", "<p>Кириллица <b>HTML</b></p>".getBytes(Charset.forName("windows-1251")));
            stream(root, "__substg1.0_10090102", rtf("{\\rtf1 Unselected RTF}"));
        }));
        assertThat(text(file)).contains("Кириллица **HTML**").doesNotContain("Unselected");
        Files.write(file, msg(root -> {
            properties(root, false, 0);
            stream(root, "__substg1.0_10090102", rtf("{\\rtf1\\ansi\\uc1 \\u1058?\\u1077?\\u1082?\\u1089?\\u1090? \\b RTF\\b0}"));
        }));
        assertThat(text(file)).contains("Текст", "**RTF**").doesNotContain("\\rtf", "ansicpg");
    }

    @Test void tnefBodyBecomesMarkdown() throws Exception {
        assumeTrue(pandocAvailable());
        var attachments = dir.resolve("attachments");
        var parsed = mail.mcp.mime.MimeReader.read(fixture("M10"), attachments);
        var attachment = parsed.message().attachments().getFirst();
        var result = AttachmentText.toMarkdown(attachments.resolve(attachment.file()), attachment, pandoc).orElseThrow();
        assertThat(result).contains("Synthetic RTF body for the training layout.").doesNotContain("\\rtf", "\\ansi", "ansicpg");
        Path file = dir.resolve("WINMAIL.DAT");
        Files.write(file, tnef("Тема", "{\\rtf1 Readable body}"));
        assertThat(text(file)).isEqualTo("Subject: Тема\n\nReadable body\n");
        assertThat(AttachmentText.toMarkdown(file, info(file, "misleading.txt", "APPLICATION/VND.MS-TNEF", null), pandoc))
                .contains(text(file));
    }

    @Test void outlookConversionFailuresRemainBestEffort() throws Exception {
        for (String name : new String[]{"broken.msg", "broken.tnef", "winmail.dat"}) {
            Path file = dir.resolve(name);
            Files.writeString(file, "invalid");
            assertThat(AttachmentText.toMarkdown(file, info(file, name, "text/plain", null), pandoc)).isEmpty();
            try (var sparse = FileChannel.open(file, StandardOpenOption.WRITE)) {
                sparse.position(20L * 1024 * 1024);
                sparse.write(ByteBuffer.wrap(new byte[]{1}));
            }
            assertThat(AttachmentText.toMarkdown(file, info(file, name, "text/plain", null), pandoc)).isEmpty();
        }
        Path file = dir.resolve("headers.msg");
        Files.write(file, msg("Only subject", null));
        assertThat(text(file)).isEqualTo("Subject: Only subject\n");
        Files.write(file, msg(null, null));
        assertThat(AttachmentText.toMarkdown(file, info(file, null, "application/vnd.ms-outlook", null), pandoc)).isEmpty();
        Files.write(file, msg(root -> {
            properties(root, false, 0);
            string(root, 0x1013, "<p>HTML</p>");
        }));
        assertThat(AttachmentText.toMarkdown(file, info(file, null, "application/vnd.ms-outlook", null),
                new Pandoc("nonexistent-it15-pandoc"))).isEmpty();
    }

    @Test void unknownFormatHasNoText() throws Exception {
        for (String name : new String[]{"image.png", "archive.zip"}) {
            Path file = dir.resolve(name);
            Files.write(file, new byte[]{(byte) 0x89, 80, 78, 71});
            assertThat(AttachmentText.toMarkdown(file, info(file, name, "application/octet-stream", null), pandoc)).isEmpty();
        }
    }

    private String legacyText(byte[] data, String extension, String type) throws Exception {
        Path file = dir.resolve("legacy." + extension.toUpperCase(java.util.Locale.ROOT));
        Files.write(file, data);
        var timestamp = Files.getLastModifiedTime(file);
        String converted = text(file);
        assertThat(AttachmentText.toMarkdown(file, info(file, "part", type, null), new Pandoc("unused"))).contains(converted);
        assertThat(AttachmentText.toMarkdown(file, info(file, null, type, null), new Pandoc("unused"))).contains(converted);
        assertThat(Files.readAllBytes(file)).isEqualTo(data);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(timestamp);
        return converted;
    }

    @Test void docTextIsExtracted() throws Exception {
        byte[] data = LegacyOfficeFixtures.doc("Договор поставки\nEnglish paragraph");
        Path direct = dir.resolve("direct.doc");
        Files.write(direct, data);
        assertThat(DocText.extract(direct)).contains("Договор поставки");
        String converted = legacyText(data, "doc", "APPLICATION/MSWORD");
        assertThat(converted).contains("Договор поставки", "English paragraph");
    }

    @Test void xlsCellsAreExtracted() throws Exception {
        String converted = legacyText(LegacyOfficeFixtures.xls("Договор таблицы"), "xls", "APPLICATION/VND.MS-EXCEL");
        assertThat(converted).contains("--- Sheet: Данные ---", "Договор таблицы | 00042", "Second row", "SUM(1,2)",
                "--- Sheet: Другой лист ---", "English cell")
                .isEqualTo("--- Sheet: Данные ---\nДоговор таблицы | 00042\nSecond row\nSUM(1,2)\n\n"
                        + "--- Sheet: Другой лист ---\nEnglish cell\n\n");
    }

    @Test void pptSlideTextIsExtracted() throws Exception {
        String converted = legacyText(LegacyOfficeFixtures.ppt("Отчёт презентации"), "ppt", "APPLICATION/VND.MS-POWERPOINT");
        assertThat(converted).contains("--- Slide 1 ---", "Отчёт презентации", "Таблица | Table cell",
                "--- Slide 2 ---", "Second slide")
                .isEqualTo("--- Slide 1 ---\nОтчёт презентации\nТаблица | Table cell\n\n"
                        + "--- Slide 2 ---\nSecond slide\n\n");
    }

    @Test void xlsEmptySheetsHaveNoText() throws Exception {
        Path file = dir.resolve("empty-sheets.xls");
        for (String value : new String[]{null, "", " \t\r\n"}) {
            try (var workbook = new HSSFWorkbook(); var output = Files.newOutputStream(file)) {
                var sheet = workbook.createSheet("Empty sheet");
                if (value != null) {
                    var row = sheet.createRow(0);
                    row.createCell(0).setCellValue(value);
                    row.createCell(1); // A physically present blank cell is not text either.
                }
                workbook.createSheet("Another empty sheet");
                workbook.write(output);
            }
            var original = Files.readAllBytes(file);
            var timestamp = Files.getLastModifiedTime(file);
            assertThat(XlsText.extract(file)).isEmpty();
            assertThat(AttachmentText.toMarkdown(file, info(file, null, "application/vnd.ms-excel", null), pandoc)).isEmpty();
            assertThat(Files.readAllBytes(file)).isEqualTo(original);
            assertThat(Files.getLastModifiedTime(file)).isEqualTo(timestamp);
        }
    }

    @Test void pptEmptyTablesHaveNoText() throws Exception {
        Path file = dir.resolve("empty-tables.ppt");
        for (String value : new String[]{null, "", " \t\r\n"}) {
            try (var slides = new HSLFSlideShow(); var output = Files.newOutputStream(file)) {
                var table = slides.createSlide().createTable(2, 2);
                if (value != null) {
                    for (int row = 0; row < table.getNumberOfRows(); row++) {
                        for (int column = 0; column < table.getNumberOfColumns(); column++) {
                            table.getCell(row, column).setText(value);
                        }
                    }
                }
                slides.createSlide();
                slides.write(output);
            }
            var original = Files.readAllBytes(file);
            var timestamp = Files.getLastModifiedTime(file);
            assertThat(PptText.extract(file)).isEmpty();
            assertThat(AttachmentText.toMarkdown(file, info(file, null, "application/vnd.ms-powerpoint", null), pandoc)).isEmpty();
            assertThat(Files.readAllBytes(file)).isEqualTo(original);
            assertThat(Files.getLastModifiedTime(file)).isEqualTo(timestamp);
        }
    }

    @Test void legacyOfficeFailuresRemainBestEffort() throws Exception {
        for (String extension : new String[]{"doc", "xls", "ppt"}) {
            Path file = dir.resolve("broken." + extension);
            Files.writeString(file, "Malformed binary document");
            assertThat(AttachmentText.toMarkdown(file, info(file, null, "text/plain", null), pandoc)).isEmpty();
            try (var sparse = FileChannel.open(file, StandardOpenOption.WRITE)) {
                sparse.position(20L * 1024 * 1024);
                sparse.write(ByteBuffer.wrap(new byte[]{1}));
            }
            assertThat(AttachmentText.toMarkdown(file, info(file, null, "text/plain", null), pandoc)).isEmpty();
        }
        Path blank = dir.resolve("blank.doc");
        Files.write(blank, LegacyOfficeFixtures.doc(""));
        assertThat(AttachmentText.toMarkdown(blank, info(blank, null, "application/msword", null), pandoc)).isEmpty();
        blank = dir.resolve("blank.xls");
        try (var workbook = new HSSFWorkbook(); var output = Files.newOutputStream(blank)) { workbook.write(output); }
        assertThat(AttachmentText.toMarkdown(blank, info(blank, null, "application/vnd.ms-excel", null), pandoc)).isEmpty();
        blank = dir.resolve("blank.ppt");
        try (var slides = new HSLFSlideShow(); var output = Files.newOutputStream(blank)) { slides.write(output); }
        assertThat(AttachmentText.toMarkdown(blank, info(blank, null, "application/vnd.ms-powerpoint", null), pandoc)).isEmpty();

        Path encrypted = dir.resolve("encrypted.xls");
        // A fresh test-only key lives in memory; the reader never receives it.
        Biff8EncryptionKey.setCurrentUserPassword(java.util.UUID.randomUUID().toString());
        try (var workbook = new HSSFWorkbook(); var output = Files.newOutputStream(encrypted)) {
            workbook.createSheet("Synthetic").createRow(0).createCell(0).setCellValue("Encrypted content");
            workbook.write(output);
        } finally { Biff8EncryptionKey.setCurrentUserPassword(null); }
        var original = Files.readAllBytes(encrypted);
        var timestamp = Files.getLastModifiedTime(encrypted);
        assertThat(AttachmentText.toMarkdown(encrypted, info(encrypted, null, "application/vnd.ms-excel", null), pandoc)).isEmpty();
        assertThat(Files.readAllBytes(encrypted)).isEqualTo(original);
        assertThat(Files.getLastModifiedTime(encrypted)).isEqualTo(timestamp);

        Path container = dir.resolve("conflict.doc");
        Files.writeString(container, "Content-Type: text/plain; charset=UTF-8\r\n\r\nEML body");
        assertThat(AttachmentText.toMarkdown(container, info(container, null, "message/rfc822", null), pandoc)).contains("EML body");
        Files.write(container, msg("MSG subject", "MSG body"));
        assertThat(AttachmentText.toMarkdown(container, info(container, null, "application/vnd.ms-outlook", null), pandoc))
                .hasValueSatisfying(t -> assertThat(t).contains("MSG body"));
        Files.write(container, LegacyOfficeFixtures.zip("inside.xls", LegacyOfficeFixtures.xls("cell")));
        assertThat(AttachmentText.toMarkdown(container, info(container, null, "application/zip", null), pandoc)).isEmpty();
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
