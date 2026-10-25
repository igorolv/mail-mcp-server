# IT-04 — `EwsMailSource`

Plan step: `docs/plan.md` 7.3. Size: one full session. Next brief: IT-05 (MIME reader, pandoc,
`body.md`, step 7.4 first half; not written yet).

## Goal

The corporate Exchange mailbox syncs with the same `SyncRunner` and the same `mail-sync`
command as IMAP. Add `EwsMailSource` with three operations: folders through `FindFolder`, changes
through `SyncFolderItems`, and MIME download through the existing `GetItem`. The production
connection factory then returns it for `type: ews`. The synchronizer, store and index do not
change.

## Read first

1. `docs/plan.md` sections 6.3, 6.6, 6.9 and 7.3 (Russian; what you need is repeated below).
2. `docs/ews-source-agent.md`.
3. `src/main/java/mail/mcp/source/ews/` — all files; `EwsClient`, `EwsXml` and
   `EwsResponseReader` closely.
4. `src/main/java/mail/mcp/source/` — `MailSource`, `RemoteFolder`, `FolderChanges`,
   `Downloaded`, `MessageUnavailableException`; `source/imap/ImapMailSource.java` as the pattern.
5. `src/main/java/mail/mcp/sync/SyncRunner.java` — how `changes()` and `download()` are used.
6. `src/main/java/mail/mcp/connections/{MailConnection,MailConnectionFactory}.java`,
   `src/main/java/mail/mcp/cli/MailSyncCommand.java`.
7. `src/test/java/mail/mcp/source/ews/{EwsRequestTest,EwsResponseReaderTest}.java`,
   `src/test/java/mail/mcp/cli/MailSyncCommandTest.java`, and the test
   `productionFactoryIsLazyAndRequiresVerifiedTls` in `src/test/java/mail/mcp/source/imap/ImapClientTest.java`.

## Already decided — do not revisit

- Only operations already in `EwsOperation` are used: `GetFolder`, `FindFolder`,
  `SyncFolderItems`, `GetItem`. The allow-list does not change.
- One `EwsMailSource` = one `EwsClient` = one transport = one NTLM connection. Calls are
  sequential. No retries: a sign-in failure, a SOAP fault (including `ErrorServerBusy`), an HTTP
  or transport error ends the run (the exception propagates). The next run resumes, because
  downloaded refs are known.
- `RemoteFolder.remoteId` is the EWS `FolderId` `Id`. It survives a rename. `ref` is the
  `ItemId` `Id`. `ChangeKey` is ignored.
- The cursor is the `SyncState` string from the **last** page of `SyncFolderItems`, opaque, stored
  as is.
- **An invalid `SyncState` is not a reset.** EWS item ids stay valid, so re-downloading the whole
  folder would be wasted work. When the server answers `ErrorInvalidSyncStateData`, the source
  starts that folder over without a `SyncState` and treats it like a first sync (see "changes"
  below). `reset` is always `false` for EWS.
- Flags, read state and item updates are not synced: `Update` and `ReadFlagChange` entries are
  skipped.
- The EWS spike (`ews-spike`) and `findNewestItems` stay as they are.

## Current code facts you need

- `EwsXml.envelope(op, body)` writes a request; `EwsXml.typesElement`, `fieldUri`,
  `distinguishedFolderId` are helpers. `EwsResponseReader.read(op, in, handler)` applies the
  fault, error and warning checks, then calls the handler for every other start element. The
  handler may consume an element.
- A response message with `ResponseClass="Error"` becomes `EwsResponseException` with
  `responseCode()`, for example `ErrorItemNotFound` or `ErrorInvalidSyncStateData`.
- `EwsClient.getItemMime(itemId, target)` writes `<target>.part`, then moves it into place. Over
  `maxMimeBytes` (64 MiB), `Base64ChunkDecoder` throws `EwsProtocolException`.
- `MailConnectionFactory.standard()` returns `new MailConnection(definition, null)` for EWS.
  `MailConnection.openSource()` then throws `UnsupportedOperationException("Connection '<name>'
  has no mail source yet")`. `MailSyncCommand` catches that and exits 2. All of this becomes dead
  code with this task.
- Creating `HttpClient5EwsTransport` does not contact the server, so a supplier
  `() -> new EwsMailSource(new EwsClient(new HttpClient5EwsTransport(settings)))` is lazy.
