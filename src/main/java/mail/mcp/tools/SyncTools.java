package mail.mcp.tools;

import mail.mcp.api.FolderSyncInfo;
import mail.mcp.api.SyncStartResult;
import mail.mcp.api.SyncStatusResult;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.redaction.SecretRedactor;
import mail.mcp.store.MessageStore;
import mail.mcp.sync.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Background synchronization requests and local status, including cross-process progress. */
@Service("mailSyncTools")
public class SyncTools {
    private static final Logger log = LoggerFactory.getLogger(SyncTools.class);
    private final ConnectionRegistry registry;
    private final SyncService syncService;
    private final ObjectMapper mapper;
    private final SecretRedactor redactor;

    public SyncTools(ConnectionRegistry registry, SyncService syncService, ObjectMapper mapper) {
        this(registry, syncService, mapper, new SecretRedactor(false));
    }

    @Autowired
    public SyncTools(ConnectionRegistry registry, SyncService syncService, ObjectMapper mapper, SecretRedactor redactor) {
        this.registry = registry;
        this.syncService = syncService;
        this.mapper = mapper;
        this.redactor = redactor;
    }

    @McpTool(description = "Refresh one connection's local mail copy from the mail server, including new " +
            "messages, removals, moves and read/flag states. The remote mailbox is not changed. Call when " +
            "the user needs newer mail than the local copy. Runs in the background; the first sync can " +
            "take a long time.\n\n" +
            "status=started means launched, not finished. For started or alreadyRunning, check syncStatus " +
            "with the same connection while state is running; do not call sync again to check progress. " +
            "lockedByOtherProcess means another process holds the local write lock and this call started " +
            "no sync. That process may be an offline command; syncStatus reports sync runs only.\n\n" +
            "For refused, relay message to the user and do not retry: after a rejected sign-in, the user " +
            "must fix the credentials and run mail-sync in a terminal.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false))
    public SyncStartResult sync(@McpToolParam(description = "Connection name from listConnections") String connection) {
        long start = System.nanoTime();
        try {
            String name = registry.resolveName(connection);
            var result = syncService.start(name);
            String status = switch (result.status()) {
                case STARTED -> "started";
                case ALREADY_RUNNING -> "alreadyRunning";
                case LOCKED_BY_OTHER_PROCESS -> "lockedByOtherProcess";
                case REFUSED -> "refused";
            };
            ToolLogger.completed(log, "sync", start);
            return new SyncStartResult(name, status, redactor.text(result.message()));
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "sync", start, e.getClass().getSimpleName());
            throw e;
        }
    }

    @McpTool(description = "Read local progress or the result of the current or last sync; this does not " +
            "start a sync or contact the mail server. state=running means still working; completed means " +
            "the run finished; failed means the run stopped with an error; interrupted means the syncing " +
            "process ended before finishing; never means no sync is recorded.\n\n" +
            "While running, other tools may see incomplete results. Check state to decide whether to " +
            "keep waiting. The failed count is a count of messages: completed can still have failed>0. " +
            "Inspect error and signInRejected for a failed run. If signInRejected is true, tell the user " +
            "to fix credentials and run mail-sync in a terminal; repeated sync calls cannot recover it. " +
            "Timestamps describe this run, not a guarantee that the local mail is current now.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public SyncStatusResult syncStatus(@McpToolParam(description = "Connection name from listConnections") String connection) {
        long start = System.nanoTime();
        try {
            var definition = registry.definition(connection);
            var status = new MessageStore(definition.connectionDir(), mapper).readSyncStatus().orElse(null);
            SyncStatusResult result;
            if (status == null) {
                result = new SyncStatusResult(definition.name(), "never", null, null, 0, 0, 0, List.of(), null, false);
            } else {
                String state = status.state();
                if (state.equals("running") && !syncService.runningHere(definition.name())
                        && ProcessHandle.of(status.pid()).filter(ProcessHandle::isAlive).isEmpty()) {
                    state = "interrupted";
                }
                var folders = status.folders().stream().map(p -> new FolderSyncInfo(redactor.text(p.path()), p.downloaded(),
                        p.failed(), p.removed(), p.reset(), p.done(), p.rescanned())).toList();
                result = new SyncStatusResult(definition.name(), state, status.startedAt(), status.finishedAt(),
                        folders.stream().mapToInt(FolderSyncInfo::downloaded).sum(),
                        folders.stream().mapToInt(FolderSyncInfo::failed).sum(),
                        folders.stream().mapToInt(FolderSyncInfo::removed).sum(), folders,
                        redactor.text(status.error()), status.signInRejected());
            }
            ToolLogger.completed(log, "syncStatus", start);
            return result;
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "syncStatus", start, e.getClass().getSimpleName());
            throw e;
        }
    }
}

