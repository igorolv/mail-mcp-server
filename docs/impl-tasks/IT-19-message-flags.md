# IT-19 — Read and marked-message flags (Д9)

Prepared: 2026-10-27. Depends on accepted IT-18 and IT-17; keep the IT-22 rescan/summary behavior.
Re-read their resulting contracts before editing. IT-18 provides the metadata-only snapshot.

## Goal and meaning

Synchronize two nullable booleans into meta.json, the derived index and local message results:
`read` and `flagged`. In this brief the plan's “важное” means a **user flag/star**, named flagged
to avoid confusing it with sender-assigned message importance. EWS uses message:IsRead and
item:Flag/FlagStatus (Flagged=true, NotFlagged/Complete=false); IMAP uses \\Seen and \\Flagged.
Do not map EWS Importance:High or Gmail's Important label to this flag. Document this concrete
implementation choice; it is not an additional user decision recorded in plan.md section 5.

Flags are a snapshot from the latest successful observation, not live mailbox state. All
operations remain read-only. Existing mail gains flags on the next user-requested sync.

## Read first

- `AGENTS.md`, `docs/impl-tasks/README.md`, `docs/plan.md` 3.1, 3.4, 3.6–3.7 and Д9 in section 4.
- `docs/ews-source-agent.md`, `docs/imap-source-agent.md`, `docs/storage-agent.md`,
  `docs/tools-agent.md`, `docs/redaction-agent.md`, `docs/build-config-agent.md`.
- `docs/research/RT-03-ews-operations.md`, `docs/research/RT-04-imap-gmail-mailru.md` (flags only;
  do not implement their superseded sync architecture).
- `src/main/java/mail/mcp/source/RemoteMessageInfo.java` (created by IT-18),
  `src/main/java/mail/mcp/source/ews/EwsClient.java`, `EwsResponseReader.java`, `EwsMailSource.java`
  in the same directory; `src/main/java/mail/mcp/source/imap/ImapClient.java`,
  `ImapMailSource.java`, `ReadOnlyImapStore.java` in the same directory.
- `src/main/java/mail/mcp/model/MessageMeta.java`, `MessageCard.java` in the same directory;
  `src/main/java/mail/mcp/sync/SyncRunner.java`, `MessageProcessor.java` in the same directory;
  `src/main/java/mail/mcp/index/MailIndex.java`, `MailIndexReader.java` in the same directory.
- `src/main/java/mail/mcp/tools/MailTools.java`, `SearchTools.java`, `ThreadTools.java` in the
  same directory (ThreadTools is created by IT-17).
- `src/main/java/mail/mcp/api/MessageSummary.java`, `SearchHitInfo.java`, `GetMessageResult.java`,
  `ThreadMessageInfo.java` in the same directory (the last is created by IT-17).
- Existing corresponding source, sync, store, index, tool tests and `McpStdioServerTest`.

## What to build

1. Append nullable Boolean read/flagged to RemoteMessageInfo and MessageMeta. Absent old JSON
   fields are null, never false. Keep compatibility constructors defaulting new fields to null.
   False must serialize as false, true as true; null is omitted under the existing mapper policy.
   Preserve both through all state changes, MIME failures, reprocessing and reindexing.
2. Extend IT-18's **one** metadata snapshot per folder to fetch flags for all present refs on
   every sync, including unchanged known messages. EWS adds message:IsRead and item:Flag to
   the metadata GetItem request; no MIME. IMAP adds FLAGS to the batch UID/envelope/size fetch.
   The existing command guard already allows FLAGS; do not permit STORE or SELECT. Ensure
   Angus actually refreshes flags instead of returning stale values cached earlier in the run.
3. A present empty IMAP FLAGS list means read=false, flagged=false. An absent/malformed flags
   response does not mean false. For EWS recognize all three FlagStatus values and explicit
   true/false IsRead; missing/unsupported values are null. Unknown values must not turn true
   into false silently. Missing per-item data leaves the existing stored flag unchanged; an
   explicit false clears it. If one field is known and the other missing, update only the known
   field. Preserve the normal sanitized protocol/auth error handling and no retry policy.
4. Persist observed flags for added messages, including a failed download when metadata was
   obtained. For existing messages, update flags without calling download or process, touching
   MIME/body/attachments, changing download time/state/error or advancing a cursor prematurely.
   Update changed metadata atomically and refresh its Lucene document using saved content.
   Unchanged flags must not cause needless metadata/index rewrites. EWS Update/ReadFlagChange
   need not be separately interpreted: the full metadata pass observes the final flag state,
   even if there are no additions/removals. Explain this correctness/traffic tradeoff.
