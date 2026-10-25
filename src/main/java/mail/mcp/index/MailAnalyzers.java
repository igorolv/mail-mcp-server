package mail.mcp.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.pattern.PatternTokenizer;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.tartarus.snowball.ext.RussianStemmer;

import java.util.Map;
import java.util.regex.Pattern;

/** Shared indexing and query analysis for schema 2. */
public final class MailAnalyzers {
    private static final Analyzer ANALYZER = create();

    private MailAnalyzers() { }

    public static Analyzer analyzer() { return ANALYZER; }

    private static Analyzer create() {
        var stops = CharArraySet.copy(EnglishAnalyzer.getDefaultStopSet());
        stops.addAll(RussianAnalyzer.getDefaultStopSet());
        var english = new EnglishAnalyzer(stops);
        var exact = new Analyzer() {
            @Override protected TokenStreamComponents createComponents(String field) {
                var tokenizer = new KeywordTokenizer();
                return new TokenStreamComponents(tokenizer, new LowerCaseFilter(tokenizer));
            }
        };
        var parts = new Analyzer() {
            @Override protected TokenStreamComponents createComponents(String field) {
                var tokenizer = new PatternTokenizer(Pattern.compile("[^\\p{L}\\p{N}]+"), -1);
                return new TokenStreamComponents(tokenizer,
                        new SnowballFilter(new LowerCaseFilter(tokenizer), new RussianStemmer()));
            }
        };
        return new PerFieldAnalyzerWrapper(new RussianAnalyzer(), Map.of(
                "subject_en", english, "body_en", english,
                "fromAddress", exact, "toAddress", exact, "ccAddress", exact,
                "attachmentFile", exact, "attachmentName", parts));
    }
}
