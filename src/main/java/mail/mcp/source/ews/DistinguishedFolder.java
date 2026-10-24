package mail.mcp.source.ews;

/**
 * Well-known EWS folders addressed by name instead of a server-generated id. The names do not depend
 * on the mailbox language: {@code inbox} is "Входящие" on a Russian mailbox.
 */
public enum DistinguishedFolder {

    INBOX("inbox"),
    SENT_ITEMS("sentitems"),
    DRAFTS("drafts"),
    DELETED_ITEMS("deleteditems"),
    JUNK_EMAIL("junkemail"),
    OUTBOX("outbox"),
    /** Root of the message folder tree. */
    MSG_FOLDER_ROOT("msgfolderroot");

    private final String id;

    DistinguishedFolder(String id) {
        this.id = id;
    }

    /** Value of the {@code Id} attribute of {@code t:DistinguishedFolderId}. */
    public String id() {
        return id;
    }
}
