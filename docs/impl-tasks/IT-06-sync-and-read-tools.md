# IT-06 — background sync and the first mail tools

Plan step: `docs/plan.md` 7.4, second half. Size: one full session. Next brief: IT-07 (search,
step 7.5; not written yet).

## Goal

An agent connected over MCP can do four things without leaving the chat:
- start a synchronization;
- watch its progress;
- list folders and messages;
- read one message: headers, body in chunks, attachment paths.

This takes five new tools: `sync`, `syncStatus`, `listFolders`, `listMessages` and `getMessage`.
Sync runs in the background of the server process and the tool returns at once. Every reading tool
answers from local data only. The status of the current or last run goes to `sync-status.json`,
which every process can read.

## Read first

1. `docs/plan.md` sections 6.4, 6.6, 6.8, 6.9 and 7.4 (Russian; what you need is repeated below).
2. `docs/architecture-agent.md` (layers, stdio rules), `docs/storage-agent.md`, `docs/mime-agent.md`.
3. `src/main/java/mail/mcp/{tools,api,config}/` — all files; `connections/ConnectionRegistry.java`.
4. `src/main/java/mail/mcp/{sync,index,store,model}/` — all files; `mime/MimeReader.java`.
5. `src/main/java/mail/mcp/cli/MailSyncCommand.java`.
6. `src/test/java/mail/mcp/McpStdioServerTest.java`, `src/test/java/mail/mcp/tools/ConnectionToolsTest.java`.

## Already decided — do not revisit

- **Stdio.** Nothing but JSON-RPC on `System.out`. Tool execution is immediate
  (`McpServerConfig`), so a tool must never wait for the network. `sync` starts a thread and
  returns.
- **Only the holder of the connection's Lucene write lock syncs** (`MailIndex.openWriter`).
  - A second process gets `lockedByOtherProcess` and does not sign in.
  - `sync-status.json` is written only by the lock holder, that is, by `SyncRunner`.
- **No automatic retries.** A rejected sign-in is recorded in `sync-status.json`
  (`signInRejected: true`). After that, the `sync` tool refuses this connection in every process
  until a run succeeds. The user clears the flag by fixing the configuration and running
  `mail-sync <connection>` in a terminal. Repeated attempts with a stale password lock the domain
  account.
- **Pandoc is required to sync.** `sync` checks it before anything else is touched. Reading tools
  do not need pandoc.
- Every tool takes a required `connection`. There is no cross-mailbox call.
- `model/` records are never returned by a tool; `api/` records are the wire contract, with
  `@Schema` descriptions like `ConnectionInfo`. Tools catch, log with `ToolLogger.failed` and
  rethrow, as `redmine-mcp-server` does. An `IllegalArgumentException` message reaches the agent
  as the tool error.
- Responses are bounded:
  - `listMessages`: `limit` defaults to 50, at most 200;
  - `getMessage`: `maxChars` defaults to 20,000, at most 100,000, and the body comes in chunks
    via `offset` and `nextOffset`.
- Every message in a response carries `localPath`, the absolute path of its directory, so an agent
  with file access can read the files itself. The sensitive-data filter is deferred (plan.md 6.9).
- Not in this task: search (7.5), attachment text and `getAttachment` (7.6), MCP resources,
  threads, flags.

## What to build

### 1. Sync status (`model/`, `store/`, `sync/`)

- Move `sync/FolderProgress` to `model/FolderProgress` (`git mv`, update imports; the record does
  not change).
- New `model/SyncStatus(String state, long pid, Instant startedAt, Instant finishedAt,
  List<FolderProgress> folders, String error, boolean signInRejected)`.
  - `state` is `running`, `completed` or `failed`.
  - `folders` holds the latest progress of each folder, in order of first appearance.
- `MessageStore`:
  - constant `SYNC_STATUS_FILE = "sync-status.json"`;
  - `Optional<SyncStatus> readSyncStatus()`;
  - `writeSyncStatus(SyncStatus)`, through the existing atomic `writeJson`.
