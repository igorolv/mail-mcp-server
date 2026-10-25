package mail.mcp.cli;

import mail.mcp.config.JsonConfig;
import mail.mcp.config.MailMcpProperties;
import mail.mcp.connections.ConnectionDefinition;
import mail.mcp.connections.ConnectionsLoader;
import mail.mcp.connections.MailConnection;
import mail.mcp.connections.MailConnectionFactory;
import mail.mcp.index.IndexLockedException;
import mail.mcp.index.MailIndex;
import mail.mcp.convert.Pandoc;
import mail.mcp.convert.PandocException;
import mail.mcp.mime.MimeReader;
import mail.mcp.sync.MessageProcessor;
import mail.mcp.source.MailSource;
import mail.mcp.source.ews.EwsAuthenticationException;
import mail.mcp.source.ews.EwsException;
import mail.mcp.source.imap.ImapException;
import mail.mcp.source.oauth.OAuthException;
import mail.mcp.store.MessageStore;
import mail.mcp.model.FolderProgress;
import mail.mcp.sync.SyncRunner;
import mail.mcp.sync.SyncErrors;

import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.function.Supplier;

/** User-run only: synchronize one connection into local message directories and the catalog. */
public final class MailSyncCommand {
    public static final String MODE = "mail-sync";
    private MailSyncCommand() { }

    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        MimeReader.configureAngus();
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "logback-spike.xml");
        }
        try {
            return run(args, MailMcpProperties.fromEnvironment(System::getenv), System.out,
                    MailConnectionFactory.standard(), Pandoc::locate, System::getenv);
        } catch (RuntimeException e) {
            System.out.println("RESULT: FAILED (unexpected local error; details withheld)");
            return 1;
        }
    }

    static int run(String[] args, MailMcpProperties properties, PrintStream out,
                   MailConnectionFactory factory, Supplier<Pandoc> pandocLocator, UnaryOperator<String> env) {
        if (args.length != 1 || List.of("--help", "-h").contains(args[0])) {
            out.println("Usage: java -jar mail-mcp-server.jar mail-sync <connection>");
            out.println("Run by the user only; signs in to a real mailbox.");
            return args.length == 1 ? 0 : 2;
        }
        out.println("Mail sync (read-only; one sign-in attempt)");
        List<ConnectionDefinition> definitions;
        try {
            definitions = ConnectionsLoader.load(properties, new JsonConfig().mailMcpObjectMapper(), env);
        } catch (RuntimeException e) {
            out.println("RESULT: FAILED (invalid connections configuration; check the file locally)");
            return 2;
        }
        var selected = definitions.stream().filter(d -> d.name().equals(args[0])).findFirst().orElse(null);
        if (selected == null || !selected.usable()) {
            out.println("RESULT: FAILED (choose one valid connection by name)");
            return 2;
        }
        out.println("Connection: " + selected.name() + " (" + selected.type().id() + ", "
                + selected.auth().id() + "; address and credentials hidden)");
        List<FolderProgress> done = new ArrayList<>();
        final Pandoc pandoc;
        try { pandoc = pandocLocator.get(); }
        catch (PandocException e) {
            out.println("RESULT: FAILED (pandoc not found; install pandoc and put it on PATH)");
            return 2;
        }
        try (MailConnection connection = factory.create(selected)) {
            MailSource source = connection.openSource();
            try (MailIndex index = MailIndex.openWriter(selected.connectionDir()); source) {
                var store = new MessageStore(selected.connectionDir(), new JsonConfig().mailMcpObjectMapper());
                new SyncRunner(store, index, source, new MessageProcessor(store, pandoc)).run(progress -> {
                    out.println("  " + progress.path() + ": " + progress.downloaded() + " downloaded, "
                            + progress.failed() + " failed, " + progress.removed() + " removed"
                            + (progress.reset() ? ", folder reset" : "")
                            + (progress.done() ? "" : " (in progress)"));
                    if (progress.done()) { done.add(progress); }
                });
            }
        } catch (IndexLockedException e) {
            out.println("RESULT: FAILED (" + SyncErrors.describe(e) + ")");
            return 5;
        } catch (OAuthException e) {
            out.println("RESULT: FAILED (" + SyncErrors.describe(e) + ")");
            return 2;
        } catch (ImapException e) {
            out.println("RESULT: FAILED (" + SyncErrors.describe(e) + ")");
            return e.kind() == ImapException.Kind.AUTHENTICATION ? 3 : 4;
        } catch (EwsAuthenticationException e) {
            out.println("RESULT: FAILED (" + SyncErrors.describe(e) + ")");
            return 3;
        } catch (EwsException e) {
            out.println("RESULT: FAILED (" + SyncErrors.describe(e) + ")");
            return 4;
        } catch (UncheckedIOException e) {
            out.println("RESULT: FAILED (" + SyncErrors.describe(e) + ")");
            return 4;
        } catch (RuntimeException e) {
            out.println("RESULT: FAILED (" + SyncErrors.describe(e) + ")");
            return 1;
        }
        out.println("RESULT: OK (" + done.size() + " folders, "
                + done.stream().mapToInt(FolderProgress::downloaded).sum() + " downloaded, "
                + done.stream().mapToInt(FolderProgress::failed).sum() + " failed, "
                + done.stream().mapToInt(FolderProgress::removed).sum() + " removed)");
        return 0;
    }
}
