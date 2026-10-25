# IT-03 — `SyncRunner` and the `mail-sync` command

Plan step: `docs/plan.md` 7.2, second half. Size: one full session. Next brief: IT-04 (EWS
source, step 7.3; not written yet).

## Goal

Synchronize one connection end to end: reconcile disk and index, compare the server's folders
with `folders.json`, apply removals, download new messages as `message.eml` + `meta.json`, index
them and move the cursor. The user runs it with a new jar mode `mail-sync <connection>`. The
user-run commands move from package `spike/` to `cli/`. Nothing is parsed or converted yet: every
downloaded message stays `downloaded`.

## Read first

1. `docs/plan.md` sections 6.2, 6.6, 6.9 and 7.2 (Russian; what you need is repeated below).
2. `docs/storage-agent.md`, then `src/main/java/mail/mcp/{model,store,index}/` — all files.
3. `src/main/java/mail/mcp/source/` — `MailSource`, `RemoteFolder`, `FolderChanges`,
   `Downloaded`, `MessageUnavailableException`; `source/imap/ImapMailSource.java`.
4. `src/main/java/mail/mcp/spike/ImapSpikeCommand.java` and its test — the pattern for the new
   command; `EwsSpikeCommand.java`, `GmailOAuthCommand.java` only to move them.
5. `src/main/java/mail/mcp/MailMcpServerApplication.java`, `build.gradle.kts` (the spike tasks).
6. `docs/build-config-agent.md`, section "The IMAP spike".

## Already decided — do not revisit

- One run = one `MailSource` session (one sign-in), one `MailIndex` writer, one `MessageStore`.
  The writer is opened **before** the first network request, so a second process is refused
  without signing in.
- Run order (plan.md 6.2): reconcile, folders, then per folder: removals, downloads in batches of
  100 with an index commit after each batch, then the folder's cursor into `folders.json`. The
  cursor of a folder moves only after the whole folder is done.
- No retries anywhere. A sign-in failure, a broken connection or a protocol error ends the run
  (the exception propagates). `MessageUnavailableException` affects one message only.
- A message that cannot be downloaded is stored as `state=failed` with `size` 0, `sha256` null
  and `error` = the exception's fixed message; its directory has no `message.eml`. It is not
  downloaded again: its ref is known to the index. When the server no longer has it, the next run
  removes it like any other ref.
- A folder that the server no longer lists is deleted locally with all its messages. A move on the
  server is a removal plus an addition (new key).
- `sync-status.json` is **not** written in this task. It arrives with the `syncStatus` tool that
  reads it (IT-06). `mail-sync` shows progress itself.
- Pandoc, MIME parsing, `body.md` and attachments are step 7.4.
- Package `spike/` becomes `cli/`; class names, jar modes (`ews-spike`, `imap-spike`,
  `gmail-oauth`), their output and the resource `logback-spike.xml` stay as they are. The spike's
  own data directory `<connection>/spike/` stays.

## Current code facts you need

- `MessageStore`: `folders()`, `saveFolders(..)`, `newKey()`, `createMessageDir(key)` (returns the
  new directory), `writeMeta(..)`, `delete(key)`, constant `MESSAGE_FILE`. `MailIndex`:
  `openWriter(connectionDir)` (`IndexLockedException` when locked), `put`, `delete`, `commit`,
  `folderRefs(folderKey)` (ref → key, sees uncommitted changes), `reconcile(store)`, `close()`
  (commits).
- `ImapMailSource.changes` already returns `removed` = all known refs and `added` = all present
  refs when `reset` is true. The runner must not rely on that: on `reset` it removes **every**
  local message of the folder itself (EWS in 7.3 may send `reset` without a full `removed` list),
  then downloads `added`.
- `MailConnection.openSource()` is lazy (no network) and throws `UnsupportedOperationException`
  for EWS until step 7.3.
- `ImapSpikeCommand.run(args, properties, out, factory, env)` is the testable entry point; keep
  the same shape for the new command.
