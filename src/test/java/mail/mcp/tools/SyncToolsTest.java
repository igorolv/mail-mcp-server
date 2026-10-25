package mail.mcp.tools;

import mail.mcp.config.JsonConfig;
import mail.mcp.connections.AuthKind;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.connections.ConnectionType;
import mail.mcp.model.FolderProgress;
import mail.mcp.model.SyncStatus;
import mail.mcp.store.MessageStore;
import mail.mcp.sync.SyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncToolsTest {
    @TempDir Path dir;

    private SyncTools tools() {
        var mapper = new JsonConfig().mailMcpObjectMapper();
        var registry = new ConnectionRegistry(List.of(new ConnectionDefinition("local", null,
                ConnectionType.IMAP, AuthKind.APP_PASSWORD, null, null, dir, null)),
                d -> { throw new AssertionError("no source"); });
        return new SyncTools(registry, new SyncService(registry,
                () -> { throw new AssertionError("no pandoc"); }, mapper), mapper);
    }

    @Test void syncStatusIsNeverWithoutAFile() {
        var result = tools().syncStatus("local");
        assertThat(result.state()).isEqualTo("never");
        assertThat(result.folders()).isEmpty();
        assertThat(result.downloaded()).isZero();
        assertThat(result.signInRejected()).isFalse();
    }

    @Test void syncStatusReportsInterruptedForADeadPid() throws Exception {
        Process child = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-version")
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        child.waitFor();
        var store = new MessageStore(dir, new JsonConfig().mailMcpObjectMapper());
        store.writeSyncStatus(new SyncStatus("running", child.pid(), Instant.now(), null,
                List.of(new FolderProgress("INBOX", 2, 1, 3, false, true)), null, false));
        var result = tools().syncStatus("local");
        assertThat(result.state()).isEqualTo("interrupted");
        assertThat(result.downloaded()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.removed()).isEqualTo(3);
        assertThat(store.readSyncStatus().orElseThrow().state()).isEqualTo("running");
    }
}
