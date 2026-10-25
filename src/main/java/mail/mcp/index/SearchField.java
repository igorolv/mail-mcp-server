package mail.mcp.index;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Public field groups supported by the search tool. */
public enum SearchField {
    ANY("any", "subject", "subject_en", "body", "body_en", "fromAddress", "fromName",
            "toAddress", "toName", "ccAddress", "ccName", "attachmentName", "attachmentFile",
            "attachmentText", "attachmentText_en"),
    SUBJECT("subject", "subject", "subject_en"),
    BODY("body", "body", "body_en"),
    FROM("from", "fromAddress", "fromName"),
    TO("to", "toAddress", "toName", "ccAddress", "ccName"),
    ATTACHMENT_NAME("attachmentName", "attachmentName", "attachmentFile"),
    ATTACHMENT_TEXT("attachmentText", "attachmentText", "attachmentText_en");

    private final String id;
    private final List<String> fields;

    SearchField(String id, String... fields) {
        this.id = id;
        this.fields = List.of(fields);
    }

    public String id() { return id; }
    public List<String> fields() { return fields; }

    public static SearchField of(String value) {
        if (value == null) { return ANY; }
        return Arrays.stream(values()).filter(field -> field.id.equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown field; use "
                        + String.join(", ", Stream.of(values()).map(SearchField::id).toList())));
    }
}
