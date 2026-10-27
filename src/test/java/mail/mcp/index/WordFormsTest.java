package mail.mcp.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WordFormsTest {
    @TempDir Path dir;

    private WordForms load(String content) throws Exception {
        Path file = dir.resolve("custom.txt");
        Files.writeString(file, content);
        return WordForms.load(file);
    }

    @Test void parsesGroupsCommentsBomAndYo() throws Exception {
        var forms = load("\uFEFF# comment\n\u00A0\nПИСЬМО писем письмо # words\n\u00A0РЕБЁНОК\u00A0дети\u00A0\n");
        assertThat(forms.mapping()).containsEntry("письмо", "письм").containsEntry("писем", "письм")
                .containsEntry("ребенок", "ребенок").containsEntry("дети", "ребенок").hasSize(4);
        assertThat(forms.source("писем")).isEqualTo(3);
        assertThat(forms.source("дети")).isEqualTo(4);
        assertThat(forms.source("договор")).isNull();
    }

    @Test void rejectsNonLettersSingleWordGroupsAndCrossGroupRepeats() {
        assertThatThrownBy(() -> load("# comment\nword word2")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("custom.txt line 2: ").hasMessageContaining("letters only");
        for (String group : new String[] { "слово", "Ёлка елка ЁЛКА" }) {
            assertThatThrownBy(() -> load(group)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("custom.txt line 1: ").hasMessageContaining("two distinct words");
        }
        assertThatThrownBy(() -> load("письмо писем\n\nписем письма"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("custom.txt line 3: ")
                .hasMessageContaining("line 1");
    }

    @Test void hashIgnoresCommentsOrderAndSpacing() throws Exception {
        var first = load("письмо писем\nчеловек люди");
        var second = load("# comment\n человек\tлюди люди # comment\nписьмо   писем\n");
        assertThat(second.hash()).isEqualTo(first.hash()).matches("[0-9a-f]{64}");
        String lines = "люди\tчеловек\nписем\tписьм\nписьмо\tписьм\nчеловек\tчеловек\n";
        assertThat(first.hash()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(lines.getBytes(StandardCharsets.UTF_8))));
        assertThat(load("письмо письма").hash()).isNotEqualTo(first.hash());
        assertThat(load("писем письмо\nчеловек люди").hash()).isNotEqualTo(first.hash());
    }

    @Test void missingFileIsEmptyWithoutHash() throws Exception {
        assertThat(WordForms.load(dir.resolve("missing.txt"))).isEqualTo(WordForms.none());
        var empty = load("\uFEFF\n# comment only\n");
        assertThat(empty.empty()).isTrue();
        assertThat(empty.hash()).isNull();
        assertThat(dir.resolve("missing.txt")).doesNotExist();
    }

    @Test void filterPreservesKeywordsAndOffsets() throws Exception {
        var forms = load("письмо писем");
        var tokenizer = new org.apache.lucene.analysis.standard.StandardTokenizer();
        tokenizer.setReader(new java.io.StringReader("писем писем"));
        var keywords = new org.apache.lucene.analysis.TokenFilter(tokenizer) {
            private final org.apache.lucene.analysis.tokenattributes.KeywordAttribute keyword =
                    addAttribute(org.apache.lucene.analysis.tokenattributes.KeywordAttribute.class);
            private int position;
            @Override public boolean incrementToken() throws java.io.IOException {
                if (!input.incrementToken()) { return false; }
                keyword.setKeyword(position++ == 0);
                return true;
            }
        };
        try (var filter = new WordFormsFilter(keywords, forms)) {
            var term = filter.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
            var offset = filter.addAttribute(org.apache.lucene.analysis.tokenattributes.OffsetAttribute.class);
            var keyword = filter.addAttribute(org.apache.lucene.analysis.tokenattributes.KeywordAttribute.class);
            filter.reset();
            assertThat(filter.incrementToken()).isTrue();
            assertThat(term.toString()).isEqualTo("писем");
            assertThat(offset.startOffset()).isZero();
            assertThat(offset.endOffset()).isEqualTo(5);
            assertThat(filter.incrementToken()).isTrue();
            assertThat(term.toString()).isEqualTo("письм");
            assertThat(offset.startOffset()).isEqualTo(6);
            assertThat(offset.endOffset()).isEqualTo(11);
            assertThat(keyword.isKeyword()).isTrue();
            assertThat(filter.incrementToken()).isFalse();
            filter.end();
        }
    }
}
