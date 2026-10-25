package mail.mcp.cli;

import mail.mcp.config.JsonConfig;
import mail.mcp.config.MailMcpProperties;
import mail.mcp.connections.ConnectionsLoader;
import mail.mcp.convert.Pandoc;
import mail.mcp.convert.PandocException;
import mail.mcp.index.IndexLockedException;
import mail.mcp.index.MailIndex;
import mail.mcp.mime.MimeReader;
import mail.mcp.model.MessageState;
import mail.mcp.store.MessageStore;
import mail.mcp.sync.MessageProcessor;

import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** User-run offline conversion of local MIME files; never constructs a mailbox connection. */
public final class MailReprocessCommand {
    public static final String MODE = "mail-reprocess";
    private MailReprocessCommand() { }

    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        MimeReader.configureAngus();
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "logback-spike.xml");
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
            out.println("Usage: java -jar mail-mcp-server.jar mail-reprocess <connection>");
            out.println("Offline; processes only messages already on disk.");
            return args.length == 1 ? 0 : 2;
        }
        out.println("Mail reprocess (offline)");
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
        int count = 0;
        int converted = 0;
        Map<String, Integer> failures = new HashMap<>();
        try {
            final Pandoc pandoc;
            try { pandoc = pandocLocator.get(); }
            catch (PandocException e) {
                out.println("RESULT: FAILED (pandoc not found; install pandoc and put it on PATH)");
                return 2;
            }
            try (var index = MailIndex.openWriter(selected.connectionDir())) {
                var store = new MessageStore(selected.connectionDir(), new JsonConfig().mailMcpObjectMapper());
                var processor = new MessageProcessor(store, pandoc);
                for (String key : store.keys()) {
                    var meta = store.readMeta(key);
                    if (meta.isEmpty() || !Files.exists(store.messageDir(key).resolve(MessageStore.MESSAGE_FILE))) {
                        continue;
                    }
                    var processed = processor.process(meta.orElseThrow());
                    index.put(processed.meta(), processed.message(), processed.body());
                    count++;
                    if (processed.meta().state() == MessageState.CONVERTED) { converted++; }
                    else { failures.merge(processed.meta().error(), 1, Integer::sum); }
                    if (count % 100 == 0) { index.commit(); }
                    if (count % 1_000 == 0) { out.println("  " + count + " messages processed"); }
                }
                index.commit();
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
        failures.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> out.println("  failed: " + entry.getKey() + ": " + entry.getValue()));
        out.println("RESULT: OK (" + count + " messages, " + converted + " converted, "
                + (count - converted) + " failed)");
        return 0;
    }
}
