package mail.mcp.spike;

import mail.mcp.config.JsonConfig;
import mail.mcp.config.MailMcpProperties;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionType;
import mail.mcp.connections.ConnectionsLoader;
import mail.mcp.connections.MailConnection;
import mail.mcp.connections.MailConnectionFactory;
import mail.mcp.source.ews.DistinguishedFolder;
import mail.mcp.source.ews.EwsAuthenticationException;
import mail.mcp.source.ews.EwsClient;
import mail.mcp.source.ews.EwsException;
import mail.mcp.source.ews.EwsFolder;
import mail.mcp.source.ews.EwsItemPage;
import mail.mcp.source.ews.EwsItemRef;
import mail.mcp.source.ews.EwsMimeItem;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The EWS spike of stage 1 (docs/plan.md, section 7, decision P8). <b>Run by the user only</b>: it
 * signs in to a real mailbox with the credentials from {@code connections.json}.
 *
 * <pre>
 * java -jar build\libs\mail-mcp-server.jar ews-spike [connection]
 * .\gradlew.bat ewsSpike --args="[connection]"
 * </pre>
 *
 * <p>Three read-only requests: {@code GetFolder} for the inbox, {@code FindItem} for the newest
 * inbox item, and {@code GetItem} with {@code IncludeMimeContent} for that item. The MIME is saved
 * as {@code <data-dir>/<connection>/spike/inbox-newest-<time>.eml}. The report shows counts, sizes,
 * timings and the HTTP request count — never the subject, addresses, body, host, login or password.
 *
 * <p>A rejected sign-in stops the spike at once: one attempt, no retries, because every failed
 * attempt counts toward the domain lockout policy.
 */
public final class EwsSpikeCommand {

    /** First program argument that selects this mode in the runnable jar. */
    public static final String MODE = "ews-spike";

