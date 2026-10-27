# IT-18 — Reuse local messages after heuristic move detection (Д8)

Prepared: 2026-10-27. Baseline: `d3fd516`, plus accepted IT-22, IT-21 and IT-17.
Implement before IT-19, which extends this task's remote metadata snapshot.

## Goal and user decision

Avoid downloading and converting MIME again for an unambiguous apparent move between synced
folders of one connection. On 2026-10-27 the user explicitly accepted **a heuristic based on
Message-ID and metadata**, prioritizing no repeat download. Do not claim byte identity:
Downloaded.sha256 is computed from downloaded bytes; it is not a remote checksum.

Preserve the local UUID and its directory. Directories are keyed by UUID, not folder: a move
changes meta.json and the index, not the physical directory path. SHA-256 continues to describe
the saved original. A false positive is possible even with all metadata equal; document it.

## Read first

- `AGENTS.md`, `docs/impl-tasks/README.md`, `docs/plan.md` 3.2, 3.4, 3.6, 3.8, Д8 and decision П2.
- `docs/architecture-agent.md`, `docs/storage-agent.md`, `docs/ews-source-agent.md`,
  `docs/imap-source-agent.md`, `docs/tools-agent.md`, `docs/build-config-agent.md`.
- `src/main/java/mail/mcp/source/MailSource.java`, `FolderChanges.java`, `Downloaded.java`
  in the same directory.
- `src/main/java/mail/mcp/source/ews/EwsClient.java`, `EwsResponseReader.java`, `EwsMailSource.java`
  in the same directory; `src/main/java/mail/mcp/source/imap/ImapClient.java`,
  `ImapMailSource.java`, `ReadOnlyImapStore.java` in the same directory.
- `src/main/java/mail/mcp/sync/SyncRunner.java`, `MessageProcessor.java` in the same directory;
  `src/main/java/mail/mcp/model/MessageMeta.java`, `LocalFolder.java` in the same directory;
  `src/main/java/mail/mcp/store/MessageStore.java`, `src/main/java/mail/mcp/index/MailIndex.java`.
- Existing SyncRunnerTest, FakeMailSource, MessageStoreTest, MailIndexTest, EwsRequestTest,
  EwsResponseReaderTest, EwsMailSourceTest, ImapClientTest, ImapMailSourceTest and FakeImapServer
  in their matching `src/test/java/mail/mcp/` packages.

## What to build

### Remote identity metadata

1. Add `model/MessageFingerprint(messageId, size, sentAt, from, subject)`: size is a nullable
   Long **reported by the source**, sentAt is Instant, other values are strings. Require a
   single nonblank Message-ID, positive size, known sentAt, nonblank sender address and nonblank
   subject to use it for matching. Strip only outer whitespace from ID/address; preserve case
   and decoded subject. Compare exact values. No body-derived identity, subject normalization
   or substitution of local MIME byte size for EWS item size.
2. Append nullable `fingerprint` to MessageMeta, with an old-signature constructor defaulting
   to null. Preserve it through MessageProcessor state transitions, reprocessing and reindexing.
   Old JSON remains readable; no index-schema change is required for the fingerprint.
3. Add `source/RemoteMessageInfo(ref, fingerprint)` and a MailSource metadata method accepting
   folder, the expected opaque cursor and a collection of present refs; return immutable entries
   keyed by ref. Use a single metadata snapshot per folder/run, with requests in batches of at
   most 100 refs. Read it for every present ref, including known messages, to seed old stores
   and detect ambiguity among retained copies. Missing optional fields disable matching;
   absent/deleted items are omitted. Other protocol/auth/transport errors abort without retry.
   No MIME or body fetch is allowed in this method.
4. EWS: use the existing allow-listed GetItem with IdOnly and AdditionalProperties for
   message:InternetMessageId, item:Size, item:DateTimeSent, message:From, item:Subject.
   Do not request IncludeMimeContent or Body. Add a dedicated response reader that validates
   each response, maps by returned ItemId, handles ErrorItemNotFound per requested item and
   never mistakes a missing item for the next response. Reject duplicates/unrequested IDs.
   Use existing envelope/security checks and one session. EWS size can differ from MIME size.
5. IMAP: explicitly extend the FETCH allow-list for **ENVELOPE** only; it supplies Message-ID,
   date, From and decoded subject, alongside UID and RFC822.SIZE. Preserve EXAMINE and the
   exact FETCH attribute guard; BODY, RFC822 body reads, SELECT and writes remain forbidden.
   Use Angus fetch profiles in batches, then cached envelope values. Require exactly one sender
   for the fingerprint. Verify UIDVALIDITY still matches the change snapshot before trusting
   these refs; if it changed during the run, abort rather than matching reused UIDs. No
   CONDSTORE, Gmail extensions, arbitrary headers or relaxed wildcard command allow-list.

### Plan before applying removals

6. After normal restoration, gather folder changes for **all** included folders before deleting
   messages, including candidates in vanished folders. Keep original metadata/fingerprints
   in memory. Calculate the resulting present refs and obtain the metadata snapshots above.
   Do not advance any folder cursor during this discovery stage. A discovery failure leaves
   local messages and old cursors available for the next run.
7. Match only an old, converted message with existing MIME which is explicitly removed by this
   run, against a new ref in a different folder. Its saved fingerprint must match the new
   complete fingerprint. There must be exactly one old occurrence of that fingerprint in the
   connection and exactly one current remote occurrence; a retained identical copy or multiple
   candidates disables reuse. Never reuse from another connection, a failed/downloaded entry,
   an entry lacking the saved fingerprint, or a folder undergoing UIDVALIDITY reset. Both
   source and destination reset folders are excluded. Normal EWS rescans are not resets.