- `SyncRunner` passes the folder's refs from the index as `known`, removes `removed` refs it
  knows (it ignores unknown ones), downloads `added` in list order, and saves `cursor` after the
  whole folder.

## What to build

### 1. Client calls (`source/ews/`)

New records, one per file, with Javadoc like `EwsFolder`:

- `EwsFolderNode(String id, String parentId, String displayName, String folderClass, String kind)`.
  `kind` is the local name of the element: `Folder`, `SearchFolder`, `CalendarFolder`,
  `ContactsFolder` or `TasksFolder`. `folderClass` is `null` when absent.
- `EwsFolderPage(List<EwsFolderNode> folders, boolean includesLastItemInRange)`.
- `EwsSyncPage(String syncState, boolean includesLastItemInRange, List<String> created, List<String> deleted)`.
  Item ids appear in server order.

New `EwsClient` methods:

- `List<String> folderIds(List<DistinguishedFolder> folders)`: one `GetFolder` with
  `BaseShape IdOnly` and one `DistinguishedFolderId` per entry. It returns the `FolderId` ids in
  request order. It is a protocol error if the count differs.
- `EwsFolderPage findFolders(DistinguishedFolder root, int offset)`: `FindFolder` with
  `Traversal="Deep"`, `BaseShape IdOnly` plus `folder:DisplayName`, `folder:FolderClass` and
  `folder:ParentFolderId`. It uses `IndexedPageFolderView` with `MaxEntriesReturned=1000`,
  `Offset=<offset>` and `BasePoint=Beginning`, and `ParentFolderIds` = the root. Element order
  follows the schema: `FolderShape`, `IndexedPageFolderView`, `ParentFolderIds`.
- `EwsSyncPage syncFolderItems(String folderId, String syncState)`: `SyncFolderItems` with
  `ItemShape` = `BaseShape IdOnly`, `SyncFolderId` = `t:FolderId Id=<folderId>`, `SyncState` (the
  element is omitted when `syncState` is `null`), `MaxChangesReturned=512` and
  `SyncScope=NormalItems`, in that order.

New `EwsResponseReader` methods: `readFolderIds`, `readFolderPage` and `readSyncPage`. They follow
the existing streaming style.

- `readFolderPage`: `IncludesLastItemInRange` is an attribute of `m:RootFolder`. Each folder
  element in `t:Folders` is one node, with `t:FolderId`, `t:ParentFolderId`, `t:DisplayName` and
  `t:FolderClass` inside.
- `readSyncPage`: `m:SyncState` and `m:IncludesLastItemInRange` are elements. In `m:Changes`, a
  `t:Create` contains one item element of any type (`t:Message`, `t:MeetingRequest`, …) with a
  `t:ItemId`. A `t:Delete` contains a `t:ItemId` directly. Consume each `t:Create` and `t:Delete`
  element whole and take its `ItemId` `Id`. Skip `t:Update` and `t:ReadFlagChange` whole. A
  missing `SyncState` or a `Create`/`Delete` without an id is an `EwsProtocolException`.

Change `Base64ChunkDecoder`: over the limit it throws
`MessageUnavailableException(TOO_LARGE)` instead of `EwsProtocolException`, as `ImapClient` does.
Update the existing test `mimeLargerThanTheLimitIsRejected` to expect that.

### 2. `source/ews/EwsMailSource` (new)

`public final class EwsMailSource implements MailSource`, constructor `EwsMailSource(EwsClient client)`.

**`folders()`**

1. `client.folderIds(List.of(DELETED_ITEMS, JUNK_EMAIL, DRAFTS, OUTBOX))` gives the excluded ids.
2. Page through `client.findFolders(MSG_FOLDER_ROOT, offset)`. The next offset is the previous
   offset plus the number of folders on the page. Stop at `includesLastItemInRange` or at an
   empty page.
3. Keep a node when all of these hold:
   - `kind` is `Folder`;
   - `folderClass` is `IPF.Note` or starts with `IPF.Note.`;
   - neither the node nor any of its ancestors is an excluded id. Walk `parentId` through the
     nodes of the listing and stop at an id that is not in the listing.
4. `path` is the display names from the topmost listed ancestor down to the folder, joined with
   `/`, for example `Входящие/Проекты`. Ancestors of any kind count for the path.
5. Return `new RemoteFolder(id, path)` in listing order.

**`changes(folder, cursor, known)`**

