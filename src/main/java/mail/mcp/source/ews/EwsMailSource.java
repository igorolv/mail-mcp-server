package mail.mcp.source.ews;

import mail.mcp.source.Downloaded;
import mail.mcp.source.FolderChanges;
import mail.mcp.source.MailSource;
import mail.mcp.source.MessageUnavailableException;
import mail.mcp.source.RemoteFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Collection;
import java.util.Map;
import mail.mcp.source.RemoteMessageInfo;

import static mail.mcp.source.ews.DistinguishedFolder.*;

/** One read-only EWS session shared by folder listing, change pages and MIME downloads. */
public final class EwsMailSource implements MailSource {
    private final EwsClient client;

    public EwsMailSource(EwsClient client) { this.client = client; }

    @Override public List<RemoteFolder> folders() {
        var excluded = new HashSet<>(client.folderIds(List.of(DELETED_ITEMS, JUNK_EMAIL, DRAFTS, OUTBOX)));
        List<EwsFolderNode> nodes = new ArrayList<>();
        int offset = 0;
        while (true) {
            var page = client.findFolders(MSG_FOLDER_ROOT, offset);
            nodes.addAll(page.folders());
            if (page.includesLastItemInRange() || page.folders().isEmpty()) {
                break;
            }
            offset += page.folders().size();
        }
        var byId = new HashMap<String, EwsFolderNode>();
        nodes.forEach(node -> byId.put(node.id(), node));
        List<RemoteFolder> result = new ArrayList<>();
        for (var node : nodes) {
            if (!"Folder".equals(node.kind()) || node.folderClass() == null
                    || !(node.folderClass().equals("IPF.Note") || node.folderClass().startsWith("IPF.Note."))) {
                continue;
            }
            List<String> names = new ArrayList<>();
            String id = node.id();
            while (id != null && !excluded.contains(id) && byId.containsKey(id)) {
                var ancestor = byId.get(id);
                names.add(ancestor.displayName());
                id = ancestor.parentId();
            }
            if (!excluded.contains(id)) {
                Collections.reverse(names);
                result.add(new RemoteFolder(node.id(), String.join("/", names)));
            }
        }
        return List.copyOf(result);
    }

    @Override public FolderChanges changes(RemoteFolder folder, String cursor, Set<String> known) {
        boolean firstSync = cursor == null;
        boolean rescanned = false;
        EwsSyncPage page;
        try {
            page = client.syncFolderItems(folder.remoteId(), cursor);
        } catch (EwsResponseException e) {
            if (cursor == null || !"ErrorInvalidSyncStateData".equals(e.responseCode())) {
                throw e;
            }
            firstSync = true;
            rescanned = true;
            page = client.syncFolderItems(folder.remoteId(), null);
        }
        var created = new LinkedHashSet<String>();
        var deleted = new HashSet<String>();
        while (true) {
            created.addAll(page.created());
            for (String id : page.deleted()) {
                if (!created.remove(id)) {
                    deleted.add(id);
                }
            }
            if (page.includesLastItemInRange()) {
                break;
            }
            page = client.syncFolderItems(folder.remoteId(), page.syncState());
        }
        var removed = new HashSet<>(deleted);
        removed.retainAll(known);
        if (firstSync) {
            known.stream().filter(ref -> !created.contains(ref)).forEach(removed::add);
        }
        return new FolderChanges(created.stream().filter(ref -> !known.contains(ref)).toList(),
                removed.stream().sorted().toList(), page.syncState(), false, rescanned);
    }

    @Override public Map<String, RemoteMessageInfo> metadata(RemoteFolder folder, String expectedCursor,
                                                           Collection<String> refs) {
        var ids = List.copyOf(refs);
        var result = new HashMap<String, RemoteMessageInfo>();
        for (int i = 0; i < ids.size(); i += 100) {
            result.putAll(client.metadata(ids.subList(i, Math.min(i + 100, ids.size()))));
        }
        return Map.copyOf(result);
    }

    @Override public Downloaded download(RemoteFolder folder, String ref, Path target) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Message ref is required");
        }
        try {
            var mime = client.getItemMime(ref, target);
            return new Downloaded(mime.bytes(), mime.sha256());
        } catch (EwsResponseException e) {
            if ("ErrorItemNotFound".equals(e.responseCode())) {
                throw new MessageUnavailableException(MessageUnavailableException.Reason.GONE);
            }
            throw e;
        }
    }

    @Override public void close() { client.close(); }
}