    static final int OK = 0;
    static final int UNEXPECTED = 1;
    static final int CONFIG = 2;
    static final int SIGN_IN_REJECTED = 3;
    static final int EWS_FAILED = 4;

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private EwsSpikeCommand() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    public static int run(String[] args) {
        // Before the first logger exists: without Spring, logback would otherwise log to stdout at DEBUG.
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "logback-spike.xml");
        }
        PrintStream out = System.out;
        try {
            return run(args, MailMcpProperties.fromEnvironment(System::getenv), out);
        } catch (RuntimeException e) {
            out.println("RESULT: FAILED (unexpected " + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            return UNEXPECTED;
        }
    }

    static int run(String[] args, MailMcpProperties properties, PrintStream out) {
        if (args.length > 1 || (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h")))) {
            out.println("Usage: java -jar mail-mcp-server.jar " + MODE + " [connection]");
            out.println("Reads connections.json from " + MailMcpProperties.CONNECTIONS_FILE_ENV + ", or from "
                    + MailMcpProperties.DATA_DIR_ENV + " / ~/.mail-mcp-server. Signs in to a real mailbox.");
            return args.length > 1 ? CONFIG : OK;
        }

        out.println("EWS spike (read-only: GetFolder, FindItem, GetItem)");
        out.println("Connections file: " + properties.resolvedConnectionsFile());
        List<ConnectionDefinition> definitions;
        try {
            definitions = ConnectionsLoader.load(properties, new JsonConfig().mailMcpObjectMapper(), System::getenv);
        } catch (IllegalStateException e) {
            out.println("RESULT: FAILED (connections file: " + e.getMessage() + ")");
            return CONFIG;
        }
        ConnectionDefinition definition = choose(definitions, args.length == 1 ? args[0] : null, out);
        if (definition == null) {
            return CONFIG;
        }
        out.println("Connection: " + definition.name() + " (type " + definition.type().id() + ", auth "
                + definition.auth().id() + "; server address and credentials are not shown)");

        try (MailConnection connection = MailConnectionFactory.standard().create(definition)) {
            return spike(connection.ews(), definition.connectionDir(), out);
        }
    }

    private static ConnectionDefinition choose(List<ConnectionDefinition> definitions, String requested,
                                               PrintStream out) {
        List<ConnectionDefinition> ews = definitions.stream()
                .filter(d -> d.type() == ConnectionType.EWS)
                .toList();
        ConnectionDefinition chosen;
        if (requested != null) {
            chosen = definitions.stream().filter(d -> d.name().equals(requested)).findFirst().orElse(null);
            if (chosen == null) {
                out.println("RESULT: FAILED (no connection named '" + requested + "'; configured: "
                        + names(definitions) + ")");
                return null;
            }
        } else if (ews.size() == 1) {
            chosen = ews.getFirst();
        } else {
            out.println("RESULT: FAILED (name the connection: " + (ews.isEmpty()
                    ? "no connection of type ews is configured" : "ews connections: " + names(ews)) + ")");
            return null;
        }
        if (chosen.type() != ConnectionType.EWS) {
            out.println("RESULT: FAILED (connection '" + chosen.name() + "' is not of type ews)");
            return null;
        }
        if (!chosen.usable()) {
            out.println("RESULT: FAILED (connection '" + chosen.name() + "' is misconfigured: "
                    + chosen.configError() + ")");
            return null;
        }
        return chosen;
    }

    private static int spike(EwsClient ews, Path connectionDir, PrintStream out) {
        String step = "1/3 GetFolder inbox";
        try {
            long start = System.nanoTime();
            EwsFolder inbox = ews.getFolder(DistinguishedFolder.INBOX);
            out.println("Step " + step + ": OK in " + millis(start) + " ms, HTTP requests so far: "
                    + ews.requestCount());
            out.println("  folder: \"" + inbox.displayName() + "\", class " + inbox.folderClass()
                    + ", total " + inbox.totalCount() + ", unread " + inbox.unreadCount()
                    + ", subfolders " + inbox.childFolderCount());

            step = "2/3 FindItem newest inbox item";
            start = System.nanoTime();
            EwsItemPage page = ews.findNewestItems(DistinguishedFolder.INBOX, 1);
            out.println("Step " + step + ": OK in " + millis(start) + " ms, items in view: "
                    + page.totalItemsInView());
            if (page.items().isEmpty()) {
                out.println("RESULT: OK (signed in; the inbox is empty, nothing to download)");
                return OK;
            }
            EwsItemRef newest = page.items().getFirst();
            out.println("  newest item: received " + newest.dateTimeReceived() + ", IsRead " + newest.read());

            step = "3/3 GetItem with MIME";
            start = System.nanoTime();
            Path target = connectionDir.resolve("spike")
                    .resolve("inbox-newest-" + LocalDateTime.now().format(FILE_TIME) + ".eml");
            EwsMimeItem mime = ews.getItemMime(newest.id(), target);
            out.println("Step " + step + ": OK in " + millis(start) + " ms");
            out.println("  MIME: " + mime.bytes() + " bytes, SHA-256 " + mime.sha256());
            out.println("  IsRead after GetItem: " + mime.read() + " (before: " + newest.read() + ")");
            out.println("  saved to: " + mime.file().toAbsolutePath());
            out.println("HTTP requests in total: " + ews.requestCount());
            out.println("RESULT: OK");
            return OK;
        } catch (EwsAuthenticationException e) {
            out.println("Step " + step + ": SIGN-IN REJECTED");
            out.println("  " + e.getMessage());
            out.println("  HTTP requests sent: " + ews.requestCount() + ". The spike made one sign-in attempt and"
                    + " stops; check username, password, domain and auth in connections.json before the next run.");
            out.println("RESULT: FAILED (sign-in rejected)");
            return SIGN_IN_REJECTED;
        } catch (EwsException | java.io.UncheckedIOException e) {
            out.println("Step " + step + ": FAILED");
            out.println("  " + e.getClass().getSimpleName() + ": " + e.getMessage());
            out.println("  HTTP requests sent: " + ews.requestCount());
            out.println("RESULT: FAILED (" + e.getClass().getSimpleName() + ")");
            return EWS_FAILED;
        }
    }

    private static String names(List<ConnectionDefinition> definitions) {
        return definitions.isEmpty() ? "none"
                : String.join(", ", definitions.stream().map(ConnectionDefinition::name).toList());
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
