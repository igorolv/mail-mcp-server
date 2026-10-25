package mail.mcp.sync;

import mail.mcp.config.JsonConfig;
import mail.mcp.convert.Pandoc;
import mail.mcp.mime.MimeReader;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.store.MessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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
