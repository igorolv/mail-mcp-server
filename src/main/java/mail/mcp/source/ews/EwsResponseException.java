package mail.mcp.source.ews;

/**
 * A response message came back with {@code ResponseClass="Error"}, e.g.
 * {@code ErrorItemNotFound} or {@code ErrorServerBusy}. HTTP 200 alone does not mean success.
 */
public class EwsResponseException extends EwsException {

    private final String responseCode;

    public EwsResponseException(EwsOperation operation, String responseCode, String messageText) {
        super(operation.elementName() + " failed: " + responseCode
                + (messageText == null || messageText.isBlank() ? "" : " (" + messageText.strip() + ")"));
        this.responseCode = responseCode;
    }

    /** EWS {@code ResponseCode}, e.g. {@code ErrorServerBusy}. */
    public String responseCode() {
        return responseCode;
    }
}
