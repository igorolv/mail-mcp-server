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
import java.util.List;
import java.util.Map;
import mail.mcp.source.RemoteMessageInfo;

/**
 * Thin read-only EWS client of one mailbox (decision P1). It builds SOAP requests for allow-listed
 * operations only ({@link EwsOperation}) and parses responses as a stream.
 *
 * <p>Covers folder listing, incremental sync and
 * {@code GetItem} with MIME. Calls are sequential: one request at a time per mailbox
 * (docs/research/RT-03-ews-operations.md, throttling). Errors are never retried here; in particular an
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

    /** Resolves distinguished folder ids in request order with one {@code GetFolder}. */
    public List<String> folderIds(List<DistinguishedFolder> folders) {
        byte[] request = EwsXml.envelope(EwsOperation.GET_FOLDER, w -> {
            w.writeStartElement("m", "FolderShape", EwsXml.MESSAGES_NS);
            EwsXml.typesElement(w, "BaseShape", "IdOnly");
            w.writeEndElement();
            w.writeStartElement("m", "FolderIds", EwsXml.MESSAGES_NS);
            for (var folder : folders) {
                EwsXml.distinguishedFolderId(w, folder);
            }
            w.writeEndElement();
        });
        var ids = transport.exchange(EwsOperation.GET_FOLDER, request, EwsResponseReader::readFolderIds);
        if (ids.size() != folders.size()) {
            throw new EwsProtocolException("GetFolder returned a different number of folder ids");
        }
        return ids;
    }

    /** One deep {@code FindFolder} page with names, classes and parent ids. */
    public EwsFolderPage findFolders(DistinguishedFolder root, int offset) {
        byte[] request = EwsXml.envelope(EwsOperation.FIND_FOLDER, w -> {
            w.writeAttribute("Traversal", "Deep");
            w.writeStartElement("m", "FolderShape", EwsXml.MESSAGES_NS);
            EwsXml.typesElement(w, "BaseShape", "IdOnly");
            w.writeStartElement("t", "AdditionalProperties", EwsXml.TYPES_NS);
            EwsXml.fieldUri(w, "folder:DisplayName");
            EwsXml.fieldUri(w, "folder:FolderClass");
            EwsXml.fieldUri(w, "folder:ParentFolderId");
            w.writeEndElement();
            w.writeEndElement();
            w.writeEmptyElement("m", "IndexedPageFolderView", EwsXml.MESSAGES_NS);
            w.writeAttribute("MaxEntriesReturned", "1000");
            w.writeAttribute("Offset", Integer.toString(offset));
            w.writeAttribute("BasePoint", "Beginning");
            w.writeStartElement("m", "ParentFolderIds", EwsXml.MESSAGES_NS);
            EwsXml.distinguishedFolderId(w, root);
            w.writeEndElement();
        });
        return transport.exchange(EwsOperation.FIND_FOLDER, request, EwsResponseReader::readFolderPage);
    }

    /** One {@code SyncFolderItems} page; a null state starts a full listing. */
    public EwsSyncPage syncFolderItems(String folderId, String syncState) {
        byte[] request = EwsXml.envelope(EwsOperation.SYNC_FOLDER_ITEMS, w -> {
            w.writeStartElement("m", "ItemShape", EwsXml.MESSAGES_NS);
            EwsXml.typesElement(w, "BaseShape", "IdOnly");
            w.writeEndElement();
            w.writeStartElement("m", "SyncFolderId", EwsXml.MESSAGES_NS);
            w.writeEmptyElement("t", "FolderId", EwsXml.TYPES_NS);
            w.writeAttribute("Id", folderId);
            w.writeEndElement();
            if (syncState != null) {
                w.writeStartElement("m", "SyncState", EwsXml.MESSAGES_NS);
                w.writeCharacters(syncState);
                w.writeEndElement();
            }
            w.writeStartElement("m", "MaxChangesReturned", EwsXml.MESSAGES_NS);
            w.writeCharacters("512");
            w.writeEndElement();
            w.writeStartElement("m", "SyncScope", EwsXml.MESSAGES_NS);
            w.writeCharacters("NormalItems");
            w.writeEndElement();
        });
        return transport.exchange(EwsOperation.SYNC_FOLDER_ITEMS, request, EwsResponseReader::readSyncPage);
    }

    /** Identity and flag properties in one batch; no MIME or body is requested. */
    public Map<String, RemoteMessageInfo> metadata(List<String> refs) {
        if (refs.isEmpty() || refs.size() > 100 || refs.stream().anyMatch(id -> id == null || id.isBlank())
                || new java.util.HashSet<>(refs).size() != refs.size()) {
            throw new IllegalArgumentException("Metadata requires 1 to 100 distinct item ids");
        }
        byte[] request = EwsXml.envelope(EwsOperation.GET_ITEM, w -> {
            w.writeStartElement("m", "ItemShape", EwsXml.MESSAGES_NS);
            EwsXml.typesElement(w, "BaseShape", "IdOnly");
            w.writeStartElement("t", "AdditionalProperties", EwsXml.TYPES_NS);
            for (String field : List.of("message:InternetMessageId", "item:Size", "item:DateTimeSent",
                    "message:From", "item:Subject", "message:IsRead", "item:Flag")) { EwsXml.fieldUri(w, field); }
            w.writeEndElement();
            w.writeEndElement();
            w.writeStartElement("m", "ItemIds", EwsXml.MESSAGES_NS);
            for (String ref : refs) {
                w.writeEmptyElement("t", "ItemId", EwsXml.TYPES_NS);
                w.writeAttribute("Id", ref);
            }
            w.writeEndElement();
        });
        return transport.exchange(EwsOperation.GET_ITEM, request, in -> EwsResponseReader.readMetadata(in, refs));
    }

    /**
     * {@code GetItem} with {@code IncludeMimeContent}: decodes the MIME of one item into
     * {@code target}. The file is written next to the target first and moved into place only after
     * the whole response was read, so a failed call leaves no partial {@code .eml}. {@code GetItem}
     * does not mark the item as read (docs/research/RT-03-ews-operations.md).
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
