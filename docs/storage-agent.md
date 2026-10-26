# docs/storage-agent.md — message directories and the derived catalog

Message directories, the derived Lucene index (schema 3), synchronization, MIME/body
processing, search and attachment text. See `docs/plan.md` 3.2–3.6 for the design.
`MessageStore` and `MailIndex` take one connection directory; neither contacts a mailbox.

## Disk layout

Under `ConnectionDefinition.connectionDir()`:

```text
<connection>/
  folders.json
  sync-status.json
  messages/<k2>/<key>/
    message.eml
    meta.json
    body.md
    attachments/<n>__<sanitized name>
    attachments/<n>__<sanitized name>.md
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
size and SHA-256, with `state=failed` and a fixed error. Supported attachments up to 20 MiB also have UTF-8
`<file>.md`; absent or failed attachment
text never changes metadata or the converted message state.
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
metadata, headers, body Markdown and joined attachment text (null text/headers on failure).
`MessageStore` never writes MIME.
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
index fields, including creating attachment `.md` files for messages already on disk; entries
without MIME remain unchanged.

Metadata, folders and sync status JSON files are written to a temporary file in the target
directory, then moved with `ATOMIC_MOVE` and `REPLACE_EXISTING`. Temporary files are cleaned up
after a write. File I/O failures become `UncheckedIOException`; Jackson exceptions propagate unchanged.

## Lucene catalog and reconciliation

`MailIndex.openWriter` creates `index/` and opens an `IndexWriter` on `FSDirectory`, using
`MailAnalyzers.analyzer()`. Lucene core, analysis-common, queryparser and highlighter use 10.5.2.
Fields come from metadata,
parsed MIME headers/attachment descriptions and saved Markdown:

| Field | Value | Type |
|---|---|---|
| `key` | Local UUID | `StringField`, stored |
| `keySort` | Local UUID for tie-breaking | `SortedDocValuesField`, not stored |
| `ref` | `folderKey + "/" + ref` | `StringField`, stored |
| `folder` | Folder path | `StringField`, stored |
| `state` | Lowercase state | `StringField`, stored |
| `error` | Failure explanation, only when non-null | `StoredField` |
| `date` | Epoch milliseconds, absent when null | `LongPoint`, `StoredField`, `NumericDocValuesField` |
| `subject`, `body` | Decoded subject and Markdown body, absent when null | `TextField`, stored |
| `subject_en`, `body_en` | Same subject and body | `TextField`, not stored |
| `attachmentText`, `attachmentText_en` | Saved attachment Markdown, in attachment order joined by a blank line | `TextField`, not stored |
| `from`, `to`, `cc` | Display addresses joined by `, ` as `Name <address>` or `address` | `StoredField` only |
| `fromAddress`, `toAddress`, `ccAddress` | One complete address per value | `TextField`, not stored |
| `fromName`, `toName`, `ccName` | One non-null display name per value | `TextField`, not stored |
| `messageId`, `inReplyTo` | Header value, absent when null | `StringField`, stored |
| `references` | One field per reference ID | `StringField`, stored |
| `attachmentNames` | Decoded names, or stored filenames when unnamed, joined by newline | `StoredField` only |
| `attachmentName`, `attachmentFile` | One decoded name per attachment, falling back to its stored filename | `TextField`, not stored |

`MailAnalyzers` builds one shared `PerFieldAnalyzerWrapper`. The default is `RussianAnalyzer`.
The `_en` fields use `EnglishAnalyzer` with both English and Russian default stop words.
Address fields and `attachmentFile` use `KeywordTokenizer` and `LowerCaseFilter`, preserving
complete addresses and filenames. `attachmentName` uses `PatternTokenizer("[^\\p{L}\\p{N}]+", -1)`,
`LowerCaseFilter` and Snowball's `RussianStemmer`, splitting underscores and punctuation.
Attachment contents use the Russian default analyzer and English analyzer for `attachmentText_en`.
They are indexed without being stored; snippets still use the message body, so an attachment-only
hit may show leading body text.

Every commit, including closing the writer, carries user data `schema=3` (`MailIndex.SCHEMA`).
Opening an existing index with another schema or no schema uses `OpenMode.CREATE`, rebuilding
in place. `openWriter(dir, true)` always recreates it. No second generation is kept.
The next `SyncRunner.restore` reconciles every missing entry from disk before network access:
converted entries load MIME headers and saved body/attachment Markdown, downloaded entries
are processed,
and failed entries get metadata only. It commits every 100 restored entries and at the end.
`mail-reindex <connection>` runs this restoration offline with recreation forced; it checks
pandoc before taking the writer lock because downloaded messages may need conversion.

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
MIME headers, saved `body.md` and attachment Markdown without writes, and indexes failed
entries with null headers, body and attachment text. Existing index entries are left alone. Failed
messages with metadata remain valid without MIME. Deleting `index/` loses no source data:
reconciliation restores headers and body from disk before the runner accesses the source.
`mail-reprocess` also restores processed entries offline, without sign-in.

`cli/MailSyncCommand` owns the source and writer, takes the writer lock before the first network
request and closes the source before the index. `SyncRunner` opens and closes neither; it reports
cumulative `FolderProgress` after each committed batch and after a folder's cursor is saved (or
the folder is deleted). `FolderProgress` lives in `model/`. `SyncService` runs the same runner in
the background; `MailIndexReader` reads the last commit; `mail-reindex` and `mail-reprocess`
rebuild offline.

## Sync status and tool readers

`MessageStore.readSyncStatus` returns an empty Optional without `sync-status.json`;
`writeSyncStatus` uses the same atomic JSON replacement. Only `SyncRunner`, while its caller
holds the writer lock, writes production status. It records running before reconciliation,
latest per-folder progress in first-appearance order, then completed or failed with finishedAt.
`SyncStatus` contains state, pid, startedAt, finishedAt, folders, error and signInRejected.
Failures use fixed `SyncErrors.describe` text and authentication failures set signInRejected.
A final status-write failure is suppressed onto the original exception. A successful explicit
`mail-sync` clears the rejection flag; MCP starts refuse a persisted rejection. Readers report
interrupted when a running status belongs to a dead pid and no local run exists. See
`docs/tools-agent.md` for the wire contract and recovery.

`MailIndexReader.open` never takes a writer lock, returns empty without a committed index and
opens one snapshot of the latest commit per call. It counts folder messages with a PrefixQuery
on ref, and pages stored MessageCard fields by date descending (missing dates last), then key.
New documents have sorted `keySort` doc values; documents without them still sort by date.
The separate field preserves the `key` field's doc values type NONE.
`current()` checks commit schema; search tools reject old schemas until a rebuild.
Search uses equal-weight field groups, required words and phrases through `SimpleQueryParser`,
an optional folder ref prefix FILTER, exact counts and score descending/key ascending sorting.
`UnifiedHighlighter` reanalyzes stored `body`, combining matches from `body_en` on the same text;
the formatter uses `**` around matches and `… ` between passages. Missing bodies yield null.
An optional output transform passed by `SearchTools` filters the complete stored body before
in-memory highlighting (`highlightWithoutSearcher`); the query used for highlighting omits
the folder filter. This affects snippets only, not search matches, scores, totals, disk or index.
See `docs/redaction-agent.md`. With no transform, the existing highlighting path is unchanged.
The writer's NRT lookups remain separate; tools see only committed documents.