- Lucene prints one `WARNING` about `jdk.incubator.vector` to `System.err` through JUL. That is
  allowed; nothing may reach `System.out` except the command's own report.

## What to build

### 1. Move `spike/` to `cli/`

`git mv` the three classes and the two tests to `mail.mcp.cli` (main and test). Update
`MailMcpServerApplication`, the `mainClass` of the `ewsSpike` and `imapSpike` Gradle tasks and
every reference in `docs/architecture-agent.md`, `docs/build-config-agent.md` and
`docs/ews-source-agent.md`. No other change to these classes.

### 2. Package `mail.mcp.sync` (new)

```java
/**
 * Progress of one folder, cumulative within the run.
 * done: false after a committed batch, true once the folder's cursor is saved
 * (or the folder was deleted because the server no longer lists it).
 */
public record FolderProgress(String path, int downloaded, int failed, int removed,
                             boolean reset, boolean done) { }
```

`public final class SyncRunner`, constructed with
`(MessageStore store, MailIndex index, MailSource source)`; one public method
`void run(Consumer<FolderProgress> progress)`. It does not open or close any of the three.

`run` does, in this order:

1. `index.reconcile(store)`.
2. Folders. `local = store.folders()`, `remote = source.folders()`. Match by `remoteId`.
   - A remote folder without a local entry gets `new LocalFolder(nextKey, remoteId, path, null)`;
     `nextKey` is `"f" + (largest number among the keys "f<n>" of `local`, counted before
     vanished folders are dropped, + 1)`, starting at `f1`. A key freed in an earlier run may come
     back; that is harmless because its documents were deleted with the folder.
   - A local entry whose `remoteId` is still listed keeps its key and cursor; its `path` is
     updated from the server.
   - A local entry no longer listed: for every key in `index.folderRefs(key).values()` call
     `store.delete(key)` and `index.delete(key)`, then `index.commit()`, then report
     `FolderProgress(path, 0, 0, removedCount, false, true)`.
   - `store.saveFolders(list)` with the folders in server order (dropped ones gone). This is done
     before any download, so a new folder has a key even if the run stops early.
3. For each folder in server order:
   1. `refs = index.folderRefs(key)`; `changes = source.changes(remoteFolder, cursor, refs.keySet())`.
   2. Removals: if `changes.reset()`, every entry of `refs`; otherwise each ref of
      `changes.removed()` that is in `refs` (unknown refs are ignored). For each:
      `store.delete(key)`, `index.delete(key)`. Then `index.commit()`.
   3. Downloads, for each ref of `changes.added()`:
      - `key = store.newKey()`, `dir = store.createMessageDir(key)`;
      - `source.download(remoteFolder, ref, dir.resolve(MessageStore.MESSAGE_FILE))` →
        `MessageMeta(key, folderKey, path, ref, size, sha256, Instant.now(), DOWNLOADED, null)`;
        `MessageUnavailableException e` →
        `MessageMeta(key, folderKey, path, ref, 0, null, Instant.now(), FAILED, e.getMessage())`;
      - `store.writeMeta(meta)`, `index.put(meta)`;
      - after every 100 messages: `index.commit()` and
        `progress.accept(new FolderProgress(path, downloaded, failed, removed, reset, false))`.
   4. `index.commit()`; set the folder's cursor to `changes.cursor()`; `store.saveFolders(list)`;
      `progress.accept(... done = true)`.

Any other exception propagates unchanged: no catch, no cleanup. The next run's reconciliation deletes a directory left without `meta.json` and indexes directories
with `meta.json` that missed the index commit.

### 3. `cli/MailSyncCommand` (new)

Same structure as `ImapSpikeCommand`: `MODE = "mail-sync"`, `main`, `run(String[] args)` that sets
`logback.configurationFile` to `logback-spike.xml` like the spikes, and the testable
`static int run(String[] args, MailMcpProperties properties, PrintStream out,
MailConnectionFactory factory, UnaryOperator<String> env)`.

