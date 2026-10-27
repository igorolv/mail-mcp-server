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
import mail.mcp.mime.MimeReader;
import jakarta.mail.MessagingException;
import mail.mcp.store.MessageStore;

import java.math.BigInteger;
import java.time.Instant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        run(progress, count -> { });
    }

    /**
     * @param restored running count of catalog entries restored from disk before the mailbox is read;
     *                 large after an index rebuild, for example on a schema change
     */
    public void run(Consumer<FolderProgress> progress, IntConsumer restored) {
        Instant startedAt = Instant.now();
        long pid = ProcessHandle.current().pid();
        var folders = new LinkedHashMap<String, FolderProgress>();
        // Only a run that did not complete can leave meta.json and the index apart (docs/plan.md 3.6, Д30).
        boolean checkMetadata = !store.readSyncStatus().map(status -> "completed".equals(status.state())).orElse(false);
        try {
            store.writeSyncStatus(new SyncStatus("running", pid, startedAt, null, List.of(), null, false));
            synchronize(checkMetadata, restored, p -> {
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
        restore(store, index, processor, progress, true);
    }

    /** @param checkMetadata compare every meta.json with the index; see {@link MailIndex#reconcile(MessageStore, boolean)} */
    static void restore(MessageStore store, MailIndex index, MessageProcessor processor, IntConsumer progress,
                        boolean checkMetadata) {
        int count = 0;
        for (String key : index.reconcile(store, checkMetadata)) {
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

    private void synchronize(boolean checkMetadata, IntConsumer restored, Consumer<FolderProgress> progress) {
        restore(store, index, processor, restored, checkMetadata);
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
        // Discovery is read-only after restoration. A run reads meta.json only of messages it removes,
        // moves or changes, never of every message (docs/plan.md 3.6, Д30).
        var previousPaths = new HashMap<String, String>();
        local.forEach(folder -> previousPaths.put(folder.key(), folder.path()));
        var vanished = local.stream().filter(f -> remote.stream().noneMatch(r -> r.remoteId().equals(f.remoteId()))).toList();
        var plans = new ArrayList<FolderPlan>();
        var removedKeys = new HashSet<String>();
        var resetFolders = new HashSet<String>();
        var vanishedKeys = new HashMap<String, List<String>>();
        for (var folder : vanished) {
            var keys = List.copyOf(index.folderRefs(folder.key()).values());
            vanishedKeys.put(folder.key(), keys);
            removedKeys.addAll(keys);
        }
        for (int i = 0; i < folders.size(); i++) {
            var folder = folders.get(i);
            var refs = index.folderRefs(folder.key());
            var changes = source.changes(remote.get(i), folder.cursor(), refs.keySet());
            Set<String> removed = changes.reset() ? Set.copyOf(refs.keySet()) : Set.copyOf(changes.removed());
            removed.stream().map(refs::get).filter(java.util.Objects::nonNull).forEach(removedKeys::add);
            if (changes.reset()) { resetFolders.add(folder.key()); }
            // Known refs keep their stored fingerprints; flags come cheaply with the changes. Only
            // added refs need identity metadata (docs/plan.md 3.6, Д29).
            var info = new HashMap<String, RemoteMessageInfo>();
            changes.observed().forEach((ref, observed) -> {
                if (refs.containsKey(ref) && !removed.contains(ref)) { info.put(ref, observed); }
            });
            if (!changes.added().isEmpty()) {
                info.putAll(source.metadata(remote.get(i), changes.cursor(), changes.added()));
            }
            plans.add(new FolderPlan(folder, remote.get(i), changes, refs, removed, Map.copyOf(info)));
        }
        // Pre-run metadata of removed messages: move candidates and the guard against deleting a reassigned one.
        var removedMetas = new HashMap<String, MessageMeta>();
        for (String key : removedKeys) { store.readMeta(key).ifPresent(meta -> removedMetas.put(key, meta)); }
        var matches = matches(removedMetas, removedKeys, plans, resetFolders);

        // Preserve identities and old cursors, including vanished folders, across every crash window.
        var pendingFolders = new ArrayList<>(folders);
        pendingFolders.addAll(vanished);
        store.saveFolders(pendingFolders);
        for (var plan : plans) {
            for (var entry : plan.info().entrySet()) {
                var old = matches.get(plan.folder().key() + "/" + entry.getKey());
                if (old != null) {
                    var meta = identity(old, plan.folder(), entry.getKey(), entry.getValue());
                    store.writeMeta(meta);
                    checkpoint.accept("metadataReassigned");
                    put(processor.load(meta));
                }
            }
            // Retained entries are refreshed only after the pre-run snapshot has been matched. Their flags
            // are compared with the index; meta.json is read only when something changed (Д30).
            boolean renamed = !plan.folder().path().equals(previousPaths.getOrDefault(plan.folder().key(), plan.folder().path()));
            var indexed = renamed ? Map.<String, String[]>of() : index.folderFlags(plan.folder().key());
            for (var entry : plan.refs().entrySet()) {
                if (plan.removed().contains(entry.getKey())) { continue; }
                if (!renamed && !changed(plan.info().get(entry.getKey()), indexed.get(entry.getKey()))) { continue; }
                var old = store.readMeta(entry.getValue()).orElseThrow();
                var meta = identity(old, plan.folder(), entry.getKey(), plan.info().get(entry.getKey()));
                if (!meta.equals(old)) {
                    store.writeMeta(meta);
                    checkpoint.accept("metadataRefreshed");
                    if (!old.folder().equals(meta.folder()) || !java.util.Objects.equals(old.read(), meta.read())
                            || !java.util.Objects.equals(old.flagged(), meta.flagged())) {
                        put(meta.state() == MessageState.CONVERTED ? processor.load(meta) : new Processed(meta, null, null, null));
                    }
                }
            }
        }
        index.commit();
        checkpoint.accept("movesCommitted");

        for (var folder : vanished) {
            int removed = 0;
            for (String key : vanishedKeys.get(folder.key())) {
                var meta = removedMetas.get(key);
                if (meta != null && deleteOriginal(meta)) { removed++; }
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
                var old = key == null ? null : removedMetas.get(key);
                if (old != null && deleteOriginal(old)) { removed++; }
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
                            Instant.now(), MessageState.DOWNLOADED, null, complete(plan.info().get(ref)),
                            plan.info().get(ref) == null ? null : plan.info().get(ref).read(),
                            plan.info().get(ref) == null ? null : plan.info().get(ref).flagged());
                } catch (MessageUnavailableException e) {
                    meta = new MessageMeta(key, folder.key(), folder.path(), ref, 0, null,
                            Instant.now(), MessageState.FAILED, e.getMessage(), complete(plan.info().get(ref)),
                            plan.info().get(ref) == null ? null : plan.info().get(ref).read(),
                            plan.info().get(ref) == null ? null : plan.info().get(ref).flagged());
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

    /**
     * Unique apparent moves. Candidates are removed converted messages with MIME, identified by their saved
     * fingerprint or, without one, by their saved headers. Uniqueness counts old and current copies among
     * the documents with the same Message-ID, so only those few meta.json are read (Д30).
     */
    private Map<String, MessageMeta> matches(Map<String, MessageMeta> removedMetas, Set<String> removedKeys,
                                           List<FolderPlan> plans, Set<String> resets) {
        var result = new HashMap<String, MessageMeta>();
        if (plans.stream().allMatch(plan -> plan.changes().added().isEmpty())) { return result; }
        var candidateFingerprints = new HashMap<String, MessageFingerprint>();
        for (var meta : removedMetas.values()) {
            Path eml = store.messageDir(meta.key()).resolve(MessageStore.MESSAGE_FILE);
            if (resets.contains(meta.folderKey()) || meta.state() != MessageState.CONVERTED || !Files.isRegularFile(eml)) {
                continue;
            }
            var fingerprint = meta.fingerprint();
            if (fingerprint == null || !fingerprint.complete()) {
                fingerprint = localFingerprint(eml); // never fetched: messages saved before Д8
            }
            if (fingerprint != null && fingerprint.complete()) { candidateFingerprints.put(meta.key(), fingerprint); }
        }
        if (candidateFingerprints.isEmpty()) { return result; }

        var candidates = new HashMap<MessageFingerprint, MessageMeta>();
        var oldCounts = new HashMap<MessageFingerprint, Integer>();
        var remoteCounts = new HashMap<MessageFingerprint, Integer>();
        candidateFingerprints.forEach((key, fingerprint) -> candidates.put(fingerprint, removedMetas.get(key)));
        for (var fingerprint : Set.copyOf(candidateFingerprints.values())) {
            // Candidates always count, even when their saved MIME has no Message-ID header to index.
            var keys = new HashSet<>(index.keysByMessageId(fingerprint.messageId()));
            candidateFingerprints.forEach((key, candidate) -> { if (candidate.equals(fingerprint)) { keys.add(key); } });
            for (String key : keys) {
                var stored = candidateFingerprints.containsKey(key) ? candidateFingerprints.get(key)
                        : removedMetas.containsKey(key) ? removedMetas.get(key).fingerprint()
                        : store.readMeta(key).map(MessageMeta::fingerprint).orElse(null);
                if (!fingerprint.equals(stored)) { continue; }
                oldCounts.merge(fingerprint, 1, Integer::sum);
                // Retained messages are current server copies: their identity does not change remotely.
                if (!removedKeys.contains(key)) { remoteCounts.merge(fingerprint, 1, Integer::sum); }
            }
        }
        for (var plan : plans) {
            for (String ref : plan.changes().added()) {
                var fingerprint = complete(plan.info().get(ref));
                if (fingerprint != null && candidates.containsKey(fingerprint)) { remoteCounts.merge(fingerprint, 1, Integer::sum); }
            }
        }
        for (var plan : plans) {
            if (resets.contains(plan.folder().key())) { continue; }
            for (String ref : plan.changes().added()) {
                if (plan.refs().containsKey(ref)) { continue; }
                var fingerprint = complete(plan.info().get(ref));
                var old = fingerprint == null ? null : candidates.get(fingerprint);
                if (old != null && !old.folderKey().equals(plan.folder().key())
                        && oldCounts.getOrDefault(fingerprint, 0) == 1 && remoteCounts.getOrDefault(fingerprint, 0) == 1) {
                    result.put(plan.folder().key() + "/" + ref, old);
                }
            }
        }
        return result;
    }

    /** True when an observation differs from the read/star values the index holds for the message. */
    private static boolean changed(RemoteMessageInfo info, String[] indexed) {
        if (info == null) { return false; }
        if (info.fingerprint() != null || indexed == null) { return true; }
        return info.read() != null && !info.read().toString().equals(indexed[1])
                || info.flagged() != null && !info.flagged().toString().equals(indexed[2]);
    }

    private static MessageFingerprint localFingerprint(Path eml) {
        try { return MimeReader.fingerprint(eml); }
        catch (MessagingException | IOException | RuntimeException e) { return null; }
    }

    private static MessageFingerprint complete(RemoteMessageInfo info) {
        return info != null && info.fingerprint() != null && info.fingerprint().complete() ? info.fingerprint() : null;
    }

    /** Flag-only observations (no fingerprint) keep the stored identity; fresh metadata replaces it. */
    private static MessageMeta identity(MessageMeta old, LocalFolder folder, String ref, RemoteMessageInfo info) {
        return new MessageMeta(old.key(), folder.key(), folder.path(), ref, old.size(), old.sha256(),
                old.downloadedAt(), old.state(), old.error(),
                info == null || info.fingerprint() == null ? old.fingerprint() : complete(info),
                info == null || info.read() == null ? old.read() : info.read(),
                info == null || info.flagged() == null ? old.flagged() : info.flagged());
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
