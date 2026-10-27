package mail.mcp.cli;

import mail.mcp.config.MailMcpProperties;
import mail.mcp.index.MailAnalyzers;
import mail.mcp.index.WordForms;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;

/** Offline analysis diagnostics; needs neither connections nor a local mail store. */
public final class MailAnalyzeCommand {
    public static final String MODE = "mail-analyze";
    private MailAnalyzeCommand() { }

    public static void main(String[] args) { System.exit(run(args)); }

    public static int run(String[] args) {
        try { return run(args, MailMcpProperties.fromEnvironment(System::getenv), System.out); }
        catch (IllegalArgumentException e) {
            System.out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 2;
        }
    }

    static int run(String[] args, MailMcpProperties properties, PrintStream out) {
        if (args.length == 0 || (args.length == 1 && List.of("--help", "-h").contains(args[0]))) {
            out.println("Usage: java -jar mail-mcp-server.jar mail-analyze <word>...");
            out.println("Offline; shows Russian analysis with optional user word forms.");
            return args.length == 1 ? 0 : 2;
        }
        try {
            var file = properties.resolvedWordFormsFile();
            var forms = WordForms.load(file);
            out.println("Word forms: " + file + (Files.exists(file) ? "" : " (none found; using Snowball)"));
            var analyzer = MailAnalyzers.analyzer(forms);
            try {
                for (String argument : args) {
                    var tokenizer = new StandardTokenizer();
                    tokenizer.setReader(new StringReader(argument));
                    try (var tokens = new LowerCaseFilter(tokenizer)) {
                        var token = tokens.addAttribute(CharTermAttribute.class);
                        tokens.reset();
                        while (tokens.incrementToken()) {
                            String word = token.toString();
                            try (var stems = analyzer.tokenStream("body", word)) {
                                var stem = stems.addAttribute(CharTermAttribute.class);
                                stems.reset();
                                if (!stems.incrementToken()) { out.println(word + " -> stop word"); }
                                else {
                                    Integer line = forms.source(word.replace('ё', 'е'));
                                    out.println(word + " -> " + stem + " (" + (line == null ? "Snowball"
                                            : file.getFileName() + " line " + line) + ")");
                                }
                                stems.end();
                            }
                        }
                        tokens.end();
                    }
                }
            } finally { if (!forms.empty()) { analyzer.close(); } }
        } catch (IllegalArgumentException e) {
            out.println("RESULT: FAILED (" + e.getMessage() + ")");
            return 2;
        } catch (IOException | UncheckedIOException e) {
            out.println("RESULT: FAILED (local file error; details withheld)");
            return 2;
        }
        out.println("RESULT: OK");
        return 0;
    }
}