- New `sync/SyncErrors` with two static methods, both pure:
  - `String describe(RuntimeException e)` — the fixed messages `MailSyncCommand` prints today,
    moved here unchanged: index locked, OAuth, IMAP, EWS sign-in, EWS code, local storage,
    unexpected;
  - `boolean signInRejected(RuntimeException e)` — IMAP `AUTHENTICATION` or
    `EwsAuthenticationException`.

  `MailSyncCommand` uses `describe` for its `RESULT: FAILED (...)` text. Its output and exit codes
  do not change.
- `SyncRunner.run` writes the status:
  - at the start: `running`, pid = `ProcessHandle.current().pid()`, `signInRejected=false`,
    before the reconcile;
  - after every progress call: replace the folder's entry;
  - at the end: `completed` with `finishedAt`;
  - on any `RuntimeException`: `failed`, `finishedAt`, `error = describe(e)`,
    `signInRejected(e)`, then rethrow. A failure while writing this last status is added as a
    suppressed exception.

### 2. `sync/SyncService` (new, plain class)

```java
public final class SyncService {
    public SyncService(ConnectionRegistry registry, Supplier<Pandoc> pandocLocator, ObjectMapper mapper);
    /** Returns at once; the run continues on its own thread. */
    public SyncStart start(String connection);
    /** True while this process runs a sync of the connection. */
    public boolean runningHere(String connection);
}
public record SyncStart(Status status, String message) {   // sync/, own file
    public enum Status { STARTED, ALREADY_RUNNING, LOCKED_BY_OTHER_PROCESS, REFUSED }
}
```

`start` is `synchronized` and works in this order:

1. `registry.definition(name)`; an unusable entry throws `IllegalArgumentException` with its
   `configError`.
2. A thread of this process already syncs the connection → `ALREADY_RUNNING`.
3. The last status has `signInRejected` → `REFUSED`, with the message
   `Sign-in was rejected in the last run; fix the connection locally and run 'mail-sync <name>' in a terminal`.
4. `pandocLocator.get()`. A `PandocException` becomes
   `IllegalStateException("pandoc not found; install pandoc and put it on PATH")`.
5. `MailIndex.openWriter`. An `IndexLockedException` → `LOCKED_BY_OTHER_PROCESS`.
6. `registry.resolve(name).openSource()` (lazy, no network).
7. Start one daemon platform thread named `sync-<name>`. The thread:
   - runs `new SyncRunner(store, index, source, new MessageProcessor(store, pandoc)).run(p -> { })`
     inside try-with-resources on the index and the source;
   - logs a failure with the exception's class name only, because the status already holds the
     message;
   - removes itself from the running map in `finally`.
8. Return `STARTED`.

If steps 6–7 throw, close the index before rethrowing.

### 3. `index/MailIndexReader` (new)

A read-only view for tools in any process. It never takes the write lock.

```java
public final class MailIndexReader implements AutoCloseable {
    /** Empty when index/ has no commit yet. Opens the last commit; one reader per tool call. */
    public static Optional<MailIndexReader> open(Path connectionDir);
    public int count(String folderKey);                                // PrefixQuery on ref
    public List<MessageCard> cards(String folderKey, int offset, int limit);
}
```

- New `model/MessageCard(String key, String folderKey, Instant date, String from, String subject,
  boolean hasAttachments, String state)`.
- `cards` sorts by `date` descending, messages without a date last, then by `key`. To support
  this, `MailIndex.put` adds a `SortedDocValuesField("key", ...)`. Documents indexed before this
  task lack it; they still sort correctly by date.
- `hasAttachments` is true when the stored `attachmentNames` is not empty.
- Add `String display()` to `model/Address`, formatting as `Name <address>` or `address`.
  `MailIndex` uses it instead of its private formatter.

### 4. `api/` records

One file each, with `@Schema` on the record and every component:

- `SyncStartResult(connection, status, message)`. `status` is `started`, `alreadyRunning`,
  `lockedByOtherProcess` or `refused`.
- `FolderSyncInfo(path, downloaded, failed, removed, reset, done)`.
- `SyncStatusResult(connection, state, startedAt, finishedAt, downloaded, failed, removed,
  folders, error, signInRejected)`.
  - `state` is `never`, `running`, `completed`, `failed` or `interrupted`.
  - `interrupted` means the file says `running`, the process with that pid is no longer alive,
    and this process is not running it.
  - Totals are sums over `folders`.
- `FolderInfo(key, path, messages, synced)`. `synced` is true when the folder has a cursor, that
  is, when it has been completed at least once.
