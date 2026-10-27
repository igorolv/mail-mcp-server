# IT-22 — Background sync summary and EWS rescan visibility (Д25)

Prepared: 2026-10-27. Baseline: `d3fd516`. Implement first in this batch, before IT-18/IT-19.

## Goal

Emit one useful INFO summary when a background run ends, and expose recovery from an invalid
EWS SyncState without deleting or redownloading known messages.

## Read first

- `AGENTS.md`, `docs/impl-tasks/README.md`, `docs/plan.md` 3.2, 3.6–3.7 and Д25 in section 4.
- `docs/ews-source-agent.md`, `docs/imap-source-agent.md`, `docs/tools-agent.md`,
  `docs/storage-agent.md`, `docs/build-config-agent.md`.
- `src/main/java/mail/mcp/source/FolderChanges.java`,
  `src/main/java/mail/mcp/source/ews/EwsMailSource.java`,
  `src/main/java/mail/mcp/source/imap/ImapMailSource.java`.
- `src/main/java/mail/mcp/sync/SyncService.java`, `SyncRunner.java` in the same directory.
- `src/main/java/mail/mcp/model/FolderProgress.java`, `SyncStatus.java` in the same directory.
- `src/main/java/mail/mcp/api/FolderSyncInfo.java`,
  `src/main/java/mail/mcp/tools/SyncTools.java`.
- `src/test/java/mail/mcp/sync/SyncServiceTest.java`, `SyncRunnerTest.java`, `FakeMailSource.java`
  in the same directory; `src/test/java/mail/mcp/source/ews/EwsMailSourceTest.java`.

## What to build

1. Keep `FolderChanges.reset` exclusively for invalidated remote refs (IMAP UIDVALIDITY).
   **Do not make EWS return reset=true:** SyncRunner currently deletes all known messages on
   that signal. Append `boolean rescanned` to FolderChanges; keep its four-argument constructor
   delegating with false. EWS sets rescanned=true only when the initial non-null SyncState gets
   ErrorInvalidSyncStateData and the existing single restart without state succeeds. Ordinary
   first/full sync is false. Preserve no-retry behavior for a second invalid state, later-page
   errors, authentication and all other errors.
2. Append `rescanned` to FolderProgress and FolderSyncInfo; keep old constructors where needed
   for fixtures. Pass it through persisted sync-status.json and syncStatus results, in every
   progress report for the affected folder. Old status JSON lacking it reads as false.
   Keep the existing reset/done meanings. EWS recovery is reset=false, rescanned=true; an IMAP
   UIDVALIDITY change is reset=true, rescanned=false. Document both explicitly.
3. In the background worker in SyncService measure elapsed time with System.nanoTime and keep
   the latest cumulative progress per folder (replace, do not sum successive batch reports).
   After resource closure emit exactly one INFO line with a fixed template:
   `Background sync finished: state={}, durationMs={}, downloaded={}, failed={}, removed={}`.
   Use state completed only if run and close both succeeded; otherwise failed. Include zero
   work runs and runs ending in RuntimeException. Preserve the existing sanitized WARN for a
   failure. No summary for refused/already-running/locked starts or errors before worker start.
4. Counters have the same meaning as syncStatus: downloaded counts converted new messages,
   failed counts per-message failures, removed counts removed local messages. A run-level error
   does not invent a failed message. On failure these are the latest reported counters, which
   can lag an unreported partial batch; say so in the documentation. Do not add another status
   writer or read status back for logging. Folder names, connection names, refs, subjects,
   addresses, credentials, paths and exception messages are absent from the summary arguments.
5. Log via SLF4J only. Do not change stdout, CLI progress, appenders, logging levels, retries,
   lock ownership or successful/failed status persistence. A close error may leave persisted
   status completed while the background summary says failed; document this existing lifecycle
   boundary instead of adding an unrelated status redesign.

## Named tests

- `EwsMailSourceTest.invalidSyncStateReportsRescanWithoutResetOrKnownDownloads`
- `EwsMailSourceTest.initialFullSyncIsNotARescan`
- `SyncRunnerTest.ewsRescanPreservesKnownFilesAndReportsRescanned`
- `SyncRunnerTest.uidValidityResetStillReplacesKnownMessages`
- `MessageStoreTest.oldSyncStatusDefaultsRescannedToFalse`
- `SyncToolsTest.syncStatusExposesRescannedSeparatelyFromReset`
- `SyncServiceTest.backgroundCompletionLogsFinalTotalsOnce`
- `SyncServiceTest.backgroundFailureLogsPartialTotalsWithoutMailData`
- `SyncServiceTest.nonStartedRunsDoNotLogCompletion`
- `SyncServiceTest.closeFailureDoesNotLogSuccessfulCompletion`

Use a test Logback ListAppender scoped to the SyncService logger, restored in finally.
Test multiple cumulative reports (100 then 101 must total 101), empty runs and two folders.
Do not assert an exact duration or sleep for timing; wait for worker completion with existing
test synchronization patterns. Inspect log message arguments for synthetic sentinel mail data.
Keep the original invalid-state and IMAP reset regression tests.

## Files

Change only the production classes named in Read first, plus
`src/main/java/mail/mcp/source/imap/ImapMailSource.java` if constructor adaptation is needed.
Change the named test classes in their corresponding packages, including
`src/test/java/mail/mcp/store/MessageStoreTest.java` and
`src/test/java/mail/mcp/tools/SyncToolsTest.java`. FakeMailSource may gain fixture controls.
No new production class is needed. Change only the documents below.

## Documents

Update `README.md`, `docs/tools-agent.md`, `docs/storage-agent.md`,
`docs/ews-source-agent.md`, `docs/imap-source-agent.md`: the two distinct signals, old JSON
compatibility and summary fields/counter semantics. Recording updates plan.md 3.6–3.7.

## Do not

Do not use reset as a display-only flag, redownload known EWS messages, add settings, a
scheduler, notifications, retries, a metrics subsystem or detailed per-message logging.

## Acceptance

Run `.\gradlew.bat build` and shared checks. Offline tests suffice; do not invalidate real EWS
cursors to force a demonstration. The user can observe the next requested background run.
