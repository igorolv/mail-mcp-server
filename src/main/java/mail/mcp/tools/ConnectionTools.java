package mail.mcp.tools;

import mail.mcp.api.ConnectionInfo;
import mail.mcp.api.ListConnectionsResult;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.redaction.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovery for the {@code connection} argument every other tool will require. This is the first
 * call to make against an unfamiliar installation: "which mailboxes does this server read?".
 *
 * <p>Reads configuration and the local filesystem only — it never contacts a mail server, so it
 * works while a server is down and costs no sign-in attempt. No host, login or password is returned.
 *
 * <p>Modeled on jdbc-mcp-server {@code tools/ConnectionTools} @ {@code c2c4362}.
 */
@Service
public class ConnectionTools {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTools.class);

    private final ConnectionRegistry connections;
    private final SecretRedactor redactor;

    public ConnectionTools(ConnectionRegistry connections) {
        this(connections, new SecretRedactor(false));
    }

    @Autowired
    public ConnectionTools(ConnectionRegistry connections, SecretRedactor redactor) {
        this.connections = connections;
        this.redactor = redactor;
    }

    @McpTool(
            description = "Discover the mailboxes served here and the valid name to pass as 'connection'. Call " +
                    "when the target mailbox is not already established; returns purpose, source type, sign-in " +
                    "method and configuration errors without contacting any mail server.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ListConnectionsResult listConnections() {
        log.info("Tool call: listConnections");
        long start = System.nanoTime();
        List<ConnectionInfo> infos = new ArrayList<>();
        for (ConnectionDefinition definition : connections.definitions()) {
            infos.add(new ConnectionInfo(
                    definition.name(),
                    redactor.text(definition.description()),
                    definition.type() == null ? null : definition.type().id(),
                    definition.auth() == null ? null : definition.auth().id(),
                    definition.sourceImplemented(),
                    definition.hasLocalData(),
                    connections.isInitialized(definition.name()),
                    redactor.text(definition.configError())));
        }
        ToolLogger.completed(log, "listConnections", start);
        return new ListConnectionsResult(List.copyOf(infos));
    }
}