- `ListFoldersResult(connection, folders)`.
- `MessageSummary(key, date, from, subject, hasAttachments, state, localPath)`.
- `ListMessagesResult(connection, folder, total, offset, nextOffset, messages)`. `nextOffset` is
  absent at the end.
- `AttachmentRef(file, name, contentType, size, inline, path)`. `path` is the absolute file path,
  absent when the file does not exist.
- `GetMessageResult(key, folder, state, error, subject, from, to, cc, date, messageId, inReplyTo,
  references, body, offset, nextOffset, bodyLength, attachments, localPath)`.
  - Addresses are `display()` strings.
  - `body` is the chunk of `body.md`; it is absent when there is no `body.md`.

### 5. Tools

**`tools/SyncTools`**
- `sync(connection)` returns `SyncStartResult`.
- `syncStatus(connection)` returns `SyncStatusResult`. It reads `sync-status.json`; without it,
  `state=never`.

**`tools/MailTools`**
- `listFolders(connection)`: folders from `folders.json` in stored order, with `count` from the
  reader (0 without an index).
- `listMessages(connection, folder, offset, limit)`:
  - `folder` is a folder key from `listFolders`; an unknown key is an `IllegalArgumentException`;
  - `offset` and `limit` are optional, with the bounds above.
- `getMessage(connection, key, offset, maxChars)`:
  - `MessageStore.messageDir` validates the key; an invalid or unknown key is an
    `IllegalArgumentException`;
  - folder, state and error come from `readMeta`;
  - headers and the attachment list come from `MimeReader.read(eml, null)` when `message.eml`
    exists; if parsing fails, the headers are absent and the rest is still returned;
  - the body is `body.md`, read as a string and cut at `offset`/`maxChars` (characters).

Tool descriptions tell the agent:
- `sync` only starts a run, and `syncStatus` shows its progress;
- the reading tools show local data, which may be incomplete while a sync runs — say how to tell,
  with `synced` and `syncStatus`;
- `localPath` points at files the agent may read directly.

All tools are annotated `readOnlyHint = true`, `destructiveHint = false`, as `listConnections`
is. `sync` is not idempotent.

**Wiring.** `config/SyncConfig` creates the `SyncService` bean with `Pandoc::locate`. The tool
classes take `ConnectionRegistry`, `SyncService` and the `ObjectMapper` by constructor.

## Tests

All offline; no test signs in or reaches an external network.

`sync/SyncServiceTest`. The registry is built with a `MailConnectionFactory` that returns
`new MailConnection(d, () -> source)`, using fake sources from the tests (`FakeMailSource`, plus
one that blocks on a latch).

- `startRunsInTheBackgroundAndWritesCompletedStatus`
- `secondStartWhileRunningIsAlreadyRunning`
- `heldWriterLockIsLockedByOtherProcessWithoutOpeningTheSource`
- `rejectedSignInIsRecordedAndRefusesTheNextStart`: the next start does not call the factory's
  source.
- `missingPandocFailsBeforeTheIndex`: no `index/` and no status file are created.

`SyncRunnerTest` (add):
- `statusIsRunningDuringTheRunAndCompletedAfter`: read it from inside a progress callback.
- `failureWritesFailedStatusAndRethrows`

`cli/MailSyncCommandTest`: the existing tests keep passing unchanged. That is the check that
`describe` returns the old messages.

`index/MailIndexReaderTest`:
- `noIndexIsEmpty`
- `countsByFolder`
- `cardsAreNewestFirstWithUndatedLastAndPaged`
- `readerSeesOnlyCommittedDocuments`

`tools/MailToolsTest` (synthetic connection directory built with `MessageProcessor` on
`testdata/mime` `M01` and `M16`, plain bodies, no pandoc needed):
- `listFoldersCountsMessagesAndShowsSynced`
- `listMessagesPagesWithNextOffset`
- `unknownFolderKeyIsRejected`
- `limitAndMaxCharsAreCapped`
- `getMessageChunksTheBody`: `maxChars` 10; concatenating the chunks gives `body.md`.
- `getMessageListsAttachmentPaths`: every `path` exists and lies under `localPath`.
- `invalidOrUnknownKeyIsRejected`: includes `..\..\x` and a valid UUID with no directory.