1. Exactly one argument, the connection name; `--help`/`-h` or a wrong count → usage
   `Usage: java -jar mail-mcp-server.jar mail-sync <connection>` and the line
   `Run by the user only; signs in to a real mailbox.` (exit 0 for help, 2 otherwise).
2. Header `Mail sync (read-only; one sign-in attempt)`. Load connections like the IMAP spike (same
   withheld-error message on failure, exit 2). Unknown or unusable connection →
   `RESULT: FAILED (choose one valid connection by name)`, exit 2.
   Print `Connection: <name> (<type id>, <auth id>; address and credentials hidden)`.
3. `factory.create(definition)`, `connection.openSource()`; `UnsupportedOperationException` →
   `RESULT: FAILED (<its message>)`, exit 2.
4. `MailIndex.openWriter(definition.connectionDir())`; `IndexLockedException` →
   `RESULT: FAILED (<its message>)`, exit 5. Then
   `new MessageStore(definition.connectionDir(), new JsonConfig().mailMcpObjectMapper())`.
5. Run with a progress consumer that prints one line per call:
   `  <path>: <downloaded> downloaded, <failed> failed, <removed> removed` plus `, folder reset`
   when `reset`, plus ` (in progress)` when not `done`.
6. Success: `RESULT: OK (<folders done> folders, <downloaded> downloaded, <failed> failed,
   <removed> removed)` summed over the `done` lines; exit 0.
7. Failures, all with `RESULT: FAILED (<fixed message>)`: `OAuthException` → exit 2;
   `ImapException` → exit 3 for `AUTHENTICATION`, else 4; `UncheckedIOException` →
   `RESULT: FAILED (local storage error; details withheld)`, exit 4; any other `RuntimeException`
   → `RESULT: FAILED (unexpected local error; details withheld)`, exit 1.

Close the source and the index with try-with-resources, source last opened first closed.
Folder paths are printed (the user needs them); addresses, subjects and credentials never.

Register the mode in `MailMcpServerApplication` and add a Gradle task `mailSync` like `imapSpike`
(comment: signs in to a real mailbox, run by the user only).

## Tests

All offline. In `src/test/java/mail/mcp/sync/` write a `public final class FakeMailSource`
(public so `MailSyncCommandTest` in `cli` can use it) implementing `MailSource` in memory: folders in order, each with a cursor value and refs mapped to
synthetic ASCII bytes; `changes` follows the IMAP contract (`added` = present minus known,
`removed` = known minus present, both ascending; a different cursor → `reset`, `added` = all,
`removed` = all known); `download` writes the bytes to a new target and returns size and SHA-256,
throws `MessageUnavailableException(TOO_LARGE)` for refs marked too large, and can be told to
throw an `ImapException(CONNECTION)` at the n-th download. It counts `folders`/`changes`/
`download` calls. Tests can change its folders and refs between runs.

`SyncRunnerTest` (each run opens `MailIndex` and a `MessageStore` on a `@TempDir`):

- `firstRunDownloadsEverythingAndSecondRunNothing` — `message.eml` bytes, `meta.json` fields,
  index refs, cursors in `folders.json`; the second run makes no `download` call
- `newMessageIsDownloadedAlone`
- `removedMessageLosesDirectoryAndDocument`
- `changedCursorResetsTheFolder` — all old directories gone, all present refs downloaded under
  new keys
- `vanishedFolderIsDeletedWithItsMessages` and `newFolderGetsTheNextKey` (after `f1`, `f2` with
  `f1` dropped, a new folder is `f3`)
- `unavailableMessageIsFailedAndNotRetried` — `state=failed`, no `message.eml`, the run goes on;
  the next run does not download it
- `interruptedRunResumesWithoutRedownloading` — a 250-message folder; the fake throws at the
  150th `download` call. The exception reaches the caller and `folders.json` has no cursor for
  the folder. Messages 1–149 have `meta.json` (100 committed by the batch, 49 by `close()`); the
  150th directory has none. The next run's reconciliation deletes that directory, the 149 refs are
  known, so exactly 101 downloads happen; afterwards 250 directories, all with `meta.json`