1. Call `syncFolderItems(folder.remoteId(), cursor)`, then keep calling with the page's
   `syncState` until `includesLastItemInRange`.
2. If the **first** call fails with `EwsResponseException` and code `ErrorInvalidSyncStateData`,
   and `cursor` is not `null`, start over once with `null`. Any other error propagates.
3. Collect the pages in order into an insertion-ordered set `created` and a set `deleted`:
   - a `Create` adds the id;
   - a `Delete` of an id already in `created` removes it from `created`; otherwise it goes to
     `deleted`.
4. Build the result:
   - `added` = `created` minus `known`, in server order;
   - `removed` = (`deleted` ∩ `known`), plus (`known` minus `created`) when the sync started
     from `null`. A sync from `null` lists every item, so whatever is known but not listed is gone.
     Sort `removed` with `String` natural order;
   - `cursor` = the last page's `syncState`;
   - `reset` = `false`.

**`download(folder, ref, target)`**

- Call `client.getItemMime(ref, target)` and return `new Downloaded(bytes, sha256)`.
- `EwsResponseException` with `ErrorItemNotFound` becomes `MessageUnavailableException(GONE)`.
- `TOO_LARGE` comes from the decoder.
- Everything else propagates.
- A blank `ref` is an `IllegalArgumentException`.

**`close()`** closes the client.

Change the Javadoc of `FolderChanges`: `added` and `removed` are in ascending UID order for IMAP
and in server order for EWS (`removed` sorted).

### 3. Wiring

- `MailConnectionFactory.standard()`: for EWS,
  `new MailConnection(definition, () -> new EwsMailSource(new EwsClient(new HttpClient5EwsTransport(definition.ews()))))`.
  Update its Javadoc.
- `MailConnection`: `sources` is never `null` now. Remove the `null` branch and the "no mail
  source yet" message, and update the Javadoc.
- `MailSyncCommand`:
  - Remove the `UnsupportedOperationException` branch.
  - Add two catches, so the address and server text never reach the output:
    - `EwsAuthenticationException` prints
      `RESULT: FAILED (EWS sign-in rejected; check the connection locally before the next run)`
      and exits 3;
    - any other `EwsException` prints `RESULT: FAILED (EWS error: <code>)` and exits 4. `<code>`
      is `responseCode()` for `EwsResponseException` and `EwsSoapFaultException` when it is not
      `null`; otherwise it is the exception's simple class name.

## Tests

All offline. Fixtures are synthetic XML in `src/test/resources/ews/` with made-up ids such as
`AAMkSYNTH-inbox`; follow the style of the existing fixtures.

New test helper `src/test/java/mail/mcp/source/ews/ScriptedEwsTransport.java`: an `EwsTransport`
that answers each `exchange` with the next fixture of a queue. It fails the test if the operation
differs from the one queued, keeps every request as a string, and can be told to throw a given
`EwsException` instead of answering.

`EwsRequestTest` (add):

- `findFolderAsksForADeepPagedListingOfTheMessageRoot`
- `syncFolderItemsOmitsTheSyncStateOnTheFirstCall`
- `syncFolderItemsSendsTheSyncStateAndFolderIdEscaped`

`EwsResponseReaderTest` (add):

- `getFolderReadsSeveralFolderIdsInOrder`
- `findFolderReadsEveryFolderKindWithParentAndClass`
- `syncFolderItemsReadsCreatesAndDeletesAndSkipsOtherChanges`, which includes one `Update` and one
  `ReadFlagChange`
- `syncFolderItemsWithoutSyncStateIsAProtocolError`
- `invalidSyncStateBecomesAResponseExceptionWithItsCode`

`EwsMailSourceTest` (new):

- `foldersKeepMailFoldersOnly`: drops a `SearchFolder`, a `CalendarFolder`, an `IPF.Contact`
  folder, Deleted Items with a subfolder, Junk, Drafts and Outbox; keeps `IPF.Note` and
  `IPF.Note.X`.
- `foldersBuildFullPathsAndPageTheListing`: two `FindFolder` pages; the second request has
  `Offset` = the size of the first page; nested Cyrillic paths.
- `firstSyncPagesUntilTheLastItemInRange`: two pages; `added` in server order; `cursor` = the
  second page's state; the second request carries the first page's state.
- `firstSyncSkipsKnownAndRemovesUnlisted`
- `laterSyncTurnsDeletesIntoRemovedRefs`: a `Delete` of an id created earlier in the same call
  is dropped from both lists; a `Delete` of an unknown id is ignored.
