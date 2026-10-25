package mail.mcp.sync;

import mail.mcp.index.MailIndex;
import mail.mcp.model.LocalFolder;
import mail.mcp.model.FolderProgress;
import mail.mcp.model.SyncStatus;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.source.MailSource;
import mail.mcp.source.MessageUnavailableException;
import mail.mcp.store.MessageStore;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/** One synchronous run; the caller owns the store, index writer and mailbox session. */
public final class SyncRunner {
    private final MessageStore store;
    private final MailIndex index;
    private final MailSource source;
    private final MessageProcessor processor;

    public SyncRunner(MessageStore store, MailIndex index, MailSource source, MessageProcessor processor) {
        this.store = store;
        this.index = index;
        this.source = source;
        this.processor = processor;
    }

    public void run(Consumer<FolderProgress> progress) {
        Instant startedAt = Instant.now();
        long pid = ProcessHandle.current().pid();
        var folders = new LinkedHashMap<String, FolderProgress>();
        try {
            store.writeSyncStatus(new SyncStatus("running", pid, startedAt, null, List.of(), null, false));
            synchronize(p -> {
                folders.put(p.path(), p);
                store.writeSyncStatus(new SyncStatus("running", pid, startedAt, null,
                        List.copyOf(folders.values()), null, false));
                progress.accept(p);
            });
            store.writeSyncStatus(new SyncStatus("completed", pid, startedAt, Instant.now(),
                    List.copyOf(folders.values()), null, false));
        } catch (RuntimeException e) {
            try {
                store.writeSyncStatus(new SyncStatus("failed", pid, startedAt, Instant.now(),
                        List.copyOf(folders.values()), SyncErrors.describe(e), SyncErrors.signInRejected(e)));
            } catch (RuntimeException statusError) { e.addSuppressed(statusError); }
            throw e;
        }
    }

    private void synchronize(Consumer<FolderProgress> progress) {
        for (String key : index.reconcile(store)) {
            var meta = store.readMeta(key).orElseThrow();
            var processed = switch (meta.state()) {
                case DOWNLOADED -> processor.process(meta);
                case CONVERTED -> processor.load(meta);
                case FAILED -> new Processed(meta, null, null);
            };
            index.put(processed.meta(), processed.message(), processed.body());
        }
        index.commit();
        var local = store.folders();
        var remote = source.folders();
        BigInteger nextKey = local.stream().map(LocalFolder::key)
                .filter(key -> key.matches("f[0-9]+"))
                .map(key -> new BigInteger(key.substring(1)))
                .max(BigInteger::compareTo).orElse(BigInteger.ZERO).add(BigInteger.ONE);
        List<LocalFolder> folders = new ArrayList<>();
        for (var folder : remote) {
            var previous = local.stream().filter(f -> f.remoteId().equals(folder.remoteId()))
                    .findFirst().orElse(null);
            if (previous == null) {
                folders.add(new LocalFolder("f" + nextKey, folder.remoteId(), folder.path(), null));
                nextKey = nextKey.add(BigInteger.ONE);
            } else {
                folders.add(new LocalFolder(previous.key(), folder.remoteId(), folder.path(), previous.cursor()));
            }
        }
        for (var folder : local) {
            if (remote.stream().noneMatch(f -> f.remoteId().equals(folder.remoteId()))) {
                var refs = index.folderRefs(folder.key());
                for (String key : refs.values()) {
                    store.delete(key);
                    index.delete(key);
                }
                index.commit();
                progress.accept(new FolderProgress(folder.path(), 0, 0, refs.size(), false, true));
            }
        }
        store.saveFolders(folders);

        for (int i = 0; i < folders.size(); i++) {
            var folder = folders.get(i);
            var refs = index.folderRefs(folder.key());
            var changes = source.changes(remote.get(i), folder.cursor(), refs.keySet());
            var removedRefs = changes.reset() ? refs.keySet() : changes.removed();
            int removed = 0;
            for (String ref : removedRefs) {
                String key = refs.get(ref);
                if (key != null) {
                    store.delete(key);
                    index.delete(key);
                    removed++;
                }
            }
            index.commit();
            int downloaded = 0;
            int failed = 0;
            for (String ref : changes.added()) {
                String key = store.newKey();
                var dir = store.createMessageDir(key);
                MessageMeta meta;
                try {
                    var mime = source.download(remote.get(i), ref, dir.resolve(MessageStore.MESSAGE_FILE));
                    meta = new MessageMeta(key, folder.key(), folder.path(), ref, mime.size(), mime.sha256(),
                            Instant.now(), MessageState.DOWNLOADED, null);
                } catch (MessageUnavailableException e) {
                    meta = new MessageMeta(key, folder.key(), folder.path(), ref, 0, null,
                            Instant.now(), MessageState.FAILED, e.getMessage());
                }
                store.writeMeta(meta);
                var processed = meta.state() == MessageState.DOWNLOADED
                        ? processor.process(meta) : new Processed(meta, null, null);
                index.put(processed.meta(), processed.message(), processed.body());
                if (processed.meta().state() == MessageState.CONVERTED) { downloaded++; }
                else { failed++; }
                if ((downloaded + failed) % 100 == 0) {
                    index.commit();
                    progress.accept(new FolderProgress(folder.path(), downloaded, failed, removed,
                            changes.reset(), false));
                }
            }
            index.commit();
            folders.set(i, new LocalFolder(folder.key(), folder.remoteId(), folder.path(), changes.cursor()));
            store.saveFolders(folders);
            progress.accept(new FolderProgress(folder.path(), downloaded, failed, removed, changes.reset(), true));
        }
    }
}
