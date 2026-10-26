package mail.mcp.index;

import jakarta.mail.MessagingException;
import mail.mcp.mime.MimeReader;
import mail.mcp.model.AttachmentInfo;
import mail.mcp.store.MessageStore;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Selects one passage from the stored body or a lazily read local attachment sidecar. */
final class SearchSnippets {
    private final Query query;
    private final UnaryOperator<String> outputText;
    private final UnifiedHighlighter candidates = highlighter(0);
    private final UnifiedHighlighter renderer = highlighter(1);

    SearchSnippets(Query query, UnaryOperator<String> outputText) {
        this.query = query;
        this.outputText = outputText == null ? UnaryOperator.identity() : outputText;
    }

    Result select(String key, String body, SearchField field, MessageStore store) throws IOException {
        if (field == SearchField.ANY && matches("body", body)) {
            return render("body", body, null, null);
        }
        if (store != null && (field == SearchField.ANY || field == SearchField.ATTACHMENT_TEXT)) {
            var directory = store.messageDir(key);
            final List<AttachmentInfo> attachments;
            try {
                attachments = MimeReader.read(directory.resolve(MessageStore.MESSAGE_FILE), null)
                        .message().attachments();
            } catch (MessagingException | IOException | RuntimeException e) {
                return render("body", body, null, null);
            }
            for (var attachment : attachments) {
                final String text;
                try {
                    text = Files.readString(directory.resolve(MessageStore.ATTACHMENTS_DIR)
                            .resolve(attachment.file() + ".md"), StandardCharsets.UTF_8);
                } catch (IOException e) { continue; }
                if (matches("attachmentText", text)) {
                    String name = attachment.name();
                    return render("attachmentText", text, attachment.file(),
                            name == null || name.isBlank() ? attachment.file() : name);
                }
            }
        }
        return render("body", body, null, null);
    }

    private boolean matches(String field, String text) throws IOException {
        return text != null && !text.isBlank()
                && candidates.highlightWithoutSearcher(field, query, text, 1) != null;
    }

    private Result render(String field, String raw, String file, String name) throws IOException {
        String text = raw == null ? null : outputText.apply(raw);
        if (text == null || text.isBlank()) { return new Result(null, null, null); }
        return new Result((String) renderer.highlightWithoutSearcher(field, query, text, 1), file, name);
    }

    private static UnifiedHighlighter highlighter(int fallbackPassages) {
        return UnifiedHighlighter.builderWithoutSearcher(MailAnalyzers.analyzer())
                .withFormatter(new DefaultPassageFormatter("**", "**", "… ", false))
                .withMaskedFieldsFunc(field -> switch (field) {
                    case "body" -> Set.of("body_en");
                    case "attachmentText" -> Set.of("attachmentText_en");
                    default -> Set.of();
                })
                .withMaxNoHighlightPassages(fallbackPassages)
                // Highlight eligible clauses even when required terms are in another saved text.
                .withWeightMatches(false)
                .withHighlightPhrasesStrictly(true)
                .withMaxLength(Integer.MAX_VALUE - 1)
                .build();
    }

    record Result(String text, String file, String name) { }
}
