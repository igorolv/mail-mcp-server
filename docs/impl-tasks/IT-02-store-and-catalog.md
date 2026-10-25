# IT-02 — message directories and the Lucene catalog

Plan step: `docs/plan.md` 7.2, first half. Size: one full session. Next brief: IT-03
(`SyncRunner`, `mail-sync`, `spike/` → `cli/`; not written yet).

## Goal

Build the local side of synchronization: one directory per message with `meta.json`, the
`folders.json` file of folders and cursors, and one Lucene index per connection that holds the
catalog fields and can be brought back in line with the disk without the network. Nothing
connects to a mailbox, parses MIME or runs a sync in this task. IT-03 is the caller; every public
method below names the IT-03 step that will call it. Do not add a method that is not listed.

## Read first

1. `docs/plan.md` sections 6.1, 6.2 (including "Сверка в начале запуска"), 6.4, 6.5, 6.9 and 7.2
   (Russian; what you need is repeated below in English).
2. `docs/architecture-agent.md` — package table and layer rules.
3. `src/main/java/mail/mcp/source/MailSource.java`, `FolderChanges.java`, `RemoteFolder.java`.
4. `src/main/java/mail/mcp/config/JsonConfig.java` — the Jackson 3 mapper.
5. `src/main/java/mail/mcp/source/oauth/google/GoogleTokenStore.java`, method `write` — the
   temporary-file-and-move idiom (without its owner-only ACL, which is not needed here).
6. `gradle/libs.versions.toml`, `build.gradle.kts`.

## Already decided — do not revisit

- Disk layout of one connection (plan.md 6.4), under `ConnectionDefinition.connectionDir()`:

  ```
  <connection>/
    folders.json
    messages/<k2>/<key>/       <k2> = first two characters of the key
      message.eml              written by MailSource.download (IT-03), never by this task's code
      meta.json
    index/                     Lucene
  ```

- The message key is a random UUID in lowercase canonical form (`UUID.randomUUID().toString()`).
- `meta.json` is written last: a message directory without `meta.json` is incomplete and is
  deleted by reconciliation.
- JSON files are written to a temporary file in the same directory and moved over the target
  with `ATOMIC_MOVE` and `REPLACE_EXISTING`.
- States are exactly `downloaded`, `converted`, `failed` (plan.md 6.9: no other states, no
  diagnostic codes). Step 7.2 only writes `downloaded` and `failed`; `converted` comes in 7.4.
- The index is derived: everything in it comes from `meta.json`. Losing `index/` loses nothing.
- One writer per connection: the Lucene write lock (`write.lock`). A second writer is refused,
  never waited for.
- Only `lucene-core` in this task. Text fields and `RussianAnalyzer` (`lucene-analysis-common`)
  come in step 7.4 together with the fields that need them.
- `sync-status.json` is not part of this task (IT-03).

## Current code and library facts you need

- JSON is Jackson 3 (`tools.jackson.databind.ObjectMapper`); annotations are still
  `com.fasterxml.jackson.annotation` (jar `jackson-annotations-2.21`). Checked with the project's
  jars on 2026-10-25: a record with an `Instant` field is written as an ISO-8601 string
  (`"2026-10-25T12:00:00Z"`) and read back; enum constants annotated `@JsonProperty("failed")` are
  written and read as `"failed"`. `JsonConfig.mailMcpObjectMapper()` drops `null` properties on
  write (`NON_NULL`) and keeps the record's declaration order; missing properties read as `null`.
- Lucene `10.5.2` is the latest release on Maven Central (checked 2026-10-25; Java 21+). Facts the
  design relies on:
  - `new IndexWriter(FSDirectory.open(dir), new IndexWriterConfig())` takes `write.lock`. A second
    `IndexWriter` on the same directory, in this JVM or another process, throws
    `LockObtainFailedException` (an `IOException`). Closing the writer releases the lock.
  - `IndexWriter.close()` commits by default.
  - `DirectoryReader.open(writer)` gives a near-real-time reader that already sees uncommitted
    `updateDocument`/`deleteDocuments` of that writer.
  - `IndexSearcher.search(query, n)` needs `n >= 1`; read stored values with
    `searcher.storedFields().document(docId)`.
- `ConnectionDefinition.connectionDir()` is `<data-dir>/<connection>`; nothing creates it until
  something writes into it.

## What to build

### 1. Dependency

`gradle/libs.versions.toml`: version `lucene = "10.5.2"`, library
`lucene-core = { module = "org.apache.lucene:lucene-core", version.ref = "lucene" }`;
`build.gradle.kts`: `implementation(libs.lucene.core)`. Nothing else.

### 2. Package `mail.mcp.model` (new; one type per file)

