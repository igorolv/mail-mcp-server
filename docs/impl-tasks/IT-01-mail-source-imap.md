# IT-01 — `MailSource` and the IMAP source

Plan step: `docs/plan.md` 7.1. Size: one full session. Next brief: IT-02 (not written yet).

## Goal

Introduce the `MailSource` interface that synchronization will use for every mailbox, and
implement it for IMAP on top of the existing `ImapClient`. One `MailSource` is one session: it
signs in at most once and serves any number of folder listings, change queries and downloads
across folders. Nothing is stored, indexed or parsed yet.

## Read first

1. `docs/plan.md` sections 6.1, 6.2, 6.3, 6.6, 6.9 and 7.1 (Russian; the parts you need are
   repeated below in English).
2. `docs/imap-source-agent.md` — TLS, authentication and the command guard.
3. `src/main/java/mail/mcp/source/imap/` — all six files.
4. `src/test/java/mail/mcp/source/imap/ImapClientTest.java` — the fake IMAP server and tests.
5. `src/main/java/mail/mcp/connections/MailConnection.java`, `MailConnectionFactory.java`.
6. `src/main/java/mail/mcp/spike/ImapSpikeCommand.java`, `EwsSpikeCommand.java` (only the part
   that creates the client) and their tests in `src/test/java/mail/mcp/spike/`.

## Already decided — do not revisit

- The interface and record shapes below (plan.md 6.3). A message reference `ref` is the IMAP UID
  as a decimal string. The cursor of an IMAP folder is its `UIDVALIDITY` as a decimal string.
- One sign-in per `MailSource`. A rejected sign-in, a broken connection or a protocol error ends
  the session (the client is closed for good, exactly as today). Nothing retries.
- A problem with one message (gone from the server, too large) does **not** end the session.
- The IMAP command guard (`ReadOnlyImapStore`) stays; this task adds exactly one command to it:
  `CLOSE` (user decision 2026-10-25, plan.md section 10). Reason: Angus closes a `READ_ONLY`
  folder with `CLOSE` before it opens the next one on the same socket. `SELECT` stays forbidden,
  so the selected mailbox is always one opened with `EXAMINE`, and RFC 3501 6.4.2 says `CLOSE`
  removes no messages from a mailbox selected with `EXAMINE`.
- The per-download `\Seen` before/after check of today's `downloadLastMessage` is dropped; the
  guard (only `BODY.PEEK`, no `STORE`) and the tests are the read-only proof.
- EWS gets its `MailSource` in step 7.3. Until then an EWS connection has none.

## Current code facts you need

- `ImapClient` connects lazily, keeps one socket (`connectionpoolsize=1`,
  `separatestoreconnection=false`) and closes itself for good on any failure (`fail(...)`).
- `downloadLastMessage` closes the whole client at the end ("a download ends this session").
  This is the behavior to replace.
- `ReadOnlyImapStore.requireReadCommand` allows `FETCH`/`UID FETCH` only with `UID`, `FLAGS`,
  `INTERNALDATE`, `RFC822.SIZE` and `BODY.PEEK[]` (optionally `<offset.length>`), and a message
  set of digits, `:` and `,` only (no `*`).
- Angus 2.0.5 facts (checked in its sources): `IMAPMessage.getSize()` loads the `ENVELOPE`, which
  the guard rejects — fetch the size with a `FetchProfile` containing `FetchProfile.Item.SIZE`
  instead (wire: `FETCH <n> (RFC822.SIZE)`). `IMAPFolder.close(false)` on a `READ_ONLY` folder
  sends `CLOSE` and returns the socket to the pool. `folder.getMessages()` plus `folder.fetch(...)`
  with `UIDFolder.FetchProfileItem.UID` sends `FETCH 1:<n> (UID)`. `getMessageByUID(uid)` sends
  `UID FETCH <uid> (UID)` unless the UID is already known, and returns `null` when the server has
  no such message. With `mail.imaps.partialfetch=true` the body arrives in separate
  `BODY.PEEK[]<offset.16384>` commands, so stopping between chunks leaves the socket usable.
- Tests and the factory create `new MailConnection(d, null)` in several places
  (`ConnectionRegistryTest`, `ConnectionToolsTest`); keep that call valid.

## What to build

### 1. Package `mail.mcp.source` (new; one type per file)

