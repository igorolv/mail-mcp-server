# docs/storage-agent.md — message directories and the derived catalog

Storage and catalog implemented in IT-02, synchronization in IT-03 (plan.md 7.2), MIME/body
processing and header/body index fields in IT-05 (plan.md 7.4, first half).
See `docs/plan.md` 6.2–6.6 for the design.
`MessageStore` and `MailIndex` take one connection directory; neither contacts a mailbox.

## Disk layout

Under `ConnectionDefinition.connectionDir()`:

```text
<connection>/
  folders.json
  messages/<k2>/<key>/
    message.eml
    meta.json
    body.md
    attachments/<n>__<sanitized name>
  index/
```

`key` is a lowercase canonical UUID; `k2` is its first two characters. `messageDir` validates
the key without I/O. `createMessageDir` requires a new directory. `keys` returns sorted valid
message directory names under the matching prefix; unrelated names and files are ignored.
`delete` removes a message directory recursively and leaves its prefix directory.

`meta.json` example (synthetic):

```json
{
  "key": "3f2a0000-0000-0000-0000-000000000001",
  "folderKey": "f1",
  "folder": "INBOX",
  "ref": "101",
  "size": 12,
  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "downloadedAt": "2026-10-25T12:00:00Z",
  "state": "downloaded"
}
```

States are `downloaded`, `converted` and `failed`. A failed download has size 0, no SHA-256 and
an `error` explaining the failure; it may have no `message.eml`. Null properties are omitted
by the configured Jackson 3 mapper and read back as null. A converted message has its selected
body in UTF-8 `body.md` (absent when there is no body) and decoded attachment files under
`attachments/` (absent when there are none). A conversion failure keeps the original MIME,
size and SHA-256, with `state=failed` and a fixed error. Attachment `.md` files are deferred.
See `docs/mime-agent.md` for parsing, naming and pandoc rules.

`folders.json` example (synthetic):

```json
{
  "folders": [
    { "key": "f1", "remoteId": "INBOX", "path": "INBOX", "cursor": "7" }
  ]
}
```

Sync assigns local folder keys. The source owns the opaque cursor; null means no full pass yet.
A missing folders file returns an empty list. Missing metadata returns an empty `Optional`.

`SyncRunner` matches folders by `remoteId`, preserving a known folder's key and cursor while
updating its path. New keys are `f<n>`, one above the largest number in the local list before
vanished folders are dropped, starting at `f1`. A key dropped in an earlier run may be reused.
Folders are saved in server order before downloads, so even an interrupted first run retains
their assigned keys. Vanished folders lose every message directory and index document; their
deletions are committed before the folders list is saved. A reset removes all local messages
of that folder, even if the source's removed list is incomplete, then downloads the added refs.

## Write order and failures

`SyncRunner` creates the message directory, asks `MailSource` to download `message.eml`, then
writes `meta.json` with `state=downloaded`, size, SHA-256 and download time. `MessageProcessor`
removes output from any earlier attempt, saves decoded attachments and `body.md`, then writes
metadata with `state=converted` or `failed`. The runner calls `MailIndex.put` with the resulting
metadata, headers and Markdown (null headers/body on failure). `MessageStore` never writes MIME.
Without metadata, the directory is incomplete.
Metadata writes require an existing message directory; saving folders creates the connection
directory if needed. Constructors perform no I/O.

Within each folder the runner applies removals and commits, then downloads in batches of 100
messages (including failed ones), committing each batch. It commits again after all downloads,
then saves the folder's new cursor in `folders.json`. The cursor never advances on a partial
folder. A `MessageUnavailableException` writes `state=failed`, size 0, null SHA-256 and the
exception's fixed message, without `message.eml`. Its ref is indexed, so the next run does not
retry it; normal removals delete failed messages too. Other exceptions propagate without retries
or cleanup; the next run reconciles before contacting the source. Closing the index commits
pending entries, including a partial batch with completed metadata.

`FolderProgress.downloaded` counts new messages ending converted; `failed` includes download
and conversion failures. Conversion failures are not automatically retried. The offline
`mail-reprocess` command processes every message with metadata and MIME, replacing output and
index fields; entries without MIME remain unchanged.

Both JSON files are written to a temporary file in the target directory, then moved with
`ATOMIC_MOVE` and `REPLACE_EXISTING`. Temporary files are cleaned up after a write. File I/O
failures become `UncheckedIOException`; Jackson exceptions propagate unchanged.

## Lucene catalog and reconciliation

`MailIndex.openWriter` creates `index/` and opens the default `IndexWriter` on `FSDirectory`.
Only `lucene-core 10.5.2` is used, with the default `StandardAnalyzer`. Fields come from metadata,
parsed MIME headers/attachment descriptions and saved Markdown:

| Field | Value | Type |
|---|---|---|
| `key` | Local UUID | `StringField`, stored |
| `ref` | `folderKey + "/" + ref` | `StringField`, stored |
| `folder` | Folder path | `StringField`, stored |
| `state` | Lowercase state | `StringField`, stored |
| `error` | Failure explanation, only when non-null | `StoredField` |
| `date` | Epoch milliseconds, absent when null | `LongPoint`, `StoredField`, `NumericDocValuesField` |
| `subject`, `from`, `to`, `cc` | Decoded headers; addresses joined by `, ` as `Name <address>` or `address` | `TextField`, stored |
| `messageId`, `inReplyTo` | Header value, absent when null | `StringField`, stored |
| `references` | One field per reference ID | `StringField`, stored |
| `body` | Markdown, absent when null | `TextField`, stored |
| `attachmentNames` | Decoded names, or stored filenames when unnamed, joined by newline | `TextField`, stored |

`put` replaces by key; `delete` removes by key. `keys` and `folderRefs` open and close an NRT
reader of the writer, so pending changes are visible before commit. Folder lookup uses the
`folderKey + "/"` prefix: `f1` excludes `f10`, and `/` inside a server ref is preserved.

Lucene's `write.lock` allows one writer per connection. A second writer fails immediately with
`IndexLockedException` and the message `Connection is being synchronized by another process`.
There is no retry or additional lock. Closing commits the writer and closes the directory.
Other Lucene I/O failures become `UncheckedIOException`.

`reconcile(store)` deletes incomplete message directories, removes indexed keys absent from
disk, and returns sorted keys with metadata missing from the index. It does not index or
commit them itself. `SyncRunner` processes downloaded entries, loads converted entries using
MIME headers and saved `body.md` without writes, and indexes failed entries with null headers
and body. It commits once after this loop. Existing index entries are left alone. Failed
messages with metadata remain valid without MIME. Deleting `index/` loses no source data:
reconciliation restores headers and body from disk before the runner accesses the source.
`mail-reprocess` also restores processed entries offline, without sign-in.

`cli/MailSyncCommand` owns the source and writer, takes the writer lock before the first network
request and closes the source before the index. `SyncRunner` opens and closes neither; it reports
cumulative `FolderProgress` after each committed batch and after a folder's cursor is saved (or
the folder is deleted). It writes no status file. `SyncService` and `sync-status.json` remain
planned for IT-06, as are tool readers. Search and the Russian analyzer are step 7.5;
attachment text is step 7.6.
