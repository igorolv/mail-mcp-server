package mail.mcp.cli;

import mail.mcp.config.JsonConfig;
import mail.mcp.config.MailMcpProperties;
import mail.mcp.connections.ConnectionsLoader;
import mail.mcp.convert.Pandoc;
import mail.mcp.convert.PandocException;
import mail.mcp.index.IndexLockedException;
import mail.mcp.index.MailIndex;
import mail.mcp.index.WordForms;
import mail.mcp.mime.MimeReader;
import mail.mcp.store.MessageStore;
import mail.mcp.sync.MessageProcessor;
import mail.mcp.sync.SyncRunner;

import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** User-run offline index rebuild; never constructs a mailbox connection. */
public final class MailReindexCommand {
    public static final String MODE = "mail-reindex";
    private MailReindexCommand() { }

    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        MimeReader.configureAngus();
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "logback-cli.xml");
        }
        try {
            return run(args, MailMcpProperties.fromEnvironment(System::getenv), System.out,
                    Pandoc::locate, System::getenv);
        } catch (RuntimeException e) {
            System.out.println("RESULT: FAILED (unexpected local error; details withheld)");
            return 1;
        }
    }

    static int run(String[] args, MailMcpProperties properties, PrintStream out,
                   Supplier<Pandoc> pandocLocator, UnaryOperator<String> env) {
        if (args.length != 1 || List.of("--help", "-h").contains(args[0])) {
            out.println("Usage: java -jar mail-mcp-server.jar mail-reindex <connection>");
            out.println("Offline; rebuilds the index from messages already on disk.");
            return args.length == 1 ? 0 : 2;
        }
        out.println("Mail reindex (offline)");
        final WordForms forms;
        try { forms = WordForms.load(properties.resolvedWordFormsFile()); }
        catch (IllegalArgumentException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 2;
        } catch (UncheckedIOException e) {
            out.println("RESULT: FAILED (local storage error; details withheld)");
            return 4;
        }
        final mail.mcp.connections.ConnectionDefinition selected;
        try {
            selected = ConnectionsLoader.load(properties, new JsonConfig().mailMcpObjectMapper(), env)
                    .stream().filter(d -> d.name().equals(args[0])).findFirst().orElse(null);
        } catch (RuntimeException e) {
            out.println("RESULT: FAILED (invalid connections configuration; check the file locally)");
            return 2;
        }
        if (selected == null || !selected.usable()) {
            out.println("RESULT: FAILED (choose one valid connection by name)");
            return 2;
        }
        int[] count = { 0 };
        try {
            final Pandoc pandoc;
            try { pandoc = pandocLocator.get(); }
            catch (PandocException e) {
                out.println("RESULT: FAILED (pandoc not found; install pandoc and put it on PATH)");
                return 2;
            }
            try (var index = MailIndex.openWriter(selected.connectionDir(), true, forms)) {
                var store = new MessageStore(selected.connectionDir(), new JsonConfig().mailMcpObjectMapper());
                SyncRunner.restore(store, index, new MessageProcessor(store, pandoc), indexed -> {
                    count[0] = indexed;
                    if (indexed % 1_000 == 0) { out.println("  " + indexed + " messages indexed"); }
                });
            }
        } catch (IndexLockedException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 5;
        } catch (UncheckedIOException e) {
            out.println("RESULT: FAILED (local storage error; details withheld)");
            return 4;
        } catch (RuntimeException e) {
            out.println("RESULT: FAILED (unexpected local error; details withheld)");
            return 1;
        }
        out.println("RESULT: OK (" + count[0] + " messages indexed)");
        return 0;
    }
}
