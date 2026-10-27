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

    @McpTool(description = "Start a read-only mailbox synchronization in the background and return at once. " +
            "Use syncStatus to watch progress. A rejected sign-in requires fixing the connection locally and " +
            "running mail-sync in a terminal before another tool start is allowed.", generateOutputSchema = true,
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

    @McpTool(description = "Read progress of the current or last sync from local disk, without contacting " +
            "the mailbox. never means no recorded run; interrupted means its process exited. Reading tools " +
            "may show incomplete data while a sync runs; also check listFolders.synced.", generateOutputSchema = true,
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

