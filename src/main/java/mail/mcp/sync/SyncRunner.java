package mail.mcp.sync;

import mail.mcp.index.MailIndex;
import mail.mcp.model.LocalFolder;
import mail.mcp.model.FolderProgress;
import mail.mcp.model.SyncStatus;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.model.MessageFingerprint;
import mail.mcp.source.FolderChanges;
import mail.mcp.source.RemoteFolder;
import mail.mcp.source.RemoteMessageInfo;
import mail.mcp.source.MailSource;
import mail.mcp.source.MessageUnavailableException;
import mail.mcp.store.MessageStore;

import java.math.BigInteger;
import java.time.Instant;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** One synchronous run; the caller owns the store, index writer and mailbox session. */
public final class SyncRunner {
    private final MessageStore store;
    private final MailIndex index;
    private final MailSource source;
    private final MessageProcessor processor;
    private final Consumer<String> checkpoint;

    public SyncRunner(MessageStore store, MailIndex index, MailSource source, MessageProcessor processor) {
        this(store, index, source, processor, point -> { });
    }

    /** Package-local interruption seam for offline recovery tests. */
    SyncRunner(MessageStore store, MailIndex index, MailSource source, MessageProcessor processor,
               Consumer<String> checkpoint) {
        this.store = store;
        this.index = index;
        this.source = source;
        this.processor = processor;
        this.checkpoint = checkpoint;
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

    /** Restores missing catalog entries from disk without accessing a mailbox. */
    public static void restore(MessageStore store, MailIndex index, MessageProcessor processor) {
        restore(store, index, processor, count -> { });
    }

    /** The offline command supplies progress; server reconciliation stays silent. */
    public static void restore(MessageStore store, MailIndex index, MessageProcessor processor, IntConsumer progress) {
        int count = 0;
        for (String key : index.reconcile(store)) {
            var meta = store.readMeta(key).orElseThrow();
            var processed = switch (meta.state()) {
                case DOWNLOADED -> processor.process(meta);
                case CONVERTED -> processor.load(meta);
                case FAILED -> new Processed(meta, null, null, null);
            };
            index.put(processed.meta(), processed.message(), processed.body(), processed.attachmentText());
            count++;
            if (count % 100 == 0) { index.commit(); }
            progress.accept(count);
        }
        index.commit();
    }

    private void synchronize(Consumer<FolderProgress> progress) {
        restore(store, index, processor);
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
        // Discovery is read-only after restoration. Keep all old evidence until matching finishes.
        var original = store.keys().stream().map(key -> store.readMeta(key).orElseThrow()).toList();
        var originalByKey = new HashMap<String, MessageMeta>();
        original.forEach(meta -> originalByKey.put(meta.key(), meta));
        var vanished = local.stream().filter(f -> remote.stream().noneMatch(r -> r.remoteId().equals(f.remoteId()))).toList();
        var plans = new ArrayList<FolderPlan>();
        var removedKeys = new HashSet<String>();
        var resetFolders = new HashSet<String>();
        for (var folder : vanished) { removedKeys.addAll(index.folderRefs(folder.key()).values()); }
        for (int i = 0; i < folders.size(); i++) {
            var folder = folders.get(i);
            var refs = index.folderRefs(folder.key());
            var changes = source.changes(remote.get(i), folder.cursor(), refs.keySet());
            Set<String> removed = changes.reset() ? Set.copyOf(refs.keySet()) : Set.copyOf(changes.removed());
            removed.stream().map(refs::get).filter(java.util.Objects::nonNull).forEach(removedKeys::add);
            if (changes.reset()) { resetFolders.add(folder.key()); }
            var present = new LinkedHashSet<>(refs.keySet());
            present.removeAll(removed);
            present.addAll(changes.added());
            var info = source.metadata(remote.get(i), changes.cursor(), present);
            plans.add(new FolderPlan(folder, remote.get(i), changes, refs, removed, info));
        }
        var matches = matches(original, plans, removedKeys, resetFolders);

        // Preserve identities and old cursors, including vanished folders, across every crash window.
        var pendingFolders = new ArrayList<>(folders);
        pendingFolders.addAll(vanished);
        store.saveFolders(pendingFolders);
        for (var plan : plans) {
            for (var entry : plan.info().entrySet()) {
                var old = matches.get(plan.folder().key() + "/" + entry.getKey());
                if (old != null) {
                    var meta = identity(old, plan.folder(), entry.getKey(), complete(entry.getValue()));
                    store.writeMeta(meta);
                    checkpoint.accept("metadataReassigned");
                    put(processor.load(meta));
                }
            }
            // Retained entries are refreshed only after the pre-run snapshot has been matched.
            for (var entry : plan.refs().entrySet()) {
                if (plan.removed().contains(entry.getKey())) { continue; }
                var old = store.readMeta(entry.getValue()).orElseThrow();
                var meta = identity(old, plan.folder(), entry.getKey(), complete(plan.info().get(entry.getKey())));
                if (!meta.equals(old)) {
                    store.writeMeta(meta);
                    if (!old.folder().equals(meta.folder())) {
                        put(meta.state() == MessageState.CONVERTED ? processor.load(meta) : new Processed(meta, null, null, null));
                    }
                }
            }
        }
        index.commit();
        checkpoint.accept("movesCommitted");

        for (var folder : vanished) {
            int removed = 0;
            for (var meta : original) {
                if (meta.folderKey().equals(folder.key()) && deleteOriginal(meta)) { removed++; }
            }
            index.commit();
            progress.accept(new FolderProgress(folder.path(), 0, 0, removed, false, true));
        }
        var completed = new ArrayList<FolderProgress>();
        for (var plan : plans) {
            var folder = plan.folder();
            var changes = plan.changes();
            int removed = 0;
            for (String ref : plan.removed()) {
                String key = plan.refs().get(ref);
                if (key != null) {
                    var old = originalByKey.get(key);
                    if (deleteOriginal(old)) { removed++; }
                }
            }
            index.commit();
            int downloaded = 0;
            int failed = 0;
            var currentRefs = index.folderRefs(folder.key());
            for (String ref : changes.added()) {
                // Current local refs, not merely old cursors, make interrupted runs idempotent.
                if (currentRefs.containsKey(ref)) { continue; }
                String key = store.newKey();
                var dir = store.createMessageDir(key);
                MessageMeta meta;
                try {
                    var mime = source.download(plan.remote(), ref, dir.resolve(MessageStore.MESSAGE_FILE));
                    meta = new MessageMeta(key, folder.key(), folder.path(), ref, mime.size(), mime.sha256(),
                            Instant.now(), MessageState.DOWNLOADED, null, complete(plan.info().get(ref)));
                } catch (MessageUnavailableException e) {
                    meta = new MessageMeta(key, folder.key(), folder.path(), ref, 0, null,
                            Instant.now(), MessageState.FAILED, e.getMessage(), complete(plan.info().get(ref)));
                }
                store.writeMeta(meta);
                var processed = meta.state() == MessageState.DOWNLOADED
                        ? processor.process(meta) : new Processed(meta, null, null, null);
                put(processed);
                currentRefs.put(ref, key);
                if (processed.meta().state() == MessageState.CONVERTED) { downloaded++; }
                else { failed++; }
                if ((downloaded + failed) % 100 == 0) {
                    index.commit();
                    progress.accept(new FolderProgress(folder.path(), downloaded, failed, removed,
                            changes.reset(), false, changes.rescanned()));
                }
            }
            index.commit();
            completed.add(new FolderProgress(folder.path(), downloaded, failed, removed, changes.reset(), true, changes.rescanned()));
        }
        checkpoint.accept("beforeCursorSave");
        store.saveFolders(plans.stream().map(p -> new LocalFolder(p.folder().key(), p.folder().remoteId(),
                p.folder().path(), p.changes().cursor())).toList());
        completed.forEach(progress);
    }

    private Map<String, MessageMeta> matches(List<MessageMeta> original, List<FolderPlan> plans,
                                           Set<String> removed, Set<String> resets) {
        var oldCounts = new HashMap<MessageFingerprint, Integer>();
        var remoteCounts = new HashMap<MessageFingerprint, Integer>();
        var candidates = new HashMap<MessageFingerprint, MessageMeta>();
        for (var meta : original) {
            var fingerprint = meta.fingerprint();
            if (fingerprint == null || !fingerprint.complete()) { continue; }
            oldCounts.merge(fingerprint, 1, Integer::sum);
            if (removed.contains(meta.key()) && !resets.contains(meta.folderKey())
                    && meta.state() == MessageState.CONVERTED
                    && Files.isRegularFile(store.messageDir(meta.key()).resolve(MessageStore.MESSAGE_FILE))) {
                candidates.put(fingerprint, meta);
            }
        }
        for (var plan : plans) {
            for (var info : plan.info().values()) {
                var fingerprint = complete(info);
                if (fingerprint != null) { remoteCounts.merge(fingerprint, 1, Integer::sum); }
            }
        }
        var result = new HashMap<String, MessageMeta>();
        for (var plan : plans) {
            if (resets.contains(plan.folder().key())) { continue; }
            for (String ref : plan.changes().added()) {
                if (plan.refs().containsKey(ref)) { continue; }
                var fingerprint = complete(plan.info().get(ref));
                var old = candidates.get(fingerprint);
                if (old != null && !old.folderKey().equals(plan.folder().key())
                        && oldCounts.getOrDefault(fingerprint, 0) == 1 && remoteCounts.getOrDefault(fingerprint, 0) == 1) {
                    result.put(plan.folder().key() + "/" + ref, old);
                }
            }
        }
        return result;
    }

    private static MessageFingerprint complete(RemoteMessageInfo info) {
        return info != null && info.fingerprint() != null && info.fingerprint().complete() ? info.fingerprint() : null;
    }

    private static MessageMeta identity(MessageMeta old, LocalFolder folder, String ref, MessageFingerprint fingerprint) {
        return new MessageMeta(old.key(), folder.key(), folder.path(), ref, old.size(), old.sha256(),
                old.downloadedAt(), old.state(), old.error(), fingerprint);
    }

    private boolean deleteOriginal(MessageMeta old) {
        var current = store.readMeta(old.key()).orElse(null);
        if (current == null || !current.folderKey().equals(old.folderKey()) || !current.ref().equals(old.ref())) { return false; }
        store.delete(old.key());
        index.delete(old.key());
        return true;
    }

    private void put(Processed processed) {
        index.put(processed.meta(), processed.message(), processed.body(), processed.attachmentText());
    }

    private record FolderPlan(LocalFolder folder, RemoteFolder remote, FolderChanges changes,
                              Map<String, String> refs, Set<String> removed, Map<String, RemoteMessageInfo> info) { }
}
