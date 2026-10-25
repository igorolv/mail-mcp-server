package mail.mcp.extraction.mime;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

/** Internal, unredacted offline data. Must never be used directly as an MCP response. */
public record ParsedMail(Headers headers, Body body, Part root, Status status,
                         List<Diagnostic> diagnostics, Path directory) {
    public ParsedMail { diagnostics = List.copyOf(diagnostics); }

    public enum Status { OK, PARTIAL, UNSUPPORTED, ENCRYPTED, LIMIT_EXCEEDED, PARSE_ERROR }

    public record Diagnostic(String code, String partPath) { }
    public record Header(String name, String rawBody, String value) { }
    public record Address(String name, String address) { }
    public record Headers(String subject, List<Address> from, List<Address> to, List<Address> cc,
                          List<Address> replyTo, OffsetDateTime date, String messageId,
                          String inReplyTo, List<String> references, List<Header> fields) {
        public Headers {
            from = List.copyOf(from); to = List.copyOf(to); cc = List.copyOf(cc);
            replyTo = List.copyOf(replyTo); references = List.copyOf(references); fields = List.copyOf(fields);
        }
    }
    public record Body(String partPath, String contentType, String charsetDeclared,
                       String charsetUsed, String text) { }
    /** storagePath always holds decoded original bytes, including unselected body representations. */
    public record Part(String path, String contentType, String disposition, String filename,
                       String contentId, String charsetDeclared, List<Header> headers,
                       Path storagePath, long size, String sha256, Status status,
                       String format, List<Part> children, ParsedMail nestedMessage) {
        public Part { headers = List.copyOf(headers); children = List.copyOf(children); }
    }
}