`tools/SyncToolsTest`:
- `syncStatusIsNeverWithoutAFile`
- `syncStatusReportsInterruptedForADeadPid`: write a status with `running` and a pid that is not
  alive, for example from a short child process that has exited.

`McpStdioServerTest`:
- `tools/list` now returns these six names: `listConnections`, `sync`, `syncStatus`,
  `listFolders`, `listMessages`, `getMessage`.
- Add an IMAP connection `local` with username and password placeholders. Before starting the
  child JVM, build its data directory in the test (`folders.json`, two processed messages, a
  committed index).
- Over stdio, call `listFolders`, `listMessages`, `getMessage` with `maxChars` 10 (expect
  `nextOffset`) and `syncStatus` (`never`).
- Do not call `sync` over stdio.
- stdout stays pure JSON-RPC.

## Do not

- Add search, `getAttachment`, attachment text, MCP resources or threads.
- Add retries, back-off, a scheduler, a queue, or a timeout around sync.
- Let a reading tool open `MailIndex.openWriter`, call pandoc, or contact a mailbox.
- Return `model/` records from a tool, or put headers into `meta.json`.
- Add the sensitive-data filter (7.7).
- Change the `mail-sync` output or exit codes.

## Stop and ask when

- Spring AI does not turn a thrown `IllegalArgumentException` into a tool error result.
- Reading `meta.json`, `sync-status.json` or the index from a second process fails on Windows
  while the first one writes.
- A test needs a change in `source/`, `mime/` or `convert/`.

## Files

Move: `src/main/java/mail/mcp/sync/FolderProgress.java` → `src/main/java/mail/mcp/model/`.

Create:

- `src/main/java/mail/mcp/model/{SyncStatus,MessageCard}.java`
- `src/main/java/mail/mcp/sync/{SyncService,SyncStart,SyncErrors}.java`
- `src/main/java/mail/mcp/index/MailIndexReader.java`
- `src/main/java/mail/mcp/api/`:
  - `SyncStartResult`, `SyncStatusResult`, `FolderSyncInfo`
  - `FolderInfo`, `ListFoldersResult`
  - `MessageSummary`, `ListMessagesResult`
  - `AttachmentRef`, `GetMessageResult`
- `src/main/java/mail/mcp/tools/{SyncTools,MailTools}.java`
- `src/main/java/mail/mcp/config/SyncConfig.java`
- tests:
  - `src/test/java/mail/mcp/sync/SyncServiceTest.java`
  - `src/test/java/mail/mcp/index/MailIndexReaderTest.java`
  - `src/test/java/mail/mcp/tools/{MailToolsTest,SyncToolsTest}.java`
- `docs/tools-agent.md`

Change:

- `model/Address.java`, `store/MessageStore.java`, `index/MailIndex.java`, `sync/SyncRunner.java`
- `cli/MailSyncCommand.java`
- `SyncRunnerTest.java`, `McpStdioServerTest.java`, and tests that import `FolderProgress`
- the documents below

## Documents

- `docs/tools-agent.md` (new, English). The reviewer registers it in `AGENTS.md`; do not edit
  `AGENTS.md`. Cover:
  - the six tools, their arguments, bounds and errors;
  - local-only reading and how coverage is shown;
  - `sync-status.json` (fields, who writes it, `interrupted`, `signInRejected` and how the user
    clears it);
  - the single writer across processes.
- `docs/storage-agent.md`: `sync-status.json`; `MailIndexReader`; the `key` doc values.
- `docs/architecture-agent.md`:
  - `tools/`, `api/`, `config/SyncConfig`, `SyncService`;
  - `FolderProgress` is now in `model/`;
  - nothing in `sync/` and `cli/` is planned any more;
  - the status line.
- `docs/build-config-agent.md`: `mail-sync` writes `sync-status.json` too, and a successful run
  clears `signInRejected`.

## Done when

- Every test named above exists and passes; `.\gradlew.bat build` passes.
- `git status` shows only the move and the files listed under "Files".
- The report lists under "For the user to run": connect an MCP client to the jar and call
  `listConnections`, `listFolders work`, `listMessages` on one folder, `getMessage` on one key,
  then `sync mailru` followed by `syncStatus mailru` until `completed`.
