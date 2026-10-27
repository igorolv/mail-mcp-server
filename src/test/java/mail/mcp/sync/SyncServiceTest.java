package mail.mcp.sync;

import mail.mcp.config.JsonConfig;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.connections.ConnectionType;
import mail.mcp.connections.AuthKind;
import mail.mcp.connections.MailConnection;
import mail.mcp.connections.SecretUnavailableException;
import mail.mcp.connections.Secret;
import mail.mcp.convert.Pandoc;
import mail.mcp.convert.PandocException;
import mail.mcp.index.MailIndex;
import mail.mcp.source.Downloaded;
import mail.mcp.source.FolderChanges;
import mail.mcp.source.MailSource;
import mail.mcp.source.RemoteFolder;
import mail.mcp.source.imap.ImapException;
import mail.mcp.source.imap.ImapSettings;
import mail.mcp.store.MessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncServiceTest {
    @TempDir Path dir;
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger located = new AtomicInteger();
    private final AtomicInteger forgotten = new AtomicInteger();
    private final FakeMailSource fake = new FakeMailSource();

    private MessageStore store() { return new MessageStore(dir, new JsonConfig().mailMcpObjectMapper()); }

    private SyncService service(Supplier<MailSource> source, Supplier<Pandoc> pandoc) {
        Secret password = new Secret() {
            @Override public String value() { return "synthetic-test-value"; }
            @Override public void forget() { forgotten.incrementAndGet(); }
        };
        var definition = new ConnectionDefinition("local", null, ConnectionType.IMAP, AuthKind.APP_PASSWORD,
                null, new ImapSettings("127.0.0.1", 993, true, AuthKind.APP_PASSWORD, "user", password, null), dir, null);
        var registry = new ConnectionRegistry(List.of(definition), d -> new MailConnection(d, () -> {
            opened.incrementAndGet();
            return source.get();
        }));
        return new SyncService(registry, () -> { located.incrementAndGet(); return pandoc.get(); },
                new JsonConfig().mailMcpObjectMapper());
    }

    private void awaitDone(SyncService service) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (service.runningHere("local") && System.nanoTime() < deadline) { Thread.sleep(10); }
        assertThat(service.runningHere("local")).isFalse();
    }

    @Test void startRunsInTheBackgroundAndWritesCompletedStatus() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        fake.putFolder("inbox", "INBOX", "7", "1");
        var service = service(() -> blocking(entered, release), () -> new Pandoc("unused"));
        try {
            assertThat(service.start("local").status()).isEqualTo(SyncStart.Status.STARTED);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(service.runningHere("local")).isTrue();
            assertThat(store().readSyncStatus().orElseThrow().state()).isEqualTo("running");
            // A second service reads the status while this process holds the write lock.
            assertThat(service(() -> fake, () -> new Pandoc("unused")).start("local").status())
                    .isEqualTo(SyncStart.Status.LOCKED_BY_OTHER_PROCESS);
        } finally { release.countDown(); awaitDone(service); }
        assertThat(store().readSyncStatus().orElseThrow().state()).isEqualTo("completed");
        assertThat(store().keys()).hasSize(1);
    }

    @Test void secondStartWhileRunningIsAlreadyRunning() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var service = service(() -> blocking(entered, release), () -> new Pandoc("unused"));
        try {
            service.start("local");
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(service.start("local").status()).isEqualTo(SyncStart.Status.ALREADY_RUNNING);
            assertThat(opened).hasValue(1);
            assertThat(located).hasValue(1);
        } finally { release.countDown(); awaitDone(service); }
    }

    @Test void heldWriterLockIsLockedByOtherProcessWithoutOpeningTheSource() {
        var service = service(() -> fake, () -> new Pandoc("unused"));
        try (var writer = MailIndex.openWriter(dir)) {
            assertThat(service.start("local").status()).isEqualTo(SyncStart.Status.LOCKED_BY_OTHER_PROCESS);
            assertThat(opened).hasValue(0);
            assertThat(store().readSyncStatus()).isEmpty();
        }
    }

    @Test void rejectedSignInIsRecordedAndRefusesTheNextStart() throws Exception {
        var service = service(() -> new MailSource() {
            @Override public java.util.Map<String, mail.mcp.source.RemoteMessageInfo> metadata(RemoteFolder f, String c, java.util.Collection<String> r) {
                return fake.metadata(f, c, r);
            }
            @Override public List<RemoteFolder> folders() { throw new ImapException(ImapException.Kind.AUTHENTICATION); }
            @Override public FolderChanges changes(RemoteFolder f, String c, Set<String> known) { throw new AssertionError(); }
            @Override public Downloaded download(RemoteFolder f, String r, Path target) { throw new AssertionError(); }
            @Override public void close() { }
        }, () -> new Pandoc("unused"));
        assertThat(service.start("local").status()).isEqualTo(SyncStart.Status.STARTED);
        awaitDone(service);
        var status = store().readSyncStatus().orElseThrow();
        assertThat(status.state()).isEqualTo("failed");
        assertThat(status.signInRejected()).isTrue();
        assertThat(forgotten).as("a rejected password is not offered again").hasValue(1);
        // Even a fresh service cannot retry from MCP.
        var next = service(() -> { throw new AssertionError("source must not open"); }, () -> new Pandoc("unused"));
        assertThat(next.start("local").status()).isEqualTo(SyncStart.Status.REFUSED);
        assertThat(opened).hasValue(1);
        assertThat(located).hasValue(1);
        // A successful explicit runner (the CLI path) clears the rejected status.
        try (var index = MailIndex.openWriter(dir)) {
            new SyncRunner(store(), index, fake, new MessageProcessor(store(), new Pandoc("unused"))).run(p -> { });
        }
        assertThat(store().readSyncStatus().orElseThrow().signInRejected()).isFalse();
    }

    @Test void failedPasswordCommandIsRecordedButIsNotARejectedSignIn() throws Exception {
        var service = service(() -> new MailSource() {
            @Override public java.util.Map<String, mail.mcp.source.RemoteMessageInfo> metadata(RemoteFolder f, String c, java.util.Collection<String> r) {
                throw new AssertionError();
            }
            @Override public List<RemoteFolder> folders() { throw new SecretUnavailableException("local", "exited with code 1"); }
            @Override public FolderChanges changes(RemoteFolder f, String c, Set<String> known) { throw new AssertionError(); }
            @Override public Downloaded download(RemoteFolder f, String r, Path target) { throw new AssertionError(); }
            @Override public void close() { }
        }, () -> new Pandoc("unused"));
        assertThat(service.start("local").status()).isEqualTo(SyncStart.Status.STARTED);
        awaitDone(service);
        var status = store().readSyncStatus().orElseThrow();
        assertThat(status.state()).isEqualTo("failed");
        assertThat(status.signInRejected()).isFalse();
        assertThat(status.error()).contains("exited with code 1").contains("no sign-in was attempted");
        assertThat(forgotten).hasValue(0);
        var next = service(() -> fake, () -> new Pandoc("unused"));
        assertThat(next.start("local").status()).isEqualTo(SyncStart.Status.STARTED);
        awaitDone(next);
    }

    @Test void missingPandocFailsBeforeTheIndex() {
        var service = service(() -> fake, () -> { throw new PandocException("missing"); });
        assertThatThrownBy(() -> service.start("local")).isInstanceOf(IllegalStateException.class)
                .hasMessage("pandoc not found; install pandoc and put it on PATH");
        assertThat(dir.resolve("index")).doesNotExist();
        assertThat(dir.resolve(MessageStore.SYNC_STATUS_FILE)).doesNotExist();
        assertThat(opened).hasValue(0);
    }

    private MailSource blocking(CountDownLatch entered, CountDownLatch release) {
        return new MailSource() {
            @Override public java.util.Map<String, mail.mcp.source.RemoteMessageInfo> metadata(RemoteFolder f, String c, java.util.Collection<String> r) {
                return fake.metadata(f, c, r);
            }
            @Override public List<RemoteFolder> folders() {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) { throw new AssertionError("not released"); }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return fake.folders();
            }
            @Override public FolderChanges changes(RemoteFolder f, String c, Set<String> k) { return fake.changes(f, c, k); }
            @Override public Downloaded download(RemoteFolder f, String r, Path t) { return fake.download(f, r, t); }
            @Override public void close() { fake.close(); }
        };
    }
    private void withLogs(java.util.function.Consumer<ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>> test) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SyncService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try { test.accept(appender); }
        finally { logger.detachAppender(appender); appender.stop(); }
    }

    private List<ch.qos.logback.classic.spi.ILoggingEvent> summaries(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        return appender.list.stream().filter(e -> e.getMessage().startsWith("Background sync finished:")).toList();
    }

    @Test void backgroundCompletionLogsFinalTotalsOnce() {
        withLogs(logs -> {
            try {
                String[] refs = java.util.stream.IntStream.rangeClosed(1, 101).mapToObj(String::valueOf).toArray(String[]::new);
                fake.putFolder("first", "synthetic-folder", "7", refs);
                fake.putFolder("second", "other", "8", "one");
                fake.markTooLarge("second", "one");
                var service = service(() -> fake, () -> new Pandoc("unused"));
                service.start("local"); awaitDone(service);
                var events = summaries(logs);
                assertThat(events).hasSize(1);
                assertThat(events.getFirst().getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
                assertThat(events.getFirst().getArgumentArray()).containsExactly("completed", events.getFirst().getArgumentArray()[1], 101, 1, 0);
                fake.removeFolder("first"); fake.removeFolder("second");
                service.start("local"); awaitDone(service);
                assertThat(summaries(logs).getLast().getArgumentArray()).containsExactly("completed", summaries(logs).getLast().getArgumentArray()[1], 0, 0, 102);
                service.start("local"); awaitDone(service);
                assertThat(summaries(logs)).hasSize(3);
                assertThat(summaries(logs).getLast().getArgumentArray()).containsExactly("completed", summaries(logs).getLast().getArgumentArray()[1], 0, 0, 0);
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test void backgroundFailureLogsPartialTotalsWithoutMailData() {
        withLogs(logs -> {
            try {
                String[] refs = java.util.stream.IntStream.rangeClosed(1, 102).mapToObj(String::valueOf).toArray(String[]::new);
                fake.putFolder("synthetic-ref", "sentinel-mail-data", "7", refs);
                fake.failOnDownload(102);
                var service = service(() -> fake, () -> new Pandoc("unused"));
                service.start("local"); awaitDone(service);
                var events = summaries(logs);
                assertThat(events).hasSize(1);
                assertThat(events.getFirst().getArgumentArray()).containsExactly("failed", events.getFirst().getArgumentArray()[1], 100, 0, 0);
                assertThat(java.util.Arrays.toString(events.getFirst().getArgumentArray())).doesNotContain("sentinel", "synthetic", "local");
                assertThat(logs.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)).hasSize(1);
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test void nonStartedRunsDoNotLogCompletion() {
        withLogs(logs -> {
            try {
                var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                var service = service(() -> blocking(entered, release), () -> new Pandoc("unused"));
                try {
                    service.start("local");
                    assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(service.start("local").status()).isEqualTo(SyncStart.Status.ALREADY_RUNNING);
                    assertThat(service(() -> fake, () -> new Pandoc("unused")).start("local").status()).isEqualTo(SyncStart.Status.LOCKED_BY_OTHER_PROCESS);
                    assertThat(summaries(logs)).isEmpty();
                } finally { release.countDown(); awaitDone(service); }
                var rejected = new mail.mcp.model.SyncStatus("failed", 1, java.time.Instant.now(), java.time.Instant.now(), List.of(), "rejected", true);
                store().writeSyncStatus(rejected);
                assertThat(service.start("local").status()).isEqualTo(SyncStart.Status.REFUSED);
                assertThat(summaries(logs)).hasSize(1);
                store().writeSyncStatus(new mail.mcp.model.SyncStatus("completed", 1, java.time.Instant.now(), java.time.Instant.now(), List.of(), null, false));
                assertThatThrownBy(() -> service(() -> fake, () -> { throw new PandocException("missing"); }).start("local"))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(summaries(logs)).hasSize(1);
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

    @Test void closeFailureDoesNotLogSuccessfulCompletion() {
        withLogs(logs -> {
            try {
                var service = service(() -> new MailSource() {
                    @Override public java.util.Map<String, mail.mcp.source.RemoteMessageInfo> metadata(RemoteFolder f, String c, java.util.Collection<String> r) {
                        return fake.metadata(f, c, r);
                    }
                    @Override public List<RemoteFolder> folders() { return List.of(); }
                    @Override public FolderChanges changes(RemoteFolder f, String c, Set<String> k) { throw new AssertionError(); }
                    @Override public Downloaded download(RemoteFolder f, String r, Path t) { throw new AssertionError(); }
                    @Override public void close() { throw new IllegalStateException("sentinel-mail-data"); }
                }, () -> new Pandoc("unused"));
                service.start("local"); awaitDone(service);
                assertThat(store().readSyncStatus().orElseThrow().state()).isEqualTo("completed");
                assertThat(summaries(logs)).hasSize(1);
                assertThat(summaries(logs).getFirst().getArgumentArray()[0]).isEqualTo("failed");
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }

}