- `invalidSyncStateStartsOverWithoutReset`: exactly two `SyncFolderItems` requests, the second
  without `SyncState`; `reset` is false; known items are not added again.
- `downloadWritesMimeAndReturnsSizeAndHash`
- `missingItemIsGone`
- `tooLargeItemIsUnavailable`
- `signInFailureIsNotRetried`: the transport throws `EwsAuthenticationException`; it reaches the
  caller after exactly one `exchange`.
- `twoSyncRunsDownloadEachMessageOnce`: `SyncRunner` with a real `MessageStore` and `MailIndex` on
  a `@TempDir` and an `EwsMailSource` over the scripted transport. The first run downloads two
  messages. The second run sends the saved `SyncState`, gets an empty page and makes no `GetItem`
  request.

Change existing tests:

- `MailSyncCommandTest.ewsConnectionHasNoSourceYet` becomes `ewsFailuresUseFixedMessagesAndExitCodes`.
  The factory throws `EwsAuthenticationException` (exit 3), `EwsSoapFaultException` with
  `ErrorServerBusy` (exit 4, `EWS error: ErrorServerBusy`) and `EwsTransportException` whose
  message contains `webmail.example.com` (exit 4, `EWS error: EwsTransportException`, and the
  output does not contain `webmail.example.com`).
- `ImapClientTest.productionFactoryIsLazyAndRequiresVerifiedTls`: the EWS part builds an `ews`
  definition with `EwsSettings` for `https://127.0.0.1:1/EWS/Exchange.asmx` and asserts that
  `openSource()` returns an `EwsMailSource` without contacting the server.

## Do not

- Add an operation to `EwsOperation`, use `FindItem` for sync, or batch several ids in one
  `GetItem`.
- Add retries, back-off on `ErrorServerBusy`, throttling, parallel downloads or timeouts beyond
  the existing settings (plan.md 6.9).
- Sync flags, read state, categories or item updates; store `ChangeKey`.
- Change `SyncRunner`, `MessageStore`, `MailIndex`, `model/` or the EWS spike.
- Add the `HttpsURLConnection` fallback transport.
- Print folder ids, item ids, `SyncState`, the host or the login.

## Stop and ask when

- A test needs a change in `sync/`, `store/`, `index/` or `model/`.
- The schema order or element names in this brief contradict the EWS schema you know. Name the
  element, and do not guess.
- The existing response checks in `EwsResponseReader.read` get in the way of reading the new
  responses.

## Files

Create:

- `src/main/java/mail/mcp/source/ews/{EwsMailSource,EwsFolderNode,EwsFolderPage,EwsSyncPage}.java`
- `src/test/java/mail/mcp/source/ews/{EwsMailSourceTest,ScriptedEwsTransport}.java`
- new fixtures in `src/test/resources/ews/`

Change:

- `EwsClient.java`, `EwsResponseReader.java`, `Base64ChunkDecoder.java`
- `source/FolderChanges.java` (Javadoc only)
- `connections/{MailConnection,MailConnectionFactory}.java`
- `cli/MailSyncCommand.java`
- `EwsRequestTest.java`, `EwsResponseReaderTest.java`, `MailSyncCommandTest.java`, `ImapClientTest.java`
- the documents below

## Documents

- `docs/ews-source-agent.md`: the new client methods; a section "Mail source" covering:
  - which folders are kept;
  - how paths are built;
  - paging of `SyncFolderItems`;
  - first sync against later syncs;
  - invalid `SyncState` handling, and why it is not a reset;
  - `ErrorItemNotFound` → `GONE`;
  - no retries, including `ErrorServerBusy`, as a known risk for long first runs.
- `docs/build-config-agent.md`, section "Mail sync": EWS is supported; the two EWS failure lines;
  exit codes 3 and 4 cover EWS too; exit 2 no longer mentions "no source yet".
- `docs/architecture-agent.md`: `EwsMailSource` under `source/ews/`; the status line.

## Done when

- Every test named above exists and passes; `.\gradlew.bat build` passes.
- `git status` shows only the files listed under "Files".
- The report lists under "For the user to run":
  `java -jar build\libs\mail-mcp-server.jar mail-sync work` twice. The first run downloads the
  whole mailbox and takes long: one `GetItem` per message. The second run ends with
  `0 downloaded`.
