package mail.mcp.sync;

import mail.mcp.config.JsonConfig;
import mail.mcp.convert.Pandoc;
import mail.mcp.convert.XlsxText;
import mail.mcp.index.MailIndex;
import mail.mcp.index.MailIndexReader;
import mail.mcp.index.SearchField;
import mail.mcp.mime.MimeReader;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.store.MessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static mail.mcp.convert.PandocTest.pandocAvailable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MessageProcessorTest {
    @TempDir Path temp;

    private MessageStore store() { return new MessageStore(temp, new JsonConfig().mailMcpObjectMapper()); }
    private MessageProcessor processor() { return new MessageProcessor(store(), new Pandoc("pandoc")); }

    private MessageMeta message(String id) throws Exception {
        var store = store();
        String key = store.newKey();
        var directory = store.createMessageDir(key);
        try (var files = Files.list(Path.of("testdata/mime"))) {
            Files.copy(files.filter(p -> p.getFileName().toString().startsWith(id + "-"))
                    .filter(p -> p.toString().endsWith(".eml")).findFirst().orElseThrow(),
                    directory.resolve(MessageStore.MESSAGE_FILE));
        }
        var meta = new MessageMeta(key, "f1", "INBOX", "1", 12, "a".repeat(64), Instant.now(),
                MessageState.DOWNLOADED, null);
        store.writeMeta(meta);
        return meta;
    }

    @Test void reprocessingPreservesRemoteFingerprint() throws Exception {
        var old = message("M01");
        var fingerprint = new mail.mcp.model.MessageFingerprint("<remote@example.com>", 987654L,
                Instant.parse("2026-10-25T12:00:00Z"), "user@example.com", "Remote subject");
        var meta = new MessageMeta(old.key(), old.folderKey(), old.folder(), old.ref(), old.size(), old.sha256(),
                old.downloadedAt(), old.state(), old.error(), fingerprint);
        var converted = processor().process(meta);
        assertThat(converted.meta().fingerprint()).isEqualTo(fingerprint);
        assertThat(processor().process(converted.meta()).meta().fingerprint()).isEqualTo(fingerprint);
        assertThat(processor().load(converted.meta()).meta().fingerprint()).isEqualTo(fingerprint);
        Files.writeString(store().messageDir(meta.key()).resolve(MessageStore.MESSAGE_FILE),
                "Content-Type: multipart/mixed; boundary=missing\r\n\r\nnot a multipart");
        var failed = processor().process(converted.meta());
        assertThat(failed.meta().state()).isEqualTo(MessageState.FAILED);
        assertThat(failed.meta().fingerprint()).isEqualTo(fingerprint);
        assertThat(store().readMeta(meta.key()).orElseThrow().fingerprint()).isEqualTo(fingerprint);
    }

    @Test void plainMessageIsConvertedWithBodyAndAttachments() throws Exception {
        var meta = message("M01");
        byte[] original = Files.readAllBytes(store().messageDir(meta.key()).resolve(MessageStore.MESSAGE_FILE));
        var result = processor().process(meta);
        assertThat(result.meta().state()).isEqualTo(MessageState.CONVERTED);
        assertThat(result.meta().error()).isNull();
        assertThat(store().readMeta(meta.key())).contains(result.meta());
        assertThat(result.body()).contains("Проект макета");
        Path directory = store().messageDir(meta.key());
        assertThat(Files.readString(directory.resolve(MessageStore.BODY_FILE))).isEqualTo(result.body());
        assertThat(result.message().attachments()).hasSize(1);
        var attachment = result.message().attachments().getFirst();
        assertThat(Files.size(directory.resolve(MessageStore.ATTACHMENTS_DIR).resolve(attachment.file())))
                .isEqualTo(attachment.size());
        assertThat(Files.readAllBytes(directory.resolve(MessageStore.MESSAGE_FILE))).isEqualTo(original);
    }

    @Test void htmlOnlyMessageBecomesMarkdown() throws Exception {
        assumeTrue(pandocAvailable());
        var result = processor().process(message("M07"));
        assertThat(result.meta().state()).isEqualTo(MessageState.CONVERTED);
        Path expectedFile;
        try (var files = Files.list(Path.of("testdata/mime"))) {
            expectedFile = files.filter(p -> p.getFileName().toString().startsWith("M07-"))
                    .filter(p -> p.toString().endsWith(".expected.json")).findFirst().orElseThrow();
        }
        var body = new JsonConfig().mailMcpObjectMapper().readTree(Files.readString(expectedFile)).get("body");
        for (var text : body.get("textContains")) { assertThat(result.body()).contains(text.asString()); }
        for (var text : body.get("textNotContains")) { assertThat(result.body()).doesNotContain(text.asString()); }
    }

    @Test void unreadableMessageIsFailedAndKeepsTheOriginal() throws Exception {
        var meta = message("M01");
        var eml = store().messageDir(meta.key()).resolve(MessageStore.MESSAGE_FILE);
        Files.writeString(eml, "Content-Type: multipart/mixed; boundary=missing\r\n\r\nnot a multipart");
        byte[] original = Files.readAllBytes(eml);
        var result = processor().process(meta);
        assertThat(result.meta().state()).isEqualTo(MessageState.FAILED);
        assertThat(result.meta().error()).isEqualTo("MIME parsing failed (ParseException)");
        assertThat(result.message()).isNull();
        assertThat(result.body()).isNull();
        assertThat(store().readMeta(meta.key())).contains(result.meta());
        assertThat(Files.readAllBytes(eml)).isEqualTo(original);

        var html = message("M07");
        result = new MessageProcessor(store(), new Pandoc("nonexistent-pandoc-it05")).process(html);
        assertThat(result.meta().state()).isEqualTo(MessageState.FAILED);
        assertThat(result.meta().error()).isEqualTo("pandoc I/O failed");
    }

    @Test void reprocessingReplacesOldOutput() throws Exception {
        var meta = message("M01");
        processor().process(meta);
        Path directory = store().messageDir(meta.key());
        Files.writeString(directory.resolve(MessageStore.ATTACHMENTS_DIR).resolve("stale.txt"), "old");
        Files.writeString(directory.resolve(MessageStore.BODY_FILE), "old body");
        var result = processor().process(meta);
        assertThat(Files.readString(directory.resolve(MessageStore.BODY_FILE))).isEqualTo(result.body());
        assertThat(directory.resolve(MessageStore.ATTACHMENTS_DIR).resolve("stale.txt")).doesNotExist();
        // A later version without attachments must also remove all earlier output.
        Files.writeString(directory.resolve(MessageStore.MESSAGE_FILE), "Subject: Empty\r\n\r\nnew body");
        result = processor().process(meta);
        assertThat(result.body()).isEqualTo("new body");
        assertThat(directory.resolve(MessageStore.ATTACHMENTS_DIR)).doesNotExist();
    }

    @Test void loadReadsWithoutWriting() throws Exception {
        var processed = processor().process(message("M01"));
        Path directory = store().messageDir(processed.meta().key());
        Map<Path, String> before = snapshot(directory);
        var loaded = processor().load(processed.meta());
        assertThat(loaded).isEqualTo(processed);
        assertThat(snapshot(directory)).isEqualTo(before);
        Files.delete(directory.resolve(MessageStore.BODY_FILE));
        assertThat(processor().load(processed.meta()).body()).isNull();
        Files.writeString(directory.resolve(MessageStore.MESSAGE_FILE),
                "Content-Type: multipart/mixed; boundary=missing\r\n\r\nnot a multipart");
        before = snapshot(directory);
        loaded = processor().load(processed.meta());
        assertThat(loaded.meta()).isEqualTo(processed.meta());
        assertThat(loaded.message()).isNull();
        assertThat(loaded.body()).isNull();
        assertThat(snapshot(directory)).isEqualTo(before);
    }

    private MessageMeta attachmentsMessage() throws Exception {
        var meta = message("M01");
        Files.writeString(store().messageDir(meta.key()).resolve(MessageStore.MESSAGE_FILE), """
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary=parts

                --parts
                Content-Type: text/plain; charset=UTF-8

                Message body
                --parts
                Content-Type: text/plain; charset=UTF-8
                Content-Disposition: attachment; filename=first.txt

                First attachment
                --parts
                Content-Type: application/pdf
                Content-Disposition: attachment; filename=broken.pdf

                not a PDF
                --parts
                Content-Type: text/plain
                Content-Disposition: attachment; filename=last.txt

                Last attachment
                --parts--
                """);
        return meta;
    }

    @Test void attachmentMarkdownIsWrittenNextToTheFile() throws Exception {
        var result = processor().process(attachmentsMessage());
        var attachments = store().messageDir(result.meta().key()).resolve(MessageStore.ATTACHMENTS_DIR);
        assertThat(result.message().attachments().getFirst().charset()).isEqualTo("utf-8");
        assertThat(result.message().attachments().getLast().charset()).isNull();
        assertThat(Files.readString(attachments.resolve("1__first.txt.md"))).isEqualTo("First attachment");
        assertThat(Files.readString(attachments.resolve("3__last.txt.md"))).isEqualTo("Last attachment");
        assertThat(result.attachmentText()).isEqualTo("First attachment\n\nLast attachment");
    }

    @Test void failedAttachmentLeavesTheMessageConverted() throws Exception {
        var result = processor().process(attachmentsMessage());
        assertThat(result.meta().state()).isEqualTo(MessageState.CONVERTED);
        assertThat(result.meta().error()).isNull();
        assertThat(store().readMeta(result.meta().key())).contains(result.meta());
        var attachments = store().messageDir(result.meta().key()).resolve(MessageStore.ATTACHMENTS_DIR);
        assertThat(attachments.resolve("2__broken.pdf")).isRegularFile();
        assertThat(attachments.resolve("2__broken.pdf.md")).doesNotExist();
        assertThat(result.attachmentText()).contains("First attachment", "Last attachment");
    }

    @Test void loadReadsAttachmentMarkdown() throws Exception {
        var result = processor().process(attachmentsMessage());
        var directory = store().messageDir(result.meta().key());
        var attachments = directory.resolve(MessageStore.ATTACHMENTS_DIR);
        Files.writeString(attachments.resolve("1__first.txt.md"), "Saved Markdown");
        Files.delete(attachments.resolve("3__last.txt.md"));
        var before = snapshot(directory);
        var loaded = new MessageProcessor(store(), new Pandoc("unused")).load(result.meta());
        assertThat(loaded.attachmentText()).isEqualTo("Saved Markdown");
        assertThat(snapshot(directory)).isEqualTo(before);
        Files.delete(attachments.resolve("1__first.txt.md"));
        assertThat(processor().load(result.meta()).attachmentText()).isNull();
    }

    private MessageMeta archiveMessage() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("folder/entry.txt"));
            zip.write("Archive needle text".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var meta = message("M01");
        Files.writeString(store().messageDir(meta.key()).resolve(MessageStore.MESSAGE_FILE),
                "Content-Type: application/zip\r\nContent-Disposition: attachment; filename=archive.zip\r\n"
                        + "Content-Transfer-Encoding: base64\r\n\r\n"
                        + java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()));
        return meta;
    }

    @Test void archiveEntriesGetMarkdownAndText() throws Exception {
        var result = processor().process(archiveMessage());
        assertThat(result.meta().state()).isEqualTo(MessageState.CONVERTED);
        assertThat(result.message().attachments()).extracting(a -> a.file())
                .containsExactly("1__archive.zip", "2__entry.txt");
        assertThat(result.message().attachments().getLast().parent()).isEqualTo("1__archive.zip");
        var attachments = store().messageDir(result.meta().key()).resolve(MessageStore.ATTACHMENTS_DIR);
        assertThat(Files.readString(attachments.resolve("2__entry.txt.md"))).isEqualTo("Archive needle text");
        assertThat(attachments.resolve("1__archive.zip.md")).doesNotExist();
        assertThat(result.attachmentText()).isEqualTo("Archive needle text");
    }

    @Test void oversizedNestedXlsxKeepsBinaryWithoutMarkdown() throws Exception {
        byte[] workbook = oversizedWorkbook();
        var meta = workbookMessage(workbook, true);
        var directory = store().messageDir(meta.key());
        byte[] original = Files.readAllBytes(directory.resolve(MessageStore.MESSAGE_FILE));
        var result = processor().process(meta);
        assertThat(result.meta().state()).isEqualTo(MessageState.CONVERTED);
        assertThat(result.meta().error()).isNull();
        assertThat(store().readMeta(meta.key())).contains(result.meta());
        assertThat(result.message().attachments()).extracting(a -> a.file())
                .containsExactly("1__archive.zip", "2__cells.XLSX");
        var nested = result.message().attachments().getLast();
        assertThat(nested.parent()).isEqualTo("1__archive.zip");
        assertThat(nested.size()).isEqualTo(workbook.length);
        var attachments = directory.resolve(MessageStore.ATTACHMENTS_DIR);
        assertThat(Files.readAllBytes(attachments.resolve(nested.file()))).isEqualTo(workbook);
        assertThat(attachments.resolve(nested.file() + ".md")).doesNotExist();
        assertThat(result.attachmentText()).isNull();
        assertThat(Files.readAllBytes(directory.resolve(MessageStore.MESSAGE_FILE))).isEqualTo(original);
    }

    @Test void reprocessRemovesPreviouslyIndexedOversizedXlsxText() throws Exception {
        byte[] workbook = oversizedWorkbook();
        var meta = workbookMessage(workbook, false);
        var processed = processor().process(meta);
        var directory = store().messageDir(meta.key());
        var eml = directory.resolve(MessageStore.MESSAGE_FILE);
        byte[] original = Files.readAllBytes(eml);
        var emlTime = Files.getLastModifiedTime(eml);
        var attachment = processed.message().attachments().getFirst();
        var binary = directory.resolve(MessageStore.ATTACHMENTS_DIR).resolve(attachment.file());
        var sidecar = binary.resolveSibling(attachment.file() + ".md");
        // Simulate Markdown saved by the previous 20 MiB conversion policy.
        Files.writeString(sidecar, XlsxText.extract(binary));
        String oldText = Files.readString(sidecar);
        var sidecarTime = Files.getLastModifiedTime(sidecar);
        assertThat(processor().load(processed.meta()).attachmentText()).isEqualTo(oldText);
        try (var index = MailIndex.openWriter(temp, true)) {
            SyncRunner.restore(store(), index, processor());
        }
        try (var reader = MailIndexReader.open(temp).orElseThrow()) {
            assertThat(reader.current()).isTrue();
            assertThat(reader.search("oversizedneedle", SearchField.ATTACHMENT_TEXT, null, 0, 10).total()).isEqualTo(1);
        }
        assertThat(Files.readString(sidecar)).isEqualTo(oldText);
        assertThat(Files.getLastModifiedTime(sidecar)).isEqualTo(sidecarTime);

        var reprocessed = processor().process(processed.meta());
        assertThat(reprocessed.meta()).isEqualTo(processed.meta());
        assertThat(sidecar).doesNotExist();
        assertThat(reprocessed.attachmentText()).isNull();
        assertThat(processor().load(reprocessed.meta()).attachmentText()).isNull();
        assertThat(Files.readAllBytes(binary)).isEqualTo(workbook);
        assertThat(Files.readAllBytes(eml)).isEqualTo(original);
        assertThat(Files.getLastModifiedTime(eml)).isEqualTo(emlTime);
        try (var index = MailIndex.openWriter(temp)) {
            index.put(reprocessed.meta(), reprocessed.message(), reprocessed.body(), reprocessed.attachmentText());
            index.commit();
        }
        try (var reader = MailIndexReader.open(temp).orElseThrow()) {
            assertThat(reader.current()).isTrue();
            assertThat(reader.search("oversizedneedle", SearchField.ATTACHMENT_TEXT, null, 0, 10).total()).isZero();
        }
    }

    private MessageMeta workbookMessage(byte[] workbook, boolean nested) throws Exception {
        var meta = message("M01");
        byte[] attachment = nested ? mail.mcp.LegacyOfficeFixtures.zip("folder/cells.XLSX", workbook) : workbook;
        String type = nested ? "application/zip" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        String name = nested ? "archive.zip" : "cells.xlsx";
        Files.writeString(store().messageDir(meta.key()).resolve(MessageStore.MESSAGE_FILE),
                "Content-Type: " + type + "\r\nContent-Disposition: attachment; filename=" + name + "\r\n"
                        + "Content-Transfer-Encoding: base64\r\n\r\n"
                        + java.util.Base64.getEncoder().encodeToString(attachment));
        return meta;
    }

    private static byte[] oversizedWorkbook() throws Exception {
        int size = 5 * 1024 * 1024 + 1;
        var source = new ByteArrayOutputStream();
        try (var workbook = new XSSFWorkbook()) {
            workbook.createSheet("Boundary").createRow(0).createCell(0).setCellValue("oversizedneedle");
            workbook.write(source);
        }
        byte[] result = new byte[0];
        int padding = 0;
        for (int pass = 0; pass < 2; pass++) {
            var output = new ByteArrayOutputStream();
            try (var input = new ZipInputStream(new ByteArrayInputStream(source.toByteArray()));
                 var zip = new ZipOutputStream(output)) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    zip.putNextEntry(new ZipEntry(entry.getName()));
                    input.transferTo(zip);
                    zip.closeEntry();
                }
                byte[] bytes = new byte[padding];
                java.util.Arrays.fill(bytes, (byte) ' ');
                if (padding > 10) { System.arraycopy("<padding/>".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, bytes, 0, 10); }
                var crc = new CRC32();
                crc.update(bytes);
                var extra = new ZipEntry("padding.xml");
                extra.setMethod(ZipEntry.STORED);
                extra.setSize(bytes.length);
                extra.setCrc(crc.getValue());
                zip.putNextEntry(extra);
                zip.write(bytes);
                zip.closeEntry();
            }
            result = output.toByteArray();
            padding = size - result.length;
        }
        assertThat(result).hasSize(size);
        return result;
    }

    @Test void loadListsArchiveEntriesWithoutWriting() throws Exception {
        var result = processor().process(archiveMessage());
        var directory = store().messageDir(result.meta().key());
        var before = snapshot(directory);
        var loader = new MessageProcessor(store(), new Pandoc("unused"));
        assertThat(loader.load(result.meta())).isEqualTo(result);
        assertThat(snapshot(directory)).isEqualTo(before);
        // Old messages can list an entry before reprocessing has saved it or its Markdown.
        Files.delete(directory.resolve("attachments/2__entry.txt"));
        Files.delete(directory.resolve("attachments/2__entry.txt.md"));
        before = snapshot(directory);
        var loaded = loader.load(result.meta());
        assertThat(loaded.message()).isEqualTo(result.message());
        assertThat(loaded.attachmentText()).isNull();
        assertThat(snapshot(directory)).isEqualTo(before);
    }

    @Test void outlookEntriesGetMarkdownAndText() throws Exception {
        assumeTrue(pandocAvailable());
        for (String id : new String[]{"M09", "M10"}) {
            var meta = message(id);
            var directory = store().messageDir(meta.key());
            var eml = directory.resolve(MessageStore.MESSAGE_FILE);
            byte[] original = Files.readAllBytes(eml);
            var outer = MimeReader.read(eml, null);
            var processed = processor().process(meta);
            assertThat(processed.meta().state()).isEqualTo(MessageState.CONVERTED);
            assertThat(processed.meta().error()).isNull();
            assertThat(processed.body()).isEqualTo(outer.body());
            assertThat(processed.message().attachments()).hasSize(id.equals("M09") ? 4 : 2);
            var last = processed.message().attachments().getLast();
            Path markdown = directory.resolve(MessageStore.ATTACHMENTS_DIR).resolve(last.file() + ".md");
            assertThat(markdown).isRegularFile();
            assertThat(processed.attachmentText()).contains(Files.readString(markdown));
            Path containerText = directory.resolve("attachments/" + (id.equals("M09") ? "2__nested.msg.md" : "1__winmail.dat.md"));
            assertThat(containerText).isRegularFile();
            assertThat(processed.attachmentText()).contains(Files.readString(containerText));
            assertThat(Files.readAllBytes(eml)).isEqualTo(original);
            var before = snapshot(directory);
            assertThat(new MessageProcessor(store(), new Pandoc("unused")).load(processed.meta())).isEqualTo(processed);
            assertThat(snapshot(directory)).isEqualTo(before);
        }
    }

    @Test void legacyOfficeAttachmentsGetMarkdownAndText() throws Exception {
        var meta = message("M01");
        var directory = store().messageDir(meta.key());
        var eml = directory.resolve(MessageStore.MESSAGE_FILE);
        byte[] original = mail.mcp.LegacyOfficeFixtures.message();
        Files.write(eml, original);
        var emlTime = Files.getLastModifiedTime(eml);
        var result = processor().process(meta);
        assertThat(result.meta().state()).isEqualTo(MessageState.CONVERTED);
        assertThat(result.meta().error()).isNull();
        assertThat(result.body()).isEqualTo("Outer body");
        assertThat(result.message().attachments()).extracting(a -> a.file())
                .containsExactly("1__document.doc", "2__cells.xls", "3__slides.ppt", "4__broken.doc");
        var attachments = directory.resolve(MessageStore.ATTACHMENTS_DIR);
        for (var entry : result.message().attachments().subList(0, 3)) {
            var sidecar = attachments.resolve(entry.file() + ".md");
            assertThat(sidecar).isRegularFile();
            assertThat(result.attachmentText()).contains(Files.readString(sidecar));
        }
        assertThat(result.attachmentText()).contains("Договор документа", "Договор таблицы", "Отчёт презентации");
        assertThat(attachments.resolve("4__broken.doc")).isRegularFile();
        assertThat(attachments.resolve("4__broken.doc.md")).doesNotExist();
        assertThat(Files.readAllBytes(eml)).isEqualTo(original);
        assertThat(Files.getLastModifiedTime(eml)).isEqualTo(emlTime);
        var before = snapshot(directory);
        assertThat(new MessageProcessor(store(), new Pandoc("unused")).load(result.meta())).isEqualTo(result);
        assertThat(snapshot(directory)).isEqualTo(before);
    }

    private static Map<Path, String> snapshot(Path directory) throws Exception {
        Map<Path, String> result = new LinkedHashMap<>();
        try (var files = Files.walk(directory)) {
            for (var path : files.filter(Files::isRegularFile).sorted().toList()) {
                result.put(path, java.util.HexFormat.of().formatHex(Files.readAllBytes(path))
                        + Files.getLastModifiedTime(path));
            }
        }
        return result;
    }
}