```java
/** Message lifecycle on disk; written to meta.json and the index in lowercase. */
public enum MessageState {
    @JsonProperty("downloaded") DOWNLOADED,
    @JsonProperty("converted") CONVERTED,
    @JsonProperty("failed") FAILED;
    /** The lowercase value used in meta.json and the index. */
    public String id() { ... }
}

/**
 * Contents of meta.json: what the .eml does not say about a message.
 * size and sha256: of message.eml; 0 and null when the download failed.
 * error: why the state is failed, otherwise null.
 */
public record MessageMeta(String key, String folderKey, String folder, String ref, long size,
                          String sha256, Instant downloadedAt, MessageState state, String error) { }

/**
 * One entry of folders.json. key: local folder key ("f1", "f2", ...), assigned by sync;
 * remoteId/path: from RemoteFolder; cursor: from FolderChanges, null before the first full pass.
 */
public record LocalFolder(String key, String remoteId, String path, String cursor) { }
```

No validation logic in the records.

### 3. Package `mail.mcp.store` (new): `MessageStore`

`public final class MessageStore`, constructed with `(Path connectionDir, ObjectMapper mapper)`.
Public constants `MESSAGE_FILE = "message.eml"`, `META_FILE = "meta.json"`. No I/O in the
constructor. `IOException` → `UncheckedIOException`; Jackson's own exceptions propagate as they are.

| Method | Behavior | Caller in IT-03 |
|---|---|---|
| `List<LocalFolder> folders()` | reads `folders.json` (`{"folders":[...]}`, through a private nested record); missing file → empty list | start of a run |
| `void saveFolders(List<LocalFolder> folders)` | writes `folders.json` atomically, creating `connectionDir` if needed | after each folder |
| `String newKey()` | `UUID.randomUUID().toString()` | before each download |
| `Path messageDir(String key)` | `connectionDir/messages/<first two chars>/<key>`; no I/O; a key that is not a lowercase canonical UUID → `IllegalArgumentException` | everywhere a key is used |
| `Path createMessageDir(String key)` | creates `messageDir(key)` and its parents; the directory must not exist yet (`FileAlreadyExistsException` → `UncheckedIOException`); returns it | before each download |
| `void writeMeta(MessageMeta meta)` | writes `meta.json` into `messageDir(meta.key())` atomically; the directory must exist | after each download |
| `Optional<MessageMeta> readMeta(String key)` | empty when `meta.json` is missing | reconciliation |
| `boolean hasMeta(String key)` | `meta.json` exists; reads nothing | reconciliation |
| `List<String> keys()` | names of all directories `messages/*/*` that are valid keys and sit under the right `<k2>`, sorted; ignores files and other names; missing `messages/` → empty | reconciliation |
| `void delete(String key)` | deletes `messageDir(key)` recursively; missing → no-op; leaves the `<k2>` directory | removed messages, reset folders, incomplete directories |

### 4. Package `mail.mcp.index` (new)

`public final class IndexLockedException extends RuntimeException` with the fixed message
`"Connection is being synchronized by another process"`, no cause, no other members.

`public final class MailIndex implements AutoCloseable`, writer side only (readers for tools come
in step 7.4):

- `public static MailIndex openWriter(Path connectionDir)`: creates `connectionDir/index` if
  needed, opens `FSDirectory` and one `IndexWriter` with the default `IndexWriterConfig`.
  `LockObtainFailedException` → close the directory, throw `IndexLockedException`. Other
  `IOException` → `UncheckedIOException`.
- Document fields (plan.md 6.5, catalog part only), all `StringField` with `Store.YES`:
  `key`; `ref` = `folderKey + "/" + ref`; `folder` = `meta.folder()`; `state` = `state().id()`.
  `error` is a `StoredField`, added only when not null. No other fields.
- `public void put(MessageMeta meta)`: `updateDocument(new Term("key", key), doc)`.
- `public void delete(String key)`: `deleteDocuments(new Term("key", key))`.
- `public void commit()`.
- `public Set<String> keys()`: keys of all documents, seen through `DirectoryReader.open(writer)`
  (so uncommitted changes count); the reader is closed before returning.
- `public Map<String, String> folderRefs(String folderKey)`: server ref → key for the documents of
  one folder: `PrefixQuery` on `ref` with `folderKey + "/"`, the prefix cut from the returned refs.
  Folder `f1` must not return documents of `f10`; a ref may itself contain `/` (EWS ids are base64).
- `public void reconcile(MessageStore store)` — plan.md 6.2 "Сверка в начале запуска", without
  the conversion steps (they come in 7.4):
  1. every key of `store.keys()` without `meta.json` → `store.delete(key)`;
  2. every key of `keys()` that is not on disk (any more) → `delete(key)`;
  3. every key on disk with `meta.json` that is not in `keys()` → `put(store.readMeta(key))`;
  4. `commit()`.

  Read `meta.json` only in step 3; for the rest use directory names and the index. A deleted
  `index/` directory is restored by `openWriter` + `reconcile`; there is no separate rebuild method.
- `public void close()`: closes the writer and the directory.

`IOException` from Lucene → `UncheckedIOException` everywhere except the lock case above.

## Tests

All with `@TempDir`, no network, synthetic values only (`example.com` addresses are not needed;
use keys from `newKey()` or fixed UUID literals, refs like `"101"` and `"AAMk/x+y="`).

