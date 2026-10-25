package mail.mcp.sync;

import mail.mcp.config.JsonConfig;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.connections.ConnectionType;
import mail.mcp.connections.AuthKind;
import mail.mcp.connections.MailConnection;
import mail.mcp.convert.Pandoc;
import mail.mcp.convert.PandocException;
import mail.mcp.index.MailIndex;
import mail.mcp.source.Downloaded;
import mail.mcp.source.FolderChanges;
import mail.mcp.source.MailSource;
import mail.mcp.source.RemoteFolder;
import mail.mcp.source.imap.ImapException;
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
    private final FakeMailSource fake = new FakeMailSource();

    private MessageStore store() { return new MessageStore(dir, new JsonConfig().mailMcpObjectMapper()); }

    private SyncService service(Supplier<MailSource> source, Supplier<Pandoc> pandoc) {
        var definition = new ConnectionDefinition("local", null, ConnectionType.IMAP, AuthKind.APP_PASSWORD,
                null, null, dir, null);
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
}
