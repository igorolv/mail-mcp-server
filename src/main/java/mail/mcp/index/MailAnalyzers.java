package mail.mcp.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.pattern.PatternTokenizer;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.tartarus.snowball.ext.RussianStemmer;

import java.util.Map;
import java.util.regex.Pattern;

/** Shared indexing, query and highlighting analysis. */
public final class MailAnalyzers {
    private static final Analyzer ANALYZER = create(WordForms.none());

    private MailAnalyzers() { }

    public static Analyzer analyzer() { return ANALYZER; }

    /** Nonempty rules create an analyzer owned by the caller. */
    public static Analyzer analyzer(WordForms forms) { return forms.empty() ? ANALYZER : create(forms); }

    private static Analyzer create(WordForms forms) {
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
                TokenStream stream = new LowerCaseFilter(tokenizer);
                if (!forms.empty()) { stream = new WordFormsFilter(stream, forms); }
                return new TokenStreamComponents(tokenizer,
                        new SnowballFilter(stream, new RussianStemmer()));
            }
        };
        var russianStops = new CharArraySet(0, false);
        for (var stop : RussianAnalyzer.getDefaultStopSet()) {
            String word = new String((char[]) stop);
            // Explicit user rules take precedence over the Russian stop list.
            if (!forms.mapping().containsKey(word.replace('ё', 'е'))) { russianStops.add(word); }
        }
        Analyzer russian = forms.empty() ? new RussianAnalyzer() : new Analyzer() {
            @Override protected TokenStreamComponents createComponents(String field) {
                var tokenizer = new StandardTokenizer();
                TokenStream stream = new StopFilter(new LowerCaseFilter(tokenizer), russianStops);
                stream = new WordFormsFilter(stream, forms);
                return new TokenStreamComponents(tokenizer, new SnowballFilter(stream, new RussianStemmer()));
            }

            @Override protected TokenStream normalize(String field, TokenStream input) {
                return new LowerCaseFilter(input);
            }
        };
        return new PerFieldAnalyzerWrapper(russian, Map.of(
                "subject_en", english, "body_en", english, "attachmentText_en", english,
                "fromAddress", exact, "toAddress", exact, "ccAddress", exact,
                "attachmentFile", exact, "attachmentName", parts));
    }
}
