package mail.mcp.cli;

import mail.mcp.config.JsonConfig;
import mail.mcp.config.MailMcpProperties;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionType;
import mail.mcp.connections.ConnectionsLoader;
import mail.mcp.connections.MailConnection;
import mail.mcp.connections.MailConnectionFactory;
import mail.mcp.source.MailSource;
import mail.mcp.source.MessageUnavailableException;
import mail.mcp.source.imap.ImapException;
import mail.mcp.source.oauth.OAuthException;

import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/** User-run only: list sync folders, EXAMINE inbox UIDs, save one MIME with BODY.PEEK. */
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
        try (MailConnection connection = factory.create(selected); MailSource source = connection.openSource()) {
            var folders = source.folders();
            out.println("Step 1/3 LIST: OK, folders to sync: " + folders.size() + " (names hidden)");
            var inbox = folders.stream().filter(f -> f.remoteId().equalsIgnoreCase("INBOX")).findFirst().orElse(null);
            if (inbox == null) {
                out.println("RESULT: OK (signed in; inbox empty)");
                return 0;
            }
            var changes = source.changes(inbox, null, Set.of());
            out.println("Step 2/3 EXAMINE inbox: OK, messages: " + changes.added().size()
                    + ", cursor (UIDVALIDITY): " + changes.cursor());
            if (changes.added().isEmpty()) {
                out.println("RESULT: OK (signed in; inbox empty)");
                return 0;
            }
            var target = selected.connectionDir().resolve("spike").resolve("inbox-last-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".eml");
            var mime = source.download(inbox, changes.added().getLast(), target);
            out.println("Step 3/3 BODY.PEEK last message: OK, " + mime.size() + " bytes, SHA-256 " + mime.sha256());
            out.println("  saved to: " + target);
            out.println("RESULT: OK");
            return 0;
        } catch (OAuthException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 2;
        } catch (MessageUnavailableException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 4;
        } catch (ImapException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return e.kind() == ImapException.Kind.AUTHENTICATION ? 3 : 4;
        } catch (RuntimeException e) {
            out.println("RESULT: FAILED (unexpected local error; details withheld)");
            return 1;
        }
    }
}