5. IT-18 move identity excludes flags: toggling a flag must not prevent a move match. After a
   reused move, apply the destination's observed flags. A UIDVALIDITY reset uses fresh metadata,
   never the old UID's flag values. Reused moves and flag-only updates remain zero downloaded,
   failed and removed; preserve rescan reporting.
6. Add stored StringFields read/flagged with literal true/false; omit unknown values. Extend
   MessageCard with nullable Boolean fields. Increment MailIndex.SCHEMA from 3 to 4 (or current
   schema + 1 if the batch baseline has changed), using the existing automatic offline rebuild
   mechanism. No migration command. Restored old metadata produces unknown flags; reindexing
   alone cannot obtain remote state. Extend IT-18 reconciliation to repair mismatched persisted
   flags as well as folder/ref after an interrupted metadata-before-index write.
7. Append optional read/flagged fields to MessageSummary, SearchHitInfo, GetMessageResult and
   ThreadMessageInfo. Populate listMessages, search (text and date-only), getMessage and
   getThread. getMessage uses metadata even if MIME cannot be parsed; list/search/thread use
   committed index values. Booleans are not passed through SecretRedactor. Do not expose raw
   protocol structures or add filters, counters or mutation tools.

## Named tests

- `EwsRequestTest.metadataRequestsReadAndFlagWithoutMime`
- `EwsResponseReaderTest.metadataReadsFlaggedCompleteNotFlaggedAndUnknown`
- `ImapClientTest.metadataRefreshesSeenAndFlaggedWithoutWrites`
- `MessageStoreTest.oldMetadataFlagsAreUnknownAndFalseRoundTrips`
- `MessageProcessorTest.reprocessingPreservesKnownAndUnknownFlags`
- `SyncRunnerTest.flagOnlySyncUpdatesExistingMessageWithoutDownloadOrConversion`
- `SyncRunnerTest.missingFlagDataDoesNotClearKnownValues`
- `SyncRunnerTest.moveUsesDestinationFlagsWithoutChangingItsIdentityMatch`
- `SyncRunnerTest.uidValidityResetDoesNotInheritOldUidFlags`
- `MailIndexTest.rebuildAndReconciliationPreserveAndRepairFlags`
- `MailToolsTest.messageAndListExposeTrueFalseAndUnknownFlags`
- `SearchToolsTest.textAndDateSearchExposeStoredFlags`
- `ThreadToolsTest.threadExposesStoredFlags`
- `McpStdioServerTest.messageFlagsAreOptionalBooleansInSchemaAndContent`

Use synthetic server responses, including a second run with only read/flag changes and a move
with different flags. Assert unchanged content bytes, SHA-256 and download time, no body requests
on known messages, no STORE/SELECT, and unchanged single-auth behavior. Test metadata written
before a simulated failure: restore must repair the existing index document. Keep old constructor
fixtures working and include explicit old JSON, not only null-valued new records.

## Files

Change the production files explicitly named in Read first (including prior-brief files).
Change tests in the corresponding source/ews, source/imap, sync, store, index and tools packages
that are named above, `FakeMailSource`, `FakeImapServer`, and `McpStdioServerTest`. Synthetic EWS
fixtures may be added under `src/test/resources/ews/`. No new production abstraction is needed.
Only the documents below may change.

## Documents and references

Update `README.md`, `docs/architecture-agent.md`, `docs/storage-agent.md`, `docs/tools-agent.md`,
`docs/ews-source-agent.md`, `docs/imap-source-agent.md`, `docs/redaction-agent.md`: flag meanings,
unknown versus false, refresh of known mail, schema rebuild and immutable MIME. Recording
updates plan.md 3.4/3.6/3.7 and the schema/flags statements in AGENTS.md.

Primary references: [EWS FieldURI](https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/fielduri),
[item Flag](https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/flag),
[FlagStatus](https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/flagstatus),
[RFC 3501 flags and FETCH](https://www.rfc-editor.org/rfc/rfc3501.html).
The EWS field list and IMAP flag semantics were checked on 2026-10-27; validate exact EWS
payload handling with synthetic responses. Real-server behavior remains **проверить**.

## Do not

Do not add write operations, importance/label synchronization, automatic sync, flags filters,
read-receipt sending, CONDSTORE/QRESYNC, new authentication, dependencies or settings. Do not
redownload MIME to update flags or count flag changes as newly downloaded mail.

## Acceptance

Run `.\gradlew.bat build` and shared checks. Offline acceptance can leave real-mail validation
pending: the user changes read/star flags in their own client, runs sync, and compares local
results, including clearing flags. Agents never change remote flags or sign in.
