package mail.mcp.index;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute;

import java.io.IOException;

/** Replaces listed words with their canonical stem while preserving source offsets. */
public final class WordFormsFilter extends TokenFilter {
    private final WordForms forms;
    private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
    private final KeywordAttribute keyword = addAttribute(KeywordAttribute.class);

    public WordFormsFilter(TokenStream input, WordForms forms) {
        super(input);
        this.forms = forms;
    }

    @Override public boolean incrementToken() throws IOException {
        if (!input.incrementToken()) { return false; }
        if (!keyword.isKeyword()) {
            String stem = forms.mapping().get(term.toString().replace('ё', 'е'));
            if (stem != null) {
                term.setEmpty().append(stem);
                keyword.setKeyword(true);
            }
        }
        return true;
    }
}
