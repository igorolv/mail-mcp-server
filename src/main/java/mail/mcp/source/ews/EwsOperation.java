package mail.mcp.source.ews;

/**
 * The allow-list of EWS operations this server may send (decision P4, research.md 1.2). All ten only
 * read: {@code Create}/{@code Update}/{@code Delete} entries in a sync response describe changes that
 * already happened, they are not commands.
 *
 * <p>The request writer takes the operation element name from this enum, so a write operation such
 * as {@code UpdateItem}, {@code MoveItem} or {@code SendItem} cannot even be expressed. Adding a
 * constant here needs a recorded decision in {@code docs/plan.md} section 10.
 */
public enum EwsOperation {

    GET_FOLDER("GetFolder"),
    FIND_FOLDER("FindFolder"),
    SYNC_FOLDER_HIERARCHY("SyncFolderHierarchy"),
    FIND_ITEM("FindItem"),
    SYNC_FOLDER_ITEMS("SyncFolderItems"),
    GET_ITEM("GetItem"),
    GET_ATTACHMENT("GetAttachment"),
    FIND_CONVERSATION("FindConversation"),
    GET_CONVERSATION_ITEMS("GetConversationItems"),
    CONVERT_ID("ConvertId");

    private final String elementName;

    EwsOperation(String elementName) {
        this.elementName = elementName;
    }

    /** Local name of the request element in the messages namespace, e.g. {@code GetFolder}. */
    public String elementName() {
        return elementName;
    }

    /** Value of the {@code SOAPAction} HTTP header. */
    public String soapAction() {
        return EwsXml.MESSAGES_NS + "/" + elementName;
    }
}