- `deletedIndexIsRebuiltBeforeAnyDownload` — after a full run delete `index/`; the next run makes
  no `download` call and `folderRefs` is complete again
- `progressReportsBatchesAndFolderEnds` — 250 messages in one folder → calls with 100 and 200
  (`done` false), then 250 (`done` true)
- `pathChangeKeepsKeyAndCursor` — same `remoteId`, new `path`

`cli/MailSyncCommandTest`:

- `helpAndWrongArgumentsNeverLoadConfiguration`
- `unknownConnectionNeverSignsIn` (factory that throws `AssertionError` if called)
- `ewsConnectionHasNoSourceYet` — exit 2, message from `MailConnection`, no `index/` created
- `lockedIndexStopsBeforeSignIn` — hold `MailIndex.openWriter` on the connection directory in the
  test; the command exits 5 and the factory's source is never asked for folders
- `successfulRunPrintsFoldersAndTotals` — a factory returning
  `new MailConnection(definition, () -> fakeSource)`; checks the folder lines and the `RESULT` line

Existing `EwsSpikeCommandTest` and `ImapSpikeCommandTest` move with their classes and keep passing.

## Do not

- Write `sync-status.json`, start threads, add a background service or an MCP tool (IT-06).
- Parse MIME, call pandoc, write `body.md` or attachments (7.4).
- Add retries, timeouts, parallel downloads, a download limit or a "depth" option (plan.md 6.9).
- Add states, counters or fields beyond those listed; no `SyncResult` type beyond `FolderProgress`.
- Change `MessageStore`, `MailIndex`, `source/` or `connections/` (if one of them must change,
  stop and ask).
- Rename `logback-spike.xml`, the jar modes or the spike output.

## Stop and ask when

- A test needs a change in `store/`, `index/`, `source/` or `connections/`.
- The interrupted-run arithmetic or reconciliation behaves differently from the rules above.
- Anything other than the report appears on `System.out` during `mail-sync`.

## Files

Move (`git mv`): `src/main/java/mail/mcp/spike/{EwsSpikeCommand,ImapSpikeCommand,GmailOAuthCommand}.java`
→ `src/main/java/mail/mcp/cli/`; `src/test/java/mail/mcp/spike/{EwsSpikeCommandTest,ImapSpikeCommandTest}.java`
→ `src/test/java/mail/mcp/cli/` (package line updated).

Create: `src/main/java/mail/mcp/sync/{SyncRunner,FolderProgress}.java`,
`src/main/java/mail/mcp/cli/MailSyncCommand.java`,
`src/test/java/mail/mcp/sync/{SyncRunnerTest,FakeMailSource}.java`,
`src/test/java/mail/mcp/cli/MailSyncCommandTest.java`.

Change: `MailMcpServerApplication.java`, `build.gradle.kts`, and the documents below.

## Documents

- `docs/build-config-agent.md`: a section "Mail sync" after "The IMAP spike": command (jar and
  Gradle), what one run does, the output lines, exit codes (0, 1, 2, 3, 4, 5), that a second run
  downloads nothing new, and that an interrupted run is resumed by the next one. Update the class
  and package names of the spikes.
- `docs/storage-agent.md`: who writes what in a run (order of `message.eml`, `meta.json`, index,
  `folders.json`), folder keys, failed messages, vanished folders, cursor only after a folder.
- `docs/architecture-agent.md`: `spike/` → `cli/` everywhere; move `sync/` and `cli/` from
  "Planned packages" to "Existing packages" (`SyncService` and `sync-status.json` stay planned for
  IT-06); dependency diagram; status line.
- `docs/ews-source-agent.md`: the `spike/EwsSpikeCommand` reference.

## Done when

- Every test named above exists and passes; `.\gradlew.bat build` passes.
- `git status` shows only the moves and files listed under "Files".
- The report lists under "For the user to run":
  `java -jar build\libs\mail-mcp-server.jar mail-sync mailru` twice — the first run downloads the
  whole mailbox (expect it to take a while), the second ends with `0 downloaded`.
