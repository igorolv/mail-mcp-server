package mail.mcp.extraction;

/** Contains a stable diagnostic code only; never includes input data or local paths. */
public final class ExtractionLimitException extends RuntimeException {
    private final String code;

    public ExtractionLimitException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