`src/test/java/mail/mcp/store/MessageStoreTest.java`:

- `foldersRoundTripAndMissingFileIsEmpty`
- `foldersFileHasThePlannedShape` — the written JSON has a top-level `folders` array whose objects
  have `key`, `remoteId`, `path`, `cursor`
- `metaRoundTripKeepsIsoInstantAndLowercaseState` — the file text contains
  `"downloadedAt":"2026-10-25T12:00:00Z"` and `"state":"failed"`; a `null` error and sha256 read back as `null`
- `messageDirUsesTheFirstTwoKeyCharacters`
- `invalidKeysAreRejectedWithoutIo` — `".."`, `"ab"`, uppercase UUID, UUID with a `/`; nothing is created
- `createMessageDirRefusesAnExistingDirectory`
- `writesLeaveNoTemporaryFiles` — after two `writeMeta` and two `saveFolders` the directories hold
  only `meta.json` / `folders.json` (plus `messages/`)
- `keysListOnlyMessageDirectories` — a stray file, a directory with a non-UUID name and a valid key
  under the wrong `<k2>` are ignored
- `deleteRemovesTheDirectoryAndIgnoresMissingKeys`

`src/test/java/mail/mcp/index/MailIndexTest.java`:

- `putReplacesByKeyAndDeleteRemoves` — visible through `keys()`/`folderRefs()` before `commit()`
- `folderRefsReturnOnlyThatFolder` — folders `f1` and `f10`, a ref containing `/`
- `committedDocumentsSurviveReopen`
- `secondWriterOnTheSameConnectionIsRefused` — `IndexLockedException` with the fixed message;
  after the first writer is closed, `openWriter` succeeds again
- `reconcileDeletesIncompleteDirectoriesAndIndexesMissingOnes` — on disk: A with `meta.json` (not
  indexed), B without `meta.json`; in the index: C without a directory → afterwards A is indexed,
  B's directory is gone, C's document is gone
- `deletedIndexIsRestoredFromDisk` — three messages in two folders written and indexed; close;
  delete `index/`; `openWriter` + `reconcile` → the same `keys()` and `folderRefs()` as before
- `failedMessageWithoutMimeIsIndexed` — `state=failed`, size 0, sha256 null, error set, no
  `message.eml`: `reconcile` indexes it and does not delete its directory

## Do not

- Connect to a mailbox, call `MailSource`, write `message.eml`, parse MIME or build any part of
  `SyncRunner`, `mail-sync` or `sync-status.json` (IT-03).
- Add Lucene text fields, analyzers other than the default, `lucene-analysis-common`, searching,
  sorting, readers for tools or `SearcherManager` (7.4–7.5).
- Add caches, in-memory copies of the catalog, background threads, file locks of your own, retries,
  fsync calls, checksums of `meta.json`, schema versions or migrations.
- Add states, error codes or fields beyond those listed (plan.md 6.9).
- Touch `source/`, `connections/`, `spike/`, `config/` or `tools/`.

## Stop and ask when

- The resolved `lucene-core` version is not `10.5.2` (Spring dependency management overrides it).
- Lucene behaves differently from the facts above (for example the second writer in the same JVM
  waits instead of failing, or the NRT reader does not see uncommitted changes).
- Jackson writes `Instant` or the enum differently from the facts above.
- The JVM prints anything to `System.out` when Lucene starts (stdio must carry only JSON-RPC);
  warnings on `System.err` are fine, list them in the report.

## Files

Create: `src/main/java/mail/mcp/model/{MessageState,MessageMeta,LocalFolder}.java`,
`src/main/java/mail/mcp/store/MessageStore.java`,
`src/main/java/mail/mcp/index/{MailIndex,IndexLockedException}.java`,
`src/test/java/mail/mcp/store/MessageStoreTest.java`,
`src/test/java/mail/mcp/index/MailIndexTest.java`, `docs/storage-agent.md`.

Change: `gradle/libs.versions.toml`, `build.gradle.kts`, and the documents below.

## Documents

- `docs/storage-agent.md` (new, English): the on-disk layout of a connection, `meta.json` and
  `folders.json` with one example each (synthetic values), the write order (`message.eml`, then
  `meta.json`) and atomic writes, the index fields, the single-writer rule and
  `IndexLockedException`, and reconciliation. Short; point to plan.md 6.2–6.5 for the design. The
  reviewer registers it in `AGENTS.md`.
- `docs/architecture-agent.md`: move `model/`, `store/`, `index/` from "Planned packages" to
  "Existing packages" (only what exists now; `sync-status.json` and the 7.4 records stay planned
  under `store/`/`model/` notes), add `lucene-core 10.5.2` where the tech stack is listed, show in
  the dependency diagram that `index/` reads `store/` for reconciliation, update the status line.

## Done when

- Every test named above exists and passes; `.\gradlew.bat build` passes.
- `git status` shows only the files listed under "Files".
- The report says "nothing" under "For the user to run": this task does not touch a mailbox.
