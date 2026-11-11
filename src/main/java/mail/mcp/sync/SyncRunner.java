package mail.mcp.sync;

import mail.mcp.index.IndexedObservation;
import mail.mcp.index.MailIndex;
import mail.mcp.model.LocalFolder;
import mail.mcp.model.FolderProgress;
import mail.mcp.model.SyncStatus;
import mail.mcp.model.MessageMeta;
import mail.mcp.model.MessageState;
import mail.mcp.model.MessageFingerprint;
import mail.mcp.source.FolderChanges;
import mail.mcp.source.FolderGoneException;
import mail.mcp.source.RemoteFolder;
import mail.mcp.source.RemoteMessageInfo;
import mail.mcp.source.MailSource;
import mail.mcp.source.MessageUnavailableException;
import mail.mcp.mime.MimeReader;
import jakarta.mail.MessagingException;
import mail.mcp.store.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.time.Instant;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.function.Supplier;

/** One synchronous run; the caller owns the store, index writer and mailbox session. */
public final class SyncRunner {
    private static final Logger log = LoggerFactory.getLogger(SyncRunner.class);
    /**
     * A scanner or another reader can hold a file of one message briefly; the download or the
     * conversion is retried before the run fails (docs/storage-agent.md, "Write order and failures").
     */
    private static final int MESSAGE_ATTEMPTS = 3;
    private static final long[] MESSAGE_RETRY_MILLIS = {250, 1000};
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
        run(new SyncListener() {
            @Override public void restored(int count) { restored.accept(count); }
            @Override public void progress(FolderProgress p) { progress.accept(p); }
        });
    }

    /** Runs once, recording each phase and folder progress in sync-status.json before telling the listener. */
    public void run(SyncListener listener) {
        var report = new Report(listener);
        // Only a run that did not complete can leave meta.json and the index apart (docs/decisions.md, Д30).
        boolean checkMetadata = !store.readSyncStatus().map(status -> "completed".equals(status.state())).orElse(false);
        try {
            report.phase(SyncPhase.CHECKING_LOCAL);
            synchronize(checkMetadata, report);
            report.write("completed", Instant.now(), null, false);
        } catch (RuntimeException e) {
            report.write("failed", Instant.now(), SyncErrors.describe(e), SyncErrors.signInRejected(e));
            throw e;
        }
    }

    /** Restores missing catalog entries from disk without accessing a mailbox. */
    public static void restore(MessageStore store, MailIndex index, MessageProcessor processor) {
        restore(store, index, processor, count -> { });
    }

    /** The offline command supplies progress; server reconciliation stays silent. */
    public static void restore(MessageStore store, MailIndex index, MessageProcessor processor, IntConsumer progress) {
        restore(store, index, processor, count -> { }, progress, true);
    }

    /**
     * @param checked       running count of documents compared with meta.json when checkMetadata is true
     * @param checkMetadata compare every meta.json with the index; see {@link MailIndex#reconcile(MessageStore, boolean)}
     */
    static void restore(MessageStore store, MailIndex index, MessageProcessor processor, IntConsumer checked,
                        IntConsumer progress, boolean checkMetadata) {
        int count = 0;
        for (String key : index.reconcile(store, checkMetadata, checked)) {
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

    private void synchronize(boolean checkMetadata, Report report) {
        var listener = report.listener;
        Consumer<FolderProgress> progress = report::progress;
        listener.checkingLocal(checkMetadata);
        restore(store, index, processor, listener::checked, listener::restored, checkMetadata);
        var local = store.folders();
        report.phase(SyncPhase.SIGNING_IN);
        listener.signingIn();
        var remote = source.folders();
        report.phase(SyncPhase.CHECKING_FOLDERS);
        listener.checkingFolders(remote.size());
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
        // moves or changes, never of every message (docs/decisions.md, Д30).
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
        // Folders the server lost after listing them: skipped with their old cursors (FolderGoneException).
        var goneFolders = new ArrayList<LocalFolder>();
        for (int i = 0; i < folders.size(); i++) {
            var folder = folders.get(i);
            var refs = index.folderRefs(folder.key());
            final FolderChanges changes;
            final Map<String, RemoteMessageInfo> addedInfo;
            try {
                changes = source.changes(remote.get(i), folder.cursor(), refs.keySet());
                addedInfo = changes.added().isEmpty() ? Map.of()
                        : source.metadata(remote.get(i), changes.cursor(), changes.added());
            } catch (FolderGoneException e) {
                goneFolders.add(folder);
                continue;
            }
            Set<String> removed = changes.reset() ? Set.copyOf(refs.keySet()) : Set.copyOf(changes.removed());
            removed.stream().map(refs::get).filter(java.util.Objects::nonNull).forEach(removedKeys::add);
            if (changes.reset()) { resetFolders.add(folder.key()); }
            // Known refs keep their stored fingerprints; flags come cheaply with the changes. Only
            // added refs need identity metadata (docs/decisions.md, Д29).
            var info = new HashMap<String, RemoteMessageInfo>();
            changes.observed().forEach((ref, observed) -> {
                if (refs.containsKey(ref) && !removed.contains(ref)) { info.put(ref, observed); }
            });
            info.putAll(addedInfo);
            int added = (int) changes.added().stream().filter(ref -> !refs.containsKey(ref) || removed.contains(ref)).count();
            int gone = (int) removed.stream().filter(refs::containsKey).count();
            if (added > 0 || gone > 0) { listener.changes(folder.path(), added, gone); }
            plans.add(new FolderPlan(folder, remote.get(i), changes, refs, removed, Map.copyOf(info)));
        }
        report.phase(SyncPhase.APPLYING_CHANGES);
        listener.applyingChanges();
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
            var indexed = renamed ? Map.<String, IndexedObservation>of() : index.folderObservations(plan.folder().key());
            for (var entry : plan.refs().entrySet()) {
                if (plan.removed().contains(entry.getKey())) { continue; }
                if (!renamed && !changed(plan.info().get(entry.getKey()), indexed.get(entry.getKey()))) { continue; }
                var old = store.readMeta(entry.getValue()).orElseThrow();
                var meta = identity(old, plan.folder(), entry.getKey(), plan.info().get(entry.getKey()));
                if (!meta.equals(old)) {
                    store.writeMeta(meta);
                    checkpoint.accept("metadataRefreshed");
                    if (!old.folder().equals(meta.folder()) || !java.util.Objects.equals(old.read(), meta.read())
                            || !java.util.Objects.equals(old.flagged(), meta.flagged())
                            || !java.util.Objects.equals(old.labels(), meta.labels())
                            || !java.util.Objects.equals(old.stableId(), meta.stableId())) {
                        put(meta.state() == MessageState.CONVERTED ? processor.load(meta) : new Processed(meta, null, null, null));
                    }
                }
            }
        }
        index.commit();
        checkpoint.accept("movesCommitted");

        report.phase(SyncPhase.DOWNLOADING);
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
        goneFolders.forEach(folder -> completed.add(new FolderProgress(folder.path(), 0, 0, 0, false, true, false, true)));
        var goneKeys = new HashSet<String>();
        goneFolders.forEach(folder -> goneKeys.add(folder.key()));
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
            boolean gone = false;
            for (String ref : changes.added()) {
                // Current local refs, not merely old cursors, make interrupted runs idempotent.
                if (currentRefs.containsKey(ref)) { continue; }
                String key = store.newKey();
                var dir = store.createMessageDir(key);
                MessageMeta downloadedMeta;
                try {
                    var mime = retrying("downloading a message",
                            () -> source.download(plan.remote(), ref, dir.resolve(MessageStore.MESSAGE_FILE)));
                    downloadedMeta = identity(new MessageMeta(key, folder.key(), folder.path(), ref, mime.size(),
                            mime.sha256(), Instant.now(), MessageState.DOWNLOADED, null),
                            folder, ref, plan.info().get(ref));
                } catch (MessageUnavailableException e) {
                    downloadedMeta = identity(new MessageMeta(key, folder.key(), folder.path(), ref, 0, null,
                            Instant.now(), MessageState.FAILED, e.getMessage()), folder, ref, plan.info().get(ref));
                } catch (FolderGoneException e) {
                    // Saved messages stay until the next run, which no longer lists the folder, removes them.
                    store.delete(key);
                    goneKeys.add(folder.key());
                    gone = true;
                    break;
                }
                MessageMeta meta = downloadedMeta;
                store.writeMeta(meta);
                var processed = meta.state() == MessageState.DOWNLOADED
                        ? retrying("converting a message", () -> processor.process(meta))
                        : new Processed(meta, null, null, null);
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
            completed.add(new FolderProgress(folder.path(), downloaded, failed, removed, changes.reset(), true,
                    changes.rescanned(), gone));
        }
        checkpoint.accept("beforeCursorSave");
        // A gone folder keeps its old cursor, so the next run sees it as vanished rather than finished.
        var cursors = new HashMap<String, String>();
        plans.forEach(p -> cursors.put(p.folder().key(), p.changes().cursor()));
        store.saveFolders(folders.stream().map(f -> goneKeys.contains(f.key()) ? f
                : new LocalFolder(f.key(), f.remoteId(), f.path(), cursors.get(f.key()))).toList());
        completed.forEach(progress);
    }

    /**
     * Unique apparent moves. Candidates are removed converted messages with MIME. A candidate with a
     * provider stableId (Gmail X-GM-MSGID) matches only an added message with the same stableId; this
     * identity survives UID changes, so it also applies within a folder and across a UIDVALIDITY reset.
     * Other candidates use the Message-ID heuristic: their saved fingerprint or, without one, their saved
     * headers, only across folders and never with a reset. Either way an identity must have exactly one
     * stored copy (the candidate) and exactly one current remote copy; retained copies count as current.
     * Uniqueness reads only the documents found by stableId or Message-ID (Д30).
     */
    private Map<String, MessageMeta> matches(Map<String, MessageMeta> removedMetas, Set<String> removedKeys,
                                             List<FolderPlan> plans, Set<String> resets) {
        var result = new HashMap<String, MessageMeta>();
        if (plans.stream().allMatch(plan -> plan.changes().added().isEmpty())) { return result; }
        var candidateIdentities = new HashMap<String, Identity>();
        for (var meta : removedMetas.values()) {
            Path eml = store.messageDir(meta.key()).resolve(MessageStore.MESSAGE_FILE);
            if (meta.state() != MessageState.CONVERTED || !Files.isRegularFile(eml)) { continue; }
            if (meta.stableId() != null) {
                candidateIdentities.put(meta.key(), new Identity(meta.stableId(), null));
                continue;
            }
            if (resets.contains(meta.folderKey())) { continue; }
            var fingerprint = meta.fingerprint();
            if (fingerprint == null || !fingerprint.complete()) {
                fingerprint = localFingerprint(eml); // never fetched: messages saved before Д8
            }
            if (fingerprint != null && fingerprint.complete()) { candidateIdentities.put(meta.key(), new Identity(null, fingerprint)); }
        }
        if (candidateIdentities.isEmpty()) { return result; }

        var candidates = new HashMap<Identity, MessageMeta>();
        var oldCounts = new HashMap<Identity, Integer>();
        var remoteCounts = new HashMap<Identity, Integer>();
        candidateIdentities.forEach((key, identity) -> candidates.put(identity, removedMetas.get(key)));
        for (var identity : Set.copyOf(candidateIdentities.values())) {
            // Candidates always count, even when their saved MIME has no Message-ID header to index.
            var keys = new HashSet<>(identity.stableId() != null ? index.keysByStableId(identity.stableId())
                    : index.keysByMessageId(identity.fingerprint().messageId()));
            candidateIdentities.forEach((key, candidate) -> { if (candidate.equals(identity)) { keys.add(key); } });
            for (String key : keys) {
                if (identity.stableId() == null) {
                    var stored = candidateIdentities.containsKey(key) ? candidateIdentities.get(key).fingerprint()
                            : removedMetas.containsKey(key) ? removedMetas.get(key).fingerprint()
                            : store.readMeta(key).map(MessageMeta::fingerprint).orElse(null);
                    if (!identity.fingerprint().equals(stored)) { continue; }
                }
                oldCounts.merge(identity, 1, Integer::sum);
                // Retained messages are current server copies: their identity does not change remotely.
                if (!removedKeys.contains(key)) { remoteCounts.merge(identity, 1, Integer::sum); }
            }
        }
        for (var plan : plans) {
            for (String ref : plan.changes().added()) {
                var identity = matchIdentity(plan.info().get(ref), candidates);
                if (identity != null) { remoteCounts.merge(identity, 1, Integer::sum); }
            }
        }
        for (var plan : plans) {
            boolean reset = resets.contains(plan.folder().key());
            for (String ref : plan.changes().added()) {
                if (!reset && plan.refs().containsKey(ref)) { continue; }
                var identity = matchIdentity(plan.info().get(ref), candidates);
                var old = identity == null ? null : candidates.get(identity);
                if (old == null || oldCounts.getOrDefault(identity, 0) != 1 || remoteCounts.getOrDefault(identity, 0) != 1) {
                    continue;
                }
                if (identity.stableId() == null && (reset || old.folderKey().equals(plan.folder().key()))) { continue; }
                result.put(plan.folder().key() + "/" + ref, old);
            }
        }
        return result;
    }

    /**
     * The candidate identity an added message would match: its stableId when a candidate has that one,
     * otherwise its complete fingerprint. A candidate with a stableId is never matched by fingerprint.
     */
    private static Identity matchIdentity(RemoteMessageInfo info, Map<Identity, MessageMeta> candidates) {
        if (info == null) { return null; }
        if (info.stableId() != null) {
            var stable = new Identity(info.stableId(), null);
            if (candidates.containsKey(stable)) { return stable; }
        }
        var fingerprint = complete(info);
        return fingerprint == null ? null : new Identity(null, fingerprint);
    }

    /** A provider stableId, or else a Message-ID fingerprint for the heuristic match. */
    private record Identity(String stableId, MessageFingerprint fingerprint) { }

    /** True when an observation differs from what the index holds for the message. */
    private static boolean changed(RemoteMessageInfo info, IndexedObservation indexed) {
        if (info == null) { return false; }
        if (info.fingerprint() != null || indexed == null) { return true; }
        return info.read() != null && !info.read().equals(indexed.read())
                || info.flagged() != null && !info.flagged().equals(indexed.flagged())
                || info.labels() != null && !info.labels().equals(indexed.labels())
                || info.stableId() != null && !info.stableId().equals(indexed.stableId());
    }

    private static MessageFingerprint localFingerprint(Path eml) {
        try { return MimeReader.fingerprint(eml); }
        catch (MessagingException | IOException | RuntimeException e) { return null; }
    }

    private static MessageFingerprint complete(RemoteMessageInfo info) {
        return info != null && info.fingerprint() != null && info.fingerprint().complete() ? info.fingerprint() : null;
    }

    /** Applies an observation: values it lacks keep the stored ones, and only fresh metadata replaces the fingerprint. */
    private static MessageMeta identity(MessageMeta old, LocalFolder folder, String ref, RemoteMessageInfo info) {
        return new MessageMeta(old.key(), folder.key(), folder.path(), ref, old.size(), old.sha256(),
                old.downloadedAt(), old.state(), old.error(),
                info == null || info.fingerprint() == null ? old.fingerprint() : complete(info),
                info == null || info.read() == null ? old.read() : info.read(),
                info == null || info.flagged() == null ? old.flagged() : info.flagged(),
                info == null || info.stableId() == null ? old.stableId() : info.stableId(),
                info == null || info.labels() == null ? old.labels() : info.labels());
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

    /** Runs one local step of a message again after a storage failure; the step is idempotent. */
    private <T> T retrying(String action, Supplier<T> step) {
        for (int attempt = 1; ; attempt++) {
            try {
                return step.get();
            } catch (UncheckedIOException e) {
                if (attempt == MESSAGE_ATTEMPTS || !pause(attempt)) { throw e; }
                log.warn("Local storage error while {} (attempt {}/{}); retrying: {}",
                        action, attempt, MESSAGE_ATTEMPTS, e.toString());
            }
        }
    }

    /** Waits before the next attempt; returns false when the thread was interrupted. */
    private static boolean pause(int attempt) {
        try {
            Thread.sleep(MESSAGE_RETRY_MILLIS[Math.min(attempt - 1, MESSAGE_RETRY_MILLIS.length - 1)]);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record FolderPlan(LocalFolder folder, RemoteFolder remote, FolderChanges changes,
                              Map<String, String> refs, Set<String> removed, Map<String, RemoteMessageInfo> info) { }

    /** sync-status.json of this run: the phase reached and folder progress in first-appearance order. */
    private final class Report {
        private final SyncListener listener;
        private final Instant startedAt = Instant.now();
        private final long pid = ProcessHandle.current().pid();
        private final Map<String, FolderProgress> folders = new LinkedHashMap<>();
        private SyncPhase phase = SyncPhase.CHECKING_LOCAL;

        private Report(SyncListener listener) { this.listener = listener; }

        private void write(String state, Instant finishedAt, String error, boolean signInRejected) {
            try {
                store.writeSyncStatus(new SyncStatus(state, pid, startedAt, finishedAt,
                        List.copyOf(folders.values()), error, signInRejected, phase.id()));
            } catch (RuntimeException e) {
                // The status file is diagnostic: a locked or unwritable file must not stop the run.
                log.warn("Could not write sync-status.json; the run continues", e);
            }
        }

        private void phase(SyncPhase next) {
            phase = next;
            log.info("Sync phase: {}", next.id());
            write("running", null, null, false);
        }

        private void progress(FolderProgress progress) {
            folders.put(progress.path(), progress);
            write("running", null, null, false);
            listener.progress(progress);
        }
    }
}