8. Unmatched additions/removals retain normal behavior. Old stores gain fingerprints during
   ordinary sync without reprocessing; a message moved before it was seeded falls back to a
   download. Known messages retain their latest complete fingerprint; an incomplete refreshed
   identity must clear it to null, not silently keep stale evidence. Capture candidates from
   the pre-run snapshot before refreshing retained metadata.
9. For a match, atomically rewrite metadata with the new folder key/path/ref/fingerprint,
   preserving key, state, error, MIME size/SHA-256/download time and every content file. Rebuild
   that index document using MessageProcessor.load, never process/download. Apply matches
   before unmatched deletions, and delete only if metadata still identifies the old folder/ref.
   A source folder disappearing must not delete a key already reassigned to the destination.
   Index writes stay under the existing writer lock and are committed before saving cursors.
10. Persist new/retained folder identities with old/null cursors before downloads, retaining
    vanished-folder entries until their removals have committed. Save the final folder list
    and all new cursors only after all planned changes are complete. Partial batches may commit,
    as before; the next run must be idempotent with old cursors and the current local refs.
    Do not add a journal, tombstones or a second data store.
11. Close the metadata/index crash window: extend reconciliation to detect an existing index
    document whose stored folder/ref differs from its meta.json, and reindex it from disk.
    Keep this targeted; do not reconvert every message or rebuild the index on every run.
    Regression tests must inject interruption after metadata reassignment, after index commit
    and before cursor save. After restore/rerun there is one usable key at the destination,
    no content loss and no unnecessary repeat download. A partial first sync may still show
    partial committed results, as it does now.
12. Reuse counts as neither downloaded nor failed nor removed. Do not introduce moved counters
    in this brief; document that zero downloads can include reused moved messages. Preserve
    IT-22 reset/rescanned reporting and one final background summary.

## Named tests

- `EwsRequestTest.metadataRequestOmitsMimeAndBody`
- `EwsResponseReaderTest.metadataBatchHandlesMissingItemsWithoutRefMisassociation`
- `ImapClientTest.metadataFetchUsesEnvelopeSizeUidAndNeverBodyOrWrites`
- `ImapClientTest.metadataRejectsUidValidityChange`
- `MessageStoreTest.oldMetadataHasNoFingerprint`
- `MessageProcessorTest.reprocessingPreservesRemoteFingerprint`
- `SyncRunnerTest.moveReusesKeyAndContentInEitherFolderOrder`
- `SyncRunnerTest.moveFromVanishedFolderPreservesReassignedDirectory`
- `SyncRunnerTest.ambiguousOrIncompleteMoveIdentityFallsBackToDownload`
- `SyncRunnerTest.retainedCopyAndChangedMetadataDoNotReuseKey`
- `SyncRunnerTest.firstSyncSeedsFingerprintWithoutRedownloadingKnownMime`
- `SyncRunnerTest.uidValidityResetNeverUsesMoveHeuristic`
- `SyncRunnerTest.moveDiscoveryFailureDoesNotDeleteCandidatesOrAdvanceCursors`
- `SyncRunnerTest.interruptedMoveConvergesWithoutContentLossOrRepeatDownload`
- `MailIndexTest.reconcileRepairsRefChangedInMetadata`

Assert byte-for-byte equality for MIME/body/attachments/sidecars and unchanged SHA-256/time.
Cover both directions of folder order, duplicate IDs with differing metadata, a removed message
with no corresponding addition, different connections, failed entries, and repeats of a run.
Use synthetic XML/fake IMAP only. Verify auth rejection still produces a single sign-in attempt.

## Files

Create `src/main/java/mail/mcp/model/MessageFingerprint.java` and
`src/main/java/mail/mcp/source/RemoteMessageInfo.java`. Change only the production files named in Read first,
plus a new package-private `src/main/java/mail/mcp/sync/MessageMoves.java` if needed to keep
matching separate from orchestration. No other production classes are required.
The named test classes, `src/test/java/mail/mcp/sync/MessageProcessorTest.java` and
`src/test/java/mail/mcp/sync/SyncServiceTest.java` may change (the latter has anonymous MailSource
implementations that need the new metadata method);
synthetic fixtures may be added under `src/test/resources/ews/`. Existing test constructors
should remain supported. Change only the documents below.

## Documents and primary references

Update `README.md`, `docs/storage-agent.md`, `docs/ews-source-agent.md`,
`docs/imap-source-agent.md`, `docs/tools-agent.md`, `docs/architecture-agent.md`: heuristic risk,
first-sync seeding, metadata traffic, preserved paths, recovery/order and counter semantics.
Recording updates plan.md 3.2/3.4/3.6 and AGENTS.md's old delete-plus-add statement.

Protocol references checked 2026-10-27: [EWS ItemShape](https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/itemshape),
[EWS FieldURI](https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/fielduri),
[RFC 3501, sections 2.3.5 and 6.4.5](https://www.rfc-editor.org/rfc/rfc3501.html).
Verify the exact Angus 2.0.5 emitted fetch attributes in the fake-server test; availability on
the user's servers remains **проверить**, not a claimed real-mail result.

## Do not

Do not compute a supposed remote SHA-256 without downloading, merge retained duplicates (Д4),
perform remote moves/writes, preserve candidates across completed runs, retry sign-in, add
Gmail setup, or introduce new dependencies, settings, generic transactions or a shared budget.

## Acceptance

Run `.\gradlew.bat build` and shared checks. Offline acceptance is possible with real-mail
verification pending. The user can later sync to seed identities, manually move one test
message between included folders in their mail client, then sync and check stable key/path
and zero MIME downloads for it. Agents must not perform the mailbox move or sign in.
