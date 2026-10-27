package mail.mcp.cli;

import mail.mcp.config.JsonConfig;
import mail.mcp.config.MailMcpProperties;
import mail.mcp.convert.Pandoc;
import mail.mcp.convert.PandocException;
import mail.mcp.index.MailIndex;
import mail.mcp.index.MailIndexReader;
import mail.mcp.index.SearchField;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.store.MessageStore;
import mail.mcp.sync.MessageProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class MailReindexCommandTest {
    @TempDir Path temp;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @Test void reindexAppliesWordFormsAndRejectsAnInvalidFile() throws Exception {
        configuration();
        var meta = save("Subject: Forms\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nНесколько писем");
        new MessageProcessor(store(), new Pandoc("unused")).process(meta);
        assertThat(run()).isZero();
        try (var reader = MailIndexReader.open(temp.resolve("test")).orElseThrow()) {
            assertThat(reader.search("письмо", SearchField.BODY, null, 0, 20).total()).isZero();
        }
        var file = temp.resolve("word-forms.txt");
        Files.writeString(file, "письмо писем");
        assertThat(run()).isZero();
        try (var reader = MailIndexReader.open(temp.resolve("test"), mail.mcp.index.WordForms.load(file)).orElseThrow()) {
            assertThat(reader.wordFormsCurrent()).isTrue();
            assertThat(reader.search("письмо", SearchField.BODY, null, 0, 20).hits())
                    .extracting(hit -> hit.card().key()).containsExactly(meta.key());
        }
        Files.writeString(file, "invalid");
        Supplier<Pandoc> forbidden = () -> { throw new AssertionError("no pandoc or index access"); };
        // Holding the writer proves all three commands reject rules before trying the index.
        try (var writer = MailIndex.openWriter(temp.resolve("test"), false,
                new mail.mcp.index.WordForms(java.util.Map.of("письмо", "письм", "писем", "письм"), java.util.Map.of()))) {
            output.reset();
            assertThat(run(forbidden, "test")).isEqualTo(2);
            var properties = new MailMcpProperties(temp.toString(), null);
            var out = new PrintStream(output, true, StandardCharsets.UTF_8);
            assertThat(MailReprocessCommand.run(new String[] {"test"}, properties, out, forbidden,
                    key -> "SYNTHETIC_TEST_CREDENTIAL")).isEqualTo(2);
            assertThat(MailSyncCommand.run(new String[] {"test"}, properties, out,
                    definition -> { throw new AssertionError("no connection or sign-in"); }, forbidden,
                    key -> "SYNTHETIC_TEST_CREDENTIAL")).isEqualTo(2);
            assertThat(writer.keys()).containsExactly(meta.key());
        }
        assertThat(output().lines().filter(line -> line.startsWith("RESULT: FAILED (word-forms.txt line 1:")))
                .hasSize(3);
    }

    @Test void reprocessKeepsFailedMetadataWhenRulesRecreateIndex() throws Exception {
        configuration();
        var failed = save(null);
        save("Subject: Forms\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nНесколько писем");
        assertThat(run()).isZero();
        Files.writeString(temp.resolve("word-forms.txt"), "письмо писем");
        assertThat(MailReprocessCommand.run(new String[] {"test"}, new MailMcpProperties(temp.toString(), null),
                new PrintStream(output, true, StandardCharsets.UTF_8), () -> new Pandoc("unused"),
                key -> "SYNTHETIC_TEST_CREDENTIAL")).isZero();
        var forms = mail.mcp.index.WordForms.load(temp.resolve("word-forms.txt"));
        try (var reader = MailIndexReader.open(temp.resolve("test"), forms).orElseThrow()) {
            assertThat(reader.wordFormsCurrent()).isTrue();
            assertThat(reader.count("f1")).isEqualTo(2);
            assertThat(reader.search("письмо", SearchField.BODY, null, 0, 20).total()).isEqualTo(1);
        }
        assertThat(store().readMeta(failed.key())).contains(failed);
        assertThat(output()).contains("RESULT: OK (1 messages, 1 converted, 0 failed)");
        Files.delete(temp.resolve("word-forms.txt"));
        assertThat(MailReprocessCommand.run(new String[] {"test"}, new MailMcpProperties(temp.toString(), null),
                new PrintStream(output, true, StandardCharsets.UTF_8), () -> new Pandoc("unused"),
                key -> "SYNTHETIC_TEST_CREDENTIAL")).isZero();
        try (var reader = MailIndexReader.open(temp.resolve("test")).orElseThrow()) {
            assertThat(reader.wordFormsCurrent()).isTrue();
            assertThat(reader.count("f1")).isEqualTo(2);
            assertThat(reader.search("письмо", SearchField.BODY, null, 0, 20).total()).isZero();
        }
    }

    private void configuration() throws Exception {
        Files.writeString(temp.resolve("connections.json"), """
                {"connections":{"test":{"type":"imap","host":"imap.example.com",
                "username":"user@example.com","password":"${TEST_CREDENTIAL}"}}}
                """);
    }

    private int run(Supplier<Pandoc> locator, String... args) {
        return MailReindexCommand.run(args, new MailMcpProperties(temp.toString(), null),
                new PrintStream(output, true, StandardCharsets.UTF_8), locator,
                key -> "SYNTHETIC_TEST_CREDENTIAL");
    }

    private int run() { return run(() -> new Pandoc("unused"), "test"); }
    private String output() { return output.toString(StandardCharsets.UTF_8); }
    private MessageStore store() {
        return new MessageStore(temp.resolve("test"), new JsonConfig().mailMcpObjectMapper());
    }

    private MessageMeta save(String mime) throws Exception {
        var store = store();
        String key = store.newKey();
        var directory = store.createMessageDir(key);
        if (mime != null) { Files.writeString(directory.resolve(MessageStore.MESSAGE_FILE), mime); }
        var meta = new MessageMeta(key, "f1", "INBOX", key, 0, null, Instant.now(),
                mime == null ? MessageState.FAILED : MessageState.DOWNLOADED,
                mime == null ? "Synthetic failure" : null);
        store.writeMeta(meta);
        return meta;
    }

    @Test void reindexRebuildsHeadersAndBodyWithoutTheNetwork() throws Exception {
        configuration();
        var converted = save("Subject: Converted header\r\nFrom: Sender <sender@example.com>\r\n\r\nOriginal body");
        new MessageProcessor(store(), new Pandoc("unused")).process(converted);
        // Converted entries must load the saved body instead of reconverting the MIME.
        var bodyFile = store().messageDir(converted.key()).resolve(MessageStore.BODY_FILE);
        Files.writeString(bodyFile, "Preserved markdown body");
        var downloaded = save("Subject: Downloaded header\r\n\r\nDownloaded content");
        var failed = save(null);
        var failedConversion = save("Subject: Failed conversion\r\n\r\nExcluded content");
        store().writeMeta(new MessageMeta(failedConversion.key(), "f1", "INBOX", failedConversion.ref(),
                0, null, failedConversion.downloadedAt(), MessageState.FAILED, "Synthetic conversion failure"));
        String incomplete = store().newKey();
        store().createMessageDir(incomplete);
        try (var index = MailIndex.openWriter(temp.resolve("test"))) {
            index.put(converted, null, "Stale data", null);
            index.put(new MessageMeta(store().newKey(), "f1", "INBOX", "stale", 0, null, Instant.now(),
                    MessageState.DOWNLOADED, null), null, "Stale data", null);
        }
        byte[] mime = Files.readAllBytes(store().messageDir(converted.key()).resolve(MessageStore.MESSAGE_FILE));
        assertThat(run()).isZero();
        assertThat(output().lines().toList()).containsExactly("Mail reindex (offline)", "RESULT: OK (4 messages indexed)");
        assertThat(bodyFile).hasContent("Preserved markdown body");
        assertThat(Files.readAllBytes(store().messageDir(converted.key()).resolve(MessageStore.MESSAGE_FILE))).isEqualTo(mime);
        assertThat(store().messageDir(incomplete)).doesNotExist();
        assertThat(store().readMeta(downloaded.key()).orElseThrow().state()).isEqualTo(MessageState.CONVERTED);
        assertThat(store().readMeta(failed.key())).contains(failed);
        try (var reader = MailIndexReader.open(temp.resolve("test")).orElseThrow()) {
            assertThat(reader.current()).isTrue();
            assertThat(reader.count("f1")).isEqualTo(4);
            assertThat(reader.search("converted", SearchField.SUBJECT, null, 0, 20).hits())
                    .extracting(hit -> hit.card().key()).containsExactly(converted.key());
            assertThat(reader.search("preserved", SearchField.BODY, null, 0, 20).total()).isEqualTo(1);
            assertThat(reader.search("downloaded", SearchField.BODY, null, 0, 20).total()).isEqualTo(1);
            assertThat(reader.search("stale", SearchField.BODY, null, 0, 20).total()).isZero();
            assertThat(reader.search("excluded", SearchField.ANY, null, 0, 20).total()).isZero();
        }
    }

    @Test void lockedIndexExitsFive() throws Exception {
        configuration();
        try (var index = MailIndex.openWriter(temp.resolve("test"))) {
            var meta = save(null);
            index.put(meta, null, null, null);
            index.commit();
            assertThat(run()).isEqualTo(5);
            assertThat(index.keys()).containsExactly(meta.key());
        }
        assertThat(output()).contains("RESULT: FAILED (Connection is being synchronized by another process)");
    }

    @Test void missingPandocStopsBeforeTheIndex() throws Exception {
        configuration();
        assertThat(run(() -> { throw new PandocException("pandoc not found on PATH"); }, "test")).isEqualTo(2);
        assertThat(output()).contains("RESULT: FAILED (pandoc not found; install pandoc and put it on PATH)");
        assertThat(temp.resolve("test/index")).doesNotExist();
    }

    @Test void printsProgressEveryThousand() throws Exception {
        configuration();
        for (int i = 0; i < 1_001; i++) { save(null); }
        assertThat(run()).isZero();
        assertThat(output().lines().toList()).containsExactly("Mail reindex (offline)",
                "  1000 messages indexed", "RESULT: OK (1001 messages indexed)");
    }

    @Test void configurationAndStorageErrorsUseFixedMessages() throws Exception {
        assertThat(run(() -> { throw new AssertionError("Must not locate"); }, "--help")).isZero();
        assertThat(run(() -> new Pandoc("unused"))).isEqualTo(2);
        assertThat(run()).isEqualTo(2);
        configuration();
        assertThat(run(() -> new Pandoc("unused"), "unknown")).isEqualTo(2);
        Files.writeString(temp.resolve("connections.json"), "not JSON");
        assertThat(run()).isEqualTo(2);
        configuration();
        Files.createDirectories(temp.resolve("test"));
        Files.writeString(temp.resolve("test/index"), "SYNTHETIC_PRIVATE_VALUE");
        assertThat(run()).isEqualTo(4);
        assertThat(output()).contains("RESULT: FAILED (local storage error; details withheld)")
                .doesNotContain("SYNTHETIC_PRIVATE_VALUE");
    }
}
