package mail.mcp.source.ews;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Thin read-only EWS client of one mailbox (decision P1). It builds SOAP requests for allow-listed
 * operations only ({@link EwsOperation}) and parses responses as a stream.
 *
 * <p>So far it covers what the spike needs: {@code GetFolder}, a one-page {@code FindItem} and
 * {@code GetItem} with MIME; step 7.3 of docs/plan.md adds what sync needs. Calls are sequential: one request at a time per mailbox (research.md
 * 1.2, throttling). Errors are never retried here; in particular an
 * {@link EwsAuthenticationException} must reach the caller after a single sign-in attempt.
 */
public final class EwsClient implements AutoCloseable {

    /** Largest decoded MIME accepted from {@code GetItem}: the EWS request limit of Exchange 2013. */
    public static final long DEFAULT_MAX_MIME_BYTES = 64L * 1024 * 1024;

    private final EwsTransport transport;
    private final long maxMimeBytes;

    public EwsClient(EwsTransport transport) {
        this(transport, DEFAULT_MAX_MIME_BYTES);
    }

    public EwsClient(EwsTransport transport, long maxMimeBytes) {
        this.transport = transport;
        this.maxMimeBytes = maxMimeBytes;
    }

    /** {@code GetFolder} with the {@code Default} shape plus {@code FolderClass}. */
    public EwsFolder getFolder(DistinguishedFolder folder) {
        byte[] request = EwsXml.envelope(EwsOperation.GET_FOLDER, w -> {
            w.writeStartElement("m", "FolderShape", EwsXml.MESSAGES_NS);
            EwsXml.typesElement(w, "BaseShape", "Default");
            w.writeStartElement("t", "AdditionalProperties", EwsXml.TYPES_NS);
            EwsXml.fieldUri(w, "folder:FolderClass");
            w.writeEndElement();
            w.writeEndElement();
            w.writeStartElement("m", "FolderIds", EwsXml.MESSAGES_NS);
            EwsXml.distinguishedFolderId(w, folder);
            w.writeEndElement();
        });
        return transport.exchange(EwsOperation.GET_FOLDER, request, EwsResponseReader::readFolder);
    }

    /**
     * The newest items of a folder: one {@code FindItem} page sorted by {@code DateTimeReceived}
     * descending, with ids, receive time and {@code IsRead}.
     */
    public EwsItemPage findNewestItems(DistinguishedFolder folder, int maxEntries) {
        if (maxEntries < 1 || maxEntries > 1000) {
            throw new IllegalArgumentException("maxEntries must be 1..1000, got " + maxEntries);
        }
        byte[] request = EwsXml.envelope(EwsOperation.FIND_ITEM, w -> {
            w.writeAttribute("Traversal", "Shallow");
            w.writeStartElement("m", "ItemShape", EwsXml.MESSAGES_NS);
            EwsXml.typesElement(w, "BaseShape", "IdOnly");
            w.writeStartElement("t", "AdditionalProperties", EwsXml.TYPES_NS);
            EwsXml.fieldUri(w, "item:DateTimeReceived");
            EwsXml.fieldUri(w, "message:IsRead");
            w.writeEndElement();
            w.writeEndElement();
            w.writeEmptyElement("m", "IndexedPageItemView", EwsXml.MESSAGES_NS);
            w.writeAttribute("MaxEntriesReturned", Integer.toString(maxEntries));
            w.writeAttribute("Offset", "0");
            w.writeAttribute("BasePoint", "Beginning");
            w.writeStartElement("m", "SortOrder", EwsXml.MESSAGES_NS);
            w.writeStartElement("t", "FieldOrder", EwsXml.TYPES_NS);
            w.writeAttribute("Order", "Descending");
            EwsXml.fieldUri(w, "item:DateTimeReceived");
            w.writeEndElement();
            w.writeEndElement();
            w.writeStartElement("m", "ParentFolderIds", EwsXml.MESSAGES_NS);
            EwsXml.distinguishedFolderId(w, folder);
            w.writeEndElement();
        });
        return transport.exchange(EwsOperation.FIND_ITEM, request, EwsResponseReader::readItemPage);
    }

    /**
     * {@code GetItem} with {@code IncludeMimeContent}: decodes the MIME of one item into
     * {@code target}. The file is written next to the target first and moved into place only after
     * the whole response was read, so a failed call leaves no partial {@code .eml}. {@code GetItem}
     * does not mark the item as read (research.md 1.2).
     */
    public EwsMimeItem getItemMime(String itemId, Path target) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId is required");
        }
        byte[] request = EwsXml.envelope(EwsOperation.GET_ITEM, w -> {
            w.writeStartElement("m", "ItemShape", EwsXml.MESSAGES_NS);
            EwsXml.typesElement(w, "BaseShape", "IdOnly");
            EwsXml.typesElement(w, "IncludeMimeContent", "true");
            w.writeStartElement("t", "AdditionalProperties", EwsXml.TYPES_NS);
            EwsXml.fieldUri(w, "message:IsRead");
            w.writeEndElement();
            w.writeEndElement();
            w.writeStartElement("m", "ItemIds", EwsXml.MESSAGES_NS);
            w.writeEmptyElement("t", "ItemId", EwsXml.TYPES_NS);
            w.writeAttribute("Id", itemId);
            w.writeEndElement();
        });

        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            EwsResponseReader.MimeFields fields;
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(temp), sha256)) {
                fields = transport.exchange(EwsOperation.GET_ITEM, request,
                        body -> EwsResponseReader.readMime(body, out, maxMimeBytes));
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return new EwsMimeItem(fields.itemId, fields.read, target, fields.bytes,
                    HexFormat.of().formatHex(sha256.digest()));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write MIME to " + target, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            deleteQuietly(temp);
        }
    }

    /** HTTP requests sent so far, including NTLM handshake legs. */
    public int requestCount() {
        return transport.requestCount();
    }

    @Override
    public void close() {
        transport.close();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort: a leftover .part file is harmless and overwritten next time
        }
    }
}
