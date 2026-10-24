package mail.mcp.source.ews;

/** The server answered with a SOAP fault instead of a response message (often with HTTP 500). */
public class EwsSoapFaultException extends EwsException {

    private final String responseCode;

    public EwsSoapFaultException(EwsOperation operation, String faultCode, String faultString, String responseCode) {
        super(operation.elementName() + " failed with a SOAP fault: "
                + (responseCode != null ? responseCode : faultCode)
                + (faultString == null || faultString.isBlank() ? "" : " (" + faultString.strip() + ")"));
        this.responseCode = responseCode;
    }

    /** EWS {@code ResponseCode} from the fault detail, {@code null} when the fault has none. */
    public String responseCode() {
        return responseCode;
    }
}
