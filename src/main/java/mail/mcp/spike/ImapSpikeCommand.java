package mail.mcp.spike;

import mail.mcp.config.JsonConfig;
import mail.mcp.config.MailMcpProperties;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionType;
import mail.mcp.connections.ConnectionsLoader;
import mail.mcp.connections.MailConnection;
import mail.mcp.connections.MailConnectionFactory;
import mail.mcp.source.imap.ImapClient;
import mail.mcp.source.imap.ImapException;
import mail.mcp.source.oauth.OAuthException;

import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.UnaryOperator;

/** User-run only: list folders, EXAMINE inbox, save one MIME with BODY.PEEK, compare flags. */
public final class ImapSpikeCommand {
    public static final String MODE = "imap-spike";
    private ImapSpikeCommand() { }

    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "logback-spike.xml");
        }
        return run(args, MailMcpProperties.fromEnvironment(System::getenv), System.out,
                MailConnectionFactory.standard(), System::getenv);
    }

    static int run(String[] args, MailMcpProperties properties, PrintStream out,
                   MailConnectionFactory factory, UnaryOperator<String> env) {
        if (args.length > 1 || (args.length == 1 && List.of("--help", "-h").contains(args[0]))) {
            out.println("Usage: java -jar mail-mcp-server.jar imap-spike [connection]");
            out.println("Run by the user only; signs in to a real mailbox.");
            return args.length > 1 ? 2 : 0;
        }
        out.println("IMAP spike (read-only: LIST, EXAMINE, BODY.PEEK; one sign-in attempt)");
        List<ConnectionDefinition> definitions;
        try {
            definitions = ConnectionsLoader.load(properties, new JsonConfig().mailMcpObjectMapper(), env);
        } catch (RuntimeException e) {
            // Loader errors may include configuration values. Inspect the file locally.
            out.println("RESULT: FAILED (invalid connections configuration; check the file locally)");
            return 2;
        }
        var imap = definitions.stream().filter(d -> d.type() == ConnectionType.IMAP).toList();
        var selected = args.length == 1
                ? imap.stream().filter(d -> d.name().equals(args[0])).findFirst().orElse(null)
                : imap.size() == 1 ? imap.getFirst() : null;
        if (selected == null || !selected.usable()) {
            out.println("RESULT: FAILED (choose one valid IMAP connection by name)");
            return 2;
        }
        out.println("Connection: " + selected.name() + " (imap, " + selected.auth().id() + "; address and credentials hidden)");
        try (MailConnection connection = factory.create(selected)) {
            var client = connection.imap();
            var folders = client.listFolders();
            out.println("Step 1/2 LIST: OK, folders: " + folders.size() + " (names hidden)");
            var target = selected.connectionDir().resolve("spike").resolve("inbox-last-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".eml");
            var item = client.downloadLastMessage("INBOX", target, ImapClient.DEFAULT_MAX_MIME_BYTES);
            if (item.isEmpty()) {
                out.println("RESULT: OK (signed in; inbox empty)");
                return 0;
            }
            var mime = item.orElseThrow();
            out.println("Step 2/2 EXAMINE inbox and BODY.PEEK: OK");
            out.println("  UIDVALIDITY: " + mime.uidValidity() + ", UID: " + mime.uid());
            out.println("  MIME: " + mime.bytes() + " bytes, SHA-256 " + mime.sha256());
            out.println("  Seen after download: " + mime.seenAfter() + " (before: " + mime.seenBefore() + ")");
            out.println("  saved to: " + mime.file());
            out.println("RESULT: OK");
            return 0;
        } catch (OAuthException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 2;
        } catch (ImapException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return e.kind() == ImapException.Kind.AUTHENTICATION ? 3 : 4;
        } catch (RuntimeException e) {
            out.println("RESULT: FAILED (unexpected local error; details withheld)");
            return 1;
        }
    }
}
