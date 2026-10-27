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

    @McpTool(description = "Download new and changed mail into the local copy that all other tools read; " +
            "nothing in the mailbox is changed. Call when the user needs mail newer than the local copy. " +
            "Returns at once and runs in the background; poll syncStatus until it finishes. The first run " +
            "of a mailbox can take long. When status is refused, relay message to the user: after a " +
            "rejected sign-in only the user can recover, by fixing the credentials and running mail-sync " +
            "in a terminal.", generateOutputSchema = true,
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

    @McpTool(description = "Progress or result of the current or last sync of a connection, with counts " +
            "per folder. state: running, completed, failed, interrupted (the syncing process stopped " +
            "before finishing) or never (no sync yet). While running, other tools see partial data.",
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