```java
package mail.mcp.source;

/** One session with one mailbox: signs in at most once, never retries, read-only. */
public interface MailSource extends AutoCloseable {
    /** Mail folders to synchronize, in server order. */
    List<RemoteFolder> folders();
    /**
     * What changed in a folder since {@code cursor}.
     * @param cursor value returned by the previous call for this folder, or null for the first call
     * @param known  refs of this folder that are already stored locally
     */
    FolderChanges changes(RemoteFolder folder, String cursor, Set<String> known);
    /** Writes the raw MIME of one message to {@code target}, which must not exist yet. */
    Downloaded download(RemoteFolder folder, String ref, Path target);
    @Override void close();
}

/** remoteId: what the server needs to open the folder; path: human-readable full name. */
public record RemoteFolder(String remoteId, String path) { }

/** added/removed: refs in ascending order; reset: the old cursor is invalid (see rules). */
public record FolderChanges(List<String> added, List<String> removed, String cursor, boolean reset) {
    // compact constructor: List.copyOf both lists
}

public record Downloaded(long size, String sha256) { }

/** One message cannot be downloaded; the session stays usable. Message has no mail data. */
public final class MessageUnavailableException extends RuntimeException {
    public enum Reason { GONE, TOO_LARGE }
    // constructor(Reason), reason(); fixed messages:
    // GONE -> "Message is no longer on the server", TOO_LARGE -> "Message exceeds the size limit"
}
```

### 2. `ImapClient` (change)

Keep the constructor, `properties()`, `connect()`, `listFolders()`, `fail(...)`, `close()` and
all authentication behavior as they are. Then:

- Add a private "current folder": at most one `IMAPFolder` open `READ_ONLY` at a time. A private
  `IMAPFolder open(String name)` returns it if it is already the open one; otherwise it closes the
  previous one with `close(false)` and opens the new one with `Folder.READ_ONLY`. `close()` of the
  client no longer needs to close it separately if `store.close()` does; check and keep it simple.
- Add `public synchronized FolderUids folderUids(String folderName)` returning a new
  package-visible-or-public record `FolderUids(long uidValidity, List<Long> uids)` in
  `source/imap/` (`uids` ascending, copied). Empty folder: no `FETCH` at all. Otherwise
  `getMessages()`, `fetch` with `UIDFolder.FetchProfileItem.UID`, `getUID(m)` for each, sort.
  `uidValidity <= 0` or a UID `<= 0` → `ImapException(PROTOCOL)` (through the usual close).
- Add `public synchronized Downloaded download(String folderName, long uid, Path target, long maxBytes)`:
  1. `maxBytes <= 0`, blank folder name or `uid <= 0` → `IllegalArgumentException` before any I/O.
  2. Open the folder; `getMessageByUID(uid)`; `null` → `MessageUnavailableException(GONE)`.
  3. `setPeek(true)`; fetch `FetchProfile.Item.SIZE`; `getSizeLong() > maxBytes` →
     `MessageUnavailableException(TOO_LARGE)` without fetching the body.
  4. Stream `getMimeStream()` into a temp `.part` file next to `target` with SHA-256, exactly
     like today's code. More than `maxBytes` bytes despite the declared size →
     delete the partial file, `MessageUnavailableException(TOO_LARGE)`.
  5. Zero bytes → `ImapException(PROTOCOL)`. Move to `target` without replacing (today's code);
     an `IOException` → `ImapException(LOCAL_IO)` as today.
  6. The client stays open after success and after `MessageUnavailableException`.
     `MessagingException` → `fail(e)` as today. The partial file never survives.
- Delete `downloadLastMessage`, `flagsProfile()` and `ImapMimeItem.java`. Remove
  `ImapException.Kind.LIMIT` and its message if nothing uses it any more.

### 3. `ReadOnlyImapStore` (change)

Add `"CLOSE"` to the allowed control commands in `requireReadCommand`, with a one-line comment
giving the reason from "Already decided". Change nothing else in the guard.

### 4. `ImapMailSource` (new, `source/imap/`)

`public final class ImapMailSource implements MailSource`, constructed with one `ImapClient`,
which it closes in `close()`. No public methods beyond the interface.

- `folders()`: `client.listFolders()`, keep entries with `holdsMessages()` true and no attribute
  equal (ignoring case) to `\Noselect`, `\NonExistent`, `\Trash`, `\Junk`, `\Drafts`. Map each to
  `new RemoteFolder(name, name)`. No provider-specific handling (Gmail is deferred).
- `changes(folder, cursor, known)`:
  1. `cursor` not null and not a non-negative decimal number → `IllegalArgumentException`
     before any I/O. A `known` entry that is not a positive decimal number is treated as a ref
     that is no longer present (it ends up in `removed`).
  2. `uids = client.folderUids(folder.remoteId())`; `present` = UIDs as decimal strings.
  3. `newCursor = String.valueOf(uidValidity)`.
  4. `reset = cursor != null && !cursor.equals(newCursor)`.
  5. If `reset`: `added` = all of `present`, `removed` = all of `known`.
     Otherwise: `added` = `present` minus `known`, `removed` = `known` minus `present`.
  6. Both lists ascending by numeric value (non-numeric removed refs last, in string order).
- `download(folder, ref, target)`: `ref` not a positive decimal number →
  `IllegalArgumentException`; otherwise `client.download(folder.remoteId(), uid, target,
  ImapClient.DEFAULT_MAX_MIME_BYTES)`.

### 5. `MailConnection` and `MailConnectionFactory` (change)

```java
public record MailConnection(ConnectionDefinition definition, Supplier<MailSource> sources)
        implements AutoCloseable {
    /** A new session; the caller closes it. Signs in only on its first request. */
    public MailSource openSource() { ... }   // sources == null -> UnsupportedOperationException
    @Override public void close() { }         // sessions are owned by their callers
}
```

Remove the `ews`/`imap` components and the two-argument `(definition, ews)` constructor. The
`UnsupportedOperationException` message: `"Connection '<name>' has no mail source yet"`.
Factory: `IMAP -> new MailConnection(definition, () -> new ImapMailSource(new ImapClient(definition.imap())))`;
`EWS -> new MailConnection(definition, null)`. Keep the factory's `usable()` and
`sourceImplemented()` checks and their messages. Update the Javadoc of both types.

### 6. `EwsSpikeCommand` (change only how the client is created)

Replace `MailConnectionFactory.standard().create(definition)` / `connection.ews()` with
`try (EwsClient client = new EwsClient(new HttpClient5EwsTransport(definition.ews())))` and pass
`client` to `spike(...)`. Output, exit codes and `EwsSpikeCommandTest` stay unchanged.

### 7. `ImapSpikeCommand` (change)

Keep argument handling, configuration loading, connection selection, exit codes and the
withheld-error behavior. Replace the work part with `connection.openSource()`:

1. `folders()`; print `Step 1/3 LIST: OK, folders to sync: <n> (names hidden)`.
2. Pick the folder whose `remoteId` equals `INBOX` ignoring case; none → treat as an empty inbox.
   `changes(inbox, null, Set.of())`; print
   `Step 2/3 EXAMINE inbox: OK, messages: <added.size()>, cursor (UIDVALIDITY): <cursor>`.
   No messages → `RESULT: OK (signed in; inbox empty)`, exit 0.
3. Download the last ref of `added` to the same target path as today
   (`<connection-dir>/spike/inbox-last-<time>.eml`); print
   `Step 3/3 BODY.PEEK last message: OK, <size> bytes, SHA-256 <hex>`, then `  saved to: <path>`
   and `RESULT: OK`.

Header line stays `IMAP spike (read-only: LIST, EXAMINE, BODY.PEEK; one sign-in attempt)`.
`MessageUnavailableException` → `RESULT: FAILED (<message>)`, exit 4.

## Tests

Keep all existing tests passing except those this section changes. Put a reusable fake server
in `src/test/java/mail/mcp/source/imap/FakeImapServer.java` (package-private) if that is simpler
than growing the inner `FakeImap`: a model of folders, each with `UIDVALIDITY`, attributes and
messages (UID → bytes, flags); it answers `CAPABILITY`, the existing authentication modes,
`LIST`, `EXAMINE` (with `EXISTS`, `UIDVALIDITY`, `UIDNEXT`, `[READ-ONLY]`), `CLOSE`,
`FETCH <set> (UID)`, `UID FETCH <uid> (UID)`, `FETCH <n> (RFC822.SIZE)` (optionally a declared
size that differs from the real one), `FETCH <n> (BODY.PEEK[]<a.b>)` and `LOGOUT`; it records
every command and fails on anything else. Never real mail: synthetic ASCII messages only.

`ImapClientTest` (adapt):

- `examinePeekFlagsAndOriginalBytesOnOneAuthenticatedSocket` → rename to
  `folderUidsAndDownloadUseOneAuthenticatedSocket`: `listFolders`, `folderUids("INBOX")`,
  `download` of one UID — exact bytes and SHA-256, one `LOGIN`, `EXAMINE` sent, only
  `BODY.PEEK[]` body fetches, no `SELECT`/`STORE`/`EXPUNGE`/`APPEND`, and the client still answers
  `listFolders()` afterwards.
- `plainAuthenticationAlsoUsesOneCredentialExchange`, `oauthWorksWithAndWithoutSaslInitialResponse`:
  replace `downloadLastMessage` with `folderUids` + `download`; assertions otherwise unchanged.
- `limitRemovesPartialFileAndPreservesExistingTarget` → replace with two tests:
  `declaredSizeOverLimitIsTooLargeWithoutBodyFetch` (no `BODY.PEEK` sent, client still usable) and
  `streamOverLimitIsTooLargeRemovesPartialAndKeepsSession` (declared size small, real bytes larger:
  no file left in the directory, next `download` of another UID succeeds without a second sign-in).
- `successfulDownloadNeverReplacesAnExistingOriginal`: same intent with `download`.
- `emptyInboxDoesNotFetchMime` → `emptyFolderHasNoUidsAndSendsNoFetch`.
- `changedFlagsAreDetectedUsingAFreshFetch`: delete (the check is dropped, see "Already decided").
- `guardRejectsEveryMutationAndNonPeekFetch`: remove `"CLOSE"` from its list; add
  `guardAllowsCloseOfAnExaminedMailbox`.
- `productionFactoryIsLazyAndRequiresVerifiedTls`: `live.openSource()` returns an
  `ImapMailSource` without contacting port 1 (close it); an EWS definition's `openSource()` throws
  `UnsupportedOperationException`.
- New: `switchingFoldersClosesTheExaminedFolderWithoutSecondSignIn`: `folderUids("A")`,
  `folderUids("B")`, `download` from `A` → exactly one credential exchange; command order shows
  `EXAMINE A`, `CLOSE`, `EXAMINE B`, `CLOSE`, `EXAMINE A`; never `SELECT`.
- New: `goneUidIsUnavailableAndSessionContinues`.

`ImapMailSourceTest` (new, uses the fake server):

- `foldersSkipNoselectNonexistentTrashJunkDraftsAndContainers`
- `firstChangesReturnAllUidsAndUidValidityCursor` — `changes(f, null, Set.of())`
- `changesSkipKnownRefs` — `known` = some present UIDs → `added` = the rest, `removed` empty
- `removedIsKnownMinusPresent` — includes a non-numeric known entry
- `changedUidValidityResetsFolder` — cursor `"6"`, server `UIDVALIDITY 7`, `known` `{"1","2"}`,
  server UIDs `{1, 5}` → `reset` true, `removed` `["1","2"]`, `added` `["1","5"]`, cursor `"7"`
- `invalidCursorOrRefIsRejectedBeforeAnyCommand` — fake server records no command after them
- `oneSignInForFoldersChangesAndDownloadsAcrossFolders` — `folders`, `changes` on two folders,
  three downloads from both → one credential exchange, only allowed commands
- `downloadWritesExactBytesAndSha256`

`ImapSpikeCommandTest`: existing tests keep passing; no new test required.

## Do not

- Store, index or parse anything; no `meta.json`, no directories under the data dir except the
  spike's existing `spike/` target.
- Add `UID SEARCH`, `*` in message sets, CONDSTORE/QRESYNC, IDLE, flag or label handling.
- Add retries, reconnects, timeouts beyond today's properties, or a second sign-in path.
- Change authentication, TLS, OAuth or the rest of the guard.
- Create `EwsMailSource` or any EWS change beyond item 6.
- Rename `spike/` (that is IT-03).

## Stop and ask when

- Angus sends a command the guard rejects in a flow this brief describes (for example a
  different form of the UID or size fetch). Report the exact command; do not widen the guard.
- Angus opens a second socket or tries a second sign-in when switching folders.
- Any existing test outside `source/imap/`, `spike/` and `connections/` breaks.

## Files

Create: `src/main/java/mail/mcp/source/{MailSource,RemoteFolder,FolderChanges,Downloaded,MessageUnavailableException}.java`,
`src/main/java/mail/mcp/source/imap/{ImapMailSource,FolderUids}.java`,
`src/test/java/mail/mcp/source/imap/ImapMailSourceTest.java`, optionally
`src/test/java/mail/mcp/source/imap/FakeImapServer.java`.

Change: `ImapClient.java`, `ReadOnlyImapStore.java`, `ImapException.java`,
`connections/MailConnection.java`, `connections/MailConnectionFactory.java`,
`spike/ImapSpikeCommand.java`, `spike/EwsSpikeCommand.java`, `ImapClientTest.java`, and the
documents below.

Delete: `src/main/java/mail/mcp/source/imap/ImapMimeItem.java`.

## Documents

- `docs/imap-source-agent.md`: "Implemented scope" (`ImapMailSource`, one session per source),
  the lifecycle bullet under "TLS and authentication", the allow-list in "Read-only command
  guard" (`CLOSE` and why), "MIME download" rewritten for `folderUids`/`download`, the expected
  spike report, and "Offline verification".
- `docs/architecture-agent.md`: move `source/` (root) from "Planned packages" to "Existing
  packages"; update the `connections/` and `source/imap/` rows and the status line.
- `docs/build-config-agent.md`, section "The IMAP spike": what the spike now does (three steps,
  no `\Seen` comparison).

## Done when

- Every test named above exists and passes; `.\gradlew.bat build` passes.
- `git status` shows only the files listed under "Files".
- The report lists, under "For the user to run":
  `java -jar build\libs\mail-mcp-server.jar imap-spike mailru` → `RESULT: OK` with three steps.
